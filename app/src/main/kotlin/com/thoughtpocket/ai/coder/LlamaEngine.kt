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
 * flag checked every token and wired into ggml's abort callback, so it lands
 * even mid-prefill (one decode call that can run tens of seconds on CPU).
 *
 * NOTE [release] while a generation is in flight is a use-after-free — the
 * caller must cancel AND wait for the generate call to return first
 * (CoderRunService joins the run job before ending the session).
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
        temperature: Float = 0f,
        onToken: TokenCallback? = null,
    ): Result<String> = withContext(Dispatchers.Default) {
        val h = handle.get()
        if (h == 0L) return@withContext Result.failure(IllegalStateException("model not loaded"))
        val out = nativeGenerate(h, prompt, maxTokens, temperature, onToken)
            ?: return@withContext Result.failure(IllegalStateException(lastError()))
        Result.success(out)
    }

    /**
     * Wrap system+user in the model's own chat template (GGUF metadata) so BYO
     * models get their native format; null when the model has none → caller
     * falls back to ChatML.
     */
    fun formatPrompt(system: String, user: String): String? {
        val h = handle.get()
        return if (h == 0L) null else nativeFormatPrompt(h, system, user)
    }

    fun cancel() = nativeCancel()

    fun release() {
        val h = handle.getAndSet(0)
        loadedPath = null
        if (h != 0L) nativeFreeContext(h)
    }

    fun lastError(): String = nativeLastError()

    private external fun nativeInitContext(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, callback: TokenCallback?): String?
    private external fun nativeFormatPrompt(handle: Long, system: String, user: String): String?
    private external fun nativeCancel()
    private external fun nativeFreeContext(handle: Long)
    private external fun nativeLastError(): String
}
