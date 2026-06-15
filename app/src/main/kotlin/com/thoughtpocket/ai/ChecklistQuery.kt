package com.thoughtpocket.ai

import android.content.Context
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.data.Note
import org.json.JSONObject

/**
 * Deterministic answers for checklist-aggregation questions ("what did I buy last week?", "what's
 * still open?"). An LLM only CLASSIFIES the question (state / time window / topic) — an easy, robust
 * task — then the items are aggregated directly from the Markdown checkboxes, so the answer is
 * complete and stable (the LLM is unreliable at exhaustively enumerating items itself; see the
 * phase-3 findings). Returns null when the question isn't a checklist query, so the caller falls
 * back to normal LLM RAG.
 */
object ChecklistQuery {
    private const val DAY = 86_400_000L

    // Cheap gate: skip the classify LLM call unless the question looks checklist-ish.
    private val HINTS = Regex(
        "(?i)\\b(buy|bought|buying|need|needed|still|open|done|get|got|todo|to-do|checklist|" +
            "left|complete|completed|finish|finished|shopping|grocer|check(ed)?|outstanding|pending)"
    )

    suspend fun tryAnswer(context: Context, notes: List<Note>, question: String, now: Long): String? {
        if (!HINTS.containsMatchIn(question)) return null
        if (notes.none { it.markdown.contains("- [") }) return null
        val intent = classify(context, question)?.takeIf { it.checklist } ?: return null

        val cutoff = intent.windowDays?.let { now - it * DAY } ?: Long.MIN_VALUE
        var scope = notes.filter { it.createdAt >= cutoff }
        if (intent.topic.isNotBlank()) {
            val t = intent.topic.lowercase()
            scope.filter { n ->
                n.tags.any { it.contains(t, true) } || n.text.contains(t, true) || n.markdown.contains(t, true)
            }.ifEmpty { null }?.let { scope = it }
        }

        val items = checklistItems(scope, checked = intent.done).map { it.second }.filter { it.isNotBlank() }.distinct()
        val verb = if (intent.done) "done / bought" else "still to do"
        return if (items.isEmpty()) "Nothing $verb found for that."
        else "Here's what's $verb:\n" + items.joinToString("\n") { "- $it" }
    }

    private data class Intent(val checklist: Boolean, val done: Boolean, val windowDays: Int?, val topic: String)

    private suspend fun classify(context: Context, question: String): Intent? {
        val model = LlmEngine.resolve(context, AppPreferences(context).tagModelFilename, "E2B")
        val raw = LlmEngine.generate(context, prompt(question), model).getOrNull() ?: return null
        val cleaned = raw.replace(Regex("(?is)<think.*?</think>"), " ")
        val json = Regex("(?s)\\{.*\\}").find(cleaned)?.value ?: return null
        return runCatching {
            val o = JSONObject(json)
            val state = o.optString("state").lowercase()
            Intent(
                checklist = o.optBoolean("checklist", false),
                done = state == "done" || state == "bought" || state == "checked" || state == "completed",
                windowDays = windowDays(o.optString("window")),
                topic = o.optString("topic").trim().takeIf { it.lowercase() !in setOf("none", "any", "") } ?: "",
            )
        }.getOrNull()
    }

    private fun windowDays(w: String): Int? = when (w.lowercase().trim()) {
        "today" -> 1
        "yesterday" -> 2
        "this_week", "last_week", "week" -> 7
        "this_month", "last_month", "month" -> 31
        else -> null   // "all"/unknown -> no time filter
    }

    private fun prompt(question: String): String =
        "Classify a question about the user's notes. Reply with ONLY JSON like " +
            "{\"checklist\":true,\"state\":\"done\",\"window\":\"last_week\",\"topic\":\"groceries\"}.\n" +
            "checklist = true ONLY if it asks which checklist items were done/bought or are still to do " +
            "(e.g. \"what did I buy\", \"what's still open\", \"what do I still need\"); false otherwise.\n" +
            "state = \"done\" (bought/done/completed) or \"todo\" (still needed/open/not done yet).\n" +
            "window = one of: today, yesterday, this_week, last_week, this_month, last_month, all.\n" +
            "topic = the subject noun if the question names one (e.g. groceries, chores), else empty.\n" +
            "Question: \"${question.trim()}\""
}
