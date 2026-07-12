package com.thoughtpocket

import com.thoughtpocket.ai.coder.CoderHarness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt builders: repair branches on whether a previous script exists. */
class CoderPromptTest {

    @Test fun repairWithCodeEmbedsIt() {
        val u = CoderHarness.repairUser("T", "body", "task", "print(x)", "NameError: x")
        assertTrue(u.contains("```python\nprint(x)\n```"))
        assertTrue(u.contains("NameError: x"))
    }

    @Test fun repairAfterGateFailureHasNoEmptyCodeBlock() {
        // Fence-gate failures carry no code — an empty ```python block would
        // only mislead the model.
        val u = CoderHarness.repairUser("T", "body", "task", "", "reply contained no code block")
        assertFalse(u.contains("```python"))
        assertTrue(u.contains("reply contained no code block"))
    }

    @Test fun chatmlWrapsSystemAndUser() {
        val p = CoderHarness.chatml("hello")
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.endsWith("<|im_start|>assistant\n"))
        assertTrue(p.contains("<|im_start|>user\nhello<|im_end|>"))
    }
}
