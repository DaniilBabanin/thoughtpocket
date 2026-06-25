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

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Word error rate (word-level Levenshtein / ref length) after case/punct normalization. */
    fun wer(ref: String, hyp: String): Double {
        val r = norm(ref).split(" ").filter { it.isNotBlank() }
        val h = norm(hyp).split(" ").filter { it.isNotBlank() }
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            val cost = if (r[i - 1] == h[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        }
        return d[r.size][h.size].toDouble() / r.size
    }
}
