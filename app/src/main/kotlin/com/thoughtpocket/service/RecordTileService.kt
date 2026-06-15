package com.thoughtpocket.service

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.thoughtpocket.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Quick Settings tile: tap to start/stop recording. Mirrors [RecordState]. */
class RecordTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var stateJob: Job? = null
    private var timerJob: Job? = null

    override fun onStartListening() {
        stateJob = scope.launch { RecordState.status.collect { updateTile(it) } }
    }

    override fun onStopListening() {
        stateJob?.cancel(); timerJob?.cancel(); stateJob = null; timerJob = null
    }

    override fun onClick() {
        if (RecordState.status.value.state == RecordState.State.RECORDING) {
            startService(RecordingService.stopIntent(this))
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(RecordingService.startIntent(this))
            return
        }
        val intent = Intent(this, PermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(status: RecordState.Status) {
        val tile = qsTile ?: return
        when (status.state) {
            RecordState.State.RECORDING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_stop)
                if (timerJob == null) {
                    timerJob = scope.launch {
                        while (true) {
                            val s = if (status.startedAtElapsedRealtime > 0)
                                (SystemClock.elapsedRealtime() - status.startedAtElapsedRealtime) / 1000 else 0
                            tile.label = "%d:%02d recording…".format(s / 60, s % 60)
                            tile.updateTile()
                            delay(1000)
                        }
                    }
                }
            }
            RecordState.State.TRANSCRIBING -> {
                timerJob?.cancel(); timerJob = null
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_notify)
                tile.label = "Transcribing…"
            }
            RecordState.State.IDLE -> {
                timerJob?.cancel(); timerJob = null
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_notify)
                tile.label = "ThoughtPocket"
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
