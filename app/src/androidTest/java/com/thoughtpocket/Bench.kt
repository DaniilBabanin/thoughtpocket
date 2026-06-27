package com.thoughtpocket

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Shared helpers for the streaming-engine bench (ML Kit / sherpa-onnx / Moonshine): load the PCM fixtures
 * and compute WER against ground truth. Kept tiny on purpose — accuracy here is a coarse best-case ceiling
 * (clean read speech), not a prediction for messy capture; Whisper still owns the final transcript.
 */
object Bench {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    /** Raw 16 kHz mono s16le PCM asset → bytes (as stored; ML Kit's fromPfd wants these bytes). */
    fun pcmBytes(name: String): ByteArray = testCtx.assets.open(name).use { it.readBytes() }

    /** Same asset as float [-1,1] for the ORT engines (sherpa/Moonshine). */
    fun pcmFloats(name: String): FloatArray {
        val b = pcmBytes(name)
        return FloatArray(b.size / 2) { i ->
            val lo = b[2 * i].toInt() and 0xFF
            val hi = b[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    /** jfk16k.pcm = the canonical public-domain JFK clip (real human speech), ~11 s. */
    const val JFK_TRUTH =
        "and so my fellow americans ask not what your country can do for you " +
            "ask what you can do for your country"

    /** Word error rate after case/punct normalization — delegates to the shared, unit-tested [Wer] ruler. */
    fun wer(ref: String, hyp: String): Double = Wer.rate(ref, hyp)
}
