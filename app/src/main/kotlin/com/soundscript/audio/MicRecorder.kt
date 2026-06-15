package com.soundscript.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Captures 16 kHz mono PCM from the mic and accumulates it as float samples — exactly
 * what [com.soundscript.WhisperEngine] expects, so no file or codec round-trip is needed.
 * Samples are held in memory only (transcript-only app); nothing is written to disk.
 *
 * [snapshot] returns the audio captured so far while recording is still running, which
 * powers live (streaming) transcription.
 */
class MicRecorder {
    private val sampleRate = 16_000
    private val frame = sampleRate / 50 // 320 samples = 20 ms

    private val lock = Any()
    private val chunks = ArrayList<FloatArray>()
    private var total = 0

    @Volatile private var recording = false
    @Volatile private var levelValue = 0f
    private var ar: AudioRecord? = null

    /** Smoothed mic loudness in 0..1 for live UI feedback (RMS; fast attack, slow decay). */
    fun level(): Float = levelValue

    private fun updateLevel(samples: FloatArray, n: Int) {
        if (n <= 0) return
        var sum = 0f
        for (i in 0 until n) sum += samples[i] * samples[i]
        val rms = sqrt(sum / n)
        val target = (rms * 4.5f).coerceIn(0f, 1f)   // map typical speech RMS → ~full scale
        levelValue += (target - levelValue) * if (target > levelValue) 0.55f else 0.12f
    }

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO is granted
    fun start() {
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(min != AudioRecord.ERROR_BAD_VALUE) { "AudioRecord params unsupported" }
        ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, frame * 2 * 4)
        ).also { check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" } }
        recording = true
        ar!!.startRecording()
    }

    fun stop() { recording = false }

    /** Blocks (on IO) reading the mic until [stop] is called. */
    suspend fun runUntilStopped() = withContext(Dispatchers.IO) {
        val a = ar ?: return@withContext
        val buf = ShortArray(frame)
        try {
            while (recording && isActive) {
                val n = a.read(buf, 0, frame)
                if (n > 0) {
                    val f = shortsToFloat(buf, n)
                    updateLevel(f, n)
                    synchronized(lock) { chunks.add(f); total += n }
                }
            }
        } finally {
            levelValue = 0f
            runCatching { a.stop() }
            runCatching { a.release() }
            ar = null
        }
    }

    /** A copy of all samples captured so far (safe to call while recording). */
    fun snapshot(): FloatArray = synchronized(lock) {
        val out = FloatArray(total)
        var o = 0
        for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
        out
    }
}
