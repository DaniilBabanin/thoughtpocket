package com.thoughtpocket.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.ThoughtPocketApp
import com.thoughtpocket.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
    // Shared with CoderEngine: a coder session holds it for its whole duration,
    // which blocks tagging/analysis from re-loading Gemma mid-session (RAM).
    private val mutex = AiMutex.mutex

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

    // ---------- download ----------

    /**
     * Apache-2.0 Gemma 4 .litertlm bundles, hosted on the project's Nextcloud as direct,
     * no-auth public links. Swap [NEXTCLOUD] if the host changes.
     */
    private const val NEXTCLOUD = "https://next.babanin.de/public.php/dav/files/cQz6gaz6H4tGyRP/llm"

    enum class Downloadable(val filename: String, val url: String, val approxSizeMb: Int) {
        E2B("gemma-4-E2B-it.litertlm", "$NEXTCLOUD/gemma-4-E2B-it.litertlm", 2588),
        E4B("gemma-4-E4B-it.litertlm", "$NEXTCLOUD/gemma-4-E4B-it.litertlm", 3660),
    }

    fun isInstalled(context: Context, d: Downloadable): Boolean =
        File(llmDir(context), d.filename).let { it.exists() && it.length() > 10_000_000L }

    /**
     * Download [d] into the llm/ dir. Emits 0..99 progress, then 100, then -1.
     * No resume — a dropped connection restarts; fine for a Settings-initiated download.
     */
    fun download(context: Context, d: Downloadable): Flow<Int> = flow {
        val target = File(llmDir(context), d.filename)
        val tmp = File(target.absolutePath + ".part")
        val conn = (URL(d.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val total = conn.contentLengthLong
            var done = 0L; var last = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n <= 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt().coerceIn(0, 99)
                            if (p != last) { last = p; emit(p) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) { tmp.delete(); throw IllegalStateException("Incomplete download: $done/$total") }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            emit(100); emit(-1)
        } finally {
            conn.disconnect(); if (tmp.exists()) tmp.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Run the model on [prompt]; [model] picks a specific bundle (else the default).
     * With [onToken] set, decoding streams and the callback fires once per emitted chunk
     * (≈ one decode step/token) — nothing fires during prefill, which the API doesn't report.
     */
    suspend fun generate(context: Context, prompt: String, model: File? = null, onToken: (() -> Unit)? = null): Result<String> =
        withContext(Dispatchers.Default) {
            val model = model ?: modelFile(context)
                ?: return@withContext Result.failure(IllegalStateException("No AI model — download one in Settings."))
            mutex.withLock {
                runCatching {
                    val eng = ensureLoaded(context, model)
                    // GPU inference can abort the whole process (driver crash), so leave a crumb only
                    // while one is in flight — ThoughtPocketApp forces CPU on the next launch if it
                    // survives. A handled failure is not a crash: the crumb must not outlive this call.
                    val crumb = if (activeBackend == "gpu")
                        File(context.filesDir, ThoughtPocketApp.GPU_CRUMB_FILE).also { it.createNewFile() }
                    else null
                    try {
                        eng.createConversation().use { conv ->
                            if (onToken == null) conv.sendMessage(prompt).plainText()
                            else {
                                val sb = StringBuilder()
                                var chunks = 0
                                conv.sendMessageAsync(prompt).collect { msg ->
                                    sb.append(msg.deltaText()); chunks++; onToken()
                                }
                                Log.i(TAG, "streamed $chunks chunks, ${sb.length} chars")
                                sb.toString().trim()
                            }
                        }
                    } finally {
                        crumb?.delete()
                    }
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

        // GPU (OpenCL) with a CPU fallback. NPU was measured on-device (2026-06-18): LiteRT-LM rejects
        // our generic .litertlm bundles with NOT_FOUND (Backend.NPU needs an NPU-COMPILED model), so it's
        // not in the chain — it would only waste ~14s of failed init per load before falling back to GPU.
        Log.i(TAG, "Loading LLM: ${model.name} (${model.length() / 1_000_000} MB) on GPU")
        // Same crumb bracket as GPU generation: a GPU init that kills the process leaves it behind;
        // a caught init failure deletes it before the CPU fallback (which must never carry a crumb).
        val crumb = File(context.filesDir, ThoughtPocketApp.GPU_CRUMB_FILE)
        val eng = try {
            crumb.createNewFile()
            make(Backend.GPU(), "gpu").also { crumb.delete() }
        } catch (t: Throwable) {
            crumb.delete()
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

    /** Text of one streamed delta chunk — no trim/space-join, so chunk boundaries stay intact. */
    private fun Message.deltaText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

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
        val chunks = chunk(text)
        if (chunks.size == 1) return LlmEngine.generate(context, buildPrompt(chunks[0]), model).map { parseTags(it) }
        // Long note → tag each window then fold duplicates with canonicalizeTags (map-reduce); the whole
        // note gets tagged, not just the first 4k chars. ponytail: capped at 5 windows — sampling, not exhaustive.
        val all = mutableListOf<String>()
        var anyOk = false
        for (c in chunks) LlmEngine.generate(context, buildPrompt(c), model)
            .onSuccess { anyOk = true; all += parseTags(it) }
        return if (anyOk) Result.success(canonicalizeTags(all, emptyList()).take(5))
        else Result.failure(IllegalStateException("Tagging failed"))
    }

    /** At most [max] windows covering the WHOLE text (not just the opening). */
    private fun chunk(text: String, target: Int = 4000, max: Int = 5): List<String> {
        if (text.length <= target) return listOf(text)
        val size = maxOf(target, (text.length + max - 1) / max)
        return text.chunked(size)
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
            "punctuation. Reply with ONLY the title.\n\nNote:\n\"\"\"\n${text.take(3000)}\n\"\"\""

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
    private const val MAX_CHARS = 16000   // context budget for one LLM call
    private const val MAX_NOTES = 50      // keep retrieval focused even when more would fit
    // Fit-or-split cap: up to this many map batches still get FULL coverage via LlmHarness
    // map-reduce (~10 extract passes worst case on-device); a scope even bigger than that
    // is RAG-trimmed by select() first so latency stays bounded.
    private const val MAX_BATCHES = 10
    private val df = SimpleDateFormat("MMM d", Locale.getDefault())

    /** Live decode progress of the current ask() — part i of n plus tokens so far; null when idle. */
    data class AskProgress(val part: Int, val parts: Int, val tokens: Int)
    val progress = MutableStateFlow<AskProgress?>(null)

    suspend fun ask(context: Context, notes: List<Note>, question: String): Result<String> = try {
        askInner(context, notes, question)
    } finally {
        progress.value = null
    }

    private suspend fun askInner(context: Context, notes: List<Note>, question: String): Result<String> {
        if (notes.isEmpty()) return Result.success("No notes in this scope.")
        if (question.isBlank()) return Result.success("")
        // Checklist-aggregation questions ("what did I buy last week", "what's still open") are
        // answered deterministically from the Markdown checkboxes — the LLM only classifies them.
        val now = System.currentTimeMillis()
        ChecklistQuery.tryAnswer(context, notes, question, now)?.let {
            return Result.success(it)
        }
        // A purely-retrospective question ("last Tuesday", "yesterday")? Hard-filter to that window before
        // RAG, so the answer can't come from topically-similar notes on the wrong day. Present/ongoing
        // windows ("today", "this week") are NOT filtered — those mean "from my backlog, what's relevant
        // now", so filtering by creation date would dead-end the query (see TimeWindow.isRetrospective).
        val win = TimeWindow.parse(question, now)?.takeIf { TimeWindow.isRetrospective(it, now) }
        val scoped = if (win != null) notes.filter { it.createdAt >= win.start && it.createdAt < win.endExclusive } else notes
        if (win != null && scoped.isEmpty()) return Result.success("No notes from ${win.label}.")
        val model = LlmEngine.resolve(context, AppPreferences(context).analysisModelFilename, "4b")
        val today = df.format(Date(now))
        val window = win?.let { "The question is about ${it.label}, so only notes from then are included. " } ?: ""
        // Bound the worst case: a scope too big even for map-reduce is RAG-trimmed to the
        // most relevant MAX_BATCHES budgets' worth before splitting.
        val capped = select(context, scoped, question, MAX_BATCHES * MAX_NOTES, MAX_BATCHES * MAX_CHARS)
        val coverage = if (capped.size < scoped.size)
            "\n\n(These are the ${capped.size} most relevant of ${scoped.size} notes in scope.)" else ""
        val batches = LlmHarness.pack(renderLines(capped, MAX_CHARS), MAX_CHARS).map { it.joinToString("\n\n") }

        var tokens = 0
        if (batches.size <= 1) {
            val prompt = singlePrompt(question, batches.firstOrNull().orEmpty(), today, window, coverage)
            return LlmEngine.generate(context, prompt, model, onToken = {
                progress.value = AskProgress(1, 1, ++tokens)
            }).map { strip(it) }
        }
        // Doesn't fit one context: one "subagent" extract pass per batch, then the extracts are
        // combined in a fresh reduce context — full coverage instead of silently dropping notes.
        val perExtract = MAX_CHARS / batches.size
        return LlmHarness.mapReduce(
            context, model, batches,
            mapPrompt = { mapPrompt(question, it, today) },
            reducePrompt = { reducePrompt(question, it, today, window, coverage) },
            clean = { raw ->
                strip(raw).takeUnless { it.lowercase().startsWith("nothing relevant") }?.take(perExtract)
            },
            onToken = { part -> progress.value = AskProgress(part, batches.size + 1, ++tokens) },
        ).map { strip(it).ifBlank { "Nothing about that in these notes." } }
    }

    // ---------- prompts ----------

    // General-purpose prompt: answer in whatever form the question asks (summary, themes,
    // direct answer). The exhaustive-enumeration instruction is scoped to which-items
    // questions only — unconditional, it made the model dump items instead of summarising.
    private fun singlePrompt(question: String, joined: String, today: String, window: String, coverage: String): String =
        "You are the user's personal notes assistant. Today is $today. $window" +
            "Answer the question below using the notes, in whatever form it asks for — a summary, " +
            "themes, a direct answer. " +
            "In checklists, \"- [x]\" means done or bought and \"- [ ] \" means still to do. " +
            "Only when the question asks which items match, scan EVERY relevant note in the time " +
            "window and list ALL matching items across all of them — do not stop after the first note.\n\n" +
            "Notes:\n\"\"\"\n$joined\n\"\"\"$coverage\n\n" +
            "Question: ${question.trim()}"

    private fun mapPrompt(question: String, joined: String, today: String): String =
        "You are reading one slice of the user's voice notes; other slices are handled separately. " +
            "Today is $today. " +
            "In checklists, \"- [x]\" means done or bought and \"- [ ] \" means still to do. " +
            "Extract everything in these notes relevant to the question as concise bullets with " +
            "their dates — do not answer the question itself. " +
            "If nothing here is relevant, reply exactly: nothing relevant.\n\n" +
            "Notes:\n\"\"\"\n$joined\n\"\"\"\n\n" +
            "Question: ${question.trim()}"

    private fun reducePrompt(question: String, extracts: List<String>, today: String, window: String, coverage: String): String =
        "You are the user's personal notes assistant. Today is $today. $window" +
            "The notes did not fit in one pass, so they were read in ${extracts.size} slices; below " +
            "are the relevant extracts from each slice. Combine them into ONE answer to the question, " +
            "in whatever form it asks for — a summary, themes, a direct answer.\n\n" +
            "Extracts:\n\"\"\"\n${extracts.joinToString("\n\n---\n\n")}\n\"\"\"$coverage\n\n" +
            "Question: ${question.trim()}"

    // ---------- selection & rendering ----------

    private fun bodyLen(n: Note) = (if (n.markdown.isNotBlank()) n.markdown.length else n.text.length) + 24

    /**
     * Each note as its "- (date) body" prompt line — Markdown when present so checklist state
     * ("- [x]" done / "- [ ]" still to do) is visible. Bodies over [maxChars] split into
     * "(date, contd.)" parts so every line fits one batch.
     */
    internal fun renderLines(notes: List<Note>, maxChars: Int): List<String> = notes.flatMap { n ->
        val head = "- (${df.format(Date(n.createdAt))}) "
        val contd = "- (${df.format(Date(n.createdAt))}, contd.) "
        val body = n.markdown.ifBlank { n.text }
        if (head.length + body.length <= maxChars) listOf(head + body)
        else body.chunked(maxChars - contd.length).mapIndexed { i, part -> (if (i == 0) head else contd) + part }
    }

    /**
     * Pick which notes are considered at all. If the whole scope fits [maxNotes]/[maxChars],
     * use it all; otherwise retrieve (RAG) the notes most relevant to [question] by centered
     * embedding cosine, up to the budget. Returned newest-first.
     */
    private suspend fun select(context: Context, notes: List<Note>, question: String, maxNotes: Int, maxChars: Int): List<Note> {
        val totalChars = notes.sumOf { bodyLen(it) }
        if (notes.size <= maxNotes && totalChars <= maxChars) return notes

        val qv = Embedder.embed(context, question, query = true)
        val withVec = notes.filter { it.embedding != null }
        if (qv == null || withVec.isEmpty()) return notes.sortedByDescending { it.createdAt }.take(maxNotes)

        val mean = Embedder.mean(withVec.mapNotNull { it.embedding })
        val ranked = withVec.sortedByDescending {
            val e = it.embedding!!
            if (mean != null) Embedder.cosineCentered(qv, e, mean) else Embedder.cosine(qv, e)
        }
        val out = ArrayList<Note>()
        var chars = 0
        for (n in ranked) {
            if (out.size >= maxNotes) break
            val len = bodyLen(n)
            if (chars + len > maxChars && out.isNotEmpty()) break
            out.add(n); chars += len
        }
        return out.sortedByDescending { it.createdAt }
    }

    private fun strip(s: String): String =
        s.replace(Regex("(?is)<think.*?</think>"), " ").replace(Regex("<[^>]*>"), " ").trim()
}
