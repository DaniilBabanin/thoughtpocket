package com.soundscript.ai

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
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
}
