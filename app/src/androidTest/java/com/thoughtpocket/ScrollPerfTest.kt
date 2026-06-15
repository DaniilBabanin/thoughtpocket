package com.thoughtpocket

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scroll-performance regression guard with a large (300-note) list.
 *
 * Seeds 300 notes via the real Room DAO (so the app renders them), launches the app, flings the
 * Home list and the Settings screen, and reads frame stats from `dumpsys gfxinfo`. Asserts on:
 *   - the **slow-UI-thread ratio** — CPU/recompose-bound, GPU-independent, so it's reliable even on a
 *     software-GPU emulator. This is what spiked during the redesign's jank regressions (per-frame
 *     recomposition, uncached glass/glow, filesystem I/O on scroll).
 *   - a **loose P50 frame-time** ceiling — catches catastrophic (slideshow) regressions.
 * Exact P90/P99 are only logged (not asserted): they're dominated by software-GPU stalls on emulators.
 * Run: `./gradlew :app:connectedDebugAndroidTest --tests com.thoughtpocket.ScrollPerfTest`.
 */
@RunWith(AndroidJUnit4::class)
class ScrollPerfTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val pkg = ctx.packageName
    private val dao = NotesDb.get(ctx).notes()
    private val device = UiDevice.getInstance(instr)
    private val seeded = mutableListOf<Long>()

    private val tagPool = listOf("work", "home", "ideas", "travel", "errands", "family", "health")

    @Before
    fun seed() {
        runBlocking {
            repeat(NOTE_COUNT) { i ->
                val id = dao.insert(
                    Note(
                        createdAt = System.currentTimeMillis() - i * 3_600_000L,
                        text = "Voice note number $i. ${"Some transcript content to give the card body. ".repeat(2)}",
                        title = "Note $i — ${tagPool[i % tagPool.size]} update",
                        markdown = if (i % 3 == 0) "# Note $i\n- [ ] first task\n- [ ] second task\n- [x] done item" else "",
                        tags = listOf(tagPool[i % tagPool.size], tagPool[(i + 2) % tagPool.size]),
                    )
                )
                seeded += id
            }
        }
    }

    @After
    fun cleanup() {
        runBlocking { seeded.forEach { id -> dao.delete(Note(id = id, createdAt = 0L, text = "")) } }
        seeded.clear()
    }

    @Test
    fun scrollPerformanceWith300Notes() {
        launchApp()

        val home = scrollAndMeasure("HOME-$NOTE_COUNT-notes")
        assertReasonable(home)

        // Settings is the densest screen (model list etc.) — was the worst regression offender.
        device.findObject(By.desc("Settings"))?.click()
        device.waitForIdle()
        Thread.sleep(1500) // let the entry reveal settle
        val settings = scrollAndMeasure("SETTINGS")
        assertReasonable(settings)
    }

    private fun launchApp() {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 8_000)
        device.waitForIdle()
        Thread.sleep(2_500) // let the list compose + reveal animation settle
    }

    private fun scrollAndMeasure(label: String): Metrics {
        device.executeShellCommand("dumpsys gfxinfo $pkg reset")
        val w = device.displayWidth
        val top = (device.displayHeight * 0.34f).toInt()
        val bottom = (device.displayHeight * 0.72f).toInt()
        repeat(8) {
            device.swipe(w / 2, bottom, w / 2, top, 8) // fling up (scroll down)
            device.swipe(w / 2, top, w / 2, bottom, 8) // fling down
        }
        device.waitForIdle()
        return parse(label, device.executeShellCommand("dumpsys gfxinfo $pkg"))
    }

    private fun parse(label: String, out: String): Metrics {
        fun int(re: String) = Regex(re).find(out)?.groupValues?.get(1)?.toIntOrNull()
        val m = Metrics(
            label = label,
            total = int("""Total frames rendered: (\d+)""") ?: 0,
            janky = int("""Janky frames: (\d+)""") ?: 0,
            p50 = int("""50th percentile: (\d+)ms""") ?: -1,
            p90 = int("""90th percentile: (\d+)ms""") ?: -1,
            p99 = int("""99th percentile: (\d+)ms""") ?: -1,
            slowUi = int("""Number Slow UI thread: (\d+)""") ?: 0,
        )
        Log.i(TAG, m.toString())
        return m
    }

    private fun assertReasonable(m: Metrics) {
        assertTrue("No frames captured for ${m.label} — did the scroll run?", m.total > 0)
        val slowRatio = m.slowUi.toFloat() / m.total
        assertTrue(
            "Slow-UI-thread frames too high (recompose/CPU regression): $m (ratio=$slowRatio)",
            slowRatio < MAX_SLOW_UI_RATIO,
        )
        assertTrue("P50 frame time too high (perf regression): $m", m.p50 in 0..MAX_P50_MS)
    }

    private data class Metrics(
        val label: String, val total: Int, val janky: Int,
        val p50: Int, val p90: Int, val p99: Int, val slowUi: Int,
    ) {
        override fun toString() =
            "$label: total=$total janky=$janky (${if (total > 0) janky * 100 / total else 0}%) " +
                "p50=${p50}ms p90=${p90}ms p99=${p99}ms slowUI=$slowUi"
    }

    private companion object {
        const val TAG = "ScrollPerfTest"
        const val NOTE_COUNT = 300
        const val MAX_P50_MS = 60          // generous: post-fix ~16ms; the regression was 85–150ms
        const val MAX_SLOW_UI_RATIO = 0.5f // post-fix ~0; regressions spiked to 0.8+
    }
}
