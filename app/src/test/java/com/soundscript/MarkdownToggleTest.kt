package com.soundscript

import com.soundscript.ai.addItem
import com.soundscript.ai.removeItem
import com.soundscript.ai.setItemChecked
import com.soundscript.ai.toggleCheckbox
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
}
