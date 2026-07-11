package com.thoughtpocket.ai

import kotlinx.coroutines.sync.Mutex

/**
 * The one lock serializing on-device inference app-wide. LlmEngine takes it
 * per call; a coder session (CoderEngine) holds it for the whole session so
 * Gemma and the multi-GB coder model are never resident together — 16 GB
 * devices cannot hold both.
 */
internal object AiMutex {
    val mutex = Mutex()
}
