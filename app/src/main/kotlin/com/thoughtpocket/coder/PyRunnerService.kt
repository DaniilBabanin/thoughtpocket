package com.thoughtpocket.coder

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Out-of-process Python executor for the coder feature. Runs in its own
 * process (see manifest) so a hung or hostile script is killable without
 * touching the app: the client unbinds and the system reaps the process.
 *
 * Bound-service Messenger protocol: MSG_RUN with [KEY_CODE] in data →
 * reply MSG_RESULT with [KEY_RESULT] = runner.py's JSON envelope.
 */
class PyRunnerService : Service() {

    companion object {
        const val MSG_RUN = 1
        const val MSG_RESULT = 2
        const val KEY_CODE = "code"
        const val KEY_NOTES_PATH = "notesPath"
        const val KEY_RESULT = "result"
        private const val TAG = "PyRunner"
    }

    // Python work off the main thread so a long exec never ANRs the service.
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("py-runner").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_RUN) return
                val code = msg.data.getString(KEY_CODE) ?: return
                val notesPath = msg.data.getString(KEY_NOTES_PATH).orEmpty()
                val replyTo = msg.replyTo ?: return
                val result = runCatching {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(this@PyRunnerService))
                    Python.getInstance().getModule("runner").callAttr("run", code, notesPath).toString()
                }.getOrElse { e ->
                    Log.e(TAG, "runner failed", e)
                    """{"ok":false,"syntax":false,"stdout":"","stderr":${jsonQuote(e.toString())}}"""
                }
                val reply = Message.obtain(null, MSG_RESULT)
                reply.data.putString(KEY_RESULT, result)
                runCatching { replyTo.send(reply) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun jsonQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
