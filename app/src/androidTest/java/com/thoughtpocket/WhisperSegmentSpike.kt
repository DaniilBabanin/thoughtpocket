package com.thoughtpocket

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.thoughtpocket.service.previewTail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the whisper.cpp `onSegment` JNI callback works on-device BEFORE it's wired into the live
 * preview ([com.thoughtpocket.service.RecordingService] `liveLoop`). That callback path
 * (`new_segment_callback` → `TranscribeCallback.jniSegment`) has never executed in production, and a bad
 * MethodID / env would be a native SIGSEGV that no `runCatching` can catch. The crash surface is the JNI
 * mechanics (MethodID lookup, env, String marshalling) — the audio content is irrelevant — so a synthetic
 * tone is enough: whisper still emits >=1 segment, exercising the callback.
 *   adb shell am instrument -e class com.thoughtpocket.WhisperSegmentSpike \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s WSEG
 */
class WhisperSegmentSpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    /** assets/speech16k.pcm — raw 16 kHz mono s16le, ~8 s of flite-synthesized speech (see git for the cmd). */
    private fun speechPcm(): FloatArray {
        val bytes = testCtx.assets.open("speech16k.pcm").use { it.readBytes() }
        return FloatArray(bytes.size / 2) { i ->
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    @Test
    fun streamsSegmentsWithoutCrashing() = runBlocking {
        val model = ModelManager.listInstalled(ctx).firstOrNull()
        assertTrue("no whisper model installed on device — push one first", model != null)
        WhisperEngine.load(ModelManager.fileFor(ctx, model!!), useGpu = false).getOrThrow()

        // Real synthesized speech — synthetic tone/noise was classified non-speech and produced zero
        // segments, so the callback never fired. Real words guarantee whisper emits segments, exercising
        // the actual native→JVM CallVoidMethod (the crash surface). Content is logged for sanity, not asserted.
        val pcm = speechPcm()
        val segments = mutableListOf<String>()
        val full = WhisperEngine.transcribe(
            pcm16k = pcm,
            language = "en",
            highQuality = false,
            onSegment = { seg -> segments.add(seg); Log.i("WSEG", "segment: \"$seg\"") },
        )
        Log.i("WSEG", "final=\"$full\" segments=${segments.size}")
        assertTrue("onSegment never fired — JNI callback not invoked", segments.isNotEmpty())
        WhisperEngine.release()
    }

    /**
     * Verifies the actual `liveLoop` shape that has never run in production: replay the speech fixture as a
     * sequence of ticks, each decoding the trailing 10 s window (same constants/params as RecordingService)
     * and running it through the real [previewTail]. Logs per-tick decode latency (the plan's ≤1 s target)
     * and the freshest-words preview, so tail jitter — the freshest words are the least stable, at the audio
     * edge with no right-context — is visible across ticks. The synthesized speech has no pauses, so this is
     * a best case for stability; real messy capture will jitter more.
     *   adb logcat -d -s LIVE
     */
    @Test
    fun simulatesLiveLoopTicks() = runBlocking {
        val model = ModelManager.listInstalled(ctx).firstOrNull()
        assertTrue("no whisper model installed on device", model != null)
        WhisperEngine.load(ModelManager.fileFor(ctx, model!!), useGpu = false).getOrThrow()

        val sr = 16_000
        val window = sr * 10            // PREVIEW_WINDOW_SAMPLES
        val maxWords = 14               // MAX_PREVIEW_WORDS
        val pcm = speechPcm()
        val durS = pcm.size / sr
        Log.i("LIVE", "fixture=${durS}s window=10s maxWords=$maxWords")
        var prevPreview = ""
        for (endS in 3..durS step 2) {
            val end = minOf(endS * sr, pcm.size)
            val start = maxOf(0, end - window)
            val win = pcm.copyOfRange(start, end)
            val t = SystemClock.elapsedRealtime()
            val text = WhisperEngine.transcribe(win, language = "en", highQuality = false)
            val ms = SystemClock.elapsedRealtime() - t
            val preview = previewTail(stripNonSpeech(text), maxWords)
            val winS = win.size / sr
            Log.i("LIVE", "tick@${endS}s win=${winS}s decode=${ms}ms preview=\"$preview\"")
            if (prevPreview.isNotEmpty()) Log.i("LIVE", "    full=\"${stripNonSpeech(text).takeLast(120)}\"")
            prevPreview = preview
        }
        WhisperEngine.release()
    }

    /**
     * Isolates the per-tick decode floor. whisper.cpp pads the mel to a fixed 30 s and runs the full-size
     * encoder every call, so decode time should be ~constant regardless of window length (the decoder adds
     * time proportional to token count). If a 1 s window still costs ~the same as a 10 s window, the
     * sub-second live-preview target is impossible with CPU re-decode — it needs GPU or a streaming model.
     *   adb logcat -d -s FLOOR
     */
    @Test
    fun decodeLatencyVsLength() = runBlocking {
        val model = ModelManager.listInstalled(ctx).firstOrNull()
        assertTrue("no whisper model installed on device", model != null)
        WhisperEngine.load(ModelManager.fileFor(ctx, model!!), useGpu = false).getOrThrow()
        val sr = 16_000
        val pcm = speechPcm()
        // Warm up once (first call pays one-time setup) so the measurements reflect steady state.
        WhisperEngine.transcribe(pcm.copyOfRange(0, sr), language = "en", highQuality = false)
        for (lenS in listOf(1, 2, 4, 7, 10)) {
            val win = pcm.copyOfRange(0, minOf(lenS * sr, pcm.size))
            val t = SystemClock.elapsedRealtime()
            val text = WhisperEngine.transcribe(win, language = "en", highQuality = false)
            val ms = SystemClock.elapsedRealtime() - t
            val nWords = stripNonSpeech(text).trim().split(Regex("\\s+")).count { it.isNotBlank() }
            Log.i("FLOOR", "len=${lenS}s decode=${ms}ms words=$nWords")
        }
        WhisperEngine.release()
    }

    /**
     * Quantifies the GPU lever. The ~2.8 s/tick floor is whisper's fixed-size encoder on CPU; Vulkan should
     * cut it sharply. Preview currently forces CPU (RecordingService loads useGpu=false) — this measures what
     * a GPU-backed preview would cost, to decide GPU-preview vs a Tier-2 streaming model. Logs the compiled
     * backend first: if "cpu", the binary has no Vulkan and GPU-preview needs a build change.
     *   adb logcat -d -s GPU
     */
    @Test
    fun decodeLatencyOnGpu() = runBlocking {
        val model = ModelManager.listInstalled(ctx).firstOrNull()
        assertTrue("no whisper model installed on device", model != null)
        Log.i("GPU", "compiledBackend=${WhisperEngine.compiledBackend}")
        val loaded = WhisperEngine.load(ModelManager.fileFor(ctx, model!!), useGpu = true)
        Log.i("GPU", "load(useGpu=true)=${if (loaded.isSuccess) "ok" else "FAIL:${loaded.exceptionOrNull()?.message}"} activeBackend=${WhisperEngine.activeBackend}")
        if (loaded.isFailure) return@runBlocking
        val sr = 16_000
        val pcm = speechPcm()
        WhisperEngine.transcribe(pcm.copyOfRange(0, sr), language = "en", highQuality = false) // warm up
        for (lenS in listOf(1, 4, 10)) {
            val win = pcm.copyOfRange(0, minOf(lenS * sr, pcm.size))
            val t = SystemClock.elapsedRealtime()
            WhisperEngine.transcribe(win, language = "en", highQuality = false)
            Log.i("GPU", "len=${lenS}s decode=${SystemClock.elapsedRealtime() - t}ms backend=${WhisperEngine.activeBackend}")
        }
        WhisperEngine.release()
    }
}
