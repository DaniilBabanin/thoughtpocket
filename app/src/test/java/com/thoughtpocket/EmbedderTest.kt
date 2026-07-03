package com.thoughtpocket

import com.thoughtpocket.ai.Embedder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Gecko model isn't testable on the JVM, but the vector math ranking semantic search is pure. */
class EmbedderTest {
    private val eps = 1e-5f

    // ---- cosine ----

    @Test fun identicalVectorsAreOne() {
        assertEquals(1f, Embedder.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f)), eps)
    }

    @Test fun orthogonalVectorsAreZero() {
        assertEquals(0f, Embedder.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), eps)
    }

    @Test fun oppositeVectorsAreMinusOne() {
        assertEquals(-1f, Embedder.cosine(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)), eps)
    }

    @Test fun mismatchedSizesAreZeroNotCrash() {
        assertEquals(0f, Embedder.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f)), 0f)
    }

    @Test fun zeroVectorIsZeroNotNaN() {
        assertEquals(0f, Embedder.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 0f)
    }

    // ---- mean ----

    @Test fun meanIsElementWise() {
        val m = Embedder.mean(listOf(floatArrayOf(1f, 3f), floatArrayOf(3f, 5f)))!!
        assertArrayEquals(floatArrayOf(2f, 4f), m, eps)
    }

    @Test fun meanOfNothingIsNull() {
        assertNull(Embedder.mean(emptyList()))
    }

    // ---- cosineCentered ----

    @Test fun centeringSharpensCompressedSimilarity() {
        // a=(2,1), b=(1,2): raw cosine 4/5 = 0.8, but centered on (1,1) they become (1,0)⊥(0,1) → 0.
        val a = floatArrayOf(2f, 1f)
        val b = floatArrayOf(1f, 2f)
        assertEquals(0.8f, Embedder.cosine(a, b), eps)
        assertEquals(0f, Embedder.cosineCentered(a, b, floatArrayOf(1f, 1f)), eps)
    }

    @Test fun zeroMeanIsPlainCosine() {
        val a = floatArrayOf(3f, 1f)
        val b = floatArrayOf(1f, 2f)
        assertEquals(Embedder.cosine(a, b), Embedder.cosineCentered(a, b, floatArrayOf(0f, 0f)), eps)
    }

    @Test fun centeredCosineRanksWhereRawCosineCannot() {
        // All vectors share a big common component (10,10); raw cosine compresses both docs
        // to ~1 for the query, but subtracting the corpus mean makes the ranking decisive.
        val q = floatArrayOf(10.1f, 10f)
        val d1 = floatArrayOf(10.1f, 10f)   // same "topic" as the query
        val d2 = floatArrayOf(10f, 10.1f)   // the other topic
        val mean = Embedder.mean(listOf(d1, d2))!!

        assertTrue(Embedder.cosine(q, d2) > 0.999f)   // raw: indistinguishable
        assertEquals(1f, Embedder.cosineCentered(q, d1, mean), eps)
        assertEquals(-1f, Embedder.cosineCentered(q, d2, mean), eps)
    }
}
