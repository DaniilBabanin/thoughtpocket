package com.soundscript.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generic on-device LLM (Gemma) runner via LiteRT-LM — the runtime the .litertlm models
 * are built for (and what AI Edge Gallery uses), which has a working GPU path where
 * MediaPipe tasks-genai's OpenCL executor failed to delegate the graph.
 *
 * Loads from the app's external files dir (Android/data/<pkg>/files/llm) so models can be
 * sideloaded via the in-app picker or adb. Tries GPU first, falls back to CPU. Kept warm;
 * one inference at a time. Reused by tagging today and more analyses later.
 */
object LlmEngine {
    private const val TAG = "LlmEngine"
    private val mutex = Mutex()

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedPath: String? = null
    @Volatile var activeBackend: String = "?"
        private set

    fun llmDir(context: Context): File =
        (context.getExternalFilesDir("llm") ?: File(context.filesDir, "llm")).apply { mkdirs() }

    /** All installed model bundles (downloaded or imported). */
    fun installed(context: Context): List<File> =
        llmDir(context).listFiles()?.filter {
            it.isFile && it.length() > 10_000_000L &&
                (it.name.endsWith(".litertlm") || it.name.endsWith(".task"))
        }?.sortedBy { it.name } ?: emptyList()

    /**
     * Pick a model: [prefer] (filename substring, e.g. "E2B") wins if installed,
     * else the selected model (AppPreferences.llmModelFilename), else the first installed.
     */
    fun modelFile(context: Context, prefer: String? = null): File? {
        val files = installed(context)
        if (prefer != null) {
            files.firstOrNull { it.name.contains(prefer, ignoreCase = true) }?.let { return it }
        }
        val selected = com.soundscript.AppPreferences(context).llmModelFilename
        return files.firstOrNull { it.name == selected } ?: files.firstOrNull()
    }

    fun isModelInstalled(context: Context): Boolean = modelFile(context) != null

    /** Run the model on [prompt]; [prefer] picks a specific model (see [modelFile]). */
    suspend fun generate(context: Context, prompt: String, prefer: String? = null): Result<String> =
        withContext(Dispatchers.Default) {
            val model = modelFile(context, prefer)
                ?: return@withContext Result.failure(IllegalStateException("No model — import one in Settings."))
            mutex.withLock {
                runCatching {
                    val eng = ensureLoaded(context, model)
                    eng.createConversation().use { conv -> conv.sendMessage(prompt).plainText() }
                }
            }
        }

    private fun ensureLoaded(context: Context, model: File): Engine {
        engine?.let { if (loadedPath == model.absolutePath) return it }
        engine?.close(); engine = null

        val cacheDir = File(llmDir(context), "cache").apply { mkdirs() }.absolutePath
        fun make(backend: Backend, label: String): Engine {
            val eng = Engine(EngineConfig(modelPath = model.absolutePath, backend = backend, cacheDir = cacheDir))
            eng.initialize()
            activeBackend = label
            return eng
        }

        Log.i(TAG, "Loading LLM: ${model.name} (${model.length() / 1_000_000} MB) on GPU")
        val eng = try {
            make(Backend.GPU(), "gpu")
        } catch (t: Throwable) {
            Log.w(TAG, "GPU backend failed, falling back to CPU", t)
            make(Backend.CPU(), "cpu")
        }
        Log.i(TAG, "LLM ready on $activeBackend")
        return eng.also { engine = it; loadedPath = model.absolutePath }
    }

    fun release() {
        runCatching { engine?.close() }
        engine = null; loadedPath = null
    }

    /** Extract the plain text from a model reply (Content.Text parts; channels as fallback). */
    private fun Message.plainText(): String {
        val text = contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(" ") { it.text }
            .trim()
        return text.ifBlank { channels.values.joinToString(" ").trim() }
    }
}

/** Note tagging built on [LlmEngine]. More analyses (summaries, titles, Q&A) will follow. */
object TaggingEngine {
    // Tagging uses Gemma 4 E2B — fastest with excellent tag quality (see benchmark).
    private const val TAG_MODEL = "E2B"

    suspend fun suggestTags(context: Context, text: String): Result<List<String>> {
        if (text.isBlank()) return Result.success(emptyList())
        return LlmEngine.generate(context, buildPrompt(text), prefer = TAG_MODEL).map { parseTags(it) }
    }

    // Plain instruction — LiteRT-LM's Conversation applies the model's chat template itself.
    private fun buildPrompt(text: String): String =
        "You label voice notes with short topic tags. Read the note and reply with ONLY " +
            "3 to 5 concise lowercase tags (one or two words each), comma-separated. No explanation.\n\n" +
            "Note:\n\"\"\"\n${text.take(4000)}\n\"\"\""

    private fun parseTags(raw: String): List<String> {
        // Reasoning models emit <think>…</think>; drop it, keep the answer.
        val s = raw
            .replace(Regex("(?is)<think.*?</think>"), " ")
            .replace(Regex("(?is)</?think[^>]*>"), " ")
            .replace(Regex("<[^>]*>"), " ")
            .substringAfterLast("Tags:")
        return s.split(',', '\n', ';')
            .map { it.trim().trim('#', '-', '*', '.', '"', '`', ' ').lowercase() }
            .filter { it.isNotEmpty() && it.length in 2..30 && it.split(" ").size <= 3 && ':' !in it }
            .distinct()
            .take(5)
    }
}
