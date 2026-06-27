package com.thoughtpocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Proves the WER ruler ([Wer]) before the long-form bench trusts it to measure (verification integrity:
 * prove the ruler before measuring). Hand-checked toy cases pin the algorithm; the last two cases run it
 * against the *actual* committed long-form reference transcripts so the ruler is exercised on real,
 * 2000-word input — exactly what `LongFormBench` feeds it on-device — with no hardware needed.
 */
class WerTest {
    private val eps = 1e-9

    @Test fun identicalIsZero() {
        assertEquals(0.0, Wer.rate("the quick brown fox", "the quick brown fox"), eps)
    }

    @Test fun oneSubstitutionInFive() {
        // 1 edit / 5 ref words = 0.2
        assertEquals(0.2, Wer.rate("a b c d e", "a b X d e"), eps)
    }

    @Test fun oneDeletionAndOneInsertion() {
        assertEquals(0.2, Wer.rate("a b c d e", "a b d e"), eps)        // dropped "c"
        assertEquals(0.2, Wer.rate("a b c d e", "a b c X d e"), eps)    // inserted "X"
    }

    @Test fun caseAndPunctuationIgnored() {
        assertEquals(0.0, Wer.rate("Ask not, what your COUNTRY!", "ask not what your country"), eps)
    }

    @Test fun emptyEdgeCases() {
        assertEquals(0.0, Wer.rate("", ""), eps)
        assertEquals(1.0, Wer.rate("", "anything here"), eps)          // all insertions, ref empty → 1.0
        assertEquals(1.0, Wer.rate("a b c", ""), eps)                  // all deletions
    }

    @Test fun emptyHypIsAllDeletions() {
        val r = Wer.score("one two three four", "")
        assertEquals(0, r.sub); assertEquals(4, r.del); assertEquals(0, r.ins)
        assertEquals(1.0, r.wer, eps)
    }

    @Test fun breakdownAttributesEdits() {
        // ref: a b c d   hyp: a X c d e  → 1 substitution (b→X) + 1 insertion (e). distance 2 / 4 = 0.5
        val r = Wer.score("a b c d", "a X c d e")
        assertEquals(4, r.refLen); assertEquals(5, r.hypLen)
        assertEquals(1, r.sub); assertEquals(0, r.del); assertEquals(1, r.ins)
        assertEquals(0.5, r.wer, eps)
    }

    @Test fun halvesSplitsDriftByRefMidpoint() {
        // identical → flat
        assertEquals(0.0, Wer.halves("a b c d e f", "a b c d e f").first, eps)
        assertEquals(0.0, Wer.halves("a b c d e f", "a b c d e f").second, eps)
        // all 3 errors in the 2nd half (ref n=6, mid=3): first half clean, second half fully wrong
        val (h1, h2) = Wer.halves("a b c d e f", "a b c X Y Z")
        assertEquals(0.0, h1, eps)
        assertEquals(1.0, h2, eps)
    }

    // ---- against the real committed transcripts (no device needed) ----

    private fun transcript(name: String): String? {
        // Unit tests run with the module dir (app/) as CWD.
        for (p in listOf("src/androidTest/assets/longform/$name", "app/src/androidTest/assets/longform/$name")) {
            val f = File(p)
            if (f.exists()) return f.readText()
        }
        return null
    }

    @Test fun realTranscriptSelfWerIsZero() {
        val jfk = transcript("jfk_rice.txt")
        assumeTrue("jfk_rice.txt not found from CWD ${File(".").absolutePath}", jfk != null)
        assertEquals(0.0, Wer.rate(jfk!!, jfk), eps)
        assertTrue("transcript should be a long speech", Wer.normalize(jfk).size > 1500)
    }

    @Test fun realTranscriptKnownEditsCountExactly() {
        val eis = transcript("eisenhower_farewell.txt")
        assumeTrue("eisenhower_farewell.txt not found", eis != null)
        val words = Wer.normalize(eis!!)
        val n = words.size
        val ref = words.joinToString(" ")
        val hyp = words.toMutableList().also {
            it[5] = "zzzqqq1"; it[300] = "zzzqqq2"; it[600] = "zzzqqq3"   // 3 substitutions (nonsense tokens)
        }.joinToString(" ")
        val r = Wer.score(ref, hyp)
        assertEquals(n, r.refLen)
        assertEquals(3, r.sub); assertEquals(0, r.del); assertEquals(0, r.ins)
        assertEquals(3.0 / n, r.wer, eps)
    }
}
