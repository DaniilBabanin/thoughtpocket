package com.soundscript.ai

import com.soundscript.data.Note

/** Markdown line patterns + pure (testable) edit ops shared by the renderer and [InteractEngine]. */
val CHECKBOX = Regex("^(\\s*)- \\[([ xX])] (.*)$")
val BULLET = Regex("^(\\s*)[-*] (.*)$")
val ORDERED = Regex("^(\\s*)(\\d+)\\. (.*)$")
val HEADING = Regex("^(#{1,6}) (.*)$")

private fun matches(label: String, item: String): Boolean {
    val a = label.trim().lowercase()
    val b = item.trim().lowercase()
    return b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
}

/** Flip the checkbox on line [lineIndex] of [markdown] to [checked]; returns the new Markdown. */
fun toggleCheckbox(markdown: String, lineIndex: Int, checked: Boolean): String {
    val lines = markdown.split("\n").toMutableList()
    val m = lines.getOrNull(lineIndex)?.let { CHECKBOX.find(it) } ?: return markdown
    val (ws, _, label) = m.destructured
    lines[lineIndex] = "$ws- [${if (checked) "x" else " "}] $label"
    return lines.joinToString("\n")
}

/** Set the checked state of the first checklist item matching [item]; unchanged if none match. */
fun setItemChecked(markdown: String, item: String, checked: Boolean): String {
    val lines = markdown.split("\n").toMutableList()
    for (i in lines.indices) {
        val m = CHECKBOX.find(lines[i]) ?: continue
        val (ws, _, label) = m.destructured
        if (matches(label, item)) {
            lines[i] = "$ws- [${if (checked) "x" else " "}] $label"
            return lines.joinToString("\n")
        }
    }
    return markdown
}

/** Insert "- [ ] item" at the top (after a leading heading) or bottom of the checklist. */
fun addItem(markdown: String, item: String, top: Boolean): String {
    val label = item.trim()
    if (label.isEmpty()) return markdown
    val newLine = "- [ ] $label"
    if (markdown.isBlank()) return newLine
    val lines = markdown.split("\n").toMutableList()
    val first = lines.indexOfFirst { CHECKBOX.containsMatchIn(it) }
    val last = lines.indexOfLast { CHECKBOX.containsMatchIn(it) }
    when {
        first < 0 -> lines.add(newLine)        // no checklist yet
        top -> lines.add(first, newLine)
        else -> lines.add(last + 1, newLine)
    }
    return lines.joinToString("\n")
}

/** Remove the first checklist item matching [item]; unchanged if none match. */
fun removeItem(markdown: String, item: String): String {
    val lines = markdown.split("\n").toMutableList()
    val idx = lines.indexOfFirst { CHECKBOX.find(it)?.let { m -> matches(m.destructured.component3(), item) } == true }
    if (idx < 0) return markdown
    lines.removeAt(idx)
    return lines.joinToString("\n")
}

/** Every checklist item across [notes] with the given [checked] state, as (note, label). */
fun checklistItems(notes: List<Note>, checked: Boolean): List<Pair<Note, String>> =
    notes.flatMap { n ->
        n.markdown.split("\n").mapNotNull { line ->
            val m = CHECKBOX.find(line) ?: return@mapNotNull null
            val isChecked = m.destructured.component2().equals("x", ignoreCase = true)
            if (isChecked == checked) n to m.destructured.component3().trim() else null
        }
    }

/** Every unchecked ("- [ ]") checklist item across [notes], as (note, label). */
fun openTasks(notes: List<Note>): List<Pair<Note, String>> = checklistItems(notes, checked = false)
