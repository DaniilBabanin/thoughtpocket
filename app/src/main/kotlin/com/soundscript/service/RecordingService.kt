package com.soundscript.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.soundscript.AppPreferences
import com.soundscript.ModelManager
import com.soundscript.WhisperEngine
import com.soundscript.ai.Embedder
import com.soundscript.ai.LlmEngine
import com.soundscript.ai.MarkdownEngine
import com.soundscript.ai.TaggingEngine
import com.soundscript.ai.TitleEngine
import com.soundscript.audio.MicRecorder
import com.soundscript.data.Note
import com.soundscript.data.NotesDb
import com.soundscript.widget.RecordWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground (microphone) service that owns the whole pipeline:
 * record → transcribe on-device → save Note → discard audio.
 *
 * Transcription is streamed two ways:
 *  - while recording, a live loop re-transcribes the audio-so-far every few seconds
 *    (fast models only) and publishes it to [RecordState.partial];
 *  - during the final pass, whisper segments are streamed to the same flow.
 *
 * START is delivered via getForegroundService; STOP via getService (service already running).
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: MicRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> recorder?.stop()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        // Always (re)assert foreground first to satisfy the startForegroundService contract.
        Notifications.ensureChannel(this)
        ServiceCompat.startForeground(
            this, Notifications.ONGOING_ID, Notifications.ongoing(this, "Recording…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        if (RecordState.status.value.state != RecordState.State.IDLE) return // already busy

        val rec = MicRecorder()
        recorder = rec
        RecordState.set(RecordState.State.RECORDING, SystemClock.elapsedRealtime())
        RecordWidget.refresh(this)

        scope.launch {
            val prefs = AppPreferences(this@RecordingService)
            val model = loadModel(prefs)

            try {
                rec.start()
            } catch (t: Throwable) {
                Log.e(TAG, "recording failed to start", t)
                finishIdle()
                return@launch
            }

            // Live preview while recording — only for models fast enough to keep up.
            val liveJob: Job? =
                if (model != null && model.approxSizeMb <= LIVE_MAX_MODEL_MB)
                    launch { liveLoop(rec, prefs) }
                else null

            rec.runUntilStopped()      // returns once ACTION_STOP flips the flag
            liveJob?.cancelAndJoin()   // waits out any in-flight transcribe (no overlap)

            transcribeAndSave(rec.snapshot(), model, prefs)
            finishIdle()
        }
    }

    private suspend fun liveLoop(rec: MicRecorder, prefs: AppPreferences) {
        while (currentCoroutineContext().isActive) {
            delay(LIVE_INTERVAL_MS)
            val snap = rec.snapshot()
            if (snap.size < MIN_SAMPLES) continue
            val text = runCatching {
                WhisperEngine.transcribe(
                    pcm16k = snap,
                    language = prefs.language.ifBlank { null },
                    translate = prefs.translateToEnglish,
                    threads = prefs.resolvedThreads(),
                    highQuality = false, // previews stay greedy/fast
                )
            }.getOrNull()
            if (!text.isNullOrBlank()) RecordState.setPartial(text)
        }
    }

    private suspend fun transcribeAndSave(
        pcm: FloatArray,
        model: ModelManager.ModelEntry?,
        prefs: AppPreferences,
    ) {
        RecordState.set(RecordState.State.TRANSCRIBING)
        RecordWidget.refresh(this)
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ONGOING_ID, Notifications.ongoing(this, "Transcribing…"))

        when {
            pcm.isEmpty() -> Notifications.done(this, "Nothing recorded")
            model == null -> Notifications.done(this, "No model — open Settings to download one")
            else -> {
                val sb = StringBuilder()
                val text = runCatching {
                    WhisperEngine.transcribe(
                        pcm16k = pcm,
                        language = prefs.language.ifBlank { null },
                        translate = prefs.translateToEnglish,
                        threads = prefs.resolvedThreads(),
                        highQuality = prefs.highQuality,
                        onSegment = { seg -> sb.append(seg); RecordState.setPartial(sb.toString()) },
                    )
                }.getOrElse { Log.e(TAG, "transcription failed", it); "" }
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
                    getSystemService(NotificationManager::class.java)
                        .notify(Notifications.ONGOING_ID, Notifications.ongoing(this, "Summarising…"))
                    title = TitleEngine.suggest(this, body).getOrNull().orEmpty()
                    if (prefs.autoTag)
                        tags = TaggingEngine.suggestTags(this, body).getOrNull().orEmpty()
                    if (prefs.autoMarkdown)
                        markdown = MarkdownEngine.toMarkdown(this, body).getOrNull().orEmpty()
                }
                if (emb != null || title.isNotEmpty() || tags.isNotEmpty() || markdown.isNotEmpty())
                    dao.update(note.copy(id = id, title = title, tags = tags, embedding = emb, markdown = markdown))
            }
        }
    }

    /** Resolve + load the selected model. Returns the entry, or null if none is available. */
    private suspend fun loadModel(prefs: AppPreferences): ModelManager.ModelEntry? {
        val entry = ModelManager.entryById(this, prefs.selectedModelId)
            ?.takeIf { ModelManager.isDownloaded(this, it) }
            ?: ModelManager.listInstalled(this).firstOrNull()
            ?: return null
        return if (WhisperEngine.load(ModelManager.fileFor(this, entry), useGpu = false).isSuccess) entry else null
    }

    private fun finishIdle() {
        RecordState.set(RecordState.State.IDLE)
        RecordWidget.refresh(this)
        recorder = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val LIVE_INTERVAL_MS = 2500L
        private const val LIVE_MAX_MODEL_MB = 200 // tiny/base/small keep up; medium/large don't
        private const val MIN_SAMPLES = 16_000    // ~1s before first preview
        const val ACTION_START = "com.soundscript.action.START"
        const val ACTION_STOP = "com.soundscript.action.STOP"

        fun startIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).setAction(ACTION_START)

        fun stopIntent(ctx: Context) =
            Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
