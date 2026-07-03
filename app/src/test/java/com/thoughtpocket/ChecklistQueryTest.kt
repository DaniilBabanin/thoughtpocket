package com.thoughtpocket

import com.thoughtpocket.ai.ChecklistQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The classify LLM call isn't testable on the JVM, but the window→days mapping applied to its
 * output is pure. (The topic/time filtering itself is inline in the suspend path — covered
 * on-device via ComplexQueryTest.)
 */
class ChecklistQueryTest {
    @Test fun mapsKnownWindows() {
        assertEquals(1, ChecklistQuery.windowDays("today"))
        assertEquals(2, ChecklistQuery.windowDays("yesterday"))
        for (w in listOf("this_week", "last_week", "week")) assertEquals(w, 7, ChecklistQuery.windowDays(w))
        for (w in listOf("this_month", "last_month", "month")) assertEquals(w, 31, ChecklistQuery.windowDays(w))
    }

    @Test fun allOrUnknownMeansNoTimeFilter() {
        assertNull(ChecklistQuery.windowDays("all"))
        assertNull(ChecklistQuery.windowDays(""))
        assertNull(ChecklistQuery.windowDays("fortnight"))
    }

    @Test fun normalizesCaseAndWhitespace() {
        // The LLM doesn't always echo the enum verbatim.
        assertEquals(7, ChecklistQuery.windowDays(" Last_Week "))
    }
}
