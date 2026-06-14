package com.soundscript

import com.soundscript.ai.addItem
import com.soundscript.ai.checklistItems
import com.soundscript.ai.openTasks
import com.soundscript.ai.removeItem
import com.soundscript.ai.setItemChecked
import com.soundscript.ai.toggleCheckbox
import com.soundscript.data.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownToggleTest {
    private val md = "- [ ] call the dentist\n- [x] booked the flights\nplain paragraph"

    @Test
    fun checksOnlyTargetLine() {
        assertEquals(
            "- [x] call the dentist\n- [x] booked the flights\nplain paragraph",
            toggleCheckbox(md, 0, true),
        )
    }

    @Test
    fun unchecksOnlyTargetLine() {
        assertEquals(
            "- [ ] call the dentist\n- [ ] booked the flights\nplain paragraph",
            toggleCheckbox(md, 1, false),
        )
    }

    @Test
    fun preservesIndent() {
        assertEquals("  - [x] sub task", toggleCheckbox("  - [ ] sub task", 0, true))
    }

    @Test
    fun nonCheckboxLineUnchanged() {
        assertEquals(md, toggleCheckbox(md, 2, true))
    }

    // ---- interact ops ----
    private val list = "Grocery run\n- [ ] milk\n- [ ] eggs\n- [x] coffee"

    @Test
    fun setItemCheckedMatchesByLabel() {
        assertEquals("Grocery run\n- [x] milk\n- [ ] eggs\n- [x] coffee", setItemChecked(list, "milk", true))
    }

    @Test
    fun setItemCheckedFuzzyMatch() {
        // command extracted "the milk" — still matches the "milk" item
        assertEquals("Grocery run\n- [x] milk\n- [ ] eggs\n- [x] coffee", setItemChecked(list, "the milk", true))
    }

    @Test
    fun addItemBottomByDefault() {
        assertEquals("$list\n- [ ] bread", addItem(list, "bread", top = false))
    }

    @Test
    fun addItemTopGoesAfterHeading() {
        assertEquals("Grocery run\n- [ ] bread\n- [ ] milk\n- [ ] eggs\n- [x] coffee", addItem(list, "bread", top = true))
    }

    @Test
    fun addItemToEmptyAppends() {
        assertEquals("- [ ] bread", addItem("", "bread", top = false))
    }

    @Test
    fun removeItemDropsMatch() {
        assertEquals("Grocery run\n- [ ] eggs\n- [x] coffee", removeItem(list, "milk"))
    }

    @Test
    fun unknownItemUnchanged() {
        assertEquals(list, setItemChecked(list, "pineapple", true))
        assertEquals(list, removeItem(list, "pineapple"))
    }

    // ---- checklist aggregation (structured query path) ----
    @Test
    fun checklistItemsSplitsByState() {
        val notes = listOf(
            Note(id = 1, createdAt = 0, text = "", markdown = "Run\n- [x] milk\n- [ ] eggs"),
            Note(id = 2, createdAt = 0, text = "", markdown = "Run\n- [x] coffee\n- [ ] bread"),
        )
        assertEquals(listOf("milk", "coffee"), checklistItems(notes, checked = true).map { it.second })
        assertEquals(listOf("eggs", "bread"), checklistItems(notes, checked = false).map { it.second })
    }

    // ---- open tasks (action-items screen source) ----
    @Test
    fun openTasksReturnsOnlyUncheckedAcrossNotesInOrder() {
        val notes = listOf(
            Note(id = 1, createdAt = 0, text = "", title = "A", markdown = "A\n- [ ] one\n- [x] done\n- [ ] two"),
            Note(id = 2, createdAt = 0, text = "", title = "B", markdown = "B\n- [ ] three"),
        )
        val tasks = openTasks(notes)
        assertEquals(listOf("one", "two", "three"), tasks.map { it.second })
        assertEquals(listOf(1L, 1L, 2L), tasks.map { it.first.id })   // carries the source note
    }

    @Test
    fun openTasksUppercaseXCountsAsDone() {
        val notes = listOf(Note(id = 1, createdAt = 0, text = "", markdown = "- [X] bought\n- [ ] todo"))
        assertEquals(listOf("todo"), openTasks(notes).map { it.second })
    }

    @Test
    fun openTasksHandlesIndentedItems() {
        val notes = listOf(Note(id = 1, createdAt = 0, text = "", markdown = "Parent\n  - [ ] sub task"))
        assertEquals(listOf("sub task"), openTasks(notes).map { it.second })
    }

    @Test
    fun openTasksIgnoresPlainBulletsAndProse() {
        // Bullets without checkboxes and plain paragraphs are not tasks; notes with no markdown contribute nothing.
        val notes = listOf(
            Note(id = 1, createdAt = 0, text = "raw only", markdown = ""),
            Note(id = 2, createdAt = 0, text = "", markdown = "Notes\n- a bullet\njust prose"),
        )
        assertEquals(emptyList<String>(), openTasks(notes).map { it.second })
    }
}
