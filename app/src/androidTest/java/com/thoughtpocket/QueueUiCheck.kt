package com.thoughtpocket

import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.service.RecordState
import com.thoughtpocket.service.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end check of the optimistic transcription queue (2026-07-13):
 * import 3 files → 3 QueueItems appear with source-filename labels →
 * cancel the last while queued → only 2 notes are created.
 * Uses the staged import-check WAVs (silent 440 Hz sines → VAD-skipped, so
 * transcription is fast and enrichment [blank text] never loads Gemma).
 * Real notes are inserted into the device DB — the test deletes them after.
 */
class QueueUiCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importQueueLifecycleAndCancel() = runBlocking<Unit> {
        val staged = ctx.getExternalFilesDir("import-check")!!
        val longName = "very_long_meeting_recording_from_stuttgart_quarterly_review_session.wav"
        val srcs = listOf("t48k16.wav", "t96k24.wav", "t96k32f.wav").map { File(staged, it) }
        if (srcs.any { !it.exists() }) { Log.i("BENCH", "SKIP: import-check wavs not staged"); return@runBlocking }
        // Copy #1 under a long name to exercise the filename labels end-to-end.
        val work = listOf(
            srcs[0].copyTo(File(ctx.cacheDir, longName), overwrite = true),
            srcs[1].copyTo(File(ctx.cacheDir, "q2.wav"), overwrite = true),
            srcs[2].copyTo(File(ctx.cacheDir, "q3.wav"), overwrite = true),
        )
        val dao = NotesDb.get(ctx).notes()
        val notesBefore = dao.all().first().map { it.id }.toSet()

        val scenario = ActivityScenario.launch(com.thoughtpocket.ui.MainActivity::class.java)
        try {
            ctx.startForegroundService(
                RecordingService.importUrisIntent(ctx, work.map { Uri.fromFile(it) })
            )

            // All three must appear as optimistic items, labelled by filename.
            val items = withTimeout(30_000) {
                while (RecordState.queue.value.size < 3) delay(100)
                RecordState.queue.value
            }
            Log.i("BENCH", "queue: ${items.map { it.label }}")
            assertEquals(3, items.size)
            assertTrue("long filename label missing", items.any { it.label == longName })
            assertTrue("q3 label missing", items.any { it.label == "q3.wav" })

            // Cancel the LAST item while it's still queued (consumer is serial).
            val victim = items.first { it.label == "q3.wav" }
            RecordingService.cancelClip(ctx, victim.id)
            withTimeout(10_000) {
                while (RecordState.queue.value.any { it.id == victim.id }) delay(50)
            }
            Log.i("BENCH", "cancelled ${victim.label}; queue now ${RecordState.queue.value.map { it.label }}")

            // Drain: the two surviving clips become notes; the cancelled one never does.
            withTimeout(120_000) {
                while (RecordState.queue.value.isNotEmpty()) delay(200)
            }
            delay(500) // let the last insert commit
            val created = dao.all().first().filter { it.id !in notesBefore }
            Log.i("BENCH", "created ${created.size} notes: ${created.map { it.text.take(30) }}")
            assertEquals("cancelled clip must not become a note", 2, created.size)

            // Leave the device as found.
            created.forEach { dao.delete(it) }
        } finally {
            scenario.close()
            work.forEach { it.delete() }
        }
    }
}
