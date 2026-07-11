package com.thoughtpocket

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.ai.coder.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Coder-runtime bench + cancellation check (TaggingBenchmark convention: manual
 * run, results via `adb logcat -s BENCH`). Uses whatever .gguf sits in
 * files/coder — Ornith on hardware, a small Qwen on the emulator. Stage via
 * run-as (Android 11+ hides shell-pushed external files from the app):
 *
 *   adb push model.gguf /data/local/tmp/ && adb shell run-as com.thoughtpocket \
 *     sh -c 'mkdir -p files/coder && cp /data/local/tmp/model.gguf files/coder/'
 *   adb shell am instrument -w -e class com.thoughtpocket.CoderBench \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 */
class CoderBench {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun model(): File? =
        // Internal (emulator run-as staging) first, then the app's real external
        // dir (hardware, tools/push-models.sh).
        File(ctx.filesDir, "coder").listFiles { f -> f.name.endsWith(".gguf") }?.firstOrNull()
            ?: ctx.getExternalFilesDir("coder")?.listFiles { f -> f.name.endsWith(".gguf") }?.firstOrNull()

    private fun rssMb(): Int {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss / 1024
    }

    @Test
    fun generateAndMeasure() = runBlocking<Unit> {
        val m = model() ?: run { Log.i("BENCH", "SKIP: no .gguf in files/coder"); return@runBlocking }
        val t0 = SystemClock.elapsedRealtime()
        LlamaEngine.load(m.absolutePath).getOrThrow()
        Log.i("BENCH", "load ${m.name}: ${SystemClock.elapsedRealtime() - t0}ms, rss=${rssMb()}MB")

        var tokens = 0
        var firstTokMs = 0L
        val g0 = SystemClock.elapsedRealtime()
        val out = LlamaEngine.generate(
            "<|im_start|>user\nWrite a Python function that returns the sum of a list of numbers.<|im_end|>\n<|im_start|>assistant\n",
            maxTokens = 96,
        ) {
            if (tokens == 0) firstTokMs = SystemClock.elapsedRealtime() - g0
            tokens++
        }.getOrThrow()
        val totalMs = SystemClock.elapsedRealtime() - g0
        val decodeTps = if (totalMs > firstTokMs) (tokens - 1) * 1000.0 / (totalMs - firstTokMs) else 0.0
        Log.i("BENCH", "gen: $tokens pieces, firstTok=${firstTokMs}ms, total=${totalMs}ms, " +
            "decode=${"%.2f".format(decodeTps)} tok/s, peakRss=${rssMb()}MB")
        Log.i("BENCH", "output: ${out.take(300)}")
        check(out.isNotBlank())
        LlamaEngine.release()
    }

    @Test
    fun cancelReturnsPromptlyAndContextSurvives() = runBlocking<Unit> {
        val m = model() ?: run { Log.i("BENCH", "SKIP: no .gguf in files/coder"); return@runBlocking }
        LlamaEngine.load(m.absolutePath).getOrThrow()

        val job = async(Dispatchers.Default) {
            LlamaEngine.generate("<|im_start|>user\nCount from 1 to 500.<|im_end|>\n<|im_start|>assistant\n", maxTokens = 512)
        }
        delay(4_000) // let prefill + a few tokens happen
        val c0 = SystemClock.elapsedRealtime()
        LlamaEngine.cancel()
        val result = job.await()
        val cancelMs = SystemClock.elapsedRealtime() - c0
        Log.i("BENCH", "cancel returned in ${cancelMs}ms, partial=${result.getOrNull()?.length ?: -1} chars")
        // Bound generously: one decode step on slow hardware. The point is
        // "seconds, not the full 512-token run".
        check(cancelMs < 10_000) { "cancel took ${cancelMs}ms" }

        // Context must be reusable after a cancel.
        val again = LlamaEngine.generate(
            "<|im_start|>user\nSay OK.<|im_end|>\n<|im_start|>assistant\n", maxTokens = 8
        )
        Log.i("BENCH", "post-cancel generate: ${again.getOrNull()}")
        check(again.isSuccess)
        LlamaEngine.release()
    }
}
