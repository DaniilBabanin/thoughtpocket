package com.thoughtpocket

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.thoughtpocket.service.RecordState
import com.thoughtpocket.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renders-on-screen check of the optimistic queue cards, via UiDevice reading
 * Compose's accessibility tree (an Espresso/compose-rule is unusable here:
 * espresso 3.5's input injection is broken on Android 17, and host-side
 * `uiautomator dump` can't run while instrumentation holds UiAutomation).
 * Items are injected straight into RecordState — no service, no timing races.
 */
class QueueCardRenderCheck {

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val longName = "very_long_meeting_recording_from_stuttgart_quarterly_review_session.wav"

    @After fun cleanup() {
        RecordState.queueRemove(9001)
        RecordState.queueRemove(9002)
    }

    @Test
    fun cardsRenderAndLongPressCancels() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            RecordState.queueAdd(
                RecordState.QueueItem(9001, "second_recording.wav", active = true, progress = 0.42f,
                    partial = "ask not what your country can do for you")
            )
            RecordState.queueAdd(RecordState.QueueItem(9002, longName))

            // Both cards under Recent: active with progress + live text, queued waiting.
            val found = device.wait(Until.hasObject(By.textContains("Transcribing… 42%")), 10_000)
            if (!found) {
                android.util.Log.i("BENCH", "flow at dump time: ${RecordState.queue.value}")
                val f = java.io.File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "a11y.xml"
                )
                device.dumpWindowHierarchy(f)
                f.readText().chunked(3000).take(4).forEachIndexed { i, c -> android.util.Log.i("BENCH", "a11y[$i]: $c") }
            }
            assertTrue("active status missing", found)
            assertTrue("live text missing", device.hasObject(By.textContains("ask not what your country")))
            assertTrue("queued status missing", device.hasObject(By.text("Queued")))
            // MiddleEllipsis is render-only — semantics carry the full filename.
            assertTrue("filename label missing", device.hasObject(By.text(longName)))

            // Long-press the queued card → cancel affordance → tap → card gone.
            device.findObject(By.text(longName)).click(1_200)
            assertTrue("cancel affordance missing", device.wait(Until.hasObject(By.desc("Cancel")), 5_000))
            device.findObject(By.desc("Cancel")).click()
            assertTrue("card did not disappear", device.wait(Until.gone(By.text(longName)), 10_000))

            // The queued item's cancel must not touch the active card.
            assertTrue(device.hasObject(By.textContains("Transcribing… 42%")))
        } finally {
            scenario.close()
        }
    }
}
