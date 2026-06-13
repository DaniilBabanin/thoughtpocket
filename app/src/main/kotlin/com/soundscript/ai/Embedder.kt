package com.soundscript.ai

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.soundscript.data.Note
import com.soundscript.data.NoteDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * On-device text embeddings for semantic relate + clustering, via MediaPipe TextEmbedder
 * (Universal Sentence Encoder, bundled in assets). Runs on CPU, ~20 ms/note. One at a time.
 */
object Embedder {
    private const val MODEL = "universal_sentence_encoder.tflite"
    private val mutex = Mutex()
    @Volatile private var embedder: TextEmbedder? = null

    private fun ensure(context: Context): TextEmbedder = embedder ?: run {
        val opts = TextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setL2Normalize(true)
            .build()
        TextEmbedder.createFromOptions(context.applicationContext, opts).also { embedder = it }
    }

    /** Embedding vector for [text], or null on blank/failure. */
    suspend fun embed(context: Context, text: String): FloatArray? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        mutex.withLock {
            runCatching {
                ensure(context).embed(text.take(4000))
                    .embeddingResult().embeddings()[0].floatEmbedding()
            }.getOrNull()
        }
    }

    /** Embed every note still missing a vector and persist it. Cheap, runs once per new note. */
    suspend fun backfillMissing(context: Context, dao: NoteDao) {
        dao.all().first()
            .filter { it.embedding == null && it.text.isNotBlank() }
            .forEach { n -> embed(context, n.text)?.let { dao.update(n.copy(embedding = it)) } }
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

    /** Cosine after subtracting [mean] from both — sharpens USE's compressed similarity range. */
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
    // Centered cosine (raw USE cosines are too compressed and merge everything into one blob).
    // 0.40 gave ~0.90 cluster purity on the 300-note scale test; see ScaleTest.
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
            .filter { it.size >= 2 } // singletons aren't a cluster
            .sortedByDescending { it.size }
            .map { Cluster(label(it), it) }
    }

    /** Label a cluster by its most common tag, else the first note's title/text. */
    private fun label(notes: List<Note>): String {
        notes.flatMap { it.tags }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key?.let { return it }
        val first = notes.first()
        return first.title.ifBlank { first.text.take(24) }
    }
}
