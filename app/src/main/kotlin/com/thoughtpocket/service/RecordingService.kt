package com.thoughtpocket.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.ModelManager
import com.thoughtpocket.Transcription
import com.thoughtpocket.ai.Embedder
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.ai.MarkdownEngine
import com.thoughtpocket.ai.TaggingEngine
import com.thoughtpocket.ai.canonicalizeTags
import com.thoughtpocket.ai.preserveChecked
import com.thoughtpocket.ai.TitleEngine
import com.thoughtpocket.audio.AudioFiles
import com.thoughtpocket.audio.MicRecorder
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.mergeTranscript
import com.thoughtpocket.widget.RecordWidget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
    // Written on main (startRecording) and Dispatchers.Default (nulled after stop), read on main.
    @Volatile private var recorder: MicRecorder? = null

    private val queue = Channel<Clip>(Channel.UNLIMITED)
    private var consumer: Job? = null
    private val pending = AtomicInteger(0)

    /**
     * [model] is the model that produces the SAVED transcript (the final/Whisper model when the final pass is
     * on, else the first-pass model). [appendNoteId] >= 0 → append this clip's transcript to that note.
     */
    private data class Clip(
        val file: File,
        val samples: Int,
        val model: ModelManager.ModelEntry?,
        val prefs: AppPreferences,
        val appendNoteId: Long = -1L,
        val createdAt: Long? = null,   // imported files carry the source file's date; null = now
        val importKey: String? = null, // imported files: mark this key done in prefs only AFTER the note saves
        // First-pass-only: the live accumulator already decoded the audio chunk-by-chunk into [firstPassText]
        // (up to [firstPassSamples]); the note reuses it + a single decode of the remaining tail, instead of
        // a full re-decode that can truncate a long clip. null → normal batch decode (both / final-only).
        val firstPassText: String? = null,
        val firstPassSamples: Int = 0,
    )

    /** Live first-pass transcript built during recording; in first-only it becomes the saved note. */
    private class LivePass {
        val committed = StringBuilder()
        @Volatile var committedSamples = 0
    }

    /** Notes that got appended recordings this drain; reformatted in one pass once the queue empties. */
    private val appendedIds = mutableSetOf<Long>()

    /** One-shot: re-queue recordings a killed session left on disk, before this process's first record. */
    @Volatile private var recovered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getLongExtra(EXTRA_APPEND_ID, -1L))
            ACTION_STOP -> recorder?.stop()
            ACTION_IMPORT -> startImport()
            ACTION_RECOVER -> startRecovery()
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
        recoverOrphans()   // re-queue any recordings a previous (killed) session left on disk

        val file = audioFile()
        val rec = MicRecorder(file)
        recorder = rec
        RecordState.set(RecordState.State.RECORDING, SystemClock.elapsedRealtime())
        RecordWidget.refresh(this)

        scope.launch {
            val prefs = AppPreferences(this@RecordingService)
            val noteModel = resolveNoteModel(prefs)       // owns the saved transcript (final, or first in first-only)
            val previewModel = resolvePreviewModel(prefs) // live-preview engine for ALL modes, or null if none fits

            try {
                rec.start()
            } catch (t: Throwable) {
                Log.e(TAG, "recording failed to start", t)
                recorder = null
                settleStateAndMaybeStop()
                return@launch
            }

            // Live accumulating preview while recording — runs in every pass mode (incl. final-only), as long
            // as some installed model can keep up. Skipped only when no live-capable model is available.
            val livePass = LivePass()
            val liveJob: Job? =
                if (previewModel != null && canLive(previewModel))
                    launch { liveLoop(rec, prefs, previewModel, livePass) }
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

            // Hand the recording (a file on disk) to the background queue and free the mic immediately.
            val n = rec.samples
            if (n > 0) {
                // Save a playable WAV copy to the user's folder (survives uninstall) BEFORE the .pcm is consumed.
                if (prefs.saveAudio && prefs.saveAudioFolder.isNotEmpty()) runCatching { saveWav(file, prefs.saveAudioFolder) }
                RecordState.setPending(pending.incrementAndGet())
                // First-pass-only: the note IS the live accumulator (+ tail finalize), not a re-decode. Only
                // when the live pass actually ran — its model equals the note model in first-only.
                val firstOnly = liveJob != null && !prefs.finalPassEnabled
                queue.send(
                    if (firstOnly)
                        Clip(file, n, noteModel, prefs, appendNoteId,
                            firstPassText = livePass.committed.toString(), firstPassSamples = livePass.committedSamples)
                    else Clip(file, n, noteModel, prefs, appendNoteId)
                )
            } else rec.discard()
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
                runCatching { transcribeAndSave(clip) }.onFailure {
                    Log.e(TAG, "transcribe failed", it)
                    // The .pcm is deleted only once the transcript is saved, so on any failure the audio
                    // is still on disk — tell the user, and recoverOrphans re-queues it on next launch.
                    Notifications.done(this@RecordingService, "Couldn't transcribe — audio kept, will retry on next launch")
                }
                val left = pending.decrementAndGet().coerceAtLeast(0)
                RecordState.setPending(left)
                // Queue drained → reformat/retag any notes we appended to, in one pass (still foreground).
                if (left == 0) runCatching { enrichAppended() }.onFailure { Log.e(TAG, "enrich failed", it) }
                settleStateAndMaybeStop()
            }
        }
    }

    private suspend fun liveLoop(rec: MicRecorder, prefs: AppPreferences, model: ModelManager.ModelEntry, pass: LivePass) {
        val engine = Transcription.engineFor(model)
        val moonshine = model.engine == ModelManager.EngineKind.MOONSHINE
        // Accumulate a FULL transcript (not a rolling window): commit completed ~segment chunks into
        // `pass.committed` and re-decode only the still-uncommitted tail each tick. Per-tick cost stays ~one
        // chunk decode regardless of total length (same cost as the old window) — the difference is the
        // committed prefix is kept so the preview grows instead of scrolling. In first-only the kept prefix
        // also BECOMES the note (finalized by [finalizeFirstPass]), so the chunk-by-chunk decode is done once
        // and never re-run. Non-overlapping commits can rarely split a boundary word — the accepted trade for
        // the fast model. Moonshine = short chunk/fast cadence; Whisper pays its ~2.8 s encoder floor per
        // decode either way, so a longer chunk buys context for free.
        val segment = if (moonshine) MOON_WINDOW_SAMPLES else PREVIEW_WINDOW_SAMPLES
        val interval = if (moonshine) MOON_INTERVAL_MS else LIVE_INTERVAL_MS
        while (currentCoroutineContext().isActive) {
            delay(interval)
            // Read+advance by what's actually on disk, NOT rec.samples: the recorder's in-memory counter runs
            // ahead of the file (BufferedOutputStream flushes ~every 2 s), so tail.size is the only honest
            // length. Advancing by it leaves the not-yet-flushed remainder for the next tick instead of
            // skipping it.
            val tail = rec.readRange(pass.committedSamples, rec.samples)
            if (tail.size < MIN_SAMPLES) continue
            val tailText = runCatching {
                engine.transcribe(this, model, tail, prefs, highQuality = false, useVad = false)
            }.getOrNull()?.trim().orEmpty()
            val full = mergeTranscript(pass.committed.toString(), tailText)
            if (full.isNotBlank()) publishPreview(prefs, full)
            // Tail grew past a chunk → fold its decode into the committed prefix and advance (reuses the
            // decode just done, so this costs nothing extra). Advance even on an empty (silent) chunk so the
            // tail can't grow unbounded across a long pause.
            if (tail.size >= segment) {
                if (tailText.isNotEmpty()) {
                    if (pass.committed.isNotEmpty()) pass.committed.append(' ')
                    pass.committed.append(tailText)
                }
                pass.committedSamples += tail.size
            }
        }
    }

    /** Publish a live-preview transcript: freshest words to the on-screen card, full window text to the shade. */
    private fun publishPreview(prefs: AppPreferences, full: String) {
        RecordState.setPartial(previewTail(full, MAX_PREVIEW_WORDS), full)
        if (prefs.liveTranscribeNotification) updateOngoing("Recording…", full)
    }

    /**
     * Produce the saved transcript for a clip through its resolved engine. The engine owns serialization +
     * lazy load and applies [stripNonSpeech]; VAD/highQuality are honoured by Whisper and ignored by Moonshine.
     */
    private suspend fun transcribeForNote(pcm: FloatArray, prefs: AppPreferences, model: ModelManager.ModelEntry): String =
        Transcription.engineFor(model).transcribe(this, model, pcm, prefs, highQuality = prefs.highQuality, useVad = true)

    /**
     * The transcript that becomes the note. First-pass-only ([Clip.firstPassText] non-null): reuse the live
     * accumulator and decode ONLY the remaining tail once — the first pass already transcribed the rest
     * chunk-by-chunk while recording, so we never re-decode the whole clip (a single long decode by a
     * short-segment model like Moonshine can emit EOS early and drop the last sentence). Otherwise (both /
     * final-only): one full batch decode by the note model.
     */
    private suspend fun noteTranscript(clip: Clip): String {
        val model = clip.model!!
        val committed = clip.firstPassText ?: return transcribeForNote(MicRecorder.readPcm(clip.file), clip.prefs, model)
        val tail = MicRecorder.readPcmRange(clip.file, clip.firstPassSamples, clip.samples)
        // A tail-decode failure must PROPAGATE (like the batch path): swallowing it would silently drop
        // the last chunk and then delete the audio. On throw the consumer keeps the .pcm for retry.
        val tailText = if (tail.isNotEmpty())
            Transcription.engineFor(model).transcribe(this, model, tail, clip.prefs, highQuality = false, useVad = false).trim()
        else ""
        return mergeTranscript(committed, tailText)
    }

    private suspend fun transcribeAndSave(clip: Clip) {
        when {
            clip.model == null -> Notifications.done(this, "No model — open Settings to download one")  // keep file for recovery
            // Append target deleted before transcription → fall back to a new note so the recording isn't lost.
            clip.appendNoteId >= 0 -> if (appendToNote(clip)) clip.file.delete() else saveAsNewNote(clip)
            else -> saveAsNewNote(clip)
        }
    }

    /** Transcribe [clip] into a brand-new note; the audio is deleted only once the note is durably saved. */
    private suspend fun saveAsNewNote(clip: Clip) {
        val prefs = clip.prefs
        val text = noteTranscript(clip)
        val body = text.ifBlank { "(no speech detected)" }
        val dao = NotesDb.get(this).notes()
        val note = Note(createdAt = clip.createdAt ?: System.currentTimeMillis(), text = body)
        val id = dao.insert(note)
        clip.file.delete()   // transcript is durably saved → release the audio now; enrichment below is best-effort
        // Imported files are marked done only now (post-save), so a failed transcription is re-imported later.
        clip.importKey?.let { prefs.importedAudio = prefs.importedAudio + it }
        Notifications.done(this, "Note saved")

        // Embed (semantic relate) + title + auto-tag + auto-markdown in the background, then update.
        // Best-effort: the note + transcript are already saved, so a failure here (e.g. disk-full on
        // the enrichment write) must NOT bubble to the consumer's onFailure as "couldn't transcribe".
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "enrichment failed (note already saved)", e)
        }
    }

    /**
     * Append a recording's raw transcript to an existing note (+ re-embed); reformat is deferred to drain.
     * Returns false when the target note no longer exists (deleted while queued) — the caller then saves
     * the clip as a new note instead, so the recording is never lost.
     */
    private suspend fun appendToNote(clip: Clip): Boolean {
        val dao = NotesDb.get(this).notes()
        val note = dao.getById(clip.appendNoteId) ?: run {
            Log.w(TAG, "append target ${clip.appendNoteId} gone — saving as new note")
            return false
        }
        val text = noteTranscript(clip)
        val add = text.ifBlank { "(no speech detected)" }
        val combined = if (note.text.isBlank()) add else note.text.trimEnd() + "\n\n" + add
        val emb = Embedder.embed(this, combined) ?: note.embedding
        dao.update(note.copy(text = combined, embedding = emb))
        synchronized(appendedIds) { appendedIds.add(note.id) }
        Notifications.done(this, "Added to note")
        return true
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

    /**
     * The model that produces the SAVED transcript: the final (Whisper) model when the final pass is on,
     * else the first-pass model (first-only). Falls back to any installed model so a note still gets made.
     */
    private fun resolveNoteModel(prefs: AppPreferences): ModelManager.ModelEntry? {
        val id = if (prefs.finalPassEnabled) prefs.finalPassModelId else prefs.firstPassModelId
        return ModelManager.entryById(this, id)?.takeIf { ModelManager.isDownloaded(this, it) }
            ?: ModelManager.listInstalled(this).firstOrNull()
    }

    /** The first-pass (live preview) model, or null when the first pass is off / its model isn't installed. */
    private fun resolveFirstModel(prefs: AppPreferences): ModelManager.ModelEntry? =
        ModelManager.entryById(this, prefs.firstPassModelId)?.takeIf { ModelManager.isDownloaded(this, it) }

    /**
     * The model that drives the live accumulating preview, in EVERY pass mode: the first-pass model when the
     * first pass is enabled (its installed model), otherwise the saved-note (final) model — so the transcript
     * still appears live in final-only mode. [canLive] then gates whether it actually runs (a large Whisper
     * final model can't keep up live, so there's simply no preview in that case).
     */
    private fun resolvePreviewModel(prefs: AppPreferences): ModelManager.ModelEntry? =
        (if (prefs.firstPassEnabled) resolveFirstModel(prefs) else null) ?: resolveNoteModel(prefs)

    /** Can this engine keep up as a live preview? Moonshine always; Whisper only on small models. */
    private fun canLive(model: ModelManager.ModelEntry): Boolean =
        model.engine == ModelManager.EngineKind.MOONSHINE || model.approxSizeMb <= LIVE_MAX_MODEL_MB

    /** Durable dir for in-flight recordings — filesDir (NOT cache, which the OS may purge). */
    private fun audioDir(): File = File(filesDir, "audio").apply { mkdirs() }
    private fun audioFile(): File = File.createTempFile("rec", ".pcm", audioDir())

    /** Write a recording's int16 PCM as a WAV into the user's picked save folder. */
    private fun saveWav(pcm: File, treeUriStr: String) {
        val name = "ThoughtPocket_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
        AudioFiles.createInTree(this, Uri.parse(treeUriStr), "audio/x-wav", name)?.use { AudioFiles.writeWav(it, pcm) }
    }

    /**
     * Re-queue + transcribe any recordings a killed session left behind, then stop. No mic. Triggered
     * at app launch (gated on [hasOrphanRecordings] so the service only starts when there's real work).
     */
    private fun startRecovery() {
        Notifications.ensureChannel(this)
        ServiceCompat.startForeground(
            this, Notifications.ONGOING_ID, Notifications.ongoing(this, "Transcribing…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        ensureConsumer()
        recoverOrphans()
        settleStateAndMaybeStop()   // nothing to recover (already done this process) → stops immediately
    }

    /** Import: scan the picked folder for audio, decode each new file, transcribe it into a dated note. */
    private fun startImport() {
        Notifications.ensureChannel(this)
        ServiceCompat.startForeground(
            this, Notifications.ONGOING_ID, Notifications.ongoing(this, "Importing audio…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        ensureConsumer()
        scope.launch {
            runCatching { scanImport() }.onFailure { Log.e(TAG, "import scan failed", it) }
            settleStateAndMaybeStop()
        }
    }

    private suspend fun scanImport() {
        val prefs = AppPreferences(this)
        val folder = prefs.importFolder.ifEmpty { return }
        val model = resolveNoteModel(prefs)
        val done = prefs.importedAudio.toMutableSet()
        for ((uri, _, modified) in AudioFiles.listAudio(this, Uri.parse(folder))) {
            val key = uri.toString()
            if (key in done) continue
            val pcm = AudioFiles.decode(this, uri)            // → 16 kHz mono float (empty on unsupported)
            if (pcm.isEmpty()) continue                       // leave unmarked so a fixed/retried file imports later
            val tmp = File.createTempFile("import", ".pcm", cacheDir)   // cache → not picked up by recoverOrphans
            AudioFiles.writePcm(tmp, pcm)
            // In-scan dedup only; the key is persisted by the consumer AFTER the note saves, so a failed
            // transcription (whose temp .pcm in cache isn't recovered) is re-imported on a later scan.
            done.add(key)
            RecordState.setPending(pending.incrementAndGet())
            queue.send(Clip(tmp, pcm.size, model, prefs, createdAt = modified, importKey = key))
        }
    }

    /**
     * Re-queue any .pcm files a previous (killed) session left on disk — once per process, before the
     * first record (so anything present is a genuine orphan). Recovered audio becomes a new note.
     * ponytail: fires on the next record, not at app launch — add a launch-scan if users report waiting.
     */
    private fun recoverOrphans() {
        if (recovered) return
        recovered = true
        val prefs = AppPreferences(this)
        val model = resolveNoteModel(prefs)
        audioDir().listFiles { f -> f.isFile && f.name.endsWith(".pcm") }?.forEach { f ->
            val n = (f.length() / 2).toInt()
            if (n <= 0) { f.delete(); return@forEach }
            RecordState.setPending(pending.incrementAndGet())
            // Always a new note (appendNoteId=-1): a clip that died mid-append recovers as its own note —
            // the thought is preserved, just not re-attached to the original.
            queue.trySend(Clip(f, n, model, prefs))
        }
    }

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
        // The ~2.8 s window decode is itself the pacer (measured) — this is just a small breather between
        // decodes, NOT the cadence. Lower → latency floor (back-to-back decode); raise → throttle heat/battery.
        private const val LIVE_INTERVAL_MS = 500L
        private const val LEVEL_INTERVAL_MS = 40L  // ~25 fps mic-level updates for the orb pulse
        private const val LIVE_MAX_MODEL_MB = 200 // (windowed-Whisper) tiny/base/small keep up; medium/large don't
        private const val MIN_SAMPLES = 16_000    // ~1s before first preview
        private const val PREVIEW_WINDOW_SAMPLES = 16_000 * 10  // windowed-Whisper preview: last ~10 s
        // Moonshine decodes a short window in ~hundreds of ms, so use a small window + fast cadence for the
        // instant live-caption feel (the decode time is the real pacer). Tuned on device; safe to adjust.
        private const val MOON_WINDOW_SAMPLES = 16_000 * 4   // Moonshine preview: last ~4 s
        private const val MOON_INTERVAL_MS = 250L
        private const val MAX_PREVIEW_WORDS = 14   // show only the freshest words in the 2–3 line card (tunable)
        const val ACTION_START = "com.thoughtpocket.action.START"
        const val ACTION_STOP = "com.thoughtpocket.action.STOP"
        const val ACTION_IMPORT = "com.thoughtpocket.action.IMPORT"
        const val ACTION_RECOVER = "com.thoughtpocket.action.RECOVER"
        private const val EXTRA_APPEND_ID = "append_note_id"

        /** Cheap disk check (call off the main thread): did a killed session leave un-transcribed audio? */
        fun hasOrphanRecordings(ctx: Context): Boolean =
            File(ctx.filesDir, "audio").listFiles { f -> f.isFile && f.name.endsWith(".pcm") }?.isNotEmpty() == true

        /** Transcribe recordings a killed session left behind (gate on [hasOrphanRecordings] first). */
        fun recoverIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).setAction(ACTION_RECOVER)

        /** Scan the user's import folder and turn any new audio files into transcribed notes. */
        fun importIntent(ctx: Context) = Intent(ctx, RecordingService::class.java).setAction(ACTION_IMPORT)

        /** [appendToNoteId] >= 0 appends the recording to that note instead of creating a new one. */
        fun startIntent(ctx: Context, appendToNoteId: Long = -1L) =
            Intent(ctx, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_APPEND_ID, appendToNoteId)

        fun stopIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP)
    }
}

/**
 * Trim a live-preview transcript to its most recent [maxWords] words so the small (2–3 line) recording
 * card shows the freshest speech instead of ellipsizing it off the end. A leading "…" marks that earlier
 * words were dropped. Pure + unit-tested; the saved note is always the full re-decode on stop, never this.
 */
internal fun previewTail(text: String, maxWords: Int): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size <= maxWords) return words.joinToString(" ")
    return "… " + words.takeLast(maxWords).joinToString(" ")
}
