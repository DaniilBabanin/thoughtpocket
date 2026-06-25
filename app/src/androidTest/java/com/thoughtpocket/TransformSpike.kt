package com.thoughtpocket

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.ai.TransformEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Quality spike for one-tap transform presets: run each preset (+ a free-form typed instruction)
 * over a messy voice-note on E4B and log the rewrite for human review, with light structural checks.
 *   adb shell am instrument -e class com.thoughtpocket.TransformSpike \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s TX
 */
class TransformSpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val bullet = Regex("(?m)^\\s*[-*]\\s+")
    private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private val note =
        "so I talked to the landlord today about the leak in the bathroom and he said he'll send " +
            "someone on thursday, also I need to remember to pay the electricity bill it's overdue, " +
            "and I was thinking we should maybe start looking for a new place anyway because this " +
            "one is getting really expensive and the area has gone downhill a bit lately"

    @Test
    fun transforms() = runBlocking<Unit> {
        val src = words(note)
        Log.i("TX", "source note: $src words")

        for (p in TransformEngine.Preset.entries) {
            val t = SystemClock.elapsedRealtime()
            val out = TransformEngine.transform(ctx, note, p).getOrElse { "ERROR:${it.message}" }
            val ms = SystemClock.elapsedRealtime() - t
            val ok = when (p) {
                TransformEngine.Preset.KEY_POINTS -> bullet.findAll(out).count() >= 2
                TransformEngine.Preset.SHORTER -> words(out) in 1 until src
                TransformEngine.Preset.LONGER -> words(out) > src
                TransformEngine.Preset.FORMAL -> out.isNotBlank() && out != note
            }
            Log.i("TX", "[${p.label}] ${if (ok) "PASS" else "CHECK"} ${ms}ms (${words(out)} words)")
            out.lines().forEach { Log.i("TX", "    | $it") }
        }

        // Typed free-form instruction (the InteractOp.Rewrite path) — proves the same engine handles
        // arbitrary user commands, not just presets.
        val freeform = "rewrite as a short polite email to the landlord"
        val t = SystemClock.elapsedRealtime()
        val out = TransformEngine.transformWith(ctx, note, freeform).getOrElse { "ERROR:${it.message}" }
        Log.i("TX", "[free: $freeform] ${SystemClock.elapsedRealtime() - t}ms (${words(out)} words)")
        out.lines().forEach { Log.i("TX", "    | $it") }

        LlmEngine.release()
    }
}
