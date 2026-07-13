package com.thoughtpocket

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.audio.AudioFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Regression check for the default-language bug (2026-07-13): whisper.cpp
 * defaults params.language to "en", so a blank ISO code in Settings silently
 * ENGLISHED non-English audio on multilingual models. Now blank → "auto".
 * German speech is synthesized on-device (Android TTS) so the check needs no
 * bundled audio; SKIPs when no German voice or no multilingual model.
 */
class GermanLangCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun multilingualModel(): File? =
        File(ctx.filesDir, "models").listFiles { f ->
            f.name.startsWith("ggml-") && !f.name.contains(".en") && f.name.endsWith(".bin") &&
                !f.name.contains("silero")
        }?.maxByOrNull { it.length() }

    @Test
    fun germanAudioStaysGermanWithoutIsoCode() = runBlocking<Unit> {
        val model = multilingualModel() ?: run { Log.i("BENCH", "SKIP: no multilingual model"); return@runBlocking }
        val wav = File(ctx.cacheDir, "german_tts.wav")
        if (!ttsToFile(ctx, Locale.GERMAN,
                "Guten Morgen. Heute ist ein schöner Tag und ich gehe später zum Markt, " +
                    "um frisches Brot und etwas Käse zu kaufen.", wav)
        ) { Log.i("BENCH", "SKIP: no German TTS voice"); return@runBlocking }

        val pcm = File(ctx.cacheDir, "german_tts.pcm")
        val n = AudioFiles.decodeToFile(ctx, Uri.fromFile(wav), pcm)
        check(n > 0) { "TTS wav did not decode" }

        WhisperEngine.load(model, useGpu = false).getOrThrow()
        try {
            // Blank ISO code + translate off — the user-reported configuration.
            val text = WhisperEngine.transcribeFile(file = pcm, language = null, translate = false, threads = 4)
            Log.i("BENCH", "auto-detect transcript (${model.name}): $text")
            val lower = text.lowercase()
            val de = listOf("heute", "schöner", "schoner", "markt", "brot", "käse", "kase", "kaufen", "guten")
                .count { lower.contains(it) }
            val en = listOf("beautiful", "bread", "cheese", "good morning").count { lower.contains(it) }
            Log.i("BENCH", "german hits=$de, english hits=$en")
            assertTrue("expected German transcript, got: $text", de >= 2 && en == 0)
        } finally {
            WhisperEngine.release()
            wav.delete(); pcm.delete()
        }
    }

    private suspend fun ttsToFile(ctx: Context, locale: Locale, text: String, out: File): Boolean {
        val ready = CompletableDeferred<Boolean>()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        if (withTimeoutOrNull(10_000) { ready.await() } != true) { tts.shutdown(); return false }
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) { tts.shutdown(); return false }
        val done = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.complete(false) }
        })
        tts.synthesizeToFile(text, null, out, "de-check")
        val ok = withTimeoutOrNull(30_000) { done.await() } ?: false
        tts.shutdown()
        return ok && out.length() > 1_000
    }
}
