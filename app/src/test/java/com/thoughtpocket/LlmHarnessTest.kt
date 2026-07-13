package com.thoughtpocket

import com.thoughtpocket.ai.LlmHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the fit-or-split harness: packing must respect the per-call budget,
 * preserve order and drop nothing (the map-reduce driver itself needs the LLM, covered
 * on-device).
 */
class LlmHarnessTest {
    @Test fun everythingFittingIsOneBatch() {
        val lines = listOf("aaa", "bbb", "ccc")
        assertEquals(listOf(lines), LlmHarness.pack(lines, 100))
    }

    @Test fun packRespectsBudgetKeepsOrderDropsNothing() {
        val lines = (1..20).map { "line-$it-" + "x".repeat(40) }
        val batches = LlmHarness.pack(lines, 100)
        assertTrue(batches.size > 1)
        for (b in batches) assertTrue(b.joinToString("\n\n").length <= 100)
        assertEquals(lines, batches.flatten())
    }

    @Test fun oversizedLineIsSplitNotDropped() {
        val big = "y".repeat(250)
        val batches = LlmHarness.pack(listOf(big), 100)
        assertTrue(batches.flatten().all { it.length <= 100 })
        assertEquals(big, batches.flatten().joinToString(""))
    }

    @Test fun exactBoundaryPacksTogether() {
        // 100 + 2 ("\n\n") + 100 = 202 → fits a 202 budget, overflows a 201 one.
        val two = listOf("a".repeat(100), "b".repeat(100))
        assertEquals(1, LlmHarness.pack(two, 202).size)
        assertEquals(2, LlmHarness.pack(two, 201).size)
    }
}
