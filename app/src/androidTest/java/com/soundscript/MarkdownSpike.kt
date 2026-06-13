package com.soundscript

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscript.ai.LlmEngine
import com.soundscript.ai.MarkdownEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Quality test for auto-markdown (phase 2): convert synthetic transcripts and check the structure,
 * across E2B and E4B. Logs the actual Markdown for human review.
 *   tools/run-scaletest.sh  (then) adb shell am instrument -e class com.soundscript.MarkdownSpike ...
 *   adb logcat -d -s MD
 */
class MarkdownSpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val unchecked = Regex("(?m)^\\s*-\\s*\\[ \\]")
    private val checked = Regex("(?m)^\\s*-\\s*\\[[xX]\\]")
    private val bullet = Regex("(?m)^\\s*[-*]\\s+(?!\\[)")     // bullets that aren't checkboxes
    private val ordered = Regex("(?m)^\\s*\\d+\\.\\s+")

    // name, transcript, expectation(md)->pass, why
    private data class Case(val name: String, val text: String, val ok: (String) -> Boolean, val want: String)

    private val cases = listOf(
        Case(
            "grocery-list",
            "okay so for the shop I need milk eggs bread butter and some coffee",
            { md -> (unchecked.findAll(md).count() + checked.findAll(md).count() + bullet.findAll(md).count()) >= 4 },
            "≥4 list items"
        ),
        Case(
            "mixed-todo",
            "things for today I still need to call the dentist and finish the quarterly report, " +
                "I already booked the flights and I picked up the dry cleaning earlier",
            { md -> unchecked.findAll(md).count() >= 2 && checked.findAll(md).count() >= 2 },
            "≥2 unchecked (dentist, report) + ≥2 checked (flights, dry cleaning)"
        ),
        Case(
            "prose-musing",
            "I keep noticing that I focus way better in the mornings so I really should protect " +
                "that first hour and not book meetings then",
            { md -> checked.findAll(md).count() == 0 && unchecked.findAll(md).count() == 0 && bullet.findAll(md).count() <= 1 },
            "stays prose: no checkboxes, ≤1 bullet"
        ),
        Case(
            "steps",
            "to reset the router first unplug it then wait ten seconds then plug it back in and wait for the lights",
            { md -> (ordered.findAll(md).count() >= 3) || (bullet.findAll(md).count() >= 3) },
            "≥3 ordered/bulleted steps"
        ),
        Case(
            "meeting-actions",
            "quick standup notes the auth migration is done QA found a flaky test " +
                "action items fix the flaky test and prepare the client demo",
            { md -> unchecked.findAll(md).count() >= 2 },
            "≥2 action-item checkboxes"
        ),
    )

    @Test
    fun markdown() = runBlocking<Unit> {
        val models = LlmEngine.installed(ctx).filter { it.name.contains("E2B", true) || it.name.contains("E4B", true) }
        Log.i("MD", "models=${models.map { it.name }}")
        for (model in models) {
            val tag = if (model.name.contains("E4B", true)) "E4B" else "E2B"
            var pass = 0
            for (c in cases) {
                val t = SystemClock.elapsedRealtime()
                val md = MarkdownEngine.toMarkdown(ctx, c.text, model).getOrElse { "ERROR:${it.message}" }
                val ms = SystemClock.elapsedRealtime() - t
                val ok = runCatching { c.ok(md) }.getOrDefault(false)
                if (ok) pass++
                Log.i("MD", "[$tag] ${c.name} ${if (ok) "PASS" else "FAIL"} ${ms}ms (want ${c.want})")
                md.lines().forEach { Log.i("MD", "    | $it") }
            }
            Log.i("MD", "[$tag] ==== $pass/${cases.size} passed ====")
            LlmEngine.release()
        }
    }
}
