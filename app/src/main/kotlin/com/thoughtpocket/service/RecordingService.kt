package com.thoughtpocket.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.ModelManager
import com.thoughtpocket.WhisperEngine
import com.thoughtpocket.ai.Embedder
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.ai.MarkdownEngine
import com.thoughtpocket.ai.TaggingEngine
import com.thoughtpocket.ai.canonicalizeTags
import com.thoughtpocket.ai.preserveChecked
import com.thoughtpocket.ai.TitleEngine
import com.thoughtpocket.audio.MicRecorder
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.widget.RecordWidget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground (microphone) service owning the pipeline: record → transcribe on-device → save Note.
 *
 * Recording and transcription are DECOUPLED so recordings can be fired in quick succession: stopping a
 * recording hands its audio to a background queue and frees the mic immediately (state → IDLE/TRANSCRIBING),
 * so the orb can start the next recording right away. A single consumer drains the queue, and all
 * WhisperEngine access (the recording's live preview + the background transcriptions) is serialized through
 * one [Mutex] because the engine is a single shared instance.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: MicRecorder? = null

    private val whisper = Mutex()
    private var loadedModelKey: String? = null
    private val queue = Channel<Clip>(Channel.UNLIMITED)
    private var consumer: Job? = null
    private val pending = AtomicInteger(0)

    /** [appendNoteId] >= 0 → append this clip's transcript to that note instead of creating a new one. */
    private data class Clip(
        val pcm: FloatArray,
        val model: ModelManager.ModelEntry?,
        val prefs: AppPreferences,
        val appendNoteId: Long = -1L,
    )

    /** Notes that got appended recordings this drain; reformatted in one pass once the queue empties. */
    private val appendedIds = mutableSetOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getLongExtra(EXTRA_APPEND_ID, -1L))
            ACTION_STOP -> recorder?.stop()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(appendNoteId: Long = -1L) {
        // Always (re)assert foreground first to satisfy the startForegroundService contract.
        Notifications.ensureChannel(this)
        ServiceCompat.startForeground(
            this, Notifications.ONGOING_ID, Notifications.ongoing(this, "Recording…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        if (RecordState.status.value.state == RecordState.State.RECORDING) return // already recording
        ensureConsumer()

        val rec = MicRecorder()
        recorder = rec
        RecordState.set(RecordState.State.RECORDING, SystemClock.elapsedRealtime())
        RecordWidget.refresh(this)

        scope.launch {
            val prefs = AppPreferences(this@RecordingService)
            val model = resolveModel(prefs)

            try {
                rec.start()
            } catch (t: Throwable) {
                Log.e(TAG, "recording failed to start", t)
                recorder = null
                settleStateAndMaybeStop()
                return@launch
            }

            // Live preview while recording — only for models fast enough to keep up.
            val liveJob: Job? =
                if (model != null && model.approxSizeMb <= LIVE_MAX_MODEL_MB)
                    launch { liveLoop(rec, prefs, model) }
                else null

            // Publish smoothed mic loudness so the orb pulse reacts to what it hears.
            val levelJob = launch {
                while (currentCoroutineContext().isActive) {
                    RecordState.setAmplitude(rec.level())
                    delay(LEVEL_INTERVAL_MS)
                }
            }

            rec.runUntilStopped()      // returns once ACTION_STOP flips the flag
            levelJob.cancel()
            liveJob?.cancelAndJoin()
            recorder = null

            // Hand the audio to the background queue and free the mic immediately.
            val pcm = rec.snapshot()
            if (pcm.isNotEmpty()) {
                RecordState.setPending(pending.incrementAndGet())
                queue.send(Clip(pcm, model, prefs, appendNoteId))
            }
            settleStateAndMaybeStop()
        }
    }

    /** Single background drainer: transcribe queued clips one at a time, save each as a Note. */
    private fun ensureConsumer() {
        if (consumer?.isActive == true) return
        consumer = scope.launch {
            for (clip in queue) {
                getSystemService(NotificationManager::class.java)
                    .notify(Notifications.ONGOING_ID, Notifications.ongoing(this@RecordingService, "Transcribing…"))
                runCatching { transcribeAndSave(clip) }.onFailure { Log.e(TAG, "transcribe failed", it) }
                val left = pending.decrementAndGet().coerceAtLeast(0)
                RecordState.setPending(left)
                // Queue drained → reformat/retag any notes we appended to, in one pass (still foreground).
                if (left == 0) runCatching { enrichAppended() }.onFailure { Log.e(TAG, "enrich failed", it) }
                settleStateAndMaybeStop()
            }
        }
    }

    private suspend fun liveLoop(rec: MicRecorder, prefs: AppPreferences, model: ModelManager.ModelEntry) {
        while (currentCoroutineContext().isActive) {
            delay(LIVE_INTERVAL_MS)
            val snap = rec.snapshot()
            if (snap.size < MIN_SAMPLES) continue
            val text = runCatching { transcribe(snap, prefs, model, highQuality = false) }.getOrNull()
            if (!text.isNullOrBlank()) {
                RecordState.setPartial(text)
                if (prefs.liveTranscribeNotification) updateOngoing("Recording…", text)
            }
        }
    }

    /** All WhisperEngine use goes through here — serialized, with the model loaded once per key. */
    private suspend fun transcribe(
        pcm: FloatArray,
        prefs: AppPreferences,
        model: ModelManager.ModelEntry,
        highQuality: Boolean,
    ): String = whisper.withLock {
        val file = ModelManager.fileFor(this, model)
        if (loadedModelKey != file.path) {
            if (WhisperEngine.load(file, useGpu = false).isSuccess) loadedModelKey = file.path
            else return@withLock ""
        }
        WhisperEngine.transcribe(
            pcm16k = pcm,
            language = prefs.language.ifBlank { null },
            translate = prefs.translateToEnglish,
            threads = prefs.resolvedThreads(),
            highQuality = highQuality,
        )
    }

    private suspend fun transcribeAndSave(clip: Clip) {
        val prefs = clip.prefs
        when {
            clip.model == null -> Notifications.done(this, "No model — open Settings to download one")
            clip.appendNoteId >= 0 -> appendToNote(clip)
            else -> {
                val text = transcribe(clip.pcm, prefs, clip.model, highQuality = prefs.highQuality)
                val body = text.ifBlank { "(no speech detected)" }
                val dao = NotesDb.get(this).notes()
                val note = Note(createdAt = System.currentTimeMillis(), text = body)
                val id = dao.insert(note)
                Notifications.done(this, "Note saved")

                // Embed (semantic relate) + title + auto-tag + auto-markdown in the background, then update.
                val emb = Embedder.embed(this, body)
                var title = ""
                var tags = emptyList<String>()
                var markdown = ""
                if (text.isNotBlank() && LlmEngine.isModelInstalled(this)) {
                    title = TitleEngine.suggest(this, body).getOrNull().orEmpty()
                    if (prefs.autoTag) {
                        val raw = TaggingEngine.suggestTags(this, body).getOrNull().orEmpty()
                        // Fold onto existing tags so near-duplicates ("work"/"works") don't accumulate.
                        val corpus = runCatching { dao.all().first().flatMap { it.tags } }.getOrElse { emptyList() }
                        tags = canonicalizeTags(raw, corpus)
                    }
                    if (prefs.autoMarkdown)
                        markdown = MarkdownEngine.toMarkdown(this, body).getOrNull().orEmpty()
                }
                if (emb != null || title.isNotEmpty() || tags.isNotEmpty() || markdown.isNotEmpty())
                    dao.update(note.copy(id = id, title = title, tags = tags, embedding = emb, markdown = markdown))
            }
        }
    }

    /** Append a recording's raw transcript to an existing note (+ re-embed); reformat is deferred to drain. */
    private suspend fun appendToNote(clip: Clip) {
        val dao = NotesDb.get(this).notes()
        val note = dao.getById(clip.appendNoteId) ?: run {
            Log.w(TAG, "append target ${clip.appendNoteId} gone")
            return
        }
        val text = transcribe(clip.pcm, clip.prefs, clip.model!!, highQuality = clip.prefs.highQuality)
        val add = text.ifBlank { "(no speech detected)" }
        val combined = if (note.text.isBlank()) add else note.text.trimEnd() + "\n\n" + add
        val emb = Embedder.embed(this, combined) ?: note.embedding
        dao.update(note.copy(text = combined, embedding = emb))
        synchronized(appendedIds) { appendedIds.add(note.id) }
        Notifications.done(this, "Added to note")
    }

    /** Queue drained → reformat Markdown + refresh tags for the notes we appended to, in one pass. */
    private suspend fun enrichAppended() {
        val ids = synchronized(appendedIds) { val c = appendedIds.toList(); appendedIds.clear(); c }
        if (ids.isEmpty()) return
        val prefs = AppPreferences(this)
        if (!prefs.reformatAppendedNotes || !LlmEngine.isModelInstalled(this)) return
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ONGOING_ID, Notifications.ongoing(this, "Formatting…"))
        val dao = NotesDb.get(this).notes()
        val corpus = runCatching { dao.all().first().flatMap { it.tags } }.getOrElse { emptyList() }
        for (id in ids) {
            val note = dao.getById(id) ?: continue
            if (note.text.isBlank()) continue
            // Reformat regenerates everything unchecked — carry over the items the user had ticked.
            val md = MarkdownEngine.toMarkdown(this, note.text).getOrNull()
                ?.let { preserveChecked(note.markdown, it) }
            val newTags = if (prefs.autoTag) TaggingEngine.suggestTags(this, note.text).getOrNull().orEmpty() else emptyList()
            dao.update(note.copy(markdown = md ?: note.markdown, tags = canonicalizeTags(note.tags + newTags, corpus)))
        }
    }

    /** Update the ongoing notification; [body] (the live transcript) shows expandably in the shade. */
    private fun updateOngoing(status: String, body: String?) {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ONGOING_ID, Notifications.ongoing(this, status, body))
    }

    private fun resolveModel(prefs: AppPreferences): ModelManager.ModelEntry? =
        ModelManager.entryById(this, prefs.selectedModelId)?.takeIf { ModelManager.isDownloaded(this, it) }
            ?: ModelManager.listInstalled(this).firstOrNull()

    /** Reflect the queue/mic in [RecordState], and stop the service once truly idle. */
    private fun settleStateAndMaybeStop() {
        if (recorder != null) return // a recording is active — RECORDING already set, leave it
        if (pending.get() > 0) {
            RecordState.set(RecordState.State.TRANSCRIBING)
            RecordWidget.refresh(this)
        } else {
            RecordState.set(RecordState.State.IDLE)
            RecordWidget.refresh(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val LIVE_INTERVAL_MS = 2500L
        private const val LEVEL_INTERVAL_MS = 40L  // ~25 fps mic-level updates for the orb pulse
        private const val LIVE_MAX_MODEL_MB = 200 // tiny/base/small keep up; medium/large don't
        private const val MIN_SAMPLES = 16_000    // ~1s before first preview
        const val ACTION_START = "com.thoughtpocket.action.START"
        const val ACTION_STOP = "com.thoughtpocket.action.STOP"
        private const val EXTRA_APPEND_ID = "append_note_id"

        /** [appendToNoteId] >= 0 appends the recording to that note instead of creating a new one. */
        fun startIntent(ctx: Context, appendToNoteId: Long = -1L) =
            Intent(ctx, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_APPEND_ID, appendToNoteId)

        fun stopIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
