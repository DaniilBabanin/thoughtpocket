package com.thoughtpocket

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.ai.InteractEngine
import com.thoughtpocket.ai.InteractOp
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Validates the hybrid Interact engine on-device: natural-language commands → correct structured op
 * (the JSON-intent path that the fast model must get right). Logs `INT`.
 *   tools/run-scaletest.sh ; adb shell am instrument -e class com.thoughtpocket.InteractSpike \
 *     -w com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner ; adb logcat -d -s INT
 */
class InteractSpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val md = "Grocery run\n- [ ] milk\n- [ ] eggs\n- [x] coffee\n- [ ] bread"

    private data class Case(val cmd: String, val ok: (InteractOp) -> Boolean, val want: String)

    private val cases = listOf(
        Case("I got the milk", { it is InteractOp.Check && it.on && it.item.contains("milk", true) }, "Check milk on"),
        Case("actually I haven't done the eggs yet", { it is InteractOp.Check && !it.on && it.item.contains("egg", true) }, "Uncheck eggs"),
        Case("add olive oil to the list", { it is InteractOp.Add && it.item.contains("olive oil", true) }, "Add olive oil"),
        Case("remove the bread", { it is InteractOp.Remove && it.item.contains("bread", true) }, "Remove bread"),
        Case("suggest some more things I might need", { it is InteractOp.Suggest }, "Suggest"),
    )

    @Test
    fun interpret() = runBlocking<Unit> {
        var pass = 0
        for (c in cases) {
            val t = SystemClock.elapsedRealtime()
            val op = InteractEngine.interpret(ctx, md, c.cmd).getOrElse { InteractOp.Unknown("ERR:${it.message}") }
            val ms = SystemClock.elapsedRealtime() - t
            val ok = runCatching { c.ok(op) }.getOrDefault(false)
            if (ok) pass++
            Log.i("INT", "'${c.cmd}' -> $op ${if (ok) "PASS" else "FAIL"} ${ms}ms (want ${c.want})")
        }
        Log.i("INT", "==== $pass/${cases.size} ====")

        val sug = InteractEngine.suggestAdditions(ctx, md).getOrElse { emptyList() }
        Log.i("INT", "suggestAdditions -> $sug")
    }
}
