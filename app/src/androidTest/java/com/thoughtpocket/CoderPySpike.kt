package com.thoughtpocket

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.coder.PyRunnerClient
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * SPIKE (Phase 3, coder feature): proves the out-of-process Python runner
 * end-to-end — happy path, error path, and the kill-on-timeout wall.
 * Manual run (BENCH logcat convention):
 *
 *   adb shell am instrument -w -e class com.thoughtpocket.CoderPySpike \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 */
class CoderPySpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun happyPath() = runBlocking<Unit> {
        val r = PyRunnerClient.exec(ctx, "print(sum([1, 2, 3]))", timeoutMs = 60_000)
        Log.i("BENCH", "happy: $r")
        check(r.ok && r.stdout.trim() == "6") { "unexpected: $r" }
    }

    @Test
    fun tracebackComesBack() = runBlocking<Unit> {
        val r = PyRunnerClient.exec(ctx, "x = 1 / 0", timeoutMs = 60_000)
        Log.i("BENCH", "traceback: $r")
        check(!r.ok && r.stderr.contains("ZeroDivisionError")) { "unexpected: $r" }
    }

    @Test
    fun hungScriptTimesOutAndRunnerRecovers() = runBlocking<Unit> {
        val t0 = System.currentTimeMillis()
        val r = PyRunnerClient.exec(ctx, "while True: pass", timeoutMs = 8_000)
        val ms = System.currentTimeMillis() - t0
        Log.i("BENCH", "hung: timedOut=${r.timedOut} after ${ms}ms")
        check(r.timedOut) { "expected timeout, got: $r" }

        // Fresh bind after the kill must work (new runner process).
        val again = PyRunnerClient.exec(ctx, "print('alive')", timeoutMs = 60_000)
        Log.i("BENCH", "post-kill: $again")
        check(again.ok && again.stdout.trim() == "alive") { "runner did not recover: $again" }
    }
}
