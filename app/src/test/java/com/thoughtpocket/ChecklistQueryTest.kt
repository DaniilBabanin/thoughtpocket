package com.thoughtpocket

import com.thoughtpocket.ai.ChecklistQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun gateAcceptsChecklistQuestions() {
        assertTrue(ChecklistQuery.looksLikeChecklistQuestion("what do I still need to buy?"))
        assertTrue(ChecklistQuery.looksLikeChecklistQuestion("what's still open from last week"))
    }

    @Test fun gateSkipsQuestionsWithoutHintWords() {
        assertFalse(ChecklistQuery.looksLikeChecklistQuestion("what did I promise Sam?"))
        assertFalse(ChecklistQuery.looksLikeChecklistQuestion("Summarize these notes into a concise digest of the key points."))
    }

    @Test fun gateSkipsSummaryAsksEvenWithHintWords() {
        // "done"/"need"/"open" are hint words, but a summary/analysis ask must fall
        // through to the general LLM path, not return a checkbox dump.
        assertFalse(ChecklistQuery.looksLikeChecklistQuestion("summarize what I got done this week"))
        assertFalse(ChecklistQuery.looksLikeChecklistQuestion("give me an overview of what I need to do"))
        assertFalse(ChecklistQuery.looksLikeChecklistQuestion("what are the main themes in my open projects?"))
        assertFalse(ChecklistQuery.looksLikeChecklistQuestion("recap what got done"))
    }
}
