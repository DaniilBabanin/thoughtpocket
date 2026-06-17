package com.thoughtpocket.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.thoughtpocket.ModelDownloads
import com.thoughtpocket.ModelManager
import com.thoughtpocket.ai.Embedder
import com.thoughtpocket.ai.LlmEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Foreground (data-sync) service that runs model downloads, so they keep going when the app is
 * backgrounded. State is published through [ModelDownloads], which the Settings screen observes —
 * the UI never touches this service directly, the same way it observes [RecordState] not
 * [RecordingService].
 *
 * The service owns both ends of each download (begin → progress → end), so the skip paths (already
 * installed, or a deduped double-tap) never leave a spinner stuck. It stops itself once no download
 * is in flight.
 */
class DownloadService : Service() {

    // Handler swallows download-flow throws (network drop, incomplete) so a failure clears the
    // spinner via finally instead of crashing the app through the scope.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "download failed", e) }
    )
    private val active = AtomicInteger(0)
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        Notifications.ensureChannel(this)
        ServiceCompat.startForeground(
            this, Notifications.DOWNLOAD_ID, Notifications.ongoing(this, "Downloading models…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        when (intent?.getStringExtra(EXTRA_KIND)) {
            KIND_WHISPER -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                ModelManager.BuiltInModel.entries.find { it.id == id }?.let { m -> runOne(m.id) { ModelManager.download(this, m) } }
            }
            KIND_GEMMA -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                LlmEngine.Downloadable.entries.find { it.name == id }?.let { d -> runOne(d.name) { LlmEngine.download(this, d) } }
            }
            KIND_GECKO -> runOne("gecko") { Embedder.download(this) }
            KIND_ALL -> runAll()
        }
        maybeStop()   // nothing launched (already installed / deduped) → don't hang foreground
        return START_NOT_STICKY
    }

    private fun runOne(key: String, source: () -> Flow<Int>) {
        if (ModelDownloads.isRunning(key)) return
        active.incrementAndGet()
        scope.launch {
            ModelDownloads.begin(key)
            var ok = false
            try {
                source().collect { ModelDownloads.setPct(key, it) }
                ok = true
            } catch (e: CancellationException) {
                throw e                                   // service teardown — not a download failure
            } catch (e: Throwable) {
                Log.w(TAG, "download failed: $key", e)
                Notifications.done(this@DownloadService, "Download failed — open Settings to retry")
            } finally {
                ModelDownloads.end(key, completed = ok)   // don't claim completion on failure
                finished()
            }
        }
    }

    /** Download everything not yet installed, sequentially, under the [ModelDownloads.ALL] marker. */
    private fun runAll() {
        if (ModelDownloads.isRunning(ModelDownloads.ALL)) return
        active.incrementAndGet()
        scope.launch {
            ModelDownloads.begin(ModelDownloads.ALL)
            try {
                val whisper = ModelManager.BuiltInModel.BASE_EN_Q5
                if (ModelManager.listInstalled(this@DownloadService).isEmpty())
                    collectInto(whisper.id, ModelManager.download(this@DownloadService, whisper))
                for (d in LlmEngine.Downloadable.entries) if (!LlmEngine.isInstalled(this@DownloadService, d))
                    collectInto(d.name, LlmEngine.download(this@DownloadService, d))
                if (!Embedder.isReady(this@DownloadService))
                    collectInto("gecko", Embedder.download(this@DownloadService))
            } catch (e: CancellationException) {
                throw e                                   // service teardown — not a download failure
            } catch (e: Throwable) {
                // One model failing aborts the rest; "Download all" again resumes (installed ones skip).
                Log.w(TAG, "download-all failed", e)
                Notifications.done(this@DownloadService, "Download failed — open Settings to retry")
            } finally {
                ModelDownloads.end(ModelDownloads.ALL, completed = false)
                finished()
            }
        }
    }

    private suspend fun collectInto(key: String, source: Flow<Int>) {
        ModelDownloads.begin(key)
        var ok = false
        try {
            source.collect { ModelDownloads.setPct(key, it) }
            ok = true
        } finally {
            ModelDownloads.end(key, completed = ok)   // a failed item must not bump the "recheck disk" tick
        }
    }

    private fun finished() {
        if (active.decrementAndGet() <= 0) maybeStop()
    }

    private fun maybeStop() {
        if (active.get() <= 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)   // ignored if a newer request already arrived (finished() runs off-main)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        const val KIND_WHISPER = "whisper"
        const val KIND_GEMMA = "gemma"
        const val KIND_GECKO = "gecko"
        const val KIND_ALL = "all"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ID = "id"

        fun intent(ctx: Context, kind: String, id: String?) =
            Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_ID, id)
    }
}
