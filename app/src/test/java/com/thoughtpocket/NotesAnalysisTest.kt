package com.thoughtpocket

import com.thoughtpocket.ai.NotesAnalysis
import com.thoughtpocket.data.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** renderLines is ask()'s pure rendering half (packing is covered by LlmHarnessTest). */
class NotesAnalysisTest {
    @Test fun rendersDatePrefixedLinePreferringMarkdown() {
        val lines = NotesAnalysis.renderLines(listOf(Note(createdAt = 0L, text = "raw", markdown = "- [ ] milk")), 200)
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("- ("))
        assertTrue(lines[0].endsWith(") - [ ] milk"))
    }

    @Test fun blankMarkdownFallsBackToTranscript() {
        val lines = NotesAnalysis.renderLines(listOf(Note(createdAt = 0L, text = "spoken words")), 200)
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith(") spoken words"))
    }

    @Test fun oversizedNoteSplitsIntoDatedParts() {
        // A single note bigger than the per-call budget must split, keep every part under
        // budget, date-label the continuations, and lose no content.
        val body = "z".repeat(500)
        val lines = NotesAnalysis.renderLines(listOf(Note(createdAt = 0L, text = body)), 120)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 120 })
        assertTrue(lines.drop(1).all { "contd." in it })
        assertEquals(body, lines.joinToString("") { it.substringAfter(") ") })
    }
}
