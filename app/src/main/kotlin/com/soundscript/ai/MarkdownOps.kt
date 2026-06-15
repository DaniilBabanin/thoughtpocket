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

/**
 * Carry checked state across a reformat. A reformat regenerates the Markdown from the raw transcript, so
 * every checklist item comes back unchecked; this re-checks, in [newMarkdown], each item that was checked
 * in [oldMarkdown] and still has a matching item. Items the reformat reworded or dropped are simply left
 * unchecked. Pure + testable.
 */
fun preserveChecked(oldMarkdown: String, newMarkdown: String): String {
    if (oldMarkdown.isBlank() || newMarkdown.isBlank()) return newMarkdown
    val checkedLabels = oldMarkdown.split("\n").mapNotNull { line ->
        val m = CHECKBOX.find(line) ?: return@mapNotNull null
        if (m.destructured.component2().equals("x", ignoreCase = true)) m.destructured.component3().trim() else null
    }
    var md = newMarkdown
    for (label in checkedLabels) md = setItemChecked(md, label, true)
    return md
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

/** Rename the first checklist item matching [oldLabel] to [newLabel], keeping its checked state + indent. */
fun renameItem(markdown: String, oldLabel: String, newLabel: String): String {
    val label = newLabel.trim()
    if (label.isEmpty()) return markdown
    val lines = markdown.split("\n").toMutableList()
    for (i in lines.indices) {
        val m = CHECKBOX.find(lines[i]) ?: continue
        val (ws, mark, cur) = m.destructured
        if (matches(cur, oldLabel)) { lines[i] = "$ws- [$mark] $label"; return lines.joinToString("\n") }
    }
    return markdown
}

/**
 * Turn the note into a checkbox list: every non-blank, non-heading line becomes "- [ ] …"
 * (bullets and numbered items unwrapped first). Items already checked or already checkboxes are
 * kept; indent is preserved. Backs the Interact "make it a checkbox list" command. Pure + testable.
 */
fun bulletsToChecklist(markdown: String): String {
    if (markdown.isBlank()) return markdown
    return markdown.split("\n").joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            CHECKBOX.containsMatchIn(line) -> line
            HEADING.containsMatchIn(line) -> line
            else -> {
                val b = BULLET.find(line)
                val o = ORDERED.find(line)
                val (ws, label) = when {
                    b != null -> b.destructured.component1() to b.destructured.component2()
                    o != null -> o.destructured.component1() to o.destructured.component3()
                    else -> "" to line.trim()
                }
                if (label.isBlank()) line else "$ws- [ ] ${label.trim()}"
            }
        }
    }
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

/**
 * Fold near-duplicate tags onto one spelling so the corpus doesn't accumulate "Work"/"work"/"works".
 * [known] = tags already in use (corpus + the note's current tags); a spelling already in use wins,
 * otherwise the first incoming spelling. Matches on case, surrounding spaces/hyphens/underscores, and a
 * trailing plural "s" (meeting≈meetings) — deliberately conservative so distinct words never merge.
 * Returns [incoming] mapped to canonical spellings, de-duplicated, order preserved. Pure + testable.
 */
fun canonicalizeTags(incoming: List<String>, known: List<String>): List<String> {
    val canon = LinkedHashMap<String, String>()   // normalized key -> chosen spelling
    for (t in known) t.trim().takeIf { it.isNotEmpty() }?.let { canon.putIfAbsent(tagKey(it), it) }
    val out = ArrayList<String>()
    for (raw in incoming) {
        val t = raw.trim()
        if (t.isEmpty()) continue
        val chosen = canon.getOrPut(tagKey(t)) { t }
        if (chosen !in out) out.add(chosen)
    }
    return out
}

private fun tagKey(tag: String): String {
    val s = tag.lowercase().replace(Regex("[\\s_-]+"), "")
    return if (s.length > 3 && s.endsWith("s") && !s.endsWith("ss")) s.dropLast(1) else s
}
