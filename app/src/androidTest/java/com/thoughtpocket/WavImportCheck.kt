package com.thoughtpocket

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.audio.AudioFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Real-pipeline checks for hi-res WAV import (2026-07-13: 24/32-bit PCM
 * support + streaming decode — a ~300 MB WAV OOM'd the whole-file path).
 * Stage 440 Hz sine WAVs first (see tools/ usage in the review doc):
 *
 *   adb push t96k24.wav t96k32f.wav t48k16.wav [t96k24big.wav] \
 *     /sdcard/Android/data/com.thoughtpocket/files/import-check/
 *   adb shell am instrument -w -e class com.thoughtpocket.WavImportCheck \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 *
 * A 440 Hz tone has ~880 zero crossings/s; misread bytes are noise with an
 * order of magnitude more. SKIPs per test when its staged file is absent.
 */
class WavImportCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** Decode a staged WAV through the production path; null = not staged. */
    private fun decodeStaged(name: String): Pair<Long, File>? {
        val f = File(ctx.getExternalFilesDir("import-check"), name)
        if (!f.exists()) { Log.i("BENCH", "SKIP: $name not staged"); return null }
        val out = File(ctx.cacheDir, "$name.pcm")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val samples = AudioFiles.decodeToFile(ctx, Uri.fromFile(f), out)
        Log.i("BENCH", "$name → $samples samples in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        return samples to out
    }

    private fun readFloats(pcm: File, maxSamples: Int): FloatArray {
        val bytes = pcm.inputStream().use { it.readNBytes(maxSamples * 2) }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 2) { bb.short / 32768f }
    }

    private fun checkTone(name: String, expectSeconds: Float = 2f) {
        val (samples, pcm) = decodeStaged(name) ?: return
        try {
            assertEquals("$name: duration", expectSeconds * 16_000, samples.toFloat(), expectSeconds * 16_000 * 0.03f)
            val head = readFloats(pcm, 32_000) // tone checks on the first 2 s
            var crossings = 0
            for (i in 1 until head.size) if (head[i - 1] < 0 != head[i] < 0) crossings++
            val peak = head.maxOf { abs(it) }
            val expected = 880f * head.size / 16_000
            Log.i("BENCH", "$name → crossings=$crossings (expect ~${expected.toInt()}), peak=$peak")
            assertTrue("$name: not a clean 440 Hz tone (crossings=$crossings)", abs(crossings - expected) < expected * 0.2f)
            assertTrue("$name: implausible amplitude $peak", peak in 0.3f..1.0f)
        } finally {
            pcm.delete()
        }
    }

    @Test fun wav96k24bitDecodes() = checkTone("t96k24.wav")
    @Test fun wav96kFloatDecodes() = checkTone("t96k32f.wav")
    @Test fun wav48k16bitControl() = checkTone("t48k16.wav")

    /** The user-repro: ~18 min of 96 kHz/24-bit (~311 MB) must decode in bounded RAM. */
    @Test fun bigHiResWavNoOom() = checkTone("t96k24big.wav", expectSeconds = 18 * 60f)
}
