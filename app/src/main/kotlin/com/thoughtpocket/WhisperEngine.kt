package com.thoughtpocket

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin Kotlin wrapper over the whisper.cpp JNI bridge.
 *
 * Lifecycle: load() once, then transcribe() many times, then release() at process death.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"

    private val ctxPtr = AtomicLong(0L)
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedWithGpu: Boolean = false

    val isLoaded: Boolean get() = ctxPtr.get() != 0L
    /** "vulkan" if the binary was compiled with Vulkan support, else "cpu". */
    val compiledBackend: String get() = nativeBackendInfo()
    /** Reflects what actually ran the last load: "gpu" only if Vulkan compiled AND load(useGpu=true). */
    val activeBackend: String get() =
        if (compiledBackend == "vulkan" && loadedWithGpu) "gpu" else "cpu"
    val activeModelPath: String? get() = loadedPath
    /** Last native error message, "" if none. Populated by failed init/transcribe. */
    fun lastError(): String = nativeLastError()

    suspend fun load(modelFile: File, useGpu: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            return@withContext Result.failure(IllegalStateException("Model not found: ${modelFile.absolutePath}"))
        }
        // Already loaded with same parameters? No-op.
        if (isLoaded && loadedPath == modelFile.absolutePath && loadedWithGpu == useGpu) {
            return@withContext Result.success(Unit)
        }
        // Different model or backend toggle — release first.
        release()
        val ptr = nativeInitContext(modelFile.absolutePath, useGpu)
        if (ptr == 0L) {
            return@withContext Result.failure(IllegalStateException("whisper_init_from_file returned null"))
        }
        ctxPtr.set(ptr)
        loadedPath = modelFile.absolutePath
        loadedWithGpu = useGpu
        Log.i(TAG, "Loaded ${modelFile.name} (gpu=$useGpu, backend=${nativeBackendInfo()})")
        Result.success(Unit)
    }

    fun release() {
        val ptr = ctxPtr.getAndSet(0L)
        if (ptr != 0L) nativeFreeContext(ptr)
        loadedPath = null
    }

    private const val VAD_MODEL = "ggml-silero-v5.1.2.bin"

    /** Extract the bundled Silero VAD model to filesDir (once) and return its path; null on failure. */
    fun ensureVadModel(context: Context): String? = runCatching {
        val f = File(context.filesDir, VAD_MODEL)
        if (f.length() < 100_000L)
            context.assets.open(VAD_MODEL).use { input -> f.outputStream().use { input.copyTo(it) } }
        f.absolutePath
    }.onFailure { Log.w(TAG, "VAD model extract failed", it) }.getOrNull()

    /**
     * @param pcm16k mono float PCM at 16 kHz, range [-1.0, 1.0]
     * @param language ISO-639-1 like "en", "de"; null = auto-detect
     * @param translate true = translate non-English to English (English-only output)
     * @param threads CPU threads to use (ignored on GPU path)
     * @param highQuality switch sampling strategy from greedy → beam search (slower, more accurate)
     * @param onSegment fires per committed segment for streaming UI updates
     * @param onProgress fires periodically with 0..1 progress
     */
    suspend fun transcribe(
        pcm16k: FloatArray,
        language: String? = null,
        translate: Boolean = false,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        highQuality: Boolean = false,
        vadModelPath: String? = null,
        onSegment: ((String) -> Unit)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        val ptr = ctxPtr.get()
        check(ptr != 0L) { "WhisperEngine not loaded — call load() first" }
        val cb = if (onSegment != null || onProgress != null) {
            TranscribeCallback().apply {
                this.onSegment = onSegment
                this.onProgress = onProgress
            }
        } else null
        nativeTranscribe(ptr, pcm16k, language ?: "", translate, threads, highQuality, cb, vadModelPath ?: "")
    }

    /**
     * Like [transcribe], but streams the int16 PCM straight from [file] disk→native (samples in
     * [startSample, endSample); [endSample] -1 = to end of file). The final pass uses this so a long
     * recording never materializes as a Kotlin FloatArray plus a second JNI pin/copy.
     */
    suspend fun transcribeFile(
        file: File,
        startSample: Long = 0,
        endSample: Long = -1,
        language: String? = null,
        translate: Boolean = false,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        highQuality: Boolean = false,
        vadModelPath: String? = null,
        onSegment: ((String) -> Unit)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): String = withContext(Dispatchers.Default) {
        val ptr = ctxPtr.get()
        check(ptr != 0L) { "WhisperEngine not loaded — call load() first" }
        val cb = if (onSegment != null || onProgress != null) {
            TranscribeCallback().apply {
                this.onSegment = onSegment
                this.onProgress = onProgress
            }
        } else null
        nativeTranscribeFile(ptr, file.absolutePath, startSample, endSample, language ?: "", translate, threads, highQuality, cb, vadModelPath ?: "")
    }

    /**
     * Convenience: full load → transcribe → keep loaded. Used by [Benchmark] which
     * iterates many (model, gpu) combinations.
     */
    suspend fun runOnce(
        modelFile: File,
        useGpu: Boolean,
        pcm16k: FloatArray,
        language: String? = null,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    ): Result<String> = runCatching {
        load(modelFile, useGpu).getOrThrow()
        transcribe(pcm16k = pcm16k, language = language, translate = false, threads = threads)
    }

    // ---------- native ----------
    private external fun nativeInitContext(modelPath: String, useGpu: Boolean): Long
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeTranscribe(
        ctxPtr: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        nThreads: Int,
        useBeam: Boolean,
        callback: TranscribeCallback?,
        vadModelPath: String,
    ): String
    private external fun nativeTranscribeFile(
        ctxPtr: Long,
        pcmPath: String,
        startSample: Long,
        endSample: Long,
        language: String,
        translate: Boolean,
        nThreads: Int,
        useBeam: Boolean,
        callback: TranscribeCallback?,
        vadModelPath: String,
    ): String
    private external fun nativeBackendInfo(): String
    private external fun nativeLastError(): String
}

