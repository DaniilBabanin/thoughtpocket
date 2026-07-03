package com.thoughtpocket.ai

import android.content.Context
import com.thoughtpocket.AppPreferences
import org.json.JSONObject

/** A structured edit derived from a natural-language command about a note's checklist. */
sealed interface InteractOp {
    data class Check(val item: String, val on: Boolean) : InteractOp
    data class Add(val item: String, val top: Boolean) : InteractOp
    data class Remove(val item: String) : InteractOp
    data class SetTitle(val title: String) : InteractOp
    data object Convert : InteractOp
    data object Suggest : InteractOp
    /** Reword/reshape the whole note as prose (formal, shorter, longer, key points). Applied via the LLM, not [MarkdownOps]. */
    data object Rewrite : InteractOp
    data class Unknown(val raw: String) : InteractOp
}

/**
 * Hybrid command handling: an LLM turns a free-form command into ONE structured [InteractOp]
 * (so the model can be flexible), then the app applies it deterministically to the Markdown via
 * the pure ops in [MarkdownOps] (so it can't corrupt the note). Mistakes are recoverable via the
 * caller's snapshot/undo. Intent parsing uses the fast model (E2B); suggestions use E4B.
 */
object InteractEngine {
    suspend fun interpret(context: Context, markdown: String, command: String): Result<InteractOp> {
        if (command.isBlank()) return Result.success(InteractOp.Unknown(""))
        val model = LlmEngine.resolve(context, AppPreferences(context).tagModelFilename, "E2B")
        return LlmEngine.generate(context, prompt(markdown, command), model).map { parse(it) }
    }

    /** Apply a structured op to the Markdown; null when it isn't a Markdown edit (SetTitle/Suggest/Unknown). */
    fun apply(markdown: String, op: InteractOp): String? = when (op) {
        is InteractOp.Check -> setItemChecked(markdown, op.item, op.on)
        is InteractOp.Add -> addItem(markdown, op.item, op.top)
        is InteractOp.Remove -> removeItem(markdown, op.item)
        is InteractOp.Convert -> bulletsToChecklist(markdown)
        else -> null
    }

    /** Suggest a few new checklist items based on the note (E4B). */
    suspend fun suggestAdditions(context: Context, note: String): Result<List<String>> {
        if (note.isBlank()) return Result.success(emptyList())
        val model = LlmEngine.resolve(context, AppPreferences(context).analysisModelFilename, "4b")
        return LlmEngine.generate(context, suggestPrompt(note), model).map { parseLines(it) }
    }

    private fun prompt(markdown: String, command: String): String =
        "Convert a command about a checklist into ONE JSON action.\n" +
            "Checklist:\n\"\"\"\n${markdown.take(1500)}\n\"\"\"\n" +
            "Command: \"${command.trim()}\"\n" +
            "Reply with ONLY JSON, e.g. {\"action\":\"check\",\"item\":\"milk\",\"position\":\"bottom\"} " +
            "or {\"action\":\"title\",\"item\":\"Groceries\"}.\n" +
            "action is one of: check (mark done/bought), uncheck (mark not done yet), add (new item), " +
            "remove (delete an item), convert (turn the whole list or note into a checkbox list), " +
            "rewrite (reword or reshape the whole note as prose — make it formal, shorter, longer, or " +
            "into key points/bullets; NOT checkboxes), " +
            "title (set/rename the note's title), suggest (propose new items), unknown.\n" +
            "For add, item is the new item text. position is \"top\" or \"bottom\" (default bottom).\n" +
            "For title, item is the new title text. " +
            "If the command names/titles the note (e.g. \"title\", \"call it\", \"name it\"), " +
            "use title even when it also mentions making a list."

    internal fun parse(raw: String): InteractOp {
        val cleaned = raw.replace(Regex("(?is)<think.*?</think>"), " ")
        val json = Regex("(?s)\\{.*\\}").find(cleaned)?.value ?: return InteractOp.Unknown(raw.trim())
        return runCatching {
            val o = JSONObject(json)
            val item = o.optString("item").trim()
            val top = o.optString("position").equals("top", ignoreCase = true)
            when (o.optString("action").lowercase().trim()) {
                "check" -> InteractOp.Check(item, true)
                "uncheck" -> InteractOp.Check(item, false)
                "add" -> InteractOp.Add(item, top)
                "remove", "delete" -> InteractOp.Remove(item)
                "title", "heading", "rename" -> InteractOp.SetTitle(item.ifBlank { o.optString("title").trim() })
                "convert", "checklist", "checkboxes", "checkbox" -> InteractOp.Convert
                "rewrite", "reword", "rephrase", "shorten", "summarize", "summarise",
                "expand", "lengthen", "formal" -> InteractOp.Rewrite
                "suggest" -> InteractOp.Suggest
                else -> InteractOp.Unknown(raw.trim())
            }
        }.getOrElse { InteractOp.Unknown(raw.trim()) }
    }

    private fun suggestPrompt(note: String): String =
        "Here is one of the user's notes (it may be a checklist):\n\"\"\"\n${note.take(2000)}\n\"\"\"\n" +
            "Suggest 3 to 5 more relevant items they likely want to add. " +
            "Reply with ONLY the items, one per line — no numbering, no markdown, no checkboxes."

    private fun parseLines(raw: String): List<String> =
        raw.replace(Regex("(?is)<think.*?</think>"), " ")
            .lineSequence()
            .map { it.trim().trimStart('-', '*', '•', ' ').removePrefix("[ ]").removePrefix("[]").trim() }
            .filter { it.isNotBlank() && it.length < 60 && !it.endsWith(":") }
            .distinct()
            .take(5)
            .toList()
}
