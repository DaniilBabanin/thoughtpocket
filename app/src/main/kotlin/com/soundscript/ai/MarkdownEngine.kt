package com.soundscript.ai

import android.content.Context
import com.soundscript.AppPreferences
import java.io.File

/**
 * Reformat a raw voice-note transcript into clean Markdown (bullet lists, checklists for tasks,
 * paragraphs for prose) without changing meaning. Built on [LlmEngine]. The note title stays
 * separate ([TitleEngine]); this only structures the body.
 */
object MarkdownEngine {
    suspend fun toMarkdown(context: Context, text: String, model: File? = null): Result<String> {
        if (text.isBlank()) return Result.success("")
        val m = model ?: LlmEngine.resolve(context, AppPreferences(context).tagModelFilename, "E2B")
        return LlmEngine.generate(context, buildPrompt(text), m).map { clean(it) }
    }

    private fun buildPrompt(text: String): String =
        "You reformat a raw voice note into clean Markdown without changing its meaning or wording.\n" +
            "Rules:\n" +
            "- A list of items or steps becomes a Markdown bullet list (\"- item\").\n" +
            "- Tasks become checkboxes: \"- [ ] task\" for things still to do, and \"- [x] task\" for " +
            "things already done (spoken as finished, done, bought, called, booked, picked up, etc.).\n" +
            "- Keep reflective or narrative speech as normal paragraphs; do NOT force it into a list.\n" +
            "- Do not add a heading or title. Do not add or remove information. Do not explain.\n" +
            "Reply with only the Markdown.\n\n" +
            "Voice note:\n\"\"\"\n${text.take(4000)}\n\"\"\""

    private fun clean(raw: String): String {
        var s = raw
            .replace(Regex("(?is)<think.*?</think>"), " ")
            .replace(Regex("(?is)</?think[^>]*>"), " ")
            .trim()
        // Strip a ```markdown ... ``` fence if the model wrapped the whole reply in one.
        Regex("(?is)^```(?:markdown)?\\s*\\n(.*)\\n```\\s*$").find(s)?.let { s = it.groupValues[1].trim() }
        // Drop a leading preamble line like "Here is the markdown:" / "Sure!".
        val firstLine = s.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.endsWith(":") && firstLine.length < 60 &&
            Regex("(?i)\\b(here|sure|markdown|reformatted|note)\\b").containsMatchIn(firstLine)
        ) {
            s = s.substringAfter('\n').trim()
        }
        return s
    }
}
