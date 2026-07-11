package com.thoughtpocket

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/**
 * SPIKE (Phase 1, coder feature): on-device smoke check that the shared-ggml
 * llama.cpp build emits coherent tokens from the Ornith GGUF. Manual run:
 *
 *   adb shell am instrument -w -e class com.thoughtpocket.LlamaProbeBench \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Needs the model pushed first (tools/push-models.sh) to files/coder/.
 * Results via `adb logcat -s BENCH`, TaggingBenchmark convention.
 */
class LlamaProbeBench {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun probeGguf() {
        // Internal storage: shell-pushed files under the app's EXTERNAL dir are invisible to the
        // app on Android 11+ (same gotcha as LongFormBench) — stage via run-as into files/coder.
        // Any llama-supported GGUF proves build coherence; Ornith itself is Phase-2's on-device check.
        val dir = File(ctx.filesDir, "coder")
        val model = dir.listFiles { f -> f.name.endsWith(".gguf") }?.firstOrNull()
        if (model == null) {
            Log.i("BENCH", "SKIP: no .gguf under ${dir.absolutePath}")
            return
        }
        val t0 = System.currentTimeMillis()
        val out = LlamaProbe.nativeProbe(model.absolutePath, 32)
        val ms = System.currentTimeMillis() - t0
        Log.i("BENCH", "probe took ${ms}ms (32 tokens incl. model load)")
        Log.i("BENCH", "probe output: $out")
        check(!out.startsWith("ERROR")) { "probe failed: $out" }
    }
}
