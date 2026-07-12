package com.thoughtpocket

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Coder-feature (experimental) GGUF model registry: one built-in download
 * (Ornith 9B) + bring-your-own imports. Deliberately separate from
 * [ModelManager]/[com.thoughtpocket.ai.LlmEngine]'s registries — different
 * runtime (llama.cpp), different lifecycle (excluded from "Download all" and
 * Settings' needsSetup, same precedent as Moonshine).
 */
object CoderModelManager {

    private const val TAG = "CoderModels"

    // Primary host. Revision-pinned so a repo force-push can't silently change
    // what users download; sha256 gates promotion either way.
    private const val HF_ORNITH =
        "https://huggingface.co/deepreinforce-ai/Ornith-1.0-9B-GGUF/resolve/d6b5f9b2ef835df5085615e0c405ce0092cc8b53"
    // Mirror, ready to swap in if HF becomes unreliable (upload the *HF* file —
    // the Ollama-registry copy of this model has different bytes/sha):
    // private const val NEXTCLOUD_CODER =
    //     "https://next.babanin.de/public.php/dav/files/cQz6gaz6H4tGyRP/coder"

    enum class BuiltInCoderModel(
        val displayName: String,
        val filename: String,
        val url: String,
        val approxSizeMb: Int,
        val sha256: String,
    ) {
        ORNITH_9B_Q4(
            "Ornith 1.0 9B (Q4_K_M)",
            "ornith-1.0-9b-Q4_K_M.gguf",
            "$HF_ORNITH/ornith-1.0-9b-Q4_K_M.gguf",
            5630,
            "5720d1f671b4996481274fffe01868c3c36e87c135cc8538471cc7bd6087b106",
        );

        val id: String get() = "coder:$name"
    }

    fun coderDir(context: Context): File =
        (context.getExternalFilesDir("coder") ?: File(context.filesDir, "coder")).apply { mkdirs() }

    fun installedModels(context: Context): List<File> =
        coderDir(context).listFiles { f -> f.name.endsWith(".gguf") && f.length() > 100_000_000L }
            ?.sortedBy { it.name } ?: emptyList()

    fun isInstalled(context: Context): Boolean = installedModels(context).isNotEmpty()

    /** The model file the coder session should load, honoring the pref when set. */
    fun selectedModel(context: Context): File? {
        val models = installedModels(context)
        val pref = AppPreferences(context).coderModelFilename
        return models.firstOrNull { it.name == pref } ?: models.firstOrNull()
    }

    /**
     * Same strict contract as [ModelManager.download] (identity encoding,
     * required Content-Length, .part + completeness + sha256 gate, atomic
     * rename), plus Range resume: a 5.6 GB file must not restart from zero
     * after one flaky moment, so a failed transfer KEEPS its .part and the
     * next attempt continues from there (HF serves 206). Only a checksum
     * mismatch discards it. Emits 0..99, 100, then -1.
     */
    fun download(context: Context, model: BuiltInCoderModel): Flow<Int> = flow {
        val target = File(coderDir(context), model.filename)
        val tmp = File(target.absolutePath + ".part")
        val offset = if (tmp.exists()) tmp.length() else 0L
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            // Gzip would drop the Content-Length the completeness guard needs.
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            conn.connect()
            val resumed = conn.responseCode == 206 && offset > 0
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} for ${model.filename}")
            }
            if (!resumed && offset > 0) tmp.delete() // server ignored Range → fresh start
            val body = conn.contentLengthLong
            if (body <= 0) throw IllegalStateException("Missing Content-Length for ${model.filename}")
            val total = if (resumed) offset + body else body
            var downloaded = if (resumed) offset else 0L
            var last = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != last) { last = pct; emit(pct) }
                    }
                }
            }
            if (downloaded != total) {
                // Keep tmp — the next attempt resumes from here.
                throw IllegalStateException("Incomplete download: $downloaded/$total bytes")
            }
            val actual = sha256(tmp)
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                tmp.delete()
                throw IllegalStateException(
                    "Checksum mismatch for ${model.filename}: expected ${model.sha256.take(12)}…, got ${actual.take(12)}…"
                )
            }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            emit(100)
            emit(-1)
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Import a user-picked GGUF. Rejects files without the GGUF magic or under
     * 100 MB (an accidental non-model pick, not a real quantized LLM).
     * [onProgress] gets 0..100 when the picker exposes the file size (multi-GB
     * copies otherwise look hung).
     */
    suspend fun importFromUri(
        context: Context, uri: Uri, onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var size = -1L
            val displayName = resolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    if (i >= 0) c.getString(i) else null
                } else null
            } ?: "imported.gguf"
            require(displayName.endsWith(".gguf", ignoreCase = true)) { "Not a .gguf file: $displayName" }

            val target = File(coderDir(context), sanitizeFilename(displayName))
            val tmp = File(target.absolutePath + ".part")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (size > 0) {
                            val pct = ((copied * 100) / size).toInt().coerceIn(0, 100)
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            } ?: throw IllegalStateException("Cannot open $uri")

            val head = ByteArray(4)
            tmp.inputStream().use { it.read(head) }
            if (!isGgufMagic(head)) { tmp.delete(); throw IllegalArgumentException("Not a GGUF file") }
            if (tmp.length() < 100_000_000L) { tmp.delete(); throw IllegalArgumentException("File too small to be a model") }

            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            Log.i(TAG, "Imported ${target.name} (${target.length() / 1_000_000} MB)")
            target
        }
    }

    fun delete(context: Context, file: File) {
        file.delete()
    }

    // Pure, JVM-tested.
    internal fun isGgufMagic(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'G'.code.toByte() && head[1] == 'G'.code.toByte() &&
            head[2] == 'U'.code.toByte() && head[3] == 'F'.code.toByte()

    // Pure, JVM-tested. Keeps the .gguf suffix, strips path tricks/odd chars.
    internal fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.endsWith(".gguf", ignoreCase = true)) cleaned else "$cleaned.gguf"
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
