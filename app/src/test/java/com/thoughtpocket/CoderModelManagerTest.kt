package com.thoughtpocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure halves of [CoderModelManager]: the GGUF magic sniff that gates BYO
 * imports and the filename sanitizer. Download/copy paths need a device.
 */
class CoderModelManagerTest {

    // ---- GGUF magic ----

    @Test fun realMagicAccepted() =
        assertTrue(CoderModelManager.isGgufMagic(byteArrayOf(0x47, 0x47, 0x55, 0x46)))

    @Test fun textFileRejected() =
        assertFalse(CoderModelManager.isGgufMagic("<!DO".toByteArray()))

    @Test fun ggmlBinRejected() =
        // whisper ggml .bin magic is "lmgg" (0x67676d6c little-endian) — must not pass as GGUF
        assertFalse(CoderModelManager.isGgufMagic(byteArrayOf(0x6c, 0x6d, 0x67, 0x67)))

    @Test fun shortReadRejected() =
        assertFalse(CoderModelManager.isGgufMagic(byteArrayOf(0x47, 0x47)))

    // ---- filename sanitizer ----

    @Test fun plainNameKept() =
        assertEquals("qwen2.5-coder.gguf", CoderModelManager.sanitizeFilename("qwen2.5-coder.gguf"))

    @Test fun pathComponentsStripped() =
        assertEquals("model.gguf", CoderModelManager.sanitizeFilename("../../evil/model.gguf"))

    @Test fun oddCharsReplaced() =
        assertEquals("my_model__1_.gguf", CoderModelManager.sanitizeFilename("my model (1).gguf"))

    @Test fun missingExtensionAppended() =
        assertEquals("model.gguf", CoderModelManager.sanitizeFilename("model"))
}
