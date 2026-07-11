package com.thoughtpocket

import com.thoughtpocket.ai.coder.CoderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fence extraction — the fragile first gate between model reply and runner. */
class CodeFenceTest {

    @Test fun cleanFence() =
        assertEquals("print(1)", CoderHarness.extractCodeFence("```python\nprint(1)\n```"))

    @Test fun fenceWithProseAround() =
        assertEquals(
            "x = 2\nprint(x)",
            CoderHarness.extractCodeFence("Here is the script:\n```python\nx = 2\nprint(x)\n```\nHope it helps!")
        )

    @Test fun bareFenceWithoutLanguage() =
        assertEquals("print(3)", CoderHarness.extractCodeFence("```\nprint(3)\n```"))

    @Test fun pyLanguageTag() =
        assertEquals("print(4)", CoderHarness.extractCodeFence("```py\nprint(4)\n```"))

    @Test fun firstOfMultipleFencesWins() =
        assertEquals("print('a')", CoderHarness.extractCodeFence("```python\nprint('a')\n```\ntext\n```python\nprint('b')\n```"))

    @Test fun thinkBlockStripped() =
        assertEquals(
            "print(5)",
            CoderHarness.extractCodeFence("<think>I should use a fence\n```python\nfake\n```\n</think>```python\nprint(5)\n```")
        )

    @Test fun unclosedThinkStripped() =
        assertNull(CoderHarness.extractCodeFence("<think>still reasoning ```python\nprint(6)\n```"))

    @Test fun noFenceIsNull() =
        assertNull(CoderHarness.extractCodeFence("print(1) — no fence here"))

    @Test fun emptyFenceIsNull() =
        assertNull(CoderHarness.extractCodeFence("```python\n\n```"))
}
