package com.thoughtpocket

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the PRODUCTION Moonshine delivery path end-to-end over the real network (Nextcloud) — the one
 * piece a manual `adb push` can't validate: does [ModelManager.downloadMoonshine] (HttpURLConnection +
 * redirect-following + the completeness guard) actually land the 5 ORT files, and does the engine then load
 * + transcribe from them? Uses TINY (~123 MB) to keep it quick. Needs device Wi-Fi.
 *   adb logcat -d -s MOONDL
 */
class MoonshineDownloadTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun downloadsAndTranscribes() = runBlocking {
        val model = ModelManager.StreamingModel.MOONSHINE_TINY
        val dir = ModelManager.moonshineDir(ctx, model)
        dir.deleteRecursively(); dir.mkdirs()   // force a real download, not a resume

        var last = -1
        ModelManager.downloadMoonshine(ctx, model).collect { pct -> if (pct != last) { last = pct } }

        val sizes = ModelManager.MOONSHINE_FILES.associateWith { File(dir, it).length() }
        Log.i("MOONDL", "downloaded → $sizes")
        assertTrue("isDownloaded should be true after a clean download", ModelManager.isDownloaded(ctx, model))
        // Each ORT file must be real bytes, not a tiny HTML redirect page (the contentLength trap).
        for ((name, len) in sizes) assertTrue("$name too small ($len) — redirect page, not the model?", len > 100_000L)

        // The downloaded files must actually drive the engine.
        val text = MoonshineTranscriber.transcribe(ctx, model, Bench.pcmFloats("jfk16k.pcm"), AppPreferences(ctx), highQuality = false)
        MoonshineTranscriber.release()
        Log.i("MOONDL", "transcript from downloaded model: \"$text\"")
        assertTrue("expected JFK words from downloaded model, got \"$text\"", text.lowercase().contains("country"))
    }
}
