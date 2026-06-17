package com.thoughtpocket.ai

import java.util.Calendar

/**
 * Deterministic natural-language time-window parser for note queries — "what did I note last
 * Tuesday?", "this morning", "last week", "3 days ago". Pure (no Android, no LLM) so it's fast and
 * unit-testable, and it pre-filters the scope before RAG so a date-anchored question can't be
 * answered from topically-similar notes on the wrong day.
 *
 * Day-granularity by default (users think in days); a few strongly-temporal sub-day phrases resolve
 * to coarse hour bands. Weekday references resolve to the most recent matching day (notes can't be
 * from the future) and — to avoid mistaking a topic for a date ("Sunday brunch") — a bare weekday is
 * NOT treated as a time filter unless qualified by last/this/on/past.
 */
object TimeWindow {
    /** Half-open [start, endExclusive) in epoch millis. [label] is a human phrase for the prompt/UI. */
    data class Window(val start: Long, val endExclusive: Long, val label: String)

    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    private val WEEKDAYS = mapOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY,
    )

    /** The window [question] refers to, anchored at [now], or null if it names no time. */
    fun parse(question: String, now: Long): Window? {
        val q = question.lowercase()
        val today0 = startOfDay(now)
        val tomorrow0 = today0 + DAY

        // Strongly-temporal sub-day bands (today). Require the qualifier so "morning routine" isn't a filter.
        if (has(q, "this morning")) return Window(today0, today0 + 12 * HOUR, "this morning")
        if (has(q, "this afternoon")) return Window(today0 + 12 * HOUR, today0 + 18 * HOUR, "this afternoon")
        if (has(q, "this evening", "tonight")) return Window(today0 + 18 * HOUR, tomorrow0, "this evening")
        if (has(q, "last night")) return Window(today0 - DAY + 18 * HOUR, today0, "last night")

        if (has(q, "today")) return Window(today0, tomorrow0, "today")
        if (has(q, "yesterday")) return Window(today0 - DAY, today0, "yesterday")

        Regex("\\b(\\d+)\\s+days?\\s+ago\\b").find(q)?.let {
            val n = it.groupValues[1].toInt(); val s = today0 - n * DAY
            return Window(s, s + DAY, "$n day${if (n == 1) "" else "s"} ago")
        }
        Regex("\\b(?:past|last|previous)\\s+(\\d+)\\s+days?\\b").find(q)?.let {
            val n = it.groupValues[1].toInt(); return Window(now - n * DAY, now, "the past $n days")
        }
        if (has(q, "past few days", "last few days", "recent days")) return Window(now - 3 * DAY, now, "the past few days")

        if (has(q, "last week")) { val sw = startOfWeek(now); return Window(sw - 7 * DAY, sw, "last week") }
        if (has(q, "this week")) return Window(startOfWeek(now), tomorrow0, "this week")
        if (has(q, "last month")) { val sm = startOfMonth(now); return Window(startOfMonth(addMonths(now, -1)), sm, "last month") }
        if (has(q, "this month")) return Window(startOfMonth(now), tomorrow0, "this month")

        // Weekdays — only when qualified, so a topic that happens to be a weekday isn't filtered away.
        Regex("\\b(last|this|on|past)\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").find(q)?.let {
            val day = WEEKDAYS.getValue(it.groupValues[2])
            val includeToday = it.groupValues[1] != "last"   // "last tuesday" is strictly in the past
            val s = mostRecentWeekday(now, day, includeToday)
            return Window(s, s + DAY, it.value)
        }
        return null
    }

    /**
     * True only when the window is entirely in the past — safe to HARD-filter the scope to it. Present/
     * ongoing windows ("today", "this week", "tonight") return false: those mean "from my backlog, what's
     * relevant now", so filtering to notes *created* in that window would dead-end the query. Let RAG handle
     * those, and never answer "No notes from <now>".
     */
    fun isRetrospective(win: Window, now: Long): Boolean = win.endExclusive <= startOfDay(now)

    private fun has(q: String, vararg phrases: String) =
        phrases.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(q) }

    private fun cal(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }

    private fun startOfDay(now: Long): Long = cal(now).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfWeek(now: Long): Long {
        val c = cal(startOfDay(now))
        val diff = (c.get(Calendar.DAY_OF_WEEK) - c.firstDayOfWeek + 7) % 7
        return startOfDay(now) - diff * DAY
    }

    private fun startOfMonth(now: Long): Long = cal(now).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun addMonths(now: Long, months: Int): Long = cal(now).apply { add(Calendar.MONTH, months) }.timeInMillis

    /** Start-of-day of the most recent [target] weekday at/before [now] (or strictly before, if !includeToday). */
    private fun mostRecentWeekday(now: Long, target: Int, includeToday: Boolean): Long {
        val dow = cal(now).get(Calendar.DAY_OF_WEEK)
        var diff = (dow - target + 7) % 7
        if (diff == 0 && !includeToday) diff = 7
        return startOfDay(now) - diff * DAY
    }
}
