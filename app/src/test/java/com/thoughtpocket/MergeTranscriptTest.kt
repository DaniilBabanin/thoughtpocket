package com.thoughtpocket

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic tests for the transcript join ([mergeTranscript]) that builds the saved note text. */
class MergeTranscriptTest {
    @Test fun joinsWithSingleSpace() {
        assertEquals("hello world", mergeTranscript("hello", "world"))
    }

    @Test fun emptyCommittedYieldsTail() {
        assertEquals("world", mergeTranscript("", "world"))
    }

    @Test fun emptyTailYieldsCommitted() {
        assertEquals("hello", mergeTranscript("hello", ""))
    }

    @Test fun bothEmptyYieldsEmpty() {
        assertEquals("", mergeTranscript("", ""))
    }

    @Test fun whitespaceOnlyMergeCollapsesToEmpty() {
        // Must be exactly "" — a stray-space result would slip past isEmpty checks and could end up as
        // note text over already-deleted audio instead of the "(no speech detected)" fallback.
        assertEquals("", mergeTranscript(" ", ""))
        assertEquals("", mergeTranscript("", "  "))
        assertEquals("", mergeTranscript(" ", " \n\t"))
    }

    @Test fun sidesAreTrimmedBeforeJoining() {
        assertEquals("hello world", mergeTranscript(" hello ", " world "))
        assertEquals("hello", mergeTranscript("hello", "   "))
        assertEquals("world", mergeTranscript("\t", "world "))
    }
}
