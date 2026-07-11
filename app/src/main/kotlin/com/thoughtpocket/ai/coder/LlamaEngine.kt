package com.thoughtpocket.ai.coder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * llama.cpp GGUF runtime for the coder feature. Lifecycle mirrors
 * [com.thoughtpocket.WhisperEngine]: load() once, generate() many times,
 * release() when the coder session ends. Serialization against the LiteRT
 * engines happens one layer up (CoderEngine + AiMutex) — this object only
 * guards its own handle.
 *
 * Cancellation is new relative to the whisper bridge: [cancel] flips a native
 * flag checked every token, so a minutes-long generation returns promptly.
 */
object LlamaEngine {

    /** JNI calls this via a cached MethodID — keep the name/signature. */
    fun interface TokenCallback { fun onToken(piece: String) }

    private val handle = AtomicLong(0)
    @Volatile private var loadedPath: String? = null

    val isLoaded: Boolean get() = handle.get() != 0L

    /** Idempotent for the same path; swaps (release+init) for a different one. */
    suspend fun load(path: String, nCtx: Int = 4096, nThreads: Int = 4): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (handle.get() != 0L && loadedPath == path) return@withContext Result.success(Unit)
            release()
            val h = nativeInitContext(path, nCtx, nThreads)
            if (h == 0L) return@withContext Result.failure(IllegalStateException(lastError()))
            handle.set(h)
            loadedPath = path
            Result.success(Unit)
        }

    /**
     * Greedy-decodes up to [maxTokens] from a full standalone prompt (callers
     * thread their own history). Returns the complete text; [onToken] streams
     * pieces for live UI. Cancel via [cancel] → returns what was decoded so far.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 1024,
        onToken: TokenCallback? = null,
    ): Result<String> = withContext(Dispatchers.Default) {
        val h = handle.get()
        if (h == 0L) return@withContext Result.failure(IllegalStateException("model not loaded"))
        val out = nativeGenerate(h, prompt, maxTokens, onToken)
            ?: return@withContext Result.failure(IllegalStateException(lastError()))
        Result.success(out)
    }

    fun cancel() = nativeCancel()

    fun release() {
        val h = handle.getAndSet(0)
        loadedPath = null
        if (h != 0L) nativeFreeContext(h)
    }

    fun lastError(): String = nativeLastError()

    private external fun nativeInitContext(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback?): String?
    private external fun nativeCancel()
    private external fun nativeFreeContext(handle: Long)
    private external fun nativeLastError(): String
}
