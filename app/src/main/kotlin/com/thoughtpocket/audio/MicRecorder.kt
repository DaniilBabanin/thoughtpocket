package com.thoughtpocket.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Captures 16 kHz mono PCM from the mic and STREAMS it to [file] as little-endian int16 — so memory
 * stays bounded no matter how long the recording is, and the audio survives a process kill (the OS
 * keeps already-flushed bytes). int16 is half the size of float and is converted to float only when
 * handed to [com.thoughtpocket.WhisperEngine]. The CALLER owns [file]'s lifecycle — call [discard]
 * (short throwaway recordings) or delete it once transcribed (the recording queue).
 *
 * [readTail] returns just the last N samples for a bounded live-preview window; [readAll] materializes
 * the whole file once (for the final transcription).
 */
class MicRecorder(private val file: File) {
    private val sampleRate = 16_000
    private val frame = sampleRate / 50          // 320 samples = 20 ms
    private val flushEvery = sampleRate * 2      // flush ~every 2 s → a kill loses ≤2 s

    @Volatile private var totalSamples = 0
    @Volatile private var stopped = false
    @Volatile private var levelValue = 0f
    private var ar: AudioRecord? = null
    private var out: BufferedOutputStream? = null

    /** Samples captured so far. */
    val samples: Int get() = totalSamples

    /** Smoothed mic loudness in 0..1 for live UI feedback (RMS; fast attack, slow decay). */
    fun level(): Float = levelValue

    private fun updateLevel(buf: ShortArray, n: Int) {
        if (n <= 0) return
        var sum = 0f
        for (i in 0 until n) { val v = buf[i] / 32768f; sum += v * v }
        val rms = sqrt(sum / n)
        val target = (rms * 4.5f).coerceIn(0f, 1f)   // map typical speech RMS → ~full scale
        levelValue += (target - levelValue) * if (target > levelValue) 0.55f else 0.12f
    }

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO is granted
    fun start() {
        if (stopped) return   // stop raced ahead of start (instant re-tap): never touch the mic
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(min != AudioRecord.ERROR_BAD_VALUE) { "AudioRecord params unsupported" }
        file.parentFile?.mkdirs()
        out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        try {
            ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, frame * 2 * 4)
            ).also { check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" } }
            ar!!.startRecording()
        } catch (t: Throwable) {
            // Mic busy is a normal failure here — release the fd + mic session instead of leaking them
            // (runUntilStopped's cleanup never runs when start throws).
            runCatching { out?.close() }; out = null
            runCatching { ar?.release() }; ar = null
            throw t
        }
    }

    /** Latched one-shot: a stop that races ahead of [start]/[runUntilStopped] is never lost. */
    fun stop() { stopped = true }

    /** Blocks (on IO) reading the mic and appending int16 to [file] until [stop] is called. */
    suspend fun runUntilStopped() = withContext(Dispatchers.IO) {
        val a = ar ?: return@withContext
        val o = out ?: return@withContext
        val buf = ShortArray(frame)
        val bytes = ByteArray(frame * 2)
        var sinceFlush = 0
        try {
            while (!stopped && isActive) {
                val n = a.read(buf, 0, frame)
                if (n > 0) {
                    updateLevel(buf, n)
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) bb.putShort(buf[i])
                    o.write(bytes, 0, n * 2)
                    totalSamples += n
                    sinceFlush += n
                    // ponytail: flush to the OS page cache survives a process-kill; fsync (power-loss) is overkill here.
                    if (sinceFlush >= flushEvery) { o.flush(); sinceFlush = 0 }
                }
            }
        } finally {
            levelValue = 0f
            runCatching { o.flush(); o.close() }
            runCatching { a.stop() }
            runCatching { a.release() }
            ar = null; out = null
        }
    }

    /** All samples captured so far, as float [-1,1] (one big array — used for the final transcription). */
    fun readAll(): FloatArray = readPcm(file)

    /** The last [maxSamples] samples as float — a bounded window for live preview. */
    fun readTail(maxSamples: Int): FloatArray = readPcm(file, maxSamples)

    /** Samples in [from, to) as float — for an accumulating live preview that commits chunk by chunk. */
    fun readRange(from: Int, to: Int): FloatArray = readPcmRange(file, from, to)

    /** Delete the backing file (throwaway recordings; the queue deletes its own after transcribing). */
    fun discard() { runCatching { file.delete() } }

    companion object {
        /** A recorder writing to a throwaway file in the cache dir (voice search / Interact commands). */
        fun temp(context: Context): MicRecorder =
            MicRecorder(File.createTempFile("voice", ".pcm", context.cacheDir))

        /**
         * Read a little-endian int16 PCM [file] as float [-1,1], optionally only the last [maxSamples].
         * Streams in 64 KB blocks so it never allocates a second big byte buffer. "" / missing → empty.
         */
        fun readPcm(file: File, maxSamples: Int = Int.MAX_VALUE): FloatArray = runCatching {
            if (!file.exists()) return FloatArray(0)
            RandomAccessFile(file, "r").use { raf ->
                val avail = (raf.length() / 2).toInt()
                val start = (avail.toLong() - maxSamples).coerceAtLeast(0L)
                val count = (avail - start).toInt()
                if (count <= 0) return FloatArray(0)
                raf.seek(start * 2)
                val out = FloatArray(count)
                val block = ByteArray(1 shl 16)
                var i = 0
                while (i < count) {
                    val toRead = minOf(block.size, (count - i) * 2)
                    raf.readFully(block, 0, toRead)
                    val bb = ByteBuffer.wrap(block, 0, toRead).order(ByteOrder.LITTLE_ENDIAN)
                    val n = toRead / 2
                    repeat(n) { out[i++] = bb.short.toFloat() / 32768f }
                }
                out
            }
        }.getOrElse { FloatArray(0) }

        /** Read a little-endian int16 PCM [file] as float [-1,1] for samples in [from, to). Clamped to file. */
        fun readPcmRange(file: File, from: Int, to: Int): FloatArray = runCatching {
            if (!file.exists()) return FloatArray(0)
            RandomAccessFile(file, "r").use { raf ->
                val avail = (raf.length() / 2).toInt()
                val start = from.coerceIn(0, avail)
                val count = to.coerceIn(start, avail) - start
                if (count <= 0) return FloatArray(0)
                raf.seek(start.toLong() * 2)
                val out = FloatArray(count)
                val block = ByteArray(1 shl 16)
                var i = 0
                while (i < count) {
                    val toRead = minOf(block.size, (count - i) * 2)
                    raf.readFully(block, 0, toRead)
                    val bb = ByteBuffer.wrap(block, 0, toRead).order(ByteOrder.LITTLE_ENDIAN)
                    val n = toRead / 2
                    repeat(n) { out[i++] = bb.short.toFloat() / 32768f }
                }
                out
            }
        }.getOrElse { FloatArray(0) }
    }
}
