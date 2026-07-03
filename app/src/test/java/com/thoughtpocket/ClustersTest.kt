package com.thoughtpocket

import com.thoughtpocket.ai.Clusters
import com.thoughtpocket.ai.Embedder
import com.thoughtpocket.data.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Topic clustering (single-linkage union-find over centered cosine) with hand-computable vectors. */
class ClustersTest {
    private fun note(text: String, vec: FloatArray?, tags: List<String> = emptyList()) =
        Note(createdAt = 0L, text = text, tags = tags, embedding = vec)

    @Test fun groupsByTopicBiggestFirstAndDropsSingletons() {
        // Three orthogonal topics: 3×(1,0,0), 2×(0,1,0), 1×(0,0,1). After centering, within-topic
        // cosine is 1 and every cross-topic cosine is negative → two clusters, the singleton dropped.
        val betaText = "beta note about the garden project"
        val notes = listOf(
            note("a1", floatArrayOf(1f, 0f, 0f), tags = listOf("errands")),
            note("a2", floatArrayOf(1f, 0f, 0f), tags = listOf("errands")),
            note("a3", floatArrayOf(1f, 0f, 0f), tags = listOf("errands", "home")),
            note(betaText, floatArrayOf(0f, 1f, 0f)),
            note("b2", floatArrayOf(0f, 1f, 0f)),
            note("c-singleton", floatArrayOf(0f, 0f, 1f)),
            note("no-vector-yet", null),   // unembedded notes must be ignored, not crash
        )
        val clusters = Clusters.build(notes)

        assertEquals(2, clusters.size)
        assertEquals(listOf("a1", "a2", "a3"), clusters[0].notes.map { it.text })   // biggest first
        assertEquals(listOf(betaText, "b2"), clusters[1].notes.map { it.text })
        assertEquals("errands", clusters[0].label)                 // majority tag wins
        assertEquals(betaText.take(24), clusters[1].label)         // no tags/title → text prefix
    }

    @Test fun fewerThanTwoEmbeddedNotesIsEmpty() {
        assertTrue(Clusters.build(emptyList()).isEmpty())
        assertTrue(Clusters.build(listOf(note("a", floatArrayOf(1f, 0f)))).isEmpty())
        assertTrue(Clusters.build(listOf(note("a", floatArrayOf(1f, 0f)), note("b", null))).isEmpty())
    }

    @Test fun singleLinkageChainsThroughAMiddleNote() {
        // Unit vectors at 0°, 40°, 80° plus three zero-vector ballast notes: after centering,
        // d1~d2 and d2~d3 clear the 0.40 threshold but d1~d3 does not — single linkage must
        // still merge all three through the middle note.
        val d1 = floatArrayOf(1f, 0f)
        val d2 = floatArrayOf(0.766f, 0.643f)
        val d3 = floatArrayOf(0.174f, 0.985f)
        val ballast = floatArrayOf(0f, 0f)
        val notes = listOf(
            note("d1", d1), note("d2", d2), note("d3", d3),
            note("x1", ballast), note("x2", ballast), note("x3", ballast),
        )

        // Prove the chain premise with the same math Clusters uses.
        val mean = Embedder.mean(notes.map { it.embedding!! })!!
        assertTrue(Embedder.cosineCentered(d1, d2, mean) >= 0.40f)
        assertTrue(Embedder.cosineCentered(d2, d3, mean) >= 0.40f)
        assertTrue(Embedder.cosineCentered(d1, d3, mean) < 0.40f)

        val chained = Clusters.build(notes).first { c -> c.notes.any { it.text == "d1" } }
        assertEquals(setOf("d1", "d2", "d3"), chained.notes.map { it.text }.toSet())
    }
}
