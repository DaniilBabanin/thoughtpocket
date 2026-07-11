package com.thoughtpocket.coder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume

/** Result of one script execution in the runner process. */
data class PyRunResult(
    val ok: Boolean,
    val syntaxError: Boolean,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/**
 * Main-process client for [PyRunnerService]. One bind per exec: on success we
 * unbind cleanly; on timeout we unbind to make the system reap the runner
 * process (it has no other clients), which is the kill switch for hung
 * scripts — no cooperation from the script needed.
 */
object PyRunnerClient {

    suspend fun exec(context: Context, code: String, timeoutMs: Long): PyRunResult {
        var conn: ServiceConnection? = null
        try {
            return withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val replyHandler = Handler(Looper.getMainLooper()) { msg ->
                        if (msg.what == PyRunnerService.MSG_RESULT && cont.isActive) {
                            val json = msg.data.getString(PyRunnerService.KEY_RESULT) ?: "{}"
                            cont.resume(parse(json))
                        }
                        true
                    }
                    val c = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val msg = Message.obtain(null, PyRunnerService.MSG_RUN)
                            msg.data.putString(PyRunnerService.KEY_CODE, code)
                            msg.replyTo = Messenger(replyHandler)
                            runCatching { Messenger(binder).send(msg) }
                                .onFailure { if (cont.isActive) cont.resume(failed("bind send failed: $it")) }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            // Runner process died mid-run (crash/OOM in the script).
                            if (cont.isActive) cont.resume(failed("runner process died"))
                        }
                    }
                    conn = c
                    val ok = context.bindService(
                        Intent(context, PyRunnerService::class.java), c, Context.BIND_AUTO_CREATE
                    )
                    if (!ok && cont.isActive) cont.resume(failed("bindService returned false"))
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Unbind alone leaves a busy-looping cached process burning CPU until
            // the system gets around to it — kill it now (same UID, so allowed).
            killRunnerProcess(context)
            return PyRunResult(ok = false, syntaxError = false, stdout = "",
                stderr = "execution exceeded ${timeoutMs}ms", timedOut = true)
        } finally {
            conn?.let { runCatching { context.unbindService(it) } }
        }
    }

    private fun parse(json: String): PyRunResult = runCatching {
        val o = JSONObject(json)
        PyRunResult(
            ok = o.optBoolean("ok", false),
            syntaxError = o.optBoolean("syntax", false),
            stdout = o.optString("stdout", ""),
            stderr = o.optString("stderr", ""),
        )
    }.getOrElse { failed("bad runner reply: $json") }

    private fun failed(msg: String) =
        PyRunResult(ok = false, syntaxError = false, stdout = "", stderr = msg)

    /** Kill the runner process outright (user cancel while a script runs). */
    fun kill(context: Context) = killRunnerProcess(context)

    private fun killRunnerProcess(context: Context) {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
        am.runningAppProcesses
            ?.firstOrNull { it.processName == context.packageName + ":coder" }
            ?.let { android.os.Process.killProcess(it.pid) }
    }
}
