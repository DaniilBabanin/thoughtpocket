package com.thoughtpocket

import com.thoughtpocket.ai.InteractEngine
import com.thoughtpocket.ai.InteractOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LLM call isn't testable on the JVM, but [InteractEngine.parse] — the fragile half of the
 * hybrid intent pattern (LLM reply → structured [InteractOp]) — is pure. A mis-parse here routes
 * to the wrong op and mutates the note.
 */
class InteractEngineTest {

    // ---- JSON extraction ----

    @Test fun parsesCleanJson() {
        val op = InteractEngine.parse("""{"action":"check","item":"milk"}""")
        assertEquals(InteractOp.Check("milk", true), op)
    }

    @Test fun extractsJsonFromSurroundingProse() {
        val op = InteractEngine.parse(
            """Sure! Here you go: {"action":"add","item":"olive oil","position":"top"} — hope that helps."""
        )
        assertEquals(InteractOp.Add("olive oil", top = true), op)
    }

    @Test fun stripsThinkBlockAndIgnoresItsBraces() {
        // Reasoning models emit <think>…</think>; braces inside it must not corrupt extraction.
        val raw = "<think>maybe {\"action\":\"remove\"} instead?</think>" +
            """{"action":"uncheck","item":"eggs"}"""
        assertEquals(InteractOp.Check("eggs", false), InteractEngine.parse(raw))
    }

    // ---- action-synonym map ----

    @Test fun removeAndDeleteBothMapToRemove() {
        assertEquals(InteractOp.Remove("bread"), InteractEngine.parse("""{"action":"remove","item":"bread"}"""))
        assertEquals(InteractOp.Remove("bread"), InteractEngine.parse("""{"action":"delete","item":"bread"}"""))
    }

    @Test fun actionIsCaseInsensitive() {
        assertEquals(InteractOp.Remove("bread"), InteractEngine.parse("""{"action":"Delete","item":"bread"}"""))
    }

    @Test fun titleSynonymsMapToSetTitle() {
        for (a in listOf("title", "heading", "rename"))
            assertEquals(a, InteractOp.SetTitle("Groceries"), InteractEngine.parse("""{"action":"$a","item":"Groceries"}"""))
    }

    @Test fun titleFallsBackToTitleKeyWhenItemBlank() {
        assertEquals(InteractOp.SetTitle("Groceries"), InteractEngine.parse("""{"action":"title","title":"Groceries"}"""))
    }

    @Test fun convertSynonymsMapToConvert() {
        for (a in listOf("convert", "checklist", "checkboxes", "checkbox"))
            assertEquals(a, InteractOp.Convert, InteractEngine.parse("""{"action":"$a"}"""))
    }

    @Test fun rewriteSynonymsMapToRewrite() {
        for (a in listOf("rewrite", "reword", "rephrase", "shorten", "summarize", "summarise", "expand", "lengthen", "formal"))
            assertEquals(a, InteractOp.Rewrite, InteractEngine.parse("""{"action":"$a"}"""))
    }

    @Test fun addDefaultsToBottom() {
        val op = InteractEngine.parse("""{"action":"add","item":"butter"}""") as InteractOp.Add
        assertFalse(op.top)
    }

    // ---- malformed input → Unknown, never a crash ----

    @Test fun noJsonIsUnknownWithRawPreserved() {
        val op = InteractEngine.parse("  Sorry, I can't help with that.  ")
        assertEquals(InteractOp.Unknown("Sorry, I can't help with that."), op)
    }

    @Test fun unbalancedBracesAreUnknown() {
        assertTrue(InteractEngine.parse("""result: {"action":"check", "item":"mi""") is InteractOp.Unknown)
    }

    @Test fun invalidJsonInsideBracesIsUnknown() {
        assertTrue(InteractEngine.parse("""{"action" "check"}""") is InteractOp.Unknown)
    }

    @Test fun unrecognizedActionIsUnknown() {
        assertTrue(InteractEngine.parse("""{"action":"dance","item":"milk"}""") is InteractOp.Unknown)
    }
}
