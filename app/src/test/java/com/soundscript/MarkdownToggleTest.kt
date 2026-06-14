package com.soundscript

import com.soundscript.ui.toggleCheckbox
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownToggleTest {
    private val md = "- [ ] call the dentist\n- [x] booked the flights\nplain paragraph"

    @Test
    fun checksOnlyTargetLine() {
        val out = toggleCheckbox(md, 0, true)
        assertEquals("- [x] call the dentist\n- [x] booked the flights\nplain paragraph", out)
    }

    @Test
    fun unchecksOnlyTargetLine() {
        val out = toggleCheckbox(md, 1, false)
        assertEquals("- [ ] call the dentist\n- [ ] booked the flights\nplain paragraph", out)
    }

    @Test
    fun preservesIndent() {
        assertEquals("  - [x] sub task", toggleCheckbox("  - [ ] sub task", 0, true))
    }

    @Test
    fun nonCheckboxLineUnchanged() {
        assertEquals(md, toggleCheckbox(md, 2, true))
    }
}
