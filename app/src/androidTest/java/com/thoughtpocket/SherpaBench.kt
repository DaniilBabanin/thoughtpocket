package com.thoughtpocket

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.junit.Test
import java.io.File

/**
 * Benchmark for the sherpa-onnx streaming Zipformer transducer (true frame-by-frame streaming) — engine #2.
 * Model pushed (encoder/decoder/joiner .onnx + tokens.txt) to the app external-files dir:
 *   /sdcard/Android/data/com.thoughtpocket/files/sherpa/
 * Two passes on the JFK clip: (1) REAL-TIME-fed → first-token + finalize lag (comparable to ML Kit);
 * (2) flat-out → compute RTF (ORT-only headroom). Plus model-load time and WER.
 *   adb logcat -d -s SHERPA
 */
class SherpaBench {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jfkStreaming() {
        val dir = File(ctx.getExternalFilesDir(null), "sherpa")
        val enc = File(dir, "encoder.onnx")
        if (!enc.exists()) { Log.e("SHERPA", "model missing at $dir — push it first"); return }

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.onnx").absolutePath,
                    decoder = File(dir, "decoder.onnx").absolutePath,
                    joiner = File(dir, "joiner.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        val loadT = SystemClock.elapsedRealtime()
        val rec = OnlineRecognizer(config = config)   // assetManager defaults null → filesystem paths
        val loadMs = SystemClock.elapsedRealtime() - loadT
        Log.i("SHERPA", "model loaded in ${loadMs}ms")

        val pcm = Bench.pcmFloats("jfk16k.pcm")
        val audioMs = pcm.size / 16L

        // --- Pass 1: real-time-fed (100 ms chunks paced to audio time) ---
        run {
            val stream = rec.createStream()
            val t0 = SystemClock.elapsedRealtime()
            fun el() = SystemClock.elapsedRealtime() - t0
            var firstTokenMs = -1L
            var i = 0; val chunk = 1600
            while (i < pcm.size) {
                val n = minOf(chunk, pcm.size - i)
                stream.acceptWaveform(pcm.copyOfRange(i, i + n), 16000); i += n
                while (rec.isReady(stream)) rec.decode(stream)
                if (firstTokenMs < 0 && rec.getResult(stream).text.isNotBlank()) firstTokenMs = el()
                val ahead = (i / 16L) - el()
                if (ahead > 0) Thread.sleep(ahead)
            }
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.getResult(stream).text
            val finalizeLag = el() - audioMs
            stream.release()
            val werPct = (Bench.wer(Bench.JFK_TRUTH, text) * 100).toInt()
            Log.i("SHERPA", "RESULT engine=sherpa-zipformer-en firstToken=${firstTokenMs}ms audioDur=${audioMs}ms " +
                "finalizeLag=${finalizeLag}ms WER=$werPct% transcript=\"$text\"")
        }

        // --- Pass 2: flat-out compute (no pacing) → RTF headroom ---
        run {
            val stream = rec.createStream()
            val t0 = SystemClock.elapsedRealtime()
            stream.acceptWaveform(pcm, 16000)
            stream.inputFinished()
            while (rec.isReady(stream)) rec.decode(stream)
            val computeMs = SystemClock.elapsedRealtime() - t0
            stream.release()
            Log.i("SHERPA", "RTF: compute=${computeMs}ms audio=${audioMs}ms rtf=${"%.3f".format(computeMs.toDouble() / audioMs)}")
        }
        rec.release()
    }
}
