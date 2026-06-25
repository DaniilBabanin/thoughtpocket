package com.thoughtpocket.ai

import android.content.Context
import com.thoughtpocket.AppPreferences

/**
 * One-tap transform presets: rewrite a note's body into a different shape (key points, formal tone,
 * shorter, longer) via the on-device LLM. Unlike [InteractEngine], a preset IS the intent — the button
 * maps straight to a prompt, so there's no command-parsing round-trip. Runs on E4B (like
 * [MarkdownEngine]); E2B over-structures prose. The caller applies the result through its existing
 * snapshot/undo, so a bad rewrite is one tap away from reverting.
 */
object TransformEngine {
    /** Each preset is just a canned [instruction] — the [label] is the chip text shown to the user. */
    enum class Preset(val label: String, val instruction: String) {
        KEY_POINTS("Key points", "Rewrite as concise bullet-point key points"),
        FORMAL("Formal", "Rewrite in a formal, professional tone"),
        SHORTER("Shorter", "Condense to the essential points"),
        LONGER("Longer", "Expand with more detail and clearer structure"),
    }

    suspend fun transform(context: Context, text: String, preset: Preset): Result<String> =
        transformWith(context, text, preset.instruction)

    /** Rewrite [text] following a free-form [instruction] (a preset's or one the user typed). */
    suspend fun transformWith(context: Context, text: String, instruction: String): Result<String> {
        if (text.isBlank() || instruction.isBlank()) return Result.success("")
        val model = LlmEngine.resolve(context, AppPreferences(context).analysisModelFilename, "4b")
        return LlmEngine.generate(context, buildPrompt(text, instruction), model).map { cleanLlmReply(it) }
    }

    private fun buildPrompt(text: String, instruction: String): String =
        "You rewrite a note's text following one instruction.\n" +
            "Instruction: \"${instruction.trim()}\"\n" +
            "Preserve every fact and the original meaning. Invent nothing. " +
            "Do not add a title or heading. Do not explain.\n" +
            "Reply with ONLY the rewritten note.\n\n" +
            "Note:\n\"\"\"\n${text.take(6000)}\n\"\"\""
}

/**
 * Strip model scaffolding from an LLM reply: `<think>` blocks, a single wrapping ```` ``` ```` fence,
 * and a leading "Here is the rewritten note:"-style preamble. Mirrors the cleanup [MarkdownEngine]
 * does; kept here as a pure, unit-testable function (the transform output is otherwise applied verbatim).
 */
internal fun cleanLlmReply(raw: String): String {
    var s = raw
        .replace(Regex("(?is)<think.*?</think>"), " ")
        .replace(Regex("(?is)</?think[^>]*>"), " ")
        .trim()
    Regex("(?is)^```(?:markdown)?\\s*\\n(.*)\\n```\\s*$").find(s)?.let { s = it.groupValues[1].trim() }
    val firstLine = s.lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.endsWith(":") && firstLine.length < 60 &&
        Regex("(?i)\\b(here|sure|note|rewritten|formatted)\\b").containsMatchIn(firstLine)
    ) {
        s = s.substringAfter('\n').trim()
    }
    return s
}
