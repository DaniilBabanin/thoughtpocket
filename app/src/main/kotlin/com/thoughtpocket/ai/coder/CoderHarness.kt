package com.thoughtpocket.ai.coder

/**
 * Pure half of the coder agent loop (generate → gate → run → diagnose →
 * retry). Everything here is deterministic and JVM-tested — the same hybrid
 * split as InteractEngine.parse: the LLM proposes, this code decides.
 * The impure orchestrator lives in CoderRunService.
 */
object CoderHarness {

    const val MAX_ATTEMPTS = 3
    // 30 min / 6 min: raised 2026-07-12 with the generous caps below — a worst-
    // case attempt (long prefill + 2000-token gen at ~4.25 tok/s) runs ~9 min.
    // Long runs are fine now that leaving the screen no longer kills them.
    const val WALL_CAP_MS = 30 * 60_000L
    /** Conservative single-attempt estimate (gen + exec), docs/coder-throughput.md. */
    const val ATTEMPT_EST_MS = 6 * 60_000L
    const val EXEC_TIMEOUT_MS = 60_000L
    // 2000 @ n_ctx 8192 (was 1300 @ 4096 — H1 post-mortem 2026-07-11: truncated
    // replies die at the fence gate). Worst follow-up prompt with the caps
    // below ≈ 4.5k tok + 2000 gen ≈ 6.5k < 8192; the JNI side additionally
    // clamps the gen budget to the remaining context instead of refusing.
    const val MAX_GEN_TOKENS = 2000
    /** Char caps for prompt building — generous; budget math above. */
    internal const val NOTE_CAP = 8000
    internal const val CODE_CAP = 4000
    internal const val OUTPUT_CAP = 2000

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
        /**
         * Generate attempt N+1 (first, or a repair round with error context).
         * [sampled] = the last two attempts failed identically, so greedy
         * decoding is provably stuck — escalate to one temperature-sampled try
         * instead of failing outright (review 2026-07-12).
         */
        data class Generate(val sampled: Boolean = false) : Action
        data class Done(val output: String) : Action
        data class Fail(val reason: String) : Action
    }

    /**
     * The loop's brain, consulted after every attempt (and before the first).
     * Ordering matters: success wins, then attempt cap, then the wall-clock
     * check — never start a generation that can't fit its estimate in the
     * remaining budget (thermal throttling makes late attempts slower, so the
     * estimate is checked every round, not once). A stuck pair only changes
     * HOW the next attempt generates, never whether it may start.
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
        if (elapsedMs + estAttemptMs > wallCapMs) {
            return Action.Fail("Ran out of time budget (${wallCapMs / 60_000} min)")
        }
        if (attempts.size >= 2) {
            val (a, b) = attempts.takeLast(2)
            if (errorKey(a) == errorKey(b) && errorKey(a).isNotEmpty()) {
                return Action.Generate(sampled = true)
            }
        }
        return Action.Generate()
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

    /**
     * Distinguishes "no fence at all" from "fence opened but never closed" —
     * the latter means the reply hit the token cap mid-script, and the retry
     * prompt should ask for a SHORTER script rather than vaguely complain.
     */
    internal fun fenceGateError(raw: String): String {
        val cleaned = raw.replace(Regex("(?is)<think>.*?(</think>|$)"), "")
        val opens = Regex("```").findAll(cleaned).count()
        return if (opens % 2 == 1) {
            "your reply was cut off before the script ended — write a shorter script"
        } else {
            "reply contained no code block"
        }
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

    // ---- prompts ----
    // Builders return the USER message content; the caller wraps it with the
    // model's own chat template (LlamaEngine.formatPrompt, GGUF metadata) and
    // falls back to [chatml] for models that ship none.

    internal const val SYSTEM = "You write one small self-contained Python script per request. " +
        "Rules: standard library only (no pip, no network, no files, no input()); " +
        "print the final result to stdout with print(); " +
        "if the task needs live or external data you don't have (exchange rates, prices, " +
        "weather), the script must say so in its output instead of using a guessed value; " +
        "reply with exactly one fenced code block and nothing else. " +
        "A global variable `notes` is already defined: a list of every saved note, each a dict " +
        "with keys 'title', 'text' (raw), 'markdown' (formatted, may be ''), 'tags' (list of str), " +
        "'created' (epoch ms). Use `notes` for tasks that span multiple notes (e.g. searching all " +
        "notes); for a task about only the note shown below, just use that text. Do not redefine `notes`."

    internal fun firstUser(noteTitle: String, noteBody: String, instruction: String): String =
        "Note: ${noteTitle.take(200)}\n\"\"\"\n${noteBody.take(NOTE_CAP)}\n\"\"\"\n\nTask: $instruction"

    internal fun repairUser(
        noteTitle: String, noteBody: String, instruction: String,
        lastCode: String, errorSummary: String,
    ): String {
        val base = "Note: ${noteTitle.take(200)}\n\"\"\"\n${noteBody.take(NOTE_CAP)}\n\"\"\"\n\nTask: $instruction\n\n"
        // A gate failure before any code was extracted (no fence / cut off)
        // has no script to show — an empty ```python block would only confuse.
        return if (lastCode.isBlank()) {
            base + "Your previous reply was rejected: $errorSummary\n" +
                "Reply with exactly one complete fenced Python code block and nothing else."
        } else {
            base + "Your previous script:\n```python\n${lastCode.take(CODE_CAP)}\n```\n" +
                "It failed with:\n$errorSummary\n\nFix it and reply with the corrected script only."
        }
    }

    internal fun followUpUser(
        noteTitle: String, noteBody: String,
        priorInstruction: String, priorCode: String, priorOutput: String,
        newInstruction: String,
    ): String =
        "Note: ${noteTitle.take(200)}\n\"\"\"\n${noteBody.take(NOTE_CAP)}\n\"\"\"\n\n" +
            "Earlier task: $priorInstruction\nYour working script:\n```python\n${priorCode.take(CODE_CAP)}\n```\n" +
            "Its output:\n\"\"\"\n${priorOutput.take(OUTPUT_CAP)}\n\"\"\"\n\nNew task: $newInstruction"

    /** Fallback wrapper for models without an embedded chat template. */
    internal fun chatml(user: String): String =
        "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
}
