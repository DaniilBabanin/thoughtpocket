package com.thoughtpocket

/**
 * SPIKE (Phase 1, coder feature): Kotlin side of the llama.cpp smoke probe.
 * Replaced by ai/coder/LlamaEngine in Phase 2.
 */
object LlamaProbe {
    external fun nativeProbe(modelPath: String, nPredict: Int): String
}
