package com.thoughtpocket

import com.thoughtpocket.ai.coder.CoderHarness
import com.thoughtpocket.ai.coder.CoderHarness.Action
import com.thoughtpocket.ai.coder.CoderHarness.Attempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loop's brain: attempts cap, stuck-error dedupe, pre-flight wall budget. */
class HarnessDecideTest {

    private fun fail(stderr: String) = Attempt(code = "x", stderr = stderr, ok = false)

    @Test fun freshRunGenerates() =
        assertTrue(CoderHarness.decide(emptyList(), elapsedMs = 0) is Action.Generate)

    @Test fun successIsDone() {
        val a = CoderHarness.decide(listOf(Attempt(code = "x", stdout = "42\n", ok = true)), 60_000)
        assertEquals("42\n", (a as Action.Done).output)
    }

    @Test fun successOnThirdAttemptStillDone() {
        val attempts = listOf(fail("A"), fail("B"), Attempt(code = "x", stdout = "ok", ok = true))
        assertTrue(CoderHarness.decide(attempts, 60_000) is Action.Done)
    }

    @Test fun failureRetries() =
        assertTrue(CoderHarness.decide(listOf(fail("ValueError: nope")), 60_000) is Action.Generate)

    @Test fun maxAttemptsAborts() {
        val attempts = listOf(fail("A"), fail("B"), fail("C"))
        assertTrue(CoderHarness.decide(attempts, 60_000) is Action.Fail)
    }

    @Test fun identicalConsecutiveErrorsAbortEarly() {
        val same = "File \"<coder>\", line 2, in <module>\nKeyError: 'total'"
        val a = CoderHarness.decide(listOf(fail(same), fail(same)), 60_000)
        assertTrue("expected early abort, got $a", a is Action.Fail)
    }

    @Test fun differentErrorsKeepGoing() {
        val a = CoderHarness.decide(listOf(fail("KeyError: 'a'"), fail("ValueError: b")), 60_000)
        assertTrue(a is Action.Generate)
    }

    @Test fun gateErrorsCountTowardDedupe() {
        val g = Attempt(code = "", gateError = "import os")
        assertTrue(CoderHarness.decide(listOf(g, g), 60_000) is Action.Fail)
    }

    @Test fun wallBudgetCheckedBeforeGenerating() {
        // 17 min elapsed + 5 min estimate > 20 min cap → refuse to start another round.
        val a = CoderHarness.decide(listOf(fail("A")), elapsedMs = 17 * 60_000L)
        assertTrue(a is Action.Fail)
    }

    @Test fun budgetFitsOneMoreRound() {
        val a = CoderHarness.decide(listOf(fail("A")), elapsedMs = 10 * 60_000L)
        assertTrue(a is Action.Generate)
    }

    @Test fun successBeatsExhaustedBudget() {
        val attempts = listOf(Attempt(code = "x", stdout = "done", ok = true))
        assertTrue(CoderHarness.decide(attempts, elapsedMs = 25 * 60_000L) is Action.Done)
    }
}
