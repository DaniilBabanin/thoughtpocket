package com.soundscript.ai

import android.content.Context
import com.soundscript.AppPreferences
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A calendar event pulled from a note's natural-language reminder. [startMillis] null = no usable date. */
data class Reminder(val title: String, val startMillis: Long?, val allDay: Boolean)

/**
 * Extract one reminder/appointment from a note. The fast LLM (E2B) resolves relative dates
 * ("next Tuesday", "tomorrow at 3") against [todayMillis] and returns structured fields; the app
 * turns them into epoch millis deterministically (a model slip can't produce a bogus timestamp).
 * Mirrors [InteractEngine]/[TitleEngine]. A blank/unparseable date yields startMillis = null, and
 * the UI still opens the calendar editor for the user to pick a time.
 */
object ReminderEngine {
    private val HM = DateTimeFormatter.ofPattern("H:mm")   // accepts "9:00" and "15:30"

    suspend fun extract(context: Context, noteText: String, todayMillis: Long): Result<Reminder> {
        if (noteText.isBlank()) return Result.success(Reminder("", null, false))
        val zone = ZoneId.systemDefault()
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(todayMillis), zone)
        val model = LlmEngine.resolve(context, AppPreferences(context).tagModelFilename, "E2B")
        return LlmEngine.generate(context, prompt(noteText, today), model).map { parse(it, zone) }
    }

    private fun prompt(note: String, today: LocalDate): String =
        "Extract a calendar reminder from this note. Today is $today (a ${today.dayOfWeek}). " +
            "Resolve any relative date to an absolute one. Reply with ONLY JSON: " +
            "{\"title\":\"...\",\"date\":\"YYYY-MM-DD\",\"time\":\"HH:mm\"}. " +
            "Use \"\" for date or time the note doesn't give. title is a short event name.\n\n" +
            "Note:\n\"\"\"\n${note.take(1500)}\n\"\"\""

    internal fun parse(raw: String, zone: ZoneId): Reminder {
        val cleaned = raw.replace(Regex("(?is)<think.*?</think>"), " ")
        val json = Regex("(?s)\\{.*\\}").find(cleaned)?.value ?: return Reminder("", null, false)
        return runCatching {
            val o = JSONObject(json)
            val title = o.optString("title").trim().take(80)
            val start = startMillis(o.optString("date").trim(), o.optString("time").trim(), zone)
            Reminder(title, start?.first, start?.second ?: false)
        }.getOrElse { Reminder("", null, false) }
    }

    /** (epochMillis, allDay) from LLM date/time strings, or null when the date is missing/unparseable. */
    fun startMillis(date: String, time: String, zone: ZoneId): Pair<Long, Boolean>? {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val t = time.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it, HM) }.getOrNull() }
        val ldt = if (t != null) d.atTime(t) else d.atStartOfDay()
        return ldt.atZone(zone).toInstant().toEpochMilli() to (t == null)
    }
}
