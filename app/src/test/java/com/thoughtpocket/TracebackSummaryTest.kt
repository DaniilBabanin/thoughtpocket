package com.thoughtpocket

import com.thoughtpocket.ai.coder.CoderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Traceback → repair-prompt summary: last user-code frame + exception line. */
class TracebackSummaryTest {

    private val multiFrame = """
        Traceback (most recent call last):
          File "/data/data/com.thoughtpocket/files/chaquopy/AssetFinder/app/runner.py", line 23, in run
          File "<coder>", line 7, in <module>
          File "<coder>", line 4, in average
        ZeroDivisionError: division by zero
    """.trimIndent()

    @Test fun lastUserFrameAndException() =
        assertEquals(
            "File \"<coder>\", line 4, in average\nZeroDivisionError: division by zero",
            CoderHarness.summarizeTraceback(multiFrame)
        )

    @Test fun exceptionLineWithoutFrames() =
        assertEquals(
            "MemoryError",
            CoderHarness.summarizeTraceback("Traceback (most recent call last):\nMemoryError")
        )

    @Test fun emptyInputEmptyOutput() =
        assertEquals("", CoderHarness.summarizeTraceback("   "))

    @Test fun pathologicallyLongTracebackCapped() {
        val huge = multiFrame + "\nValueError: " + "x".repeat(5000)
        assertTrue(CoderHarness.summarizeTraceback(huge).length <= 600)
    }
}
