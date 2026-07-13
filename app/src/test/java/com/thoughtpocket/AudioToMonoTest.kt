package com.thoughtpocket

import com.thoughtpocket.audio.AudioFiles.ChunkedPcmTo16k
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming PCM converter: every supported encoding maps to the same float
 * samples, and chunk boundaries (down to 1-byte feeds) never change the result.
 */
class AudioToMonoTest {

    private val eps = 1e-3f

    private fun convert(bytes: ByteArray, rate: Int, channels: Int, encoding: Int, chunkSize: Int = bytes.size): FloatArray {
        val out = ArrayList<Float>()
        val conv = ChunkedPcmTo16k(rate, channels, encoding) { out.add(it) }
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunkSize, bytes.size - i)
            conv.feed(bytes, i, n)
            i += n
        }
        return out.toFloatArray()
    }

    @Test fun pcm16() {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(16384).putShort(-16384).array()
        val out = convert(bytes, 16_000, 1, 2)
        assertEquals(0.5f, out[0], eps); assertEquals(-0.5f, out[1], eps)
    }

    @Test fun pcm24Packed() {
        fun put24(b: ByteBuffer, v: Int) {
            b.put((v and 0xFF).toByte()).put((v shr 8 and 0xFF).toByte()).put((v shr 16 and 0xFF).toByte())
        }
        val b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        put24(b, 4_194_304); put24(b, -4_194_304) // ±0.5 * 2^23
        val out = convert(b.array(), 16_000, 1, 21)
        assertEquals(0.5f, out[0], eps); assertEquals(-0.5f, out[1], eps)
    }

    @Test fun pcm32Int() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_073_741_824).putInt(-1_073_741_824).array() // ±0.5 * 2^31
        val out = convert(bytes, 16_000, 1, 22)
        assertEquals(0.5f, out[0], eps); assertEquals(-0.5f, out[1], eps)
    }

    @Test fun pcmFloat() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.5f).putFloat(-0.5f).array()
        val out = convert(bytes, 16_000, 1, 4)
        assertEquals(0.5f, out[0], eps); assertEquals(-0.5f, out[1], eps)
    }

    @Test fun stereoDownmix24Bit() {
        // L = +0.5, R = -0.5 → mono 0.
        val b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0).put(0).put(0x40).put(0).put(0).put(0xC0.toByte())
        val out = convert(b.array(), 16_000, 2, 21)
        assertEquals(1, out.size)
        assertEquals(0f, out[0], eps)
    }

    @Test fun highRateGetsResampled() {
        // 96 kHz mono 16-bit, 9600 samples (100 ms) → ~1600 samples at 16 kHz.
        val b = ByteBuffer.allocate(9600 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(9600) { b.putShort(1000) }
        val out = convert(b.array(), 96_000, 1, 2)
        assertEquals(1600f, out.size.toFloat(), 2f)
        assertEquals(1000f / 32768f, out[800], eps)
    }

    @Test fun oneByteChunksMatchOneShot() {
        // Awkward boundaries split every 3-byte stereo frame — carry must make
        // the output byte-identical to a single feed.
        val b = ByteBuffer.allocate(24 * 3).order(ByteOrder.LITTLE_ENDIAN)
        repeat(24) { i -> b.put((i * 11).toByte()).put((i * 7).toByte()).put((i * 3 - 30).toByte()) }
        val bytes = b.array()
        assertArrayEquals(
            convert(bytes, 48_000, 2, 21, chunkSize = bytes.size),
            convert(bytes, 48_000, 2, 21, chunkSize = 1),
            eps,
        )
    }
}
