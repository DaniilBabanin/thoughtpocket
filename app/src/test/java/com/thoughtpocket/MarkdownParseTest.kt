package com.thoughtpocket

import com.thoughtpocket.ai.MdLine
import com.thoughtpocket.ai.parseMarkdown
import com.thoughtpocket.ai.toggleCheckbox
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParseTest {

    @Test
    fun classifiesEveryLineKind() {
        val md = "# Title\n## Sub\n- [ ] open task\n  - [X] done sub\n1. first\n- bullet\n* star bullet\n\nplain text"
        assertEquals(
            listOf(
                MdLine.Heading(1, "Title"),
                MdLine.Heading(2, "Sub"),
                MdLine.Checkbox("", checked = false, label = "open task"),
                MdLine.Checkbox("  ", checked = true, label = "done sub"),
                MdLine.Ordered("", "1", "first"),
                MdLine.Bullet("", "bullet"),
                MdLine.Bullet("", "star bullet"),
                MdLine.Blank,
                MdLine.Plain("plain text"),
            ),
            parseMarkdown(md),
        )
    }

    @Test
    fun emptyStringIsSingleBlank() {
        assertEquals(listOf(MdLine.Blank), parseMarkdown(""))
    }

    @Test
    fun checkboxWinsOverBulletAndKeepsIndent() {
        // "- [ ] x" also matches BULLET; the checkbox regex must win, as in the old inline cascade.
        assertEquals(listOf(MdLine.Checkbox("    ", checked = false, label = "deep")), parseMarkdown("    - [ ] deep"))
    }

    @Test
    fun indexMatchesLineNumberForToggle() {
        // One entry per line means a checkbox's list index feeds toggleCheckbox directly.
        val md = "intro\n- [ ] task"
        assertEquals(MdLine.Checkbox("", checked = false, label = "task"), parseMarkdown(md)[1])
        assertEquals("intro\n- [x] task", toggleCheckbox(md, 1, true))
    }
}
