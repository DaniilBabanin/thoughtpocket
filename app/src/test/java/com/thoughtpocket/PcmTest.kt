package com.thoughtpocket

import com.thoughtpocket.audio.shortsToFloat
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test
    fun convertsToMinusOneToOneRange() {
        val src = shortArrayOf(0, 16384, -16384, Short.MAX_VALUE)
        val out = shortsToFloat(src, src.size)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-7f)
        assertEquals(0.5f, out[1], 1e-7f)
        assertEquals(-0.5f, out[2], 1e-7f)
        assertEquals(32767f / 32768f, out[3], 1e-7f)
    }

    @Test
    fun honoursLengthArgument() {
        val src = shortArrayOf(1, 2, 3, 4, 5)
        assertEquals(2, shortsToFloat(src, 2).size)
    }
}
