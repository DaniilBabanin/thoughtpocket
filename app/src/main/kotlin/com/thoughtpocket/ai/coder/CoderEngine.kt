package com.thoughtpocket.ai.coder

import android.content.Context
import android.util.Log
import com.thoughtpocket.CoderModelManager
import com.thoughtpocket.ai.AiMutex
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.service.RecordState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-scoped owner of the coder model. A session spans one CodeRun screen
 * visit: begin → N generations (first run + follow-ups, model kept warm) → end.
 *
 * Memory contract (16 GB devices can't hold Gemma + a 5.6 GB GGUF):
 * [beginSession] takes [AiMutex] for the WHOLE session — background tagging/
 * analysis calls into LlmEngine block until [endSession] — then drops Gemma
 * before loading the coder model. Whisper/Moonshine are untouched (small,
 * and transcription must keep working during a coder run). Gemma lazily
 * re-loads on its next call after the session ends.
 */
object CoderEngine {
    private const val TAG = "CoderEngine"
    // 4 threads: Pixel 9 Pro XL has only 4 big cores — 6 threads spill onto
    // A520s and per-op sync drags decode (4.6 vs 3.8 tok/s, llama-bench
    // 2026-07-11). Tab S11 loses ~11% vs 6 threads; the Pixel gain outweighs it.
    private const val N_THREADS = 4
    private const val N_CTX = 4096

    private val active = AtomicBoolean(false)

    val inSession: Boolean get() = active.get()

    /** Refuses while recording — mic + whisper + 5.6 GB coder model is the one combination we don't support. */
    suspend fun beginSession(context: Context): Result<Unit> {
        if (RecordState.status.value.state == RecordState.State.RECORDING) {
            return Result.failure(IllegalStateException("Finish recording first"))
        }
        val model = CoderModelManager.selectedModel(context)
            ?: return Result.failure(IllegalStateException("No coder model installed"))
        if (!active.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("A coder session is already running"))
        }
        AiMutex.mutex.lock(owner = this)
        LlmEngine.release() // drop Gemma before the big load; stateless, cheap
        Log.i(TAG, "session start: ${model.name}")
        return LlamaEngine.load(model.absolutePath, N_CTX, N_THREADS).onFailure {
            AiMutex.mutex.unlock(owner = this)
            active.set(false)
        }
    }

    /** Only valid inside a session (the session already holds [AiMutex]). */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 1024,
        onToken: LlamaEngine.TokenCallback? = null,
    ): Result<String> {
        if (!active.get()) return Result.failure(IllegalStateException("No coder session"))
        return LlamaEngine.generate(prompt, maxTokens, onToken)
    }

    fun cancelGeneration() = LlamaEngine.cancel()

    /** Idempotent. Frees the model and lets Gemma-based features resume. */
    fun endSession() {
        if (!active.compareAndSet(true, false)) return
        LlamaEngine.release()
        AiMutex.mutex.unlock(owner = this)
        Log.i(TAG, "session end")
    }
}
