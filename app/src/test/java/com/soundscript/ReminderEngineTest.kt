package com.soundscript

import com.soundscript.ai.ReminderEngine
import com.soundscript.ai.ReminderEngine.startMillis
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderEngineTest {
    private val utc = ZoneId.of("UTC")
    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun dateAndTimeIsTimedEvent() {
        val (millis, allDay) = startMillis("2026-06-16", "15:30", utc)!!
        assertEquals(epoch(2026, 6, 16, 15, 30), millis)
        assertTrue(!allDay)
    }

    @Test
    fun unpaddedHourParses() {
        val (millis, _) = startMillis("2026-06-16", "9:00", utc)!!
        assertEquals(epoch(2026, 6, 16, 9, 0), millis)
    }

    @Test
    fun dateOnlyIsAllDay() {
        val (millis, allDay) = startMillis("2026-06-16", "", utc)!!
        assertEquals(epoch(2026, 6, 16, 0, 0), millis)
        assertTrue(allDay)
    }

    @Test
    fun missingOrBadDateIsNull() {
        assertNull(startMillis("", "15:30", utc))
        assertNull(startMillis("next tuesday", "", utc))
    }

    @Test
    fun unparseableOrInvalidTimeFallsBackToAllDay() {
        // "3pm" / "25:00" can't parse as H:mm → degrade to an all-day event, never a crash.
        for (bad in listOf("3pm", "25:00", "noon")) {
            val (millis, allDay) = startMillis("2026-06-16", bad, utc)!!
            assertEquals("time=$bad", epoch(2026, 6, 16, 0, 0), millis)
            assertTrue("time=$bad", allDay)
        }
    }

    @Test
    fun explicitMidnightIsTimedNotAllDay() {
        // A note that literally says 00:00 is a timed event; only a *missing* time is all-day.
        val (millis, allDay) = startMillis("2026-06-16", "0:00", utc)!!
        assertEquals(epoch(2026, 6, 16, 0, 0), millis)
        assertTrue(!allDay)
    }

    @Test
    fun appliesTimeZone() {
        // 09:00 in New York (EDT, UTC-4 in June) is 13:00 UTC.
        val (millis, _) = startMillis("2026-06-16", "09:00", ZoneId.of("America/New_York"))!!
        assertEquals(epoch(2026, 6, 16, 13, 0), millis)
    }

    // ---- LLM-output parsing (the fragile bit) ----
    @Test
    fun parseExtractsCleanJson() {
        val r = ReminderEngine.parse("""{"title":"Dentist","date":"2026-06-16","time":"15:00"}""", utc)
        assertEquals("Dentist", r.title)
        assertEquals(epoch(2026, 6, 16, 15, 0), r.startMillis)
        assertTrue(!r.allDay)
    }

    @Test
    fun parseStripsThinkBlockAndIgnoresItsBraces() {
        // Reasoning models emit <think>…</think>; braces inside it must not corrupt extraction.
        val raw = "<think>maybe {fake:json} here</think> Here you go: " +
            """{"title":"Call mom","date":"2026-06-16","time":""}"""
        val r = ReminderEngine.parse(raw, utc)
        assertEquals("Call mom", r.title)
        assertEquals(epoch(2026, 6, 16, 0, 0), r.startMillis)
        assertTrue(r.allDay)
    }

    @Test
    fun parseTitleOnlyHasNoStart() {
        val r = ReminderEngine.parse("""{"title":"Meeting","date":"","time":""}""", utc)
        assertEquals("Meeting", r.title)
        assertNull(r.startMillis)
    }

    @Test
    fun parseNoJsonIsEmptyReminder() {
        val r = ReminderEngine.parse("Sorry, I couldn't find a date in that note.", utc)
        assertEquals("", r.title)
        assertNull(r.startMillis)
        assertTrue(!r.allDay)
    }
}
