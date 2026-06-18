package com.thoughtpocket

import android.content.Context
import android.net.Uri
import android.util.Log
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.service.RecordState
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Pure reconciliation core for two-way notes↔folder sync (the SAF I/O + low-priority scheduling wrap
 * around this). One note ⇄ one file, identified across devices by [Note.createdAt] (the stable filename
 * prefix). [decide] returns what to do for a single note key given:
 *  - the DB side (its content fingerprint, or null if no note),
 *  - the folder side (the file's fingerprint + modified-time, or null if no file),
 *  - the last-synced state (fingerprint + mtime we recorded last reconcile, or null if never),
 *  - whether a `.trash` tombstone exists for this key.
 *
 * Policies (chosen by the product owner): conflicts are last-write-wins; deletes are tombstoned (and
 * recoverable); a file that merely vanished (no tombstone) never deletes a note — it's re-exported.
 */
object NotesFolderSync {

    enum class Action { EXPORT, IMPORT, DELETE_NOTE, TRASH_FILE, NONE }

    /** What we recorded for a key at the end of the last reconcile. */
    data class State(val fingerprint: Int, val mtime: Long)

    /** Stable change-fingerprint of a note's syncable content (title/text/markdown/tags; not the id/embedding). */
    fun fingerprint(n: Note): Int = NoteFile.serialize(n).hashCode()

    fun decide(
        dbFingerprint: Int?,     // null → no note in DB for this key
        fileFingerprint: Int?,   // null → no file in the folder for this key
        fileMtime: Long,         // folder file's last-modified (0 if no file)
        tombstoned: Boolean,     // a tombstone exists in .trash for this key
        last: State?,            // sync-state from the previous reconcile (null → never synced)
    ): Action {
        // A delete was made (somewhere) and tombstoned → propagate it (delete wins over a stale edit).
        if (tombstoned) return if (dbFingerprint != null) Action.DELETE_NOTE else Action.NONE

        // No file present (and no tombstone).
        if (fileFingerprint == null) {
            // Note exists but its file is gone with no tombstone → a vanish, not a delete → re-export it
            // (never lose a note to a missing file). Nothing to do if there's no note either.
            return if (dbFingerprint != null) Action.EXPORT else Action.NONE
        }

        // File exists, no DB note. If we'd synced this key before ([last] != null), the note was deleted
        // in-app → tombstone its file (move to .trash) so the delete propagates. If we've never seen it,
        // it was authored on another device → import it.
        if (dbFingerprint == null) return if (last != null) Action.TRASH_FILE else Action.IMPORT

        // Both sides exist — did either change since we last reconciled?
        val dbChanged = last == null || dbFingerprint != last.fingerprint
        val fileChanged = last == null || fileFingerprint != last.fingerprint
        return when {
            !dbChanged && !fileChanged -> Action.NONE                    // already in sync
            dbChanged && !fileChanged -> Action.EXPORT                   // local edit → push
            !dbChanged && fileChanged -> Action.IMPORT                   // remote edit → pull
            // Both changed → conflict → last-write-wins: file newer than our last write → it wins.
            else -> if (last != null && fileMtime > last.mtime) Action.IMPORT else Action.EXPORT
        }
    }
}

/**
 * Background engine driving two-way notes↔folder sync. Strictly low-priority and yields to the user:
 * runs on a single MIN_PRIORITY thread, NEVER reconciles while recording/transcribing (gates on
 * [RecordState]), and debounces DB changes — so capture, transcription, and the UI never wait on it.
 * The DB is the source of truth; the folder is a best-effort mirror that self-heals each reconcile.
 */
object NotesSyncEngine {
    private const val TAG = "NotesSync"

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notes-sync").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false

    /** Wire the observers once per process. Safe to call when sync is off — it just idles. */
    @OptIn(FlowPreview::class)   // debounce()
    fun start(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        scope.launch {
            // A DB change (debounced) or a return to idle requests a reconcile; the channel coalesces bursts.
            launch { NotesDb.get(ctx).notes().all().debounce(2500).collect { trigger.trySend(Unit) } }
            launch { RecordState.status.collect { if (it.state == RecordState.State.IDLE) trigger.trySend(Unit) } }
            trigger.trySend(Unit)   // reconcile on start
            for (ignored in trigger) {
                val prefs = AppPreferences(ctx)
                if (!prefs.syncEnabled || prefs.syncFolder.isEmpty()) continue
                if (!idle()) continue   // user is capturing → defer; the RecordState→IDLE trigger retries
                runCatching { reconcile(ctx, prefs) }.onFailure { Log.w(TAG, "reconcile failed", it) }
            }
        }
    }

    /** Ask for a reconcile (e.g. on app resume, or right after the user enables sync). */
    fun requestSync() { trigger.trySend(Unit) }

    private fun idle() =
        RecordState.status.value.state == RecordState.State.IDLE && RecordState.pending.value == 0

    private suspend fun reconcile(ctx: Context, prefs: AppPreferences) {
        val tree = runCatching { Uri.parse(prefs.syncFolder) }.getOrNull() ?: return
        val dao = NotesDb.get(ctx).notes()
        val notes = dao.all().first()
        val files = NotesFolderIo.list(ctx, tree)
        val parsed = HashMap<Long, Pair<NotesFolderIo.DocFile, Note>>()
        for (f in files) {
            val content = NotesFolderIo.read(ctx, f.uri) ?: continue
            val n = NoteFile.parse(content)
            parsed[n.createdAt] = f to n
        }
        val tombstones = NotesFolderIo.listTrash(ctx, tree)
            .mapNotNull { f -> NotesFolderIo.read(ctx, f.uri)?.let { runCatching { NoteFile.parse(it).createdAt }.getOrNull() } }
            .toSet()
        val notesByKey = notes.associateBy { it.createdAt }
        val state = loadState(ctx)
        val keys = notesByKey.keys + parsed.keys + tombstones

        try {
            for (key in keys) {
                if (!idle()) return   // user started capturing → yield immediately
                val note = notesByKey[key]
                val pf = parsed[key]
                when (NotesFolderSync.decide(
                    dbFingerprint = note?.let { NotesFolderSync.fingerprint(it) },
                    fileFingerprint = pf?.let { NotesFolderSync.fingerprint(it.second) },
                    fileMtime = pf?.first?.mtime ?: 0L,
                    tombstoned = key in tombstones,
                    last = state[key],
                )) {
                    NotesFolderSync.Action.EXPORT -> {
                        val content = NoteFile.serialize(note!!)
                        val uri = pf?.first?.uri
                        if (uri != null) NotesFolderIo.overwrite(ctx, uri, content)
                        else NotesFolderIo.create(ctx, tree, NoteFile.filename(note), content)
                        state[key] = NotesFolderSync.State(NotesFolderSync.fingerprint(note), System.currentTimeMillis())
                    }
                    NotesFolderSync.Action.IMPORT -> {
                        val p = pf!!.second
                        // embedding=null → backfilled (re-embedded) on next Home open.
                        if (note == null) dao.insert(p)
                        else dao.update(note.copy(text = p.text, title = p.title, markdown = p.markdown, tags = p.tags, embedding = null))
                        state[key] = NotesFolderSync.State(NotesFolderSync.fingerprint(p), pf.first.mtime)
                    }
                    NotesFolderSync.Action.TRASH_FILE -> {   // deleted in-app → propagate via tombstone
                        pf?.let { NotesFolderIo.moveToTrash(ctx, tree, it.first) }
                        state.remove(key)
                    }
                    NotesFolderSync.Action.DELETE_NOTE -> {  // tombstone seen → delete locally
                        note?.let { dao.delete(it) }
                        state.remove(key)
                    }
                    NotesFolderSync.Action.NONE -> {}
                }
            }
        } finally {
            saveState(ctx, state)
        }
    }

    // ---- sync-state: per-note {fingerprint, mtime} of what we last reconciled (JSON in filesDir) ----
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "notes-sync-state.json")

    private fun loadState(ctx: Context): MutableMap<Long, NotesFolderSync.State> {
        val f = stateFile(ctx)
        if (!f.exists()) return HashMap()
        return runCatching {
            val o = JSONObject(f.readText())
            val m = HashMap<Long, NotesFolderSync.State>()
            o.keys().forEach { k -> val v = o.getJSONObject(k); m[k.toLong()] = NotesFolderSync.State(v.getInt("fp"), v.getLong("mt")) }
            m
        }.getOrElse { HashMap() }
    }

    private fun saveState(ctx: Context, m: Map<Long, NotesFolderSync.State>) {
        runCatching {
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k.toString(), JSONObject().put("fp", v.fingerprint).put("mt", v.mtime)) }
            stateFile(ctx).writeText(o.toString())
        }
    }
}
