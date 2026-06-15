package com.soundscript

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.soundscript.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Structural regression guard for the "Reach" UI: every screen renders and the bottom-bar navigation
 * works. Runs without AI models (no recording/embedding needed to display these surfaces).
 * Run: `./gradlew :app:connectedDebugAndroidTest --tests com.soundscript.UiSmokeTest`.
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeRenders() {
        compose.onNodeWithText("SoundScript").assertExists()
        compose.onNodeWithText("Search notes by meaning").assertExists()
        compose.onNodeWithContentDescription("Record").assertExists() // docked record orb
    }

    @Test
    fun bottomBarNavigatesAllTabs() {
        compose.onNodeWithContentDescription("Action items").performClick()
        compose.onNodeWithText("Action items").assertExists()

        compose.onNodeWithContentDescription("Ask").performClick()
        compose.onNodeWithText("Ask your notes").assertExists()

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Reduce animations").assertExists() // the motion toggle

        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithText("SoundScript").assertExists()
    }
}
