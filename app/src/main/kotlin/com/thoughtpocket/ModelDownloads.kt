package com.thoughtpocket

import android.content.Context
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.service.DownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * UI-facing state + trigger facade for model downloads. The actual work runs in [DownloadService] (a
 * foreground service) so downloads survive the app being backgrounded; this object is just what the
 * Settings screen observes and pokes — same split as [com.thoughtpocket.service.RecordState] ←
 * [com.thoughtpocket.service.RecordingService].
 *
 * [progress] is keyed by a transcription model id, a Gemma bundle name, or "gecko" ([ALL] marks a
 * "download everything" run); a key present means "downloading". [completed] bumps each time one
 * finishes so the UI can re-check what's on disk without polling the filesystem on every percent.
 */
object ModelDownloads {
    const val ALL = "__all__"

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress

    private val _completed = MutableStateFlow(0)
    val completed: StateFlow<Int> = _completed

    fun isRunning(key: String) = _progress.value.containsKey(key)

    fun whisper(context: Context, model: ModelManager.BuiltInModel) = start(context, DownloadService.KIND_WHISPER, model.id)
    fun moonshine(context: Context, model: ModelManager.StreamingModel) = start(context, DownloadService.KIND_MOONSHINE, model.id)
    fun gemma(context: Context, d: LlmEngine.Downloadable) = start(context, DownloadService.KIND_GEMMA, d.name)
    fun gecko(context: Context) = start(context, DownloadService.KIND_GECKO, null)
    fun all(context: Context) = start(context, DownloadService.KIND_ALL, null)

    private fun start(context: Context, kind: String, id: String?) {
        context.applicationContext.startForegroundService(DownloadService.intent(context, kind, id))
    }

    // Written only by DownloadService, which owns both ends of each download (so no stuck spinners).
    internal fun begin(key: String) = _progress.update { it + (key to 0) }
    internal fun setPct(key: String, pct: Int) { if (pct in 0..99) _progress.update { it + (key to pct) } }
    internal fun end(key: String, completed: Boolean = true) {
        _progress.update { it - key }
        if (completed) _completed.update { it + 1 }
    }
}
