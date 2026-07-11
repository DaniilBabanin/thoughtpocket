package com.thoughtpocket.ai.coder

/**
 * Pure half of the coder agent loop (generate → gate → run → diagnose →
 * retry). Everything here is deterministic and JVM-tested — the same hybrid
 * split as InteractEngine.parse: the LLM proposes, this code decides.
 * The impure orchestrator lives in CoderRunService.
 */
object CoderHarness {

    const val MAX_ATTEMPTS = 3
    const val WALL_CAP_MS = 20 * 60_000L
    /** Conservative single-attempt estimate (gen + exec) from docs/coder-throughput.md. */
    const val ATTEMPT_EST_MS = 5 * 60_000L
    const val EXEC_TIMEOUT_MS = 60_000L
    const val MAX_GEN_TOKENS = 900

    /** Python stdlib modules a generated script may import (root module names). */
    internal val IMPORT_ALLOWLIST = setOf(
        "math", "statistics", "datetime", "json", "re", "itertools", "functools",
        "collections", "random", "string", "textwrap", "decimal", "fractions",
        "heapq", "bisect", "calendar", "unicodedata", "typing", "dataclasses",
        "enum", "operator", "copy", "difflib", "zoneinfo",
    )

    // ---- results of one attempt ----

    data class Attempt(
        val code: String,
        val stdout: String = "",
        val stderr: String = "",
        val ok: Boolean = false,
        val timedOut: Boolean = false,
        /** Gate rejection (no fence / blocked import) — never executed. */
        val gateError: String? = null,
    )

    sealed interface Action {
        /** Generate attempt N+1 (first, or a repair round with error context). */
        data object Generate : Action
        data class Done(val output: String) : Action
        data class Fail(val reason: String) : Action
    }

    /**
     * The loop's brain, consulted after every attempt (and before the first).
     * Ordering matters: success wins, then attempt/robustness caps, then the
     * wall-clock check — never start a generation that can't fit its estimate
     * in the remaining budget (thermal throttling makes late attempts slower,
     * so the estimate is checked every round, not once).
     */
    internal fun decide(
        attempts: List<Attempt>,
        elapsedMs: Long,
        wallCapMs: Long = WALL_CAP_MS,
        maxAttempts: Int = MAX_ATTEMPTS,
        estAttemptMs: Long = ATTEMPT_EST_MS,
    ): Action {
        val last = attempts.lastOrNull()
        if (last?.ok == true) return Action.Done(last.stdout)
        if (attempts.size >= maxAttempts) {
            return Action.Fail("Couldn't produce a working script after $maxAttempts attempts")
        }
        if (attempts.size >= 2) {
            val (a, b) = attempts.takeLast(2)
            if (errorKey(a) == errorKey(b) && errorKey(a).isNotEmpty()) {
                return Action.Fail("The same error kept coming back — stopping early")
            }
        }
        if (elapsedMs + estAttemptMs > wallCapMs) {
            return Action.Fail("Ran out of time budget (${wallCapMs / 60_000} min)")
        }
        return Action.Generate
    }

    /** Two attempts failing identically ⇒ the model is stuck, not converging. */
    internal fun errorKey(a: Attempt): String =
        (a.gateError ?: summarizeTraceback(a.stderr)).trim()

    // ---- gates ----

    /**
     * Pulls the script out of a model reply: first fenced block (```python or
     * bare ```), after stripping <think> reasoning. Null = malformed reply
     * (treated as a failed attempt so the loop can retry).
     */
    internal fun extractCodeFence(raw: String): String? {
        val cleaned = raw.replace(Regex("(?is)<think>.*?(</think>|$)"), "")
        val m = Regex("(?s)```(?:python|py)?[ \\t]*\\n(.*?)```").find(cleaned)
            ?: return null
        val code = m.groupValues[1].trimEnd()
        return code.ifBlank { null }
    }

    sealed interface ImportScan {
        data object Allowed : ImportScan
        data class Blocked(val what: String) : ImportScan
    }

    /**
     * Static allowlist gate, run BEFORE code reaches the runner process. Not a
     * sandbox (the killable zero-trust process is the real wall) — this exists
     * to fail fast with a clear message the retry prompt can act on, and to
     * keep obviously-wrong scripts (network, filesystem) from burning a run.
     */
    internal fun scanImports(code: String): ImportScan {
        for (line in code.lineSequence()) {
            val t = line.trim()
            val roots = when {
                t.startsWith("import ") ->
                    t.removePrefix("import ").substringBefore('#').split(',')
                        .map { it.trim().substringBefore(" as ").substringBefore('.').trim() }
                t.startsWith("from ") ->
                    listOf(t.removePrefix("from ").substringBefore(" import").substringBefore('.').trim())
                else -> emptyList()
            }
            for (r in roots) {
                if (r.isNotEmpty() && r !in IMPORT_ALLOWLIST) return ImportScan.Blocked("import $r")
            }
            // Cheap denylist for the two escape hatches a script doesn't need.
            if (Regex("\\bopen\\s*\\(").containsMatchIn(t)) return ImportScan.Blocked("open()")
            if (t.contains("__import__")) return ImportScan.Blocked("__import__")
        }
        return ImportScan.Allowed
    }

    // ---- diagnosis ----

    /**
     * Boils a Python traceback down to what a repair prompt needs: the last
     * user-code frame ("<coder>") plus the exception line, capped in size.
     */
    internal fun summarizeTraceback(stderr: String): String {
        if (stderr.isBlank()) return ""
        val lines = stderr.trim().lines()
        val frame = lines.lastOrNull { it.trimStart().startsWith("File \"<coder>\"") }
        val exception = lines.lastOrNull { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("\t") }
            ?: lines.last()
        return listOfNotNull(frame?.trim(), exception.trim())
            .joinToString("\n").take(600)
    }

    // ---- prompts (ChatML — Ornith/Qwen family; BYO models are on their own template-wise) ----

    private const val SYSTEM = "You write one small self-contained Python script per request. " +
        "Rules: standard library only (no pip, no network, no files, no input()); " +
        "print the final result to stdout with print(); " +
        "reply with exactly one fenced code block and nothing else."

    internal fun buildFirstPrompt(noteTitle: String, noteBody: String, instruction: String): String =
        chatml(
            "Note: ${noteTitle.take(200)}\n\"\"\"\n${noteBody.take(6000)}\n\"\"\"\n\nTask: $instruction"
        )

    internal fun buildRepairPrompt(
        noteTitle: String, noteBody: String, instruction: String,
        lastCode: String, errorSummary: String,
    ): String = chatml(
        "Note: ${noteTitle.take(200)}\n\"\"\"\n${noteBody.take(6000)}\n\"\"\"\n\nTask: $instruction\n\n" +
            "Your previous script:\n```python\n${lastCode.take(4000)}\n```\n" +
            "It failed with:\n$errorSummary\n\nFix it and reply with the corrected script only."
    )

    internal fun buildFollowUpPrompt(
        noteTitle: String, noteBody: String,
        priorInstruction: String, priorCode: String, priorOutput: String,
        newInstruction: String,
    ): String = chatml(
        "Note: ${noteTitle.take(200)}\n\"\"\"\n${noteBody.take(6000)}\n\"\"\"\n\n" +
            "Earlier task: $priorInstruction\nYour working script:\n```python\n${priorCode.take(4000)}\n```\n" +
            "Its output:\n\"\"\"\n${priorOutput.take(2000)}\n\"\"\"\n\nNew task: $newInstruction"
    )

    private fun chatml(user: String): String =
        "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
}
