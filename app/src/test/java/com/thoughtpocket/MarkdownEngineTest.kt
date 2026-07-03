package com.thoughtpocket

import com.thoughtpocket.ai.MarkdownEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** The LLM call isn't testable on the JVM, but the reply-cleanup that runs on its output is pure. */
class MarkdownEngineTest {
    @Test fun stripsThinkBlock() {
        assertEquals("- a", MarkdownEngine.clean("<think>plan the layout</think>- a"))
    }

    @Test fun stripsUnclosedThinkTag() {
        assertEquals("- a", MarkdownEngine.clean("<think>\n- a"))
    }

    @Test fun unwrapsMarkdownFence() {
        assertEquals("- a\n- b", MarkdownEngine.clean("```markdown\n- a\n- b\n```"))
    }

    @Test fun unwrapsBareFence() {
        assertEquals("- a", MarkdownEngine.clean("```\n- a\n```"))
    }

    @Test fun dropsPreamble() {
        assertEquals("- a\n- b", MarkdownEngine.clean("Here is the markdown:\n- a\n- b"))
    }

    @Test fun keepsNormalProseUntouched() {
        val s = "Met Aoife about the roadmap. Two things: ship the spike, then measure."
        assertEquals(s, MarkdownEngine.clean(s))
    }

    @Test fun keepsColonLineThatIsntAPreamble() {
        // A real first line that ends in ":" but isn't model scaffolding must survive.
        val s = "Shopping list:\n- milk\n- eggs"
        assertEquals(s, MarkdownEngine.clean(s))
    }
}
