package com.thoughtpocket

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device smoke test for the PRODUCTION [MoonshineTranscriber] (not the raw-sherpa bench): drives the same
 * code path RecordingService's first pass will use — resolve dir from [ModelManager.StreamingModel], lazy
 * load, windowed decode, [stripNonSpeech]. Needs the model pushed to externalFilesDir/moonshine (base).
 *   adb logcat -d -s MOONENG
 */
class MoonshineEngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun now() = SystemClock.elapsedRealtime()

    @Test fun productionEngineTranscribes() = runBlocking {
        val model = ModelManager.StreamingModel.MOONSHINE_BASE
        if (!ModelManager.isDownloaded(ctx, model)) {
            Log.e("MOONENG", "model not installed at ${ModelManager.moonshineDir(ctx, model)} — push it first"); return@runBlocking
        }
        val prefs = AppPreferences(ctx)
        val pcm = Bench.pcmFloats("jfk16k.pcm")

        // Full clip through the production engine.
        val t0 = now()
        val full = MoonshineTranscriber.transcribe(ctx, model, pcm, prefs, highQuality = false)
        val fullMs = now() - t0
        val wer = (Bench.wer(Bench.JFK_TRUTH, full) * 100).toInt()

        // Re-use the loaded recognizer on a trailing 2 s window (the live-preview shape) — must stay serialized.
        val win = pcm.copyOfRange(maxOf(0, pcm.size - 16000 * 2), pcm.size)
        val tw = now()
        val winText = MoonshineTranscriber.transcribe(ctx, model, win, prefs, highQuality = false)
        val winMs = now() - tw

        Log.i("MOONENG", "full=${fullMs}ms WER=$wer% windowDecode=${winMs}ms full=\"$full\" win=\"$winText\"")
        assertTrue("expected non-blank transcript, got \"$full\"", full.isNotBlank())
        assertTrue("expected JFK words, got \"$full\"", full.lowercase().contains("country"))

        // Long-audio path (first-only over a long recording): tile the clip past the 25 s chunk threshold so
        // the internal chunk-and-join runs. Must still come back coherent (each chunk decoded + concatenated).
        val tiled = FloatArray(pcm.size * 3).also { for (k in 0 until 3) pcm.copyInto(it, k * pcm.size) }
        val tl = now()
        val long = MoonshineTranscriber.transcribe(ctx, model, tiled, prefs, highQuality = false)
        Log.i("MOONENG", "long(${tiled.size / 16000}s)=${now() - tl}ms chunks→ \"$long\"")
        MoonshineTranscriber.release()
        assertTrue("expected chunked transcript to repeat content, got \"$long\"",
            Regex("country").findAll(long.lowercase()).count() >= 2)
    }
}
