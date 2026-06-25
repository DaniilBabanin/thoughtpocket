package com.thoughtpocket

import com.thoughtpocket.service.previewTail
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic tests for the live-preview word trimming ([previewTail]). */
class RecordingPreviewTest {
    @Test fun shortTextKeptWholeAndWhitespaceNormalized() {
        assertEquals("hello there world", previewTail("  hello   there\nworld ", 14))
    }

    @Test fun exactlyMaxWordsHasNoEllipsis() {
        assertEquals("one two three", previewTail("one two three", 3))
    }

    @Test fun longerThanMaxKeepsFreshestWordsWithEllipsis() {
        val text = "a b c d e f g h"
        assertEquals("… f g h", previewTail(text, 3))
    }

    @Test fun blankInBlankOut() {
        assertEquals("", previewTail("   ", 14))
        assertEquals("", previewTail("", 14))
    }
}
