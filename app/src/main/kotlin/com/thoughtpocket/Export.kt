package com.thoughtpocket

import com.thoughtpocket.data.Note
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Serialize notes to one Markdown document for export. Pure + testable; the UI hands the
 * result to SAF (see [ui.SettingsScreen]). [formatDate] is injectable so the unit test stays
 * timezone-independent. Prefers a note's formatted [Note.markdown], falling back to raw [Note.text].
 */
fun notesToMarkdown(
    notes: List<Note>,
    formatDate: (Long) -> String = ::isoDate,
): String = notes.joinToString("\n\n---\n\n") { n ->
    val heading = n.title.ifBlank { n.text.substringBefore('\n').ifBlank { "Untitled" } }
    val tagLine = n.tags.joinToString(" ") { "#$it" }
    val meta = listOf(formatDate(n.createdAt), tagLine).filter { it.isNotBlank() }.joinToString(" · ")
    buildString {
        append("# ").append(heading).append("\n\n")
        if (meta.isNotEmpty()) append("_").append(meta).append("_\n\n")
        append(n.markdown.ifBlank { n.text }.trimEnd())
    }
}

private fun isoDate(epochMs: Long): String =
    DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
