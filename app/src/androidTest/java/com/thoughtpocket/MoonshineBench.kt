package com.thoughtpocket

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.junit.Test
import java.io.File

/**
 * Moonshine (engine #3) as an OFFLINE model on the sherpa-onnx runtime. Moonshine has NO 30 s mel pad, so a
 * short window decodes cheaply — the key idea: run our existing Tier-1 windowing, but with Moonshine instead
 * of Whisper, to get a sub-second live preview. Benches BASE and TINY: model load, full-clip WER+RTF, and a
 * REAL-TIME WINDOWED SIMULATION (audio arrives at wall-clock rate; each ~250 ms tick re-decodes the trailing
 * 2 s) measuring first-token latency + worst per-tick decode (does it keep up?).
 *   adb logcat -d -s MOON
 */
class MoonshineBench {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun now() = SystemClock.elapsedRealtime()

    @Test fun compareWindowed() {
        bench("base", "moonshine")
        bench("tiny", "moonshine-tiny")
    }

    private fun bench(label: String, dirName: String) {
        val dir = File(ctx.getExternalFilesDir(null), dirName)
        if (!File(dir, "encode.int8.onnx").exists()) { Log.e("MOON", "[$label] model missing at $dir"); return }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = File(dir, "preprocess.onnx").absolutePath,
                    encoder = File(dir, "encode.int8.onnx").absolutePath,
                    uncachedDecoder = File(dir, "uncached_decode.int8.onnx").absolutePath,
                    cachedDecoder = File(dir, "cached_decode.int8.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        )
        val loadT = now(); val rec = OfflineRecognizer(config = config); val loadMs = now() - loadT
        val pcm = Bench.pcmFloats("jfk16k.pcm"); val audioMs = pcm.size / 16L

        // Full-clip accuracy + compute.
        val t0 = now(); val s = rec.createStream(); s.acceptWaveform(pcm, 16000); rec.decode(s)
        val full = rec.getResult(s).text; val fullMs = now() - t0; s.release()
        val wer = (Bench.wer(Bench.JFK_TRUTH, full) * 100).toInt()

        // Real-time windowed preview simulation: audio "arrives" at wall-clock rate; every ~250 ms re-decode
        // the trailing 2 s and show it. firstToken = wall time to the first non-empty window result.
        val winLen = 16000 * 2; val cadence = 250L
        val st = now(); var first = -1L; var maxTick = 0L; var ticks = 0
        while (true) {
            val avail = minOf(pcm.size, ((now() - st) * 16L).toInt())
            if (avail < 1600) { Thread.sleep(20); continue }
            val win = pcm.copyOfRange(maxOf(0, avail - winLen), avail)
            val tt = now(); val ws = rec.createStream(); ws.acceptWaveform(win, 16000); rec.decode(ws)
            val txt = rec.getResult(ws).text; ws.release(); val tickMs = now() - tt
            ticks++; if (tickMs > maxTick) maxTick = tickMs
            if (first < 0 && txt.isNotBlank()) first = now() - st
            if (avail >= pcm.size) break
            val sleep = cadence - tickMs; if (sleep > 0) Thread.sleep(sleep)
        }
        rec.release()
        Log.i("MOON", "[$label] load=${loadMs}ms full=${fullMs}ms rtf=${"%.3f".format(fullMs.toDouble() / audioMs)} " +
            "WER=$wer% | windowed(2s,${cadence}ms): firstToken=${first}ms maxTickDecode=${maxTick}ms ticks=$ticks " +
            "| \"$full\"")
    }
}
