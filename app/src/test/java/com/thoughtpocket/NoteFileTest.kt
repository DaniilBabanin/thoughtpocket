package com.thoughtpocket

import com.thoughtpocket.data.Note
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFileTest {
    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun roundtrip(n: Note): Note = NoteFile.parse(NoteFile.serialize(n, ZoneOffset.UTC))

    @Test fun fullNoteRoundTrips() {
        val n = Note(
            id = 42, createdAt = epoch(2026, 6, 18, 9, 12, 3),
            text = "Need milk and eggs. Already got the bananas.",
            title = "Grocery run", markdown = "- [ ] milk\n- [x] bananas",
            tags = listOf("groceries", "shopping"),
        )
        val r = roundtrip(n)
        assertEquals(n.title, r.title)
        assertEquals(n.text, r.text)
        assertEquals(n.markdown, r.markdown)
        assertEquals(n.tags, r.tags)
        assertEquals(n.createdAt, r.createdAt)
    }

    @Test fun unformattedNoteRoundTrips() {
        val n = Note(createdAt = epoch(2026, 6, 18, 8, 0, 0), text = "just a quick thought", markdown = "", tags = emptyList())
        val r = roundtrip(n)
        assertEquals("", r.markdown)
        assertEquals("just a quick thought", r.text)
        assertEquals(emptyList<String>(), r.tags)
    }

    @Test fun titleAndTagsWithSpecialCharsRoundTrip() {
        val n = Note(createdAt = epoch(2026, 6, 18, 8, 0, 0), text = "x", title = "Plan: A, B [draft]", tags = listOf("to-do", "a:b"))
        val r = roundtrip(n)
        assertEquals("Plan: A, B [draft]", r.title)
        assertEquals(listOf("to-do", "a:b"), r.tags)
    }

    @Test fun lenientImportOfPlainMarkdown() {
        // A file dropped in with no front-matter / marker still imports as a note's text.
        val r = NoteFile.parse("Buy a birthday card\nand a gift")
        assertEquals("", r.markdown)
        assertEquals("Buy a birthday card\nand a gift", r.text)
        assertEquals("", r.title)
    }

    @Test fun filenameUsesStablePrefixNotTitle() {
        val a = Note(createdAt = epoch(2026, 6, 18, 9, 12, 3), text = "x", title = "First title")
        val b = a.copy(title = "Renamed later")
        // Same note (same createdAt) keeps the same identifying prefix even after a title change.
        assertEquals(NoteFile.prefix(a, ZoneOffset.UTC), NoteFile.prefix(b, ZoneOffset.UTC))
        assertTrue(NoteFile.filename(a, ZoneOffset.UTC).startsWith(NoteFile.prefix(a, ZoneOffset.UTC)))
        assertEquals("2026-06-18_091203 First title.md", NoteFile.filename(a, ZoneOffset.UTC))
    }
}
