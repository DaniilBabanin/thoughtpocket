package com.soundscript.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import com.soundscript.data.Note
import com.soundscript.data.NoteDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Optional
import kotlin.math.sqrt

/**
 * On-device text embeddings for semantic relate / search / clustering, via the Google AI Edge
 * RAG SDK's Gecko 110m embedder (validated to beat the old USE model — see ScaleTest/GeckoSpike).
 *
 * The model (~109 MB) + tokenizer download once on demand into the app's files dir; embeddings
 * are off until it's present. Gecko uses asymmetric task types: notes embed as RETRIEVAL_DOCUMENT,
 * search queries as RETRIEVAL_QUERY. Runs on CPU (GPU is a later optimisation). One inference at a time.
 */
object Embedder {
    private const val TAG = "Embedder"
    const val DIM = 768
    private const val TFLITE = "gecko.tflite"
    private const val TOKENIZER = "sentencepiece.model"
    private const val HF = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main"
    private const val TFLITE_URL = "$HF/Gecko_256_quant.tflite"
    private const val TOKENIZER_URL = "$HF/$TOKENIZER"
    const val SIZE_MB = 110

    private val mutex = Mutex()
    @Volatile private var model: GeckoEmbeddingModel? = null

    private fun dir(context: Context): File =
        (context.getExternalFilesDir("gecko") ?: File(context.filesDir, "gecko")).apply { mkdirs() }

    private fun tflite(context: Context) = File(dir(context), TFLITE)
    private fun tokenizer(context: Context) = File(dir(context), TOKENIZER)

    /** True once both model files are present and usable. */
    fun isReady(context: Context): Boolean =
        tflite(context).length() > 1_000_000L && tokenizer(context).length() > 0L

    private fun ensure(context: Context): GeckoEmbeddingModel? {
        model?.let { return it }
        if (!isReady(context)) return null
        return runCatching {
            GeckoEmbeddingModel(tflite(context).absolutePath, Optional.of(tokenizer(context).absolutePath), false)
        }.onFailure { Log.e(TAG, "Gecko load failed", it) }.getOrNull()?.also { model = it }
    }

    fun release() { model = null }

    /** Embedding for [text]; pass [query]=true for a search query (else it's a document). */
    suspend fun embed(context: Context, text: String, query: Boolean = false): FloatArray? =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext null
            val m = ensure(context) ?: return@withContext null
            val task = if (query) EmbedData.TaskType.RETRIEVAL_QUERY else EmbedData.TaskType.RETRIEVAL_DOCUMENT
            val req = EmbeddingRequest.create(listOf(EmbedData.create(text.take(4000), task, query)))
            mutex.withLock {
                runCatching { m.getEmbeddings(req).get().toFloatArray() }
                    .onFailure { Log.w(TAG, "embed failed: ${it.cause ?: it}") }.getOrNull()
            }
        }

    /** Download the Gecko model + tokenizer; emits 0..99 progress, then 100, then -1. */
    fun download(context: Context): Flow<Int> = flow {
        // tokenizer first (tiny), then the model with progress.
        fetch(URL(TOKENIZER_URL), tokenizer(context)) {}
        fetch(URL(TFLITE_URL), tflite(context)) { pct -> emit(pct.coerceIn(0, 99)) }
        emit(100); emit(-1)
        release()
    }.flowOn(Dispatchers.IO)

    private suspend inline fun fetch(url: URL, target: File, onPct: (Int) -> Unit) {
        val tmp = File(target.absolutePath + ".part")
        val conn = (url.openConnection() as HttpURLConnection).apply {
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

    /** Embed notes missing a vector and drop any stale (wrong-dimension) ones, then re-embed. */
    suspend fun backfillMissing(context: Context, dao: NoteDao) {
        if (!isReady(context)) return
        for (n in dao.all().first()) {
            val stale = n.embedding != null && n.embedding.size != DIM
            when {
                n.text.isBlank() -> if (stale) dao.update(n.copy(embedding = null))
                n.embedding == null || stale -> embed(context, n.text)?.let { dao.update(n.copy(embedding = it)) }
            }
        }
    }

    /** Cosine similarity in [-1, 1]. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    /** Element-wise mean of vectors — the corpus "common component" to subtract for contrast. */
    fun mean(vecs: List<FloatArray>): FloatArray? {
        if (vecs.isEmpty()) return null
        val m = FloatArray(vecs[0].size)
        for (v in vecs) for (i in m.indices) m[i] += v[i]
        for (i in m.indices) m[i] /= vecs.size
        return m
    }

    /** Cosine after subtracting [mean] from both — sharpens the similarity range. */
    fun cosineCentered(a: FloatArray, b: FloatArray, mean: FloatArray): Float {
        val ca = FloatArray(a.size) { a[it] - mean[it] }
        val cb = FloatArray(b.size) { b[it] - mean[it] }
        return cosine(ca, cb)
    }
}

/** A topic cluster of semantically-similar notes, biggest first. */
data class Cluster(val label: String, val notes: List<Note>)

/** Group notes into topic clusters by embedding similarity (single-linkage union-find). */
object Clusters {
    // Centered cosine (raw cosines are too compressed and merge everything into one blob).
    // 0.40 gave ~1.0 cluster purity with Gecko on the 300-note scale test; see ScaleTest.
    private const val THRESHOLD = 0.40f

    fun build(notes: List<Note>): List<Cluster> {
        val v = notes.filter { it.embedding != null }
        val n = v.size
        if (n < 2) return emptyList()

        val mean = Embedder.mean(v.mapNotNull { it.embedding }) ?: return emptyList()
        val cen = v.map { note -> FloatArray(mean.size) { note.embedding!![it] - mean[it] } }

        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        for (i in 0 until n) for (j in i + 1 until n)
            if (Embedder.cosine(cen[i], cen[j]) >= THRESHOLD)
                parent[find(i)] = find(j)

        return (0 until n).groupBy { find(it) }.values
            .map { idxs -> idxs.map { v[it] } }
            .filter { it.size >= 2 }
            .sortedByDescending { it.size }
            .map { Cluster(label(it), it) }
    }

    private fun label(notes: List<Note>): String {
        notes.flatMap { it.tags }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key?.let { return it }
        val first = notes.first()
        return first.title.ifBlank { first.text.take(24) }
    }
}
