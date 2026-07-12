package com.thoughtpocket

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.ai.coder.CoderHarness
import com.thoughtpocket.ai.coder.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * On-device check for the 2026-07-12 review fixes (CoderBench conventions:
 * manual run, results via `adb logcat -s BENCH`):
 *  - A4: worst-case follow-up prompt (all char caps maxed) must fit and
 *    generate at n_ctx 8192 — no "prompt too long for context".
 *  - A2: cancel must land DURING the long prefill (abort callback), not
 *    after it — this is what makes join-before-release (A1) bounded.
 *  - D3: the model's embedded chat template applies (or ChatML fallback).
 */
class CoderCtxCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun model(): File? =
        File(ctx.filesDir, "coder").listFiles { f -> f.name.endsWith(".gguf") }?.firstOrNull()
            ?: ctx.getExternalFilesDir("coder")?.listFiles { f -> f.name.endsWith(".gguf") }?.firstOrNull()

    private fun rssMb(): Int {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss / 1024
    }

    /** Every prompt-builder cap maxed out — the follow-up shape is the largest. */
    private fun worstUser(): String {
        val note = buildString { while (length < CoderHarness.NOTE_CAP) append("quarterly spend line: groceries 120, transit 40, misc 15. ") }
        val code = buildString { while (length < CoderHarness.CODE_CAP) append("total = sum(int(w) for w in text.split() if w.isdigit())\n") }
        val outp = buildString { while (length < CoderHarness.OUTPUT_CAP) append("subtotal 175\n") }
        return CoderHarness.followUpUser("Quarterly review", note, "sum all amounts", code, outp, "now break it down per week")
    }

    @Test
    fun worstCasePromptFitsAndGenerates() = runBlocking<Unit> {
        val m = model() ?: run { Log.i("BENCH", "SKIP: no .gguf in files/coder"); return@runBlocking }
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.thoughtpocket.ui.MainActivity::class.java)
        val t0 = SystemClock.elapsedRealtime()
        LlamaEngine.load(m.absolutePath, nCtx = 8192, nThreads = 4).getOrThrow()
        Log.i("BENCH", "load(8192) ${m.name}: ${SystemClock.elapsedRealtime() - t0}ms, rss=${rssMb()}MB")

        val templated = LlamaEngine.formatPrompt(CoderHarness.SYSTEM, worstUser())
        Log.i("BENCH", "chat template: ${if (templated != null) "model-embedded" else "none → ChatML fallback"}")
        val prompt = templated ?: CoderHarness.chatml(worstUser())
        Log.i("BENCH", "worst prompt: ${prompt.length} chars")

        var tokens = 0
        var firstTokMs = 0L
        val g0 = SystemClock.elapsedRealtime()
        val out = LlamaEngine.generate(prompt, maxTokens = 48) {
            if (tokens == 0) firstTokMs = SystemClock.elapsedRealtime() - g0
            tokens++
        }.getOrThrow()
        Log.i("BENCH", "gen ok: $tokens pieces, prefill=${firstTokMs}ms, " +
            "total=${SystemClock.elapsedRealtime() - g0}ms, rss=${rssMb()}MB")
        Log.i("BENCH", "output head: ${out.take(200)}")
        check(out.isNotBlank()) { "empty output on worst-case prompt" }
        LlamaEngine.release()
        scenario.close()
    }

    @Test
    fun cancelLandsMidPrefill() = runBlocking<Unit> {
        val m = model() ?: run { Log.i("BENCH", "SKIP: no .gguf in files/coder"); return@runBlocking }
        LlamaEngine.load(m.absolutePath, nCtx = 8192, nThreads = 4).getOrThrow()

        val prompt = CoderHarness.chatml(worstUser()) // multi-k-token prefill
        val job = async(Dispatchers.Default) { LlamaEngine.generate(prompt, maxTokens = 64) }
        delay(1_500) // solidly inside the prefill decode on CPU
        val c0 = SystemClock.elapsedRealtime()
        LlamaEngine.cancel()
        val result = job.await()
        val cancelMs = SystemClock.elapsedRealtime() - c0
        Log.i("BENCH", "mid-prefill cancel returned in ${cancelMs}ms (ok=${result.isSuccess})")
        // Pre-abort-callback this was the WHOLE remaining prefill (tens of s).
        check(cancelMs < 3_000) { "cancel took ${cancelMs}ms — abort callback not effective" }

        // Context must survive an aborted decode.
        val again = LlamaEngine.generate(
            "<|im_start|>user\nSay OK.<|im_end|>\n<|im_start|>assistant\n", maxTokens = 8
        )
        Log.i("BENCH", "post-abort generate: ${again.getOrNull()}")
        check(again.isSuccess)
        LlamaEngine.release()
    }
}
