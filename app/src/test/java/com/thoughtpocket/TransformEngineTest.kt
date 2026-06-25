package com.thoughtpocket

import com.thoughtpocket.ai.cleanLlmReply
import org.junit.Assert.assertEquals
import org.junit.Test

/** The LLM call isn't testable on the JVM, but the reply-cleanup that runs on its output is pure. */
class TransformEngineTest {
    @Test fun stripsThinkBlock() {
        assertEquals("Hello world", cleanLlmReply("<think>plan the rewrite</think>Hello world"))
    }

    @Test fun unwrapsMarkdownFence() {
        assertEquals("- a\n- b", cleanLlmReply("```markdown\n- a\n- b\n```"))
    }

    @Test fun dropsPreamble() {
        assertEquals("- a\n- b", cleanLlmReply("Here is the rewritten note:\n- a\n- b"))
    }

    @Test fun keepsNormalProseUntouched() {
        val s = "Met Aoife about the roadmap. Two things: ship the spike, then measure."
        assertEquals(s, cleanLlmReply(s))
    }

    @Test fun keepsColonLineThatIsntAPreamble() {
        // A real first line that ends in ":" but isn't model scaffolding must survive.
        val s = "Shopping list:\n- milk\n- eggs"
        assertEquals(s, cleanLlmReply(s))
    }
}
