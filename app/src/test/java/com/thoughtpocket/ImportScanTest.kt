package com.thoughtpocket

import com.thoughtpocket.ai.coder.CoderHarness
import com.thoughtpocket.ai.coder.CoderHarness.ImportScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static import gate. NOT a sandbox — attribute traversal (random._os) is a
 * documented residual risk; the killable zero-permission-of-consequence
 * process + timeout is the real wall. This gate exists for fast, promptable
 * failures.
 */
class ImportScanTest {

    private fun blocked(code: String): String? =
        (CoderHarness.scanImports(code) as? ImportScan.Blocked)?.what

    @Test fun stdlibMathAllowed() =
        assertTrue(CoderHarness.scanImports("import math\nprint(math.pi)") is ImportScan.Allowed)

    @Test fun fromCollectionsAllowed() =
        assertTrue(CoderHarness.scanImports("from collections import Counter") is ImportScan.Allowed)

    @Test fun dottedRootChecked() =
        assertTrue(CoderHarness.scanImports("import collections.abc") is ImportScan.Allowed)

    @Test fun osBlocked() = assertEquals("import os", blocked("import os"))

    @Test fun subprocessBlocked() = assertEquals("import subprocess", blocked("import subprocess"))

    @Test fun socketViaFromBlocked() = assertEquals("import socket", blocked("from socket import socket"))

    @Test fun multiImportCatchesSecond() = assertEquals("import sys", blocked("import math, sys"))

    @Test fun aliasedImportBlocked() = assertEquals("import os", blocked("import os as o"))

    @Test fun openCallBlocked() = assertEquals("open()", blocked("data = open('/etc/passwd').read()"))

    @Test fun dunderImportBlocked() = assertEquals("__import__", blocked("m = __import__('os')"))

    @Test fun indentedImportStillCaught() =
        assertEquals("import os", blocked("def f():\n    import os\n    return 1"))

    @Test fun commentAfterImportIgnored() =
        assertTrue(CoderHarness.scanImports("import math  # for sqrt") is ImportScan.Allowed)
}
