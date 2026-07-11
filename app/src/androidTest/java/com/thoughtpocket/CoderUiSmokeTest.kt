package com.thoughtpocket

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gating regression for the experimental coder UI: the "Code this" section on
 * a note only exists when the pref is on AND a model is installed. Runs
 * without the coder model exercising the off-state everywhere; the on-state
 * assertion is skipped unless a model is staged (emulator has one).
 */
@RunWith(AndroidJUnit4::class)
class CoderUiSmokeTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var noteId: Long = -1

    private fun seedNote(): Long = runBlocking {
        NotesDb.get(ctx).notes().insert(
            Note(createdAt = 1_720_000_000_000, text = "numbers 1 2 3", title = "CoderSmokeNote")
        )
    }

    @After fun cleanup(): Unit = runBlocking {
        if (noteId >= 0) NotesDb.get(ctx).notes().getById(noteId)?.let { NotesDb.get(ctx).notes().delete(it) }
    }

    @Test
    fun sectionHiddenWhenFlagOff() {
        AppPreferences(ctx).experimentalCoder = false
        noteId = seedNote()
        compose.onNodeWithText("CoderSmokeNote").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Code this").assertCountEquals(0)
    }

    @Test
    fun sectionShownWhenFlagOnAndModelInstalled() {
        if (!CoderModelManager.isInstalled(ctx)) return // off-device state covered above
        AppPreferences(ctx).experimentalCoder = true
        noteId = seedNote()
        compose.onNodeWithText("CoderSmokeNote").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Code this").performScrollTo().assertIsDisplayed()
        AppPreferences(ctx).experimentalCoder = false
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
    fetchSemanticsNodes().let { check(it.size == n) { "expected $n nodes, got ${it.size}" } }
}
