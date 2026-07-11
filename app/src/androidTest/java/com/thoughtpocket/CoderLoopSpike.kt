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
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * SPIKE (Phase 6, coder feature): whole loop end-to-end on device/emulator —
 * note from Room → CoderRunService → generate (GGUF in files/coder) → gate →
 * :coder exec → CodeRunState DONE/FAILED. Small models may legitimately fail
 * the task; the check is that the loop TERMINATES with an honest phase, never
 * hangs. Manual run; results via `adb logcat -s BENCH`.
 */
class CoderLoopSpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loopTerminatesHonestly() = runBlocking<Unit> {
        if (!CoderModelManager.isInstalled(ctx)) {
            Log.i("BENCH", "SKIP: no coder model installed (push via tools/push-models.sh)")
            return@runBlocking
        }
        val dao = NotesDb.get(ctx).notes()
        val id = dao.insert(
            Note(
                createdAt = 1_720_000_000_000,
                text = "Groceries were 42.50, pharmacy 17.25, and the taxi cost 12.80.",
                title = "Spending today",
            )
        )
        // FGS start needs a foregrounded app on API 31+.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            CoderRunService.run(ctx, id, "Calculate the total of the amounts in the note.")
            val terminal = withTimeout(10 * 60_000L) {
                CodeRunState.status.first {
                    it.phase == CodeRunState.Phase.DONE || it.phase == CodeRunState.Phase.FAILED
                }
            }
            Log.i("BENCH", "loop terminal phase=${terminal.phase} attempts=${terminal.attempt} result=${terminal.result.take(200)}")
            if (terminal.phase == CodeRunState.Phase.DONE) {
                check(terminal.result.contains("72.55")) {
                    "script ran but result wrong: ${terminal.result.take(200)}"
                }
            }
        } finally {
            CoderRunService.end(ctx)
            dao.getById(id)?.let { dao.delete(it) }
            scenario.close()
        }
    }
}