/**
 * Strip Whisper non-speech annotations so silence/music/noise never becomes note text: bracketed cues
 * ([BLANK_AUDIO], [MUSIC PLAYING], [INAUDIBLE]…), starred cues (*laughs*), and a whitelist of
 * parenthesized non-speech cues. Returns the remaining real speech, or "" when nothing's left (callers
 * map "" → "(no speech detected)"). Real speech almost never contains [...] so bracket-stripping is safe;
 * parentheses are only stripped for known non-speech words, to keep genuine asides like "call me (later)".
 */
fun stripNonSpeech(raw: String): String {
    val cleaned = raw
        .replace(Regex("\\[[^\\]]*]"), " ")
        .replace(Regex("\\*[^*]*\\*"), " ")
        .replace(
            Regex(
                "(?i)\\(\\s*(?:music|applause|laughter|laughs?|chuckles?|inaudible|silence|no audio|" +
                    "blank[_ ]?audio|background noise|noise|sighs?|coughs?|breathing|static|beep|wind|" +
                    "foreign language|speaking (?:in )?(?:a )?foreign language)\\s*\\)"
            ),
            " ",
        )
        .replace(Regex("\\s+"), " ")
        .trim()
    // Whatever survives is real only if it has a letter or digit — drop lone punctuation like "[…]."→".".
    return if (cleaned.any { it.isLetterOrDigit() }) cleaned else ""
}

/**
 * JNI calls these methods directly via reflection-free MethodIDs — keep public + don't rename.
 */
class TranscribeCallback {
    @JvmField var onSegment: ((String) -> Unit)? = null
    @JvmField var onProgress: ((Float) -> Unit)? = null

    @Suppress("unused")
    fun jniSegment(text: String) {
        onSegment?.invoke(text)
    }

    @Suppress("unused")
    fun jniProgress(pct: Int) {
        onProgress?.invoke(pct / 100f)
    }
}
