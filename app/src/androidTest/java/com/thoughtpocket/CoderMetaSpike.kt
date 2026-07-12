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
 * SPIKE: the "meta note" capability — a coding task that reads the `notes`
 * global (all notes, injected at runtime from a cache file, not the prompt).
 * Seeds notes with a known number of TODO mentions and asks for a cross-note
 * count. Manual run; result via `adb logcat -s BENCH`.
 */
class CoderMetaSpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun countsAcrossAllNotes() = runBlocking<Unit> {
        if (!CoderModelManager.isInstalled(ctx)) { Log.i("BENCH", "SKIP: no coder model"); return@runBlocking }
        val dao = NotesDb.get(ctx).notes()
        val seeded = listOf(
            "TODO buy milk. TODO call the dentist.",   // 2
            "grocery list, nothing pending here",      // 0
            "TODO renew passport",                     // 1
        ).map { dao.insert(Note(createdAt = 1_720_000_000_000, text = it, title = "seed")) }
        // The meta note itself: instruction lives in the prompt, not counted unless it says TODO.
        val metaId = dao.insert(Note(createdAt = 1_720_000_000_001, text = "count things", title = "Meta"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            CoderRunService.run(ctx, metaId, "Count how many times the word TODO appears across all my notes.")
            val terminal = withTimeoutOrNull(12 * 60_000L) {
                CodeRunState.status.first { it.phase == CodeRunState.Phase.DONE || it.phase == CodeRunState.Phase.FAILED }
            }
            Log.i("BENCH", "meta phase=${terminal?.phase} result=${terminal?.result?.take(200)}")
            // Plumbing check: the script ran to completion (no NameError), so the
            // `notes` global was injected. Correctness (== 3) needs a capable model
            // — verify on the Pixel with Ornith; the emulator's 0.5B is too weak.
            check(terminal?.phase == CodeRunState.Phase.DONE) { "run did not finish: ${terminal?.result}" }
        } finally {
            CoderRunService.end(ctx)
            (seeded + metaId).forEach { id -> dao.getById(id)?.let { dao.delete(it) } }
            scenario.close()
        }
    }
}
