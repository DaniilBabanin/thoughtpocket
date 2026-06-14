package com.soundscript

import com.soundscript.data.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportTest {
    // Fixed formatter keeps the assertion timezone-independent.
    private val date: (Long) -> String = { "2026-06-14" }

    @Test
    fun serializesTitleMetaAndBody() {
        val notes = listOf(
            Note(id = 1, createdAt = 0, text = "raw transcript", title = "Groceries",
                markdown = "- [ ] milk", tags = listOf("food", "errands")),
        )
        assertEquals(
            "# Groceries\n\n_2026-06-14 · #food #errands_\n\n- [ ] milk",
            notesToMarkdown(notes, date),
        )
    }

    @Test
    fun joinsNotesWithSeparator() {
        val notes = listOf(
            Note(id = 1, createdAt = 0, text = "a", title = "A"),
            Note(id = 2, createdAt = 0, text = "b", title = "B"),
        )
        assertEquals("# A\n\n_2026-06-14_\n\na\n\n---\n\n# B\n\n_2026-06-14_\n\nb", notesToMarkdown(notes, date))
    }

    @Test
    fun fallsBackToTextAndFirstLine() {
        // No title, no markdown: heading = first line, body = raw text.
        val notes = listOf(Note(id = 1, createdAt = 0, text = "first line\nsecond line"))
        assertEquals("# first line\n\n_2026-06-14_\n\nfirst line\nsecond line", notesToMarkdown(notes, date))
    }

    @Test
    fun emptyListIsEmpty() {
        assertEquals("", notesToMarkdown(emptyList(), date))
    }

    @Test
    fun untitledEmptyNoteUsesPlaceholder() {
        val notes = listOf(Note(id = 1, createdAt = 0, text = ""))
        assertEquals("# Untitled\n\n_2026-06-14_\n\n", notesToMarkdown(notes, date))
    }

    @Test
    fun tagsWithoutTitleHeadingFromText() {
        val notes = listOf(Note(id = 1, createdAt = 0, text = "buy stuff", tags = listOf("a", "b")))
        assertEquals("# buy stuff\n\n_2026-06-14 · #a #b_\n\nbuy stuff", notesToMarkdown(notes, date))
    }

    @Test
    fun trimsTrailingWhitespaceButKeepsInternalNewlines() {
        val notes = listOf(Note(id = 1, createdAt = 0, text = "", title = "T", markdown = "- [ ] x\nline2\n\n  "))
        assertEquals("# T\n\n_2026-06-14_\n\n- [ ] x\nline2", notesToMarkdown(notes, date))
    }

    @Test
    fun whitespaceOnlyMarkdownFallsBackToText() {
        val notes = listOf(Note(id = 1, createdAt = 0, text = "real body", title = "T", markdown = "   \n  "))
        assertEquals("# T\n\n_2026-06-14_\n\nreal body", notesToMarkdown(notes, date))
    }
}
