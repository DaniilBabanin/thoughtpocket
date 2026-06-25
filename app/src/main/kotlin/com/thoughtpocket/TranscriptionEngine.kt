package com.thoughtpocket

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A pluggable on-device transcription backend. Two impls today:
 *  - [WhisperTranscriber] — whisper.cpp batch encoder-decoder. The durable final pass; also usable as a
 *    (slow, encoder-floor-bound) windowed live preview.
 *  - [MoonshineTranscriber] — sherpa-onnx Moonshine. Streaming-class (no 30 s mel pad → short windows
 *    decode cheaply), so it backs the instant first pass.
 *
 * Each engine owns a single native context, so [transcribe] serializes its own calls through an internal
 * lock and loads the model lazily (idempotent per on-disk key). Two *different* engines (Moonshine preview
 * of the current recording + Whisper final of the previous one) run concurrently — they hold separate
 * native contexts and separate locks. Callers always get cleaned text ([stripNonSpeech] applied here).
 */
interface TranscriptionEngine {
    /**
     * Transcribe a 16 kHz mono float window. Ensures [model] is loaded first. [onSegment] streams committed
     * partials where the engine supports it (Whisper does; Moonshine returns the whole-window text once).
     */
    suspend fun transcribe(
        context: Context,
        model: ModelManager.ModelEntry,
        pcm16k: FloatArray,
        prefs: AppPreferences,
        highQuality: Boolean,
        useVad: Boolean = false,
        onSegment: ((String) -> Unit)? = null,
    ): String

    /** Free the native context (process teardown / model switch). */
    fun release()
}

/** Resolve the engine that backs a model. */
object Transcription {
    fun engineFor(model: ModelManager.ModelEntry): TranscriptionEngine = when (model.engine) {
        ModelManager.EngineKind.WHISPER -> WhisperTranscriber
        ModelManager.EngineKind.MOONSHINE -> MoonshineTranscriber
    }
}

/** Eligible as the instant first pass (windowed live preview). Both engines qualify (Whisper is just slow). */
val ModelManager.ModelEntry.firstPassEligible: Boolean get() = true

/** Eligible as the durable final (batch) pass. Whisper only — Moonshine is preview-grade streaming. */
val ModelManager.ModelEntry.finalPassEligible: Boolean
    get() = engine == ModelManager.EngineKind.WHISPER

// ---------------------------------------------------------------------------------------------------

/** whisper.cpp behind the [TranscriptionEngine] interface. Wraps the shared [WhisperEngine] object. */
object WhisperTranscriber : TranscriptionEngine {
    private val lock = Mutex()
    @Volatile private var loadedKey: String? = null

    override suspend fun transcribe(
        context: Context,
        model: ModelManager.ModelEntry,
        pcm16k: FloatArray,
        prefs: AppPreferences,
        highQuality: Boolean,
        useVad: Boolean,
        onSegment: ((String) -> Unit)?,
    ): String = lock.withLock {
        val file = ModelManager.fileFor(context, model)
        if (loadedKey != file.path) {
            // A present-but-unloadable model (corrupt/OOM) must NOT pass as "" — that would save a fake
            // "(no speech detected)" note and delete the audio. Throw so the clip is kept for retry.
            // Preview path forces CPU (Vulkan isn't compiled; GPU silently runs on CPU anyway).
            WhisperEngine.load(file, useGpu = false).getOrThrow()
            loadedKey = file.path
        }
        stripNonSpeech(
            WhisperEngine.transcribe(
                pcm16k = pcm16k,
                language = prefs.language.ifBlank { null },
                translate = prefs.translateToEnglish,
                threads = prefs.resolvedThreads(),
                highQuality = highQuality,
                // VAD only on final transcriptions (skip silent thinking-pauses); not on live preview.
                vadModelPath = if (useVad) WhisperEngine.ensureVadModel(context) else null,
                onSegment = onSegment,
            )
        )
    }

    override fun release() {
        WhisperEngine.release()
        loadedKey = null
    }
}

// ---------------------------------------------------------------------------------------------------

/**
 * sherpa-onnx Moonshine (offline recognizer) behind the [TranscriptionEngine] interface. Moonshine has no
 * 30 s mel pad, so a short trailing window decodes in ~hundreds of ms — that's what makes the live preview
 * feel instant where windowed-Whisper can't. English-only, CPU (ORT) provider; whole-window result per call.
 */
object MoonshineTranscriber : TranscriptionEngine {
    private const val TAG = "MoonshineTranscriber"
    private const val MAX_DECODE_SAMPLES = 16000 * 25   // ≤25 s per decode (Moonshine is short-segment trained)
    private val lock = Mutex()
    @Volatile private var loadedKey: String? = null
    @Volatile private var recognizer: OfflineRecognizer? = null

    override suspend fun transcribe(
        context: Context,
        model: ModelManager.ModelEntry,
        pcm16k: FloatArray,
        prefs: AppPreferences,
        highQuality: Boolean,   // ignored — Moonshine has a single decode path
        useVad: Boolean,        // ignored — sherpa offline recognizer has no external VAD here
        onSegment: ((String) -> Unit)?,
    ): String = lock.withLock {
        val dir = ModelManager.moonshineDir(context, model)
        ensureLoaded(dir, prefs)
        val rec = recognizer ?: throw IllegalStateException("Moonshine model failed to load at $dir")
        withContext(Dispatchers.Default) {
            // Live-preview windows are short → one decode. A first-only note over a long recording can be
            // minutes long; Moonshine is trained on short segments, so decode in consecutive ≤25 s chunks
            // and join. Clean per-window output makes naive concatenation acceptable (boundary word-splits
            // are rare); the durable note still comes from Whisper unless the user picks first-only.
            if (pcm16k.size <= MAX_DECODE_SAMPLES) decodeOne(rec, pcm16k)
            else buildString {
                var off = 0
                while (off < pcm16k.size) {
                    val end = minOf(off + MAX_DECODE_SAMPLES, pcm16k.size)
                    val part = decodeOne(rec, pcm16k.copyOfRange(off, end))
                    if (part.isNotBlank()) { if (isNotEmpty()) append(' '); append(part) }
                    off = end
                }
            }
        }
    }

    private fun decodeOne(rec: OfflineRecognizer, pcm: FloatArray): String {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(pcm, 16000)
            rec.decode(stream)
            stripNonSpeech(rec.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    private fun ensureLoaded(dir: File, prefs: AppPreferences) {
        if (loadedKey == dir.path && recognizer != null) return
        recognizer?.release()
        recognizer = null
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = File(dir, "preprocess.onnx").absolutePath,
                    encoder = File(dir, "encode.int8.onnx").absolutePath,
                    uncachedDecoder = File(dir, "uncached_decode.int8.onnx").absolutePath,
                    cachedDecoder = File(dir, "cached_decode.int8.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = prefs.resolvedThreads(),
                provider = "cpu",
            ),
        )
        recognizer = OfflineRecognizer(config = config)
        loadedKey = dir.path
        Log.i(TAG, "Loaded Moonshine from $dir (threads=${prefs.resolvedThreads()})")
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }
}
