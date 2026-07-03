package com.thoughtpocket

import com.thoughtpocket.data.Converters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Room column converters: tags as a -delimited string, embedding as a little-endian float BLOB. */
class ConvertersTest {
    private val c = Converters()

    // ---- tags ----

    @Test fun tagsRoundTrip() {
        val tags = listOf("groceries", "home improvement", "a,b")   // commas must survive
        assertEquals(tags, c.toTags(c.fromTags(tags)))
    }

    @Test fun tagsUseUnitSeparator() {
        assertEquals("ab", c.fromTags(listOf("a", "b")))
    }

    @Test fun emptyTagsAreEmptyListNotBlankTag() {
        assertEquals("", c.fromTags(emptyList()))
        assertEquals(emptyList<String>(), c.toTags(""))
    }

    // ---- embedding vector ----

    @Test fun vecRoundTrip() {
        val v = floatArrayOf(0f, 1.5f, -2.25f, 3.4e38f)
        assertArrayEquals(v, c.toVec(c.fromVec(v)), 0f)
    }

    @Test fun vecIsLittleEndian() {
        // 1.0f = 0x3F800000 → LE bytes 00 00 80 3F. A byte-order regression would silently
        // scramble every persisted embedding.
        assertArrayEquals(byteArrayOf(0, 0, -128, 0x3F), c.fromVec(floatArrayOf(1f)))
    }

    @Test fun nullVecStaysNull() {
        assertNull(c.fromVec(null))
        assertNull(c.toVec(null))
    }
}
