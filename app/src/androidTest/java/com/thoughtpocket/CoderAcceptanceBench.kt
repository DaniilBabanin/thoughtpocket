package com.thoughtpocket

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.service.CodeRunState
import com.thoughtpocket.service.CoderRunService
import com.thoughtpocket.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

/**
 * Capability ladder for the coder feature: realistic voice-note bodies +
 * instructions of increasing difficulty, run through the REAL pipeline
 * (CoderRunService → Ornith → gates → :coder exec). Answers "what can the
 * on-device model actually do, and where does it break". Manual run, one
 * ladder per invocation; results via `adb logcat -s ACCEPT`:
 *
 *   adb shell am instrument -w -e class com.thoughtpocket.CoderAcceptanceBench \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Each rung logs RESULT <id> DONE/FAILED/TIMEOUT, attempts, wall-time, and a
 * verdict where the expected answer is checkable. Per-rung ceiling 12 min
 * (Pixel decode ~4-6 tok/s → worst 3-attempt round fits); the whole ladder is
 * a long run — leave the device plugged in.
 */
class CoderAcceptanceBench {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Rung(
        val id: String,
        val title: String,
        val body: String,
        val instruction: String,
        /** Substrings that must ALL appear in stdout for a PASS verdict; empty = eyeball. */
        val expect: List<String> = emptyList(),
    )

    private val ladder = listOf(
        // L1 — baseline arithmetic over messy prose.
        Rung(
            "L1-sum", "Spending today",
            "ok so groceries came to 42.50 then the pharmacy was 17.25 and later the taxi, that was 12.80 I think",
            "Sum all the amounts.",
            expect = listOf("72.55"),
        ),
        // L2 — multi-step: group, average, argmax.
        Rung(
            "L2-week", "Coffee log",
            "monday 2 coffees, tuesday 1, wednesday 3, thursday 2, friday 4, saturday 1, sunday 2",
            "Total coffees, average per day rounded to 2 decimals, and which day had the most.",
            expect = listOf("15", "2.14", "friday"),
        ),
        // L3 — date arithmetic.
        Rung(
            "L3-dates", "Trip planning",
            "flight out is 2026-08-14 and we fly back 2026-09-02, need to sort the cat sitter",
            "How many nights are we away? Also list the number of full weekends (Sat+Sun both away).",
            expect = listOf("19"),
        ),
        // L4 — text analytics.
        Rung(
            "L4-words", "Brain dump",
            "meeting notes meeting agenda project deadline project meeting review deadline deadline sprint review planning sprint sprint",
            "Top 3 most frequent words with their counts, most frequent first.",
            expect = listOf("meeting", "deadline", "sprint", "3"),
        ),
        // L5 — semi-structured parsing + grouped totals.
        Rung(
            "L5-parse", "Shopping",
            "3x milk 1.20 each, 2x bread 2.50, eggs 3.99, 4x yogurt 0.89, 2x cheese 4.75 each",
            "Line totals per item and the grand total.",
            expect = listOf("3.60", "5.00", "3.99", "3.56", "9.50", "25.65"),
        ),
        // L6 — statistics + outlier reasoning.
        Rung(
            "L6-stats", "Run times",
            "last runs in minutes: 31, 29, 34, 30, 55, 28, 32, 30",
            "Mean and median, and flag any run more than 1.5 standard deviations above the mean.",
            expect = listOf("55"),
        ),
        // L7 — "graph that": ASCII bar chart, stdlib only.
        Rung(
            "L7-chart", "Monthly spending",
            "rent 1200, food 430, transport 120, fun 210, savings 300",
            "Draw a horizontal bar chart of these categories scaled to a max width of 40 characters, with labels and values.",
            expect = listOf("rent", "savings"),
        ),
        // L8 — small optimization/algorithm.
        Rung(
            "L8-plan", "Errands",
            "post office 15 min, pharmacy 20 min, dry cleaning 10 min, grocery run 45 min, bank 25 min, library 15 min",
            "I only have 75 minutes. Pick the combination that fits the most errands in, and show total time.",
            expect = listOf("4"),
        ),
        // L9 — iterative simulation with formatted table output.
        Rung(
            "L9-interest", "Savings idea",
            "starting with 5000, adding 200 every month, say 3.5 percent yearly interest compounded monthly",
            "Show a table of the balance at the end of each of the first 12 months, then the final balance.",
            expect = listOf("12"),
        ),
        // L10 — mini-parser + derived metrics over markdown.
        Rung(
            "L10-checklist", "Packing list",
            "- [x] passport\n- [x] tickets\n- [ ] adapter\n- [x] meds\n- [ ] sunscreen\n- [x] headphones\n- [x] charger\n- [ ] book",
            "Parse the checklist: how many done vs open, completion percentage, and the longest streak of consecutive done items.",
            // Streaks in x,x,_,x,_,x,x,_ are 2/1/2 → longest 2. (First run shipped a
            // wrong oracle of 4; the model was right and the check was wrong.)
            expect = listOf("5", "3", "62", "2"),
        ),
    )

    @Test
    fun ladder() = runBlocking<Unit> {
        check(CoderModelManager.isInstalled(ctx)) { "no coder model installed on device" }
        val dao = NotesDb.get(ctx).notes()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var pass = 0
        var done = 0
        try {
            for (rung in ladder) {
                val id = dao.insert(Note(createdAt = 1_720_000_000_000, text = rung.body, title = rung.title))
                val t0 = System.currentTimeMillis()
                try {
                    CoderRunService.run(ctx, id, rung.instruction)
                    val terminal = withTimeoutOrNull(12 * 60_000L) {
                        CodeRunState.status.first {
                            it.phase == CodeRunState.Phase.DONE || it.phase == CodeRunState.Phase.FAILED
                        }
                    }
                    val secs = (System.currentTimeMillis() - t0) / 1000
                    when {
                        terminal == null -> {
                            Log.i("ACCEPT", "RESULT ${rung.id} TIMEOUT after ${secs}s")
                            CoderRunService.cancel(ctx)
                        }
                        terminal.phase == CodeRunState.Phase.DONE -> {
                            done++
                            val out = terminal.result.lowercase()
                            val missing = rung.expect.filter { it.lowercase() !in out }
                            val verdict = if (missing.isEmpty()) { pass++; "PASS" } else "CHECK (missing: $missing)"
                            Log.i("ACCEPT", "RESULT ${rung.id} DONE attempts=${terminal.attempt} ${secs}s $verdict")
                            Log.i("ACCEPT", "OUTPUT ${rung.id}: ${terminal.result.take(400).replace("\n", " ⏎ ")}")
                        }
                        else -> Log.i("ACCEPT", "RESULT ${rung.id} FAILED after ${secs}s attempts=${terminal.attempt}: ${terminal.result.take(150)}")
                    }
                } finally {
                    // End the session per rung: otherwise the next rung becomes a
                    // "follow-up" carrying this rung's turn as context, contaminating
                    // the measurement. Costs a model reload each time (realistic UX).
                    CoderRunService.end(ctx)
                    withTimeoutOrNull(15_000L) {
                        CodeRunState.status.first { it.phase == CodeRunState.Phase.IDLE }
                    }
                    dao.getById(id)?.let { dao.delete(it) }
                }
            }
            Log.i("ACCEPT", "LADDER SUMMARY: $done/${ladder.size} completed, $pass/${ladder.size} auto-PASS")
        } finally {
            CoderRunService.end(ctx)
            scenario.close()
        }
    }
}
