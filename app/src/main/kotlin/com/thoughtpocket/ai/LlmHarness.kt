package com.thoughtpocket.ai

import android.content.Context
import java.io.File

/**
 * Fit-or-split harness for on-device LLM work over content that may exceed one context
 * window. Pure packing (JVM-tested) plus a map-reduce driver: when content overflows,
 * each batch gets its own "subagent" extract pass (map), then the partial results are
 * combined in a fresh context (reduce). Callers own the prompts; the harness owns the
 * splitting, the per-batch loop and failure bookkeeping. Same brain/IO split as
 * [com.thoughtpocket.ai.coder.CoderHarness].
 */
object LlmHarness {

    /** Split [line] into parts of at most [maxChars] (the whole line if it already fits). */
    internal fun splitLine(line: String, maxChars: Int): List<String> =
        if (line.length <= maxChars) listOf(line) else line.chunked(maxChars)

    /**
     * Greedy-pack [lines] into batches whose "\n\n"-joined length stays ≤ [maxChars].
     * Lines longer than the budget are split first, so every batch fits; order preserved,
     * nothing dropped.
     */
    fun pack(lines: List<String>, maxChars: Int): List<List<String>> {
        val batches = mutableListOf<MutableList<String>>()
        var chars = 0
        for (line in lines.flatMap { splitLine(it, maxChars) }) {
            val cost = line.length + 2   // "\n\n" separator
            if (batches.isEmpty() || chars + cost > maxChars) {
                batches.add(mutableListOf(line)); chars = line.length
            } else {
                batches.last().add(line); chars += cost
            }
        }
        return batches
    }

    /**
     * One LLM pass per batch ([mapPrompt]), then combine the surviving extracts with
     * [reducePrompt] in a new context. [clean] post-processes each map reply; return
     * null or blank to drop it. Success("") means every batch cleanly reported nothing;
     * failure only when no extract survived AND at least one batch errored.
     * [onToken] streams decode progress: 1-based part (reduce = batches+1) per emitted token.
     */
    suspend fun mapReduce(
        context: Context,
        model: File?,
        batches: List<String>,
        mapPrompt: (String) -> String,
        reducePrompt: (List<String>) -> String,
        clean: (String) -> String?,
        onToken: ((part: Int) -> Unit)? = null,
    ): Result<String> {
        val extracts = ArrayList<String>(batches.size)
        var lastError: Throwable? = null
        for ((i, batch) in batches.withIndex()) {
            LlmEngine.generate(context, mapPrompt(batch), model, onToken?.let { cb -> { cb(i + 1) } })
                .onSuccess { raw -> clean(raw)?.takeIf { it.isNotBlank() }?.let { extracts += it } }
                .onFailure { lastError = it }
        }
        if (extracts.isEmpty()) return lastError?.let { Result.failure(it) } ?: Result.success("")
        return LlmEngine.generate(context, reducePrompt(extracts), model, onToken?.let { cb -> { cb(batches.size + 1) } })
    }
}
