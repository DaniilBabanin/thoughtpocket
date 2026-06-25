package com.thoughtpocket

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic tests for the two-pass migration + the "at least one pass" invariant (see AppPreferences). */
class TwoPassMigrationTest {

    @Test fun migrationPreservesTodaysFinalPass() {
        // Existing user with live preview on → final on (same model), first mirrors live (same model).
        val c = migrateTwoPass("builtin:BASE_EN_Q5", liveTranscription = true)
        assertEquals(TwoPassConfig(
            firstEnabled = true, firstModelId = "builtin:BASE_EN_Q5",
            finalEnabled = true, finalModelId = "builtin:BASE_EN_Q5",
        ), c)
    }

    @Test fun migrationWithLiveOffKeepsFinalOnly() {
        // liveTranscription off → first pass off, but the final (quality) pass is always preserved on.
        val c = migrateTwoPass("builtin:SMALL_Q5", liveTranscription = false)
        assertEquals(false, c.firstEnabled)
        assertEquals(true, c.finalEnabled)
        assertEquals("builtin:SMALL_Q5", c.finalModelId)
        // First pass reuses the same (Whisper) model so it needs no extra download.
        assertEquals("builtin:SMALL_Q5", c.firstModelId)
    }

    @Test fun bothEnabledPassesThrough() {
        assertEquals(true to true, coercePasses(first = true, final = true))
        assertEquals(true to false, coercePasses(first = true, final = false))
        assertEquals(false to true, coercePasses(first = false, final = true))
    }

    @Test fun cannotDisableBothPasses() {
        // Turning off the last active pass is coerced back — final survives by default.
        assertEquals(false to true, coercePasses(first = false, final = false))
        assertEquals(true to false, coercePasses(first = false, final = false, keepFinalOnConflict = false))
    }
}
