package com.thoughtpocket

import com.thoughtpocket.ai.TimeWindow
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimeWindowTest {
    private val saved = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()
    private val DAY = 86_400_000L
    private val HOUR = 3_600_000L

    // TimeWindow reads Calendar.getInstance() (default tz/locale) — pin both so day/week boundaries are stable.
    @Before fun pin() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); Locale.setDefault(Locale.US) }
    @After fun restore() { TimeZone.setDefault(saved); Locale.setDefault(savedLocale) }

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun dow(millis: Long) =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)

    private val now = epoch(2026, 6, 16, 15, 30)       // a Tuesday, 15:30 UTC
    private val today0 = epoch(2026, 6, 16, 0, 0)

    @Test fun today() {
        val w = TimeWindow.parse("what did I record today", now)!!
        assertEquals(today0, w.start); assertEquals(today0 + DAY, w.endExclusive)
    }

    @Test fun yesterday() {
        val w = TimeWindow.parse("yesterday's tasks", now)!!   // possessive must still match
        assertEquals(today0 - DAY, w.start); assertEquals(today0, w.endExclusive)
    }

    @Test fun thisMorning() {
        val w = TimeWindow.parse("the idea I had this morning", now)!!
        assertEquals(today0, w.start); assertEquals(today0 + 12 * HOUR, w.endExclusive)
    }

    @Test fun lastNight() {
        val w = TimeWindow.parse("what did I note last night", now)!!
        assertEquals(today0 - DAY + 18 * HOUR, w.start); assertEquals(today0, w.endExclusive)
    }

    @Test fun nDaysAgo() {
        val w = TimeWindow.parse("the call 3 days ago", now)!!
        assertEquals(today0 - 3 * DAY, w.start); assertEquals(today0 - 2 * DAY, w.endExclusive)
    }

    @Test fun pastNDays() {
        val w = TimeWindow.parse("anything from the past 5 days", now)!!
        assertEquals(now - 5 * DAY, w.start); assertEquals(now, w.endExclusive)
    }

    @Test fun lastWeekIsSevenDaysEndingAtWeekStart() {
        val w = TimeWindow.parse("what happened last week", now)!!
        assertEquals(7 * DAY, w.endExclusive - w.start)   // a full week
        assertTrue(w.endExclusive <= today0)              // entirely before today
    }

    @Test fun lastWeekdayIsStrictlyPast() {
        val w = TimeWindow.parse("my notes from last tuesday", now)!!
        assertEquals(DAY, w.endExclusive - w.start)
        assertEquals(Calendar.TUESDAY, dow(w.start))
        assertTrue(w.endExclusive <= today0)              // "last tuesday" excludes today even though today is Tuesday
    }

    @Test fun onWeekdayMayIncludeToday() {
        val w = TimeWindow.parse("what did I do on monday", now)!!
        assertEquals(Calendar.MONDAY, dow(w.start))
        assertTrue(w.start <= today0)
    }

    @Test fun bareWeekdayIsTreatedAsTopicNotTime() {
        assertNull(TimeWindow.parse("sunday brunch ideas", now))
    }

    @Test fun questionWithoutTimeIsNull() {
        assertNull(TimeWindow.parse("what did I say about the dog", now))
    }

    // ---- retrospective discriminator: present/ongoing windows must NOT hard-filter (no dead-end) ----

    private fun retro(q: String) = TimeWindow.parse(q, now)?.let { TimeWindow.isRetrospective(it, now) }

    @Test fun pastWindowsAreRetrospective() {
        // These are safe to hard-filter — the period is over.
        for (q in listOf("yesterday", "last night", "what happened last week", "last month", "last tuesday", "the call 3 days ago"))
            assertEquals(q, true, retro(q))
    }

    @Test fun presentWindowsAreNotRetrospective() {
        // "what do I need to do today" means the backlog, not notes created today — must not dead-end.
        for (q in listOf("what do I need to do today", "my plan this week", "anything this month", "what's tonight", "this morning"))
            assertEquals(q, false, retro(q))
    }
}
