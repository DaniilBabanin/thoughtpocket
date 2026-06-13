package com.soundscript.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.soundscript.AppPreferences
import com.soundscript.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /** Default model = first installed (used when a task has no explicit pick). */
    fun modelFile(context: Context): File? = installed(context).firstOrNull()

    /**
     * Resolve a task's model: the [explicitFilename] the user picked if installed,
     * else a smart default (filename contains [fallbackContains], e.g. "E2B"/"4b"),
     * else any installed model.
     */
    fun resolve(context: Context, explicitFilename: String, fallbackContains: String): File? {
        val files = installed(context)
        files.firstOrNull { it.name == explicitFilename }?.let { return it }
        files.firstOrNull { it.name.contains(fallbackContains, ignoreCase = true) }?.let { return it }
        return files.firstOrNull()
    }

    /** Friendly label for a model filename, e.g. "Gemma 4 E2B". */
    fun prettyName(filename: String): String {
        val n = filename.lowercase()
        return when {
            "e2b" in n -> "Gemma 4 E2B"
            "e4b" in n || "4b" in n -> "Gemma 4 E4B"
            "1b" in n -> "Gemma 3 1B"
            else -> filename.removeSuffix(".litertlm").removeSuffix(".task")
        }
    }

    fun isModelInstalled(context: Context): Boolean = modelFile(context) != null

    /** Run the model on [prompt]; [model] picks a specific bundle (else the default). */
    suspend fun generate(context: Context, prompt: String, model: File? = null): Result<String> =
        withContext(Dispatchers.Default) {
            val model = model ?: modelFile(context)
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
    // Default tagging model = Gemma 4 E2B (fast, excellent tags; see benchmark). User-overridable.
    suspend fun suggestTags(context: Context, text: String): Result<List<String>> {
        if (text.isBlank()) return Result.success(emptyList())
        val model = LlmEngine.resolve(context, AppPreferences(context).tagModelFilename, "E2B")
        return LlmEngine.generate(context, buildPrompt(text), model).map { parseTags(it) }
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

/** One-line note title via [LlmEngine]. Uses the fast tag model (Gemma 4 E2B). */
object TitleEngine {
    suspend fun suggest(context: Context, text: String): Result<String> {
        if (text.isBlank()) return Result.success("")
        val model = LlmEngine.resolve(context, AppPreferences(context).tagModelFilename, "E2B")
        return LlmEngine.generate(context, buildPrompt(text), model).map { clean(it) }
    }

    private fun buildPrompt(text: String): String =
        "Write a short title for this voice note: 3 to 6 words, no quotes, no trailing " +
            "punctuation. Reply with ONLY the title.\n\nNote:\n\"\"\"\n${text.take(2000)}\n\"\"\""

    private fun clean(raw: String): String =
        raw.replace(Regex("(?is)<think.*?</think>"), " ")
            .replace(Regex("<[^>]*>"), " ")
            .substringAfterLast("Title:")
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            .trim('"', '\'', '.', '#', '*', '`', ' ')
            .take(80)
}

/**
 * Ask / analyse a set of notes (a scope: all, a tag, a timeframe, later a cluster) with a
 * free-form or preset question. Uses Gemma 4 E4B for deeper reasoning.
 */
object NotesAnalysis {
    // Default analysis model = Gemma 4 E4B (deeper reasoning). User-overridable.
    private const val MAX_CHARS = 16000   // context budget for the notes block
    private const val MAX_NOTES = 50      // keep retrieval focused even when more would fit
    private val df = SimpleDateFormat("MMM d", Locale.getDefault())

    suspend fun ask(context: Context, notes: List<Note>, question: String): Result<String> {
        if (notes.isEmpty()) return Result.success("No notes in this scope.")
        if (question.isBlank()) return Result.success("")
        val selected = select(context, notes, question)
        val joined = selected.joinToString("\n\n") { "- (${df.format(Date(it.createdAt))}) ${it.text}" }
        val coverage = if (selected.size < notes.size)
            "\n\n(These are the ${selected.size} most relevant of ${notes.size} notes in scope.)" else ""
        val prompt = "You are analysing the user's personal voice notes. ${question.trim()}\n\n" +
            "Notes:\n\"\"\"\n$joined\n\"\"\"$coverage"
        val model = LlmEngine.resolve(context, AppPreferences(context).analysisModelFilename, "4b")
        return LlmEngine.generate(context, prompt, model).map { strip(it) }
    }

    /**
     * Pick which notes go into the prompt. If the whole scope fits the budget, use it all;
     * otherwise retrieve (RAG) the notes most relevant to [question] by centered embedding
     * cosine, up to the char budget / note cap. Returned in chronological order.
     */
    private suspend fun select(context: Context, notes: List<Note>, question: String): List<Note> {
        val totalChars = notes.sumOf { it.text.length + 24 }
        if (notes.size <= MAX_NOTES && totalChars <= MAX_CHARS) return notes

        val qv = Embedder.embed(context, question, query = true)
        val withVec = notes.filter { it.embedding != null }
        if (qv == null || withVec.isEmpty()) return notes.sortedByDescending { it.createdAt }.take(MAX_NOTES)

        val mean = Embedder.mean(withVec.mapNotNull { it.embedding })
        val ranked = withVec.sortedByDescending {
            val e = it.embedding!!
            if (mean != null) Embedder.cosineCentered(qv, e, mean) else Embedder.cosine(qv, e)
        }
        val out = ArrayList<Note>()
        var chars = 0
        for (n in ranked) {
            if (out.size >= MAX_NOTES) break
            val len = n.text.length + 24
            if (chars + len > MAX_CHARS && out.isNotEmpty()) break
            out.add(n); chars += len
        }
        return out.sortedByDescending { it.createdAt }
    }

    private fun strip(s: String): String =
        s.replace(Regex("(?is)<think.*?</think>"), " ").replace(Regex("<[^>]*>"), " ").trim()
}
