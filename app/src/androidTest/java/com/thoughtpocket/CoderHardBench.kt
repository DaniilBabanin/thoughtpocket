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
 * Hard ladder — push until it breaks. Same real pipeline as
 * CoderAcceptanceBench, meaner rungs: long-context parsing, mixed-format
 * normalization, true optimization (the greedy answer is WRONG on H3),
 * simulation, money-precision traps, plus two probes with no auto-check
 * (deliberate vagueness; a task impossible offline — watching for honest
 * failure vs hallucination). All expected values computed offline with
 * CPython, not by hand (H3's hand answer was wrong; the oracle script says 13).
 *
 *   adb shell am instrument -w -e class com.thoughtpocket.CoderHardBench \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s ACCEPT2
 */
class CoderHardBench {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Rung(
        val id: String,
        val title: String,
        val body: String,
        val instruction: String,
        val expect: List<String> = emptyList(),
        val ceilingMin: Long = 12,
    )

    private val longWeek = """
        monday: grabbed coffee and a croissant, food, 12.40. bus to work, transport, 3.20. after work
        met lena for drinks, fun, 15.00. tuesday: groceries were food 8.99 and then another food run
        later 23.50 because I forgot the pasta. bus again transport 3.20. cinema with tom, fun, 32.50.
        wednesday: lunch place downtown, food 6.75, big shop food 31.20. train ticket transport 12.00.
        that escape room thing, fun, 9.99. thursday: food 9.99 bakery, food 14.30 thai takeout.
        bus transport 3.20. concert tickets!! fun 60.00, plus merch fun 12.50. friday: food 22.10
        sushi with the team. taxi home transport 45.00 ugh. bar after, fun 8.00, then karaoke fun 25.00.
        saturday: brunch fun 11.11, arcade fun 7.77. sunday: board game cafe fun 19.99, bus transport 3.20.
    """.trimIndent()

    private val ladder = listOf(
        // H1 — long-context parsing + grouped sums (24 amounts, 3 categories).
        Rung(
            "H1-longweek", "Week of spending", longWeek,
            "Total per category (food, transport, fun) and the overall total.",
            expect = listOf("129.23", "69.8", "201.86", "400.89"),
        ),
        // H2 — mixed date formats → ISO, sorted.
        Rung(
            "H2-dates", "Appointments",
            "dentist on 14.8.2026 (german format), project due 2026-09-02, " +
                "sarah's party Sep 5 2026, and the visa call on 08/22/2026 (US format)",
            "Normalize every date to YYYY-MM-DD and list them in chronological order.",
            expect = listOf("2026-08-14", "2026-08-22", "2026-09-02", "2026-09-05"),
        ),
        // H3 — optimization where greedy fails: max priority within 60 min. Optimal = 13.
        Rung(
            "H3-priorities", "Errands with priorities",
            "post office 15 min priority 3, pharmacy 20 min priority 5, dry cleaning 10 min " +
                "priority 2, grocery run 45 min priority 8, bank 25 min priority 4, library 15 min priority 3",
            "I have 60 minutes. Pick the errands that maximize total priority and show the total priority and time.",
            expect = listOf("13"),
        ),
        // H4 — stateful simulation: loan payoff month.
        Rung(
            "H4-loan", "Loan idea",
            "borrowing 10000, interest is 2 percent per month on the remaining balance, I can pay back 500 a month",
            "In which month is the loan fully paid off, and how much total interest do I pay?",
            expect = listOf("26"),
        ),
        // H5 — natural-language arithmetic with precedence.
        Rung(
            "H5-wordmath", "Quick math",
            "seven plus three times four minus two",
            "Evaluate this expression respecting operator precedence and print the result.",
            expect = listOf("17"),
        ),
        // H6 — interval merging: overlaps + free time.
        Rung(
            "H6-meetings", "Tomorrow's meetings",
            "standup 9:00-10:30, design review 10:00-11:00, lunch talk 13:00-14:00, " +
                "planning 13:30-15:00, 1:1 16:00-17:00",
            "My workday is 9:00 to 17:00. How many pairs of meetings overlap, and how many minutes are actually free?",
            expect = listOf("2", "180"),
        ),
        // H7 — combinatorics with a constraint.
        Rung(
            "H7-seating", "Dinner seating",
            "five friends around a round table: anna, ben, cora, dan, eve. anna and ben had a fight",
            "How many distinct circular seating arrangements keep anna and ben apart? Rotations count as the same arrangement.",
            expect = listOf("12"),
        ),
        // H8 — money precision traps (floating point discipline).
        Rung(
            "H8-precision", "Splitting bills",
            "three receipts: 10.10, 20.20, 30.30. also need a third of 100 euros for the gift pool",
            "Sum the receipts exactly, and compute one third of 100 rounded to cents. No floating point errors please.",
            expect = listOf("60.60", "33.33"),
        ),
        // H9 — deliberate vagueness (no auto-check: does it do something sensible?).
        Rung(
            "H9-vague", "Numbers from the gym",
            "bench 60 65 65 70 72.5 75, squat 80 85 90 92.5 100 105, deadlift 100 110 120 125 130 140",
            "Make sense of this and tell me something useful.",
            ceilingMin = 12,
        ),
        // H10 — impossible offline (no network): honest failure vs hallucinated rate?
        Rung(
            "H10-impossible", "Trip budget",
            "have 100 euros for the copenhagen day trip",
            "Convert my 100 euros to Danish krone at today's exchange rate.",
            ceilingMin = 12,
        ),
    )

    @Test
    fun hardLadder() = runBlocking<Unit> {
        check(CoderModelManager.isInstalled(ctx)) { "no coder model installed on device" }
        // Solo re-run / post-mortem: -e rung H1 filters to one rung.
        val only = InstrumentationRegistry.getArguments().getString("rung")
        val rungs = if (only == null) ladder else ladder.filter { it.id.startsWith(only) }
        val dao = NotesDb.get(ctx).notes()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var done = 0
        var pass = 0
        try {
            for (rung in rungs) {
                val id = dao.insert(Note(createdAt = 1_720_000_000_000, text = rung.body, title = rung.title))
                val t0 = System.currentTimeMillis()
                try {
                    CoderRunService.run(ctx, id, rung.instruction)
                    val terminal = withTimeoutOrNull(rung.ceilingMin * 60_000L) {
                        CodeRunState.status.first {
                            it.phase == CodeRunState.Phase.DONE || it.phase == CodeRunState.Phase.FAILED
                        }
                    }
                    val secs = (System.currentTimeMillis() - t0) / 1000
                    when {
                        terminal == null -> {
                            Log.i("ACCEPT2", "RESULT ${rung.id} TIMEOUT after ${secs}s")
                            CoderRunService.cancel(ctx)
                        }
                        terminal.phase == CodeRunState.Phase.DONE -> {
                            done++
                            val out = terminal.result.lowercase()
                            val missing = rung.expect.filter { it.lowercase() !in out }
                            val verdict = when {
                                rung.expect.isEmpty() -> "EYEBALL"
                                missing.isEmpty() -> { pass++; "PASS" }
                                else -> "CHECK (missing: $missing)"
                            }
                            Log.i("ACCEPT2", "RESULT ${rung.id} DONE attempts=${terminal.attempt} ${secs}s $verdict")
                            Log.i("ACCEPT2", "OUTPUT ${rung.id}: ${terminal.result.take(500).replace("\n", " ⏎ ")}")
                        }
                        else -> {
                            Log.i("ACCEPT2", "RESULT ${rung.id} FAILED after ${secs}s attempts=${terminal.attempt}: ${terminal.result.take(200)}")
                            terminal.failedAttempts.forEachIndexed { i, (code, err) ->
                                Log.i("ACCEPT2", "POSTMORTEM ${rung.id} attempt${i + 1} err: $err")
                                Log.i("ACCEPT2", "POSTMORTEM ${rung.id} attempt${i + 1} code: ${code.replace("\n", " ⏎ ").take(700)}")
                            }
                        }
                    }
                } finally {
                    CoderRunService.end(ctx)
                    withTimeoutOrNull(15_000L) {
                        CodeRunState.status.first { it.phase == CodeRunState.Phase.IDLE }
                    }
                    dao.getById(id)?.let { dao.delete(it) }
                }
            }
            Log.i("ACCEPT2", "HARD LADDER SUMMARY: $done/${ladder.size} completed, $pass auto-PASS of ${ladder.count { it.expect.isNotEmpty() }} checkable")
        } finally {
            CoderRunService.end(ctx)
            scenario.close()
        }
    }
}
