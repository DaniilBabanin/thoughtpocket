package com.thoughtpocket

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * SPIKE (Phase 1, coder feature): whisper-correctness canary for the shared-ggml build.
 * llama.cpp's ggml now wins the `if(NOT TARGET ggml)` guard — whisper compiles against it.
 * A silent ABI skew would corrupt transcription, so: transcribe the 11 s JFK clip and gate
 * on WER against the known truth. Needs ggml-base-q5_1.bin staged via run-as into
 * files/models (push-bench.sh convention).
 *
 *   adb shell am instrument -w -e class com.thoughtpocket.WhisperSkewCheck \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 */
class WhisperSkewCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jfkClipTranscribesCorrectly() = runBlocking {
        val model = ModelManager.BuiltInModel.BASE_Q5
        check(ModelManager.fileFor(ctx, model).exists()) { "push ggml-base-q5_1.bin first" }
        val pcm = Bench.pcmFloats("jfk16k.pcm")
        val prefs = AppPreferences(ctx).apply { language = "en"; translateToEnglish = false }
        val hyp = WhisperTranscriber.transcribe(ctx, model, pcm, prefs, highQuality = false, useVad = true)
        val wer = Bench.wer(Bench.JFK_TRUTH, hyp)
        Log.i("BENCH", "skew-check hyp=\"$hyp\" WER=$wer")
        WhisperTranscriber.release()
        check(wer < 0.15) { "WER $wer too high — possible ggml ABI skew (hyp: $hyp)" }
    }
}
