package com.thoughtpocket

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Model registry + downloader. Models live under filesDir/models/.
 *
 * Two flavours:
 *  - [BuiltInModel] — curated list of ggml quantizations served from huggingface.co.
 *    SHA-256 verified after download (size column matches HF v1.7.4 manifest).
 *  - [CustomModel] — user-imported .bin files, persisted in custom_models.json so
 *    they survive process restarts. Useful when HF is unreachable / rate-limited.
 *
 * Both implement [ModelEntry], the common interface used by the rest of the app.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    // Moonshine ORT files, hosted on the project's Nextcloud (no-auth public DAV) alongside Gemma/Gecko —
    // see LlmEngine.NEXTCLOUD. One subfolder per model (base/ tiny/), each holding the 5 MOONSHINE_FILES.
    private const val NEXTCLOUD_MOONSHINE = "https://next.babanin.de/public.php/dav/files/cQz6gaz6H4tGyRP/moonshine"
    private const val MANIFEST_FILE = "custom_models.json"
    private const val CUSTOM_PREFIX = "custom_"

    // ---------- public types ----------

    /** The on-device runtime that backs a model — selects the [TranscriptionEngine] impl. */
    enum class EngineKind { WHISPER, MOONSHINE }

    sealed interface ModelEntry {
        val id: String
        val displayName: String
        val filename: String
        val multilingual: Boolean
        val approxSizeMb: Int
        /** Defaults to whisper.cpp; streaming entries (Moonshine) override. */
        val engine: EngineKind get() = EngineKind.WHISPER
    }

    enum class BuiltInModel(
        override val displayName: String,
        override val filename: String,
        val urlPath: String,
        override val approxSizeMb: Int,
        override val multilingual: Boolean,
        // Null = no curated checksum (large community models); integrity is then
        // checked by Content-Length completeness instead of SHA-256.
        val sha256: String?
    ) : ModelEntry {
        TINY_Q5(
            "Tiny (multilingual, fastest)",
            "ggml-tiny-q5_1.bin",
            "ggml-tiny-q5_1.bin",
            31, true,
            "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
        ),
        BASE_Q5(
            "Base (multilingual, recommended)",
            "ggml-base-q5_1.bin",
            "ggml-base-q5_1.bin",
            60, true,
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        ),
        SMALL_Q5(
            "Small (multilingual, accurate)",
            "ggml-small-q5_1.bin",
            "ggml-small-q5_1.bin",
            190, true,
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
        ),
        BASE_EN_Q5(
            "Base English-only (faster)",
            "ggml-base.en-q5_1.bin",
            "ggml-base.en-q5_1.bin",
            60, false,
            "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
        ),
        TINY_EN_Q5(
            "Tiny English-only (fastest)",
            "ggml-tiny.en-q5_1.bin",
            "ggml-tiny.en-q5_1.bin",
            31, false,
            null
        ),
        SMALL_EN_Q5(
            "Small English-only (accurate)",
            "ggml-small.en-q5_1.bin",
            "ggml-small.en-q5_1.bin",
            190, false,
            null
        ),
        MEDIUM_EN_Q5(
            "Medium English-only (most accurate)",
            "ggml-medium.en-q5_0.bin",
            "ggml-medium.en-q5_0.bin",
            539, false,
            null
        ),
        MEDIUM_Q5(
            "Medium (multilingual, slow)",
            "ggml-medium-q5_0.bin",
            "ggml-medium-q5_0.bin",
            539, true,
            null
        ),
        LARGE_TURBO_Q5(
            "Large v3 Turbo (multilingual, fast & accurate)",
            "ggml-large-v3-turbo-q5_0.bin",
            "ggml-large-v3-turbo-q5_0.bin",
            574, true,
            null
        ),
        LARGE_V3_Q5(
            "Large v3 (multilingual, most accurate, slow)",
            "ggml-large-v3-q5_0.bin",
            "ggml-large-v3-q5_0.bin",
            1080, true,
            null
        );

        override val id: String get() = "builtin:$name"
        fun downloadUrl(): String = "$HF_BASE/$urlPath"
    }

    data class CustomModel(
        override val displayName: String,
        override val filename: String,
        override val multilingual: Boolean,
        override val approxSizeMb: Int
    ) : ModelEntry {
        override val id: String get() = "custom:$filename"
    }

    /**
     * sherpa-onnx Moonshine — the streaming-class first-pass engine. Unlike a single ggml file, each is a
     * directory of ORT files ([MOONSHINE_FILES]) that live under externalFilesDir/[filename]. English-only.
     * [filename] is the subdir name (there is no single model file). Hosted file-by-file on Nextcloud.
     */
    enum class StreamingModel(
        override val displayName: String,
        override val filename: String,   // = the model subdir under externalFilesDir
        val cloudDir: String,            // = the model subfolder under NEXTCLOUD_MOONSHINE
        override val approxSizeMb: Int,
    ) : ModelEntry {
        MOONSHINE_BASE("Moonshine Base (instant, English)", "moonshine", "base", 286),
        MOONSHINE_TINY("Moonshine Tiny (instant, English, smaller)", "moonshine-tiny", "tiny", 123);

        override val multilingual: Boolean get() = false
        override val engine: EngineKind get() = EngineKind.MOONSHINE
        override val id: String get() = "moonshine:$name"
        fun fileUrl(file: String): String = "$NEXTCLOUD_MOONSHINE/$cloudDir/$file"
    }

    /** The ORT files a Moonshine model dir must contain (sherpa-onnx OfflineMoonshineModelConfig + tokens). */
    val MOONSHINE_FILES = listOf(
        "preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt"
    )

    /** For UI: keep BuiltIn maintained in source order, then streaming, then customs. */
    fun listAll(context: Context): List<ModelEntry> =
        BuiltInModel.entries.toList<ModelEntry>() + StreamingModel.entries + readCustomManifest(context)

    fun listInstalled(context: Context): List<ModelEntry> =
        listAll(context).filter { isDownloaded(context, it) }

    fun entryById(context: Context, id: String): ModelEntry? =
        listAll(context).firstOrNull { it.id == id }

    // ---------- file paths ----------

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(context: Context, entry: ModelEntry): File =
        File(modelsDir(context), entry.filename)

    /**
     * Directory holding a [StreamingModel]'s ORT files. On external files dir (like Gecko) so the large
     * int8 ONNX set doesn't bloat internal storage; falls back to filesDir if external is unavailable.
     */
    fun moonshineDir(context: Context, entry: ModelEntry): File =
        (context.getExternalFilesDir(entry.filename) ?: File(context.filesDir, entry.filename)).apply { mkdirs() }

    fun isDownloaded(context: Context, entry: ModelEntry): Boolean = when (entry) {
        is StreamingModel -> moonshineDir(context, entry).let { d -> MOONSHINE_FILES.all { File(d, it).length() > 1_000L } }
        else -> fileFor(context, entry).let { it.exists() && it.length() > 1_000_000 }
    }

    // ---------- download (built-ins only) ----------

    /**
     * Emits 0..100 then -1 to signal completion. SHA-256 verified on success
     * unless [verify] is false (developer escape hatch).
     */
    fun download(context: Context, model: BuiltInModel, verify: Boolean = true): Flow<Int> = flow {
        val target = fileFor(context, model)
        val tmp = File(target.absolutePath + ".part")
        val url = URL(model.downloadUrl())
        Log.i(TAG, "Downloading $url -> $target")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            conn.connect()
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloaded = 0L
            var lastEmitted = -1

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastEmitted) {
                                lastEmitted = pct
                                emit(pct)
                            }
                        }
                    }
                }
            }

            // Truncated download guard (covers models without a curated SHA-256).
            if (total > 0 && downloaded != total) {
                tmp.delete()
                throw IllegalStateException(
                    "Incomplete download for ${model.filename}: $downloaded/$total bytes"
                )
            }

            // Verify before promoting .part -> final (only when a checksum exists).
            val expected = model.sha256
            if (verify && !expected.isNullOrBlank()) {
                val actual = sha256(tmp)
                if (!actual.equals(expected, ignoreCase = true)) {
                    tmp.delete()
                    throw IllegalStateException(
                        "Checksum mismatch for ${model.filename}: expected ${expected.take(12)}…, got ${actual.take(12)}…"
                    )
                }
            } else {
                Log.w(TAG, "SHA-256 verification skipped for ${model.filename}")
            }

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            emit(100)
            emit(-1)
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Download a [StreamingModel]'s ORT files one by one into its dir (resuming any already present).
     * Emits a coarse 0..99 (each file an equal slice), then 100, then -1 — same contract as [download].
     */
    fun downloadMoonshine(context: Context, model: StreamingModel): Flow<Int> = flow {
        val dir = moonshineDir(context, model)
        val n = MOONSHINE_FILES.size
        MOONSHINE_FILES.forEachIndexed { i, name ->
            val target = File(dir, name)
            if (target.length() > 1_000L) { emit((((i + 1) * 100) / n).coerceIn(0, 99)); return@forEachIndexed }
            fetch(URL(model.fileUrl(name)), target) { p -> emit(((i * 100 + p) / n).coerceIn(0, 99)) }
        }
        emit(100); emit(-1)
    }.flowOn(Dispatchers.IO)

    /** Download [url] → [target] with progress (0..100), via a .part temp + completeness guard. */
    private suspend inline fun fetch(url: URL, target: File, onPct: (Int) -> Unit) {
        val tmp = File(target.absolutePath + ".part")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true; requestMethod = "GET"
        }
        try {
            conn.connect()
            val total = conn.contentLengthLong
            var done = 0L; var last = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf); if (r <= 0) break
                        out.write(buf, 0, r); done += r
                        if (total > 0) { val p = ((done * 100) / total).toInt(); if (p != last) { last = p; onPct(p) } }
                    }
                }
            }
            if (total > 0 && done != total) { tmp.delete(); throw IllegalStateException("Incomplete download: $done/$total") }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } finally {
            conn.disconnect(); if (tmp.exists()) tmp.delete()
        }
    }

    fun delete(context: Context, entry: ModelEntry) {
        when (entry) {
            is StreamingModel -> moonshineDir(context, entry).deleteRecursively()
            is CustomModel -> { fileFor(context, entry).delete(); removeFromManifest(context, entry) }
            else -> fileFor(context, entry).delete()
        }
    }

    // ---------- custom import ----------

    /**
     * Copy a user-picked .bin into the app's models directory and register it in
     * the custom manifest. Falls back gracefully if the URI can't be opened.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        displayName: String,
        multilingual: Boolean
    ): Result<CustomModel> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val sanitised = sanitiseFilename(displayName)
            val filename = "$CUSTOM_PREFIX$sanitised.bin"
            val target = File(modelsDir(context), filename)
            val tmp = File(target.absolutePath + ".part")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open $uri")

            if (tmp.length() < 1_000_000) {
                tmp.delete()
                return@withContext Result.failure(
                    IllegalStateException("File is too small to be a whisper model (<1 MB)")
                )
            }

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            val entry = CustomModel(
                displayName = displayName.take(60),
                filename = filename,
                multilingual = multilingual,
                approxSizeMb = (target.length() / 1_000_000).toInt().coerceAtLeast(1)
            )
            addToManifest(context, entry)
            Log.i(TAG, "Imported custom model: ${entry.filename} (${entry.approxSizeMb} MB)")
            Result.success(entry)
        } catch (t: Throwable) {
            Log.w(TAG, "Custom import failed", t)
            Result.failure(t)
        }
    }

    // ---------- manifest helpers ----------

    private fun manifestFile(context: Context): File =
        File(modelsDir(context), MANIFEST_FILE)

    private fun readCustomManifest(context: Context): List<CustomModel> {
        val file = manifestFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val filename = o.getString("filename")
                    // Drop entries whose underlying file went missing.
                    if (!File(modelsDir(context), filename).exists()) continue
                    add(
                        CustomModel(
                            displayName = o.optString("displayName", filename),
                            filename = filename,
                            multilingual = o.optBoolean("multilingual", true),
                            approxSizeMb = o.optInt("approxSizeMb", 0)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read custom manifest", t)
            emptyList()
        }
    }

    private fun writeCustomManifest(context: Context, entries: List<CustomModel>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("filename", e.filename)
                    put("displayName", e.displayName)
                    put("multilingual", e.multilingual)
                    put("approxSizeMb", e.approxSizeMb)
                }
            )
        }
        manifestFile(context).writeText(arr.toString())
    }

    private fun addToManifest(context: Context, entry: CustomModel) {
        val current = readCustomManifest(context).filterNot { it.filename == entry.filename }
        writeCustomManifest(context, current + entry)
    }

    private fun removeFromManifest(context: Context, entry: CustomModel) {
        val current = readCustomManifest(context).filterNot { it.filename == entry.filename }
        writeCustomManifest(context, current)
    }

    private fun sanitiseFilename(name: String): String {
        val cleaned = name.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .take(40)
        return cleaned.ifBlank { "model_${System.currentTimeMillis()}" }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
