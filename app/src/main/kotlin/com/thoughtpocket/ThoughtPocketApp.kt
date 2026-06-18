package com.thoughtpocket

import android.app.Application
import android.util.Log
import java.io.File

class ThoughtPocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load the whisper.cpp native lib eagerly so the first transcription is fast.
        runCatching { System.loadLibrary("thoughtpocket") }

        // If a previous GPU run aborted the process, force CPU next launch.
        val crumb = File(filesDir, GPU_CRUMB_FILE)
        if (crumb.exists()) {
            Log.w("ThoughtPocketApp", "GPU crash-crumb found — forcing CPU")
            AppPreferences(this).onGpuCrashed()
            crumb.delete()
        }

        // Background, low-priority notes↔folder sync (no-op unless enabled in Settings). Reconciles on start.
        NotesSyncEngine.start(this)
    }

    companion object {
        const val GPU_CRUMB_FILE = ".gpu_in_flight"
    }
}
