package com.thoughtpocket

/**
 * Word Error Rate — the shared transcription-accuracy ruler for the on-device benches ([Bench],
 * `LongFormBench`) and their JVM unit test (`WerTest`). Pure and deterministic with no Android deps, so the
 * ruler can be *proven* in `app/src/test` before it is trusted to *measure* in `app/src/androidTest`
 * (verification integrity: a measurement is only as trustworthy as the ruler producing it).
 *
 * WER = (substitutions + deletions + insertions) / referenceWords, after normalization (lowercase, strip
 * punctuation, collapse whitespace). Standard word-level Levenshtein. The headline [rate] equals the edit
 * distance / ref length regardless of how ties are broken; [score] additionally backtraces the edit path to
 * split that distance into sub/del/ins, which lets a long-form baseline tell *deletions* (dropped/truncated
 * speech) from *insertions* (repetition loops / hallucination) — the failure modes that only show at length.
 */
object Wer {
    /** Lowercase, strip everything but [a-z0-9 ], collapse whitespace → word tokens. */
    fun normalize(s: String): List<String> =
        s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }

    data class Result(
        val wer: Double,
        val sub: Int,
        val del: Int,
        val ins: Int,
        val refLen: Int,
        val hypLen: Int,
    )

    /** WER plus the sub/del/ins breakdown (via Levenshtein backtrace). */
    fun score(ref: String, hyp: String): Result {
        val r = normalize(ref)
        val h = normalize(hyp)
        if (r.isEmpty()) return Result(if (h.isEmpty()) 0.0 else 1.0, 0, 0, h.size, 0, h.size)
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            val cost = if (r[i - 1] == h[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        }
        // Backtrace to attribute the edit distance to sub / del / ins.
        var i = r.size; var j = h.size; var sub = 0; var del = 0; var ins = 0
        while (i > 0 || j > 0) {
            val diag = if (i > 0 && j > 0) d[i - 1][j - 1] + (if (r[i - 1] == h[j - 1]) 0 else 1) else Int.MAX_VALUE
            val up = if (i > 0) d[i - 1][j] + 1 else Int.MAX_VALUE
            when {
                diag <= up && diag <= (if (j > 0) d[i][j - 1] + 1 else Int.MAX_VALUE) -> {
                    if (r[i - 1] != h[j - 1]) sub++
                    i--; j--
                }
                up <= (if (j > 0) d[i][j - 1] + 1 else Int.MAX_VALUE) -> { del++; i-- }
                else -> { ins++; j-- }
            }
        }
        return Result((sub + del + ins).toDouble() / r.size, sub, del, ins, r.size, h.size)
    }

    /** Headline WER only. */
    fun rate(ref: String, hyp: String): Double = score(ref, hyp).wer

    /**
     * Drift probe: WER of the first vs second half of the reference, attributed via the *global* alignment
     * (not a separate windowed decode). A clean speech is flat across halves; a big 1st→2nd jump means errors
     * concentrate later — either real decay over a long decode, or harder content in the back half. This is
     * artifact-free, unlike comparing a re-decoded audio window to a proportional ref prefix (speech rate
     * isn't constant, so that boundary is off by tens of words — pure noise).
     */
    fun halves(ref: String, hyp: String): Pair<Double, Double> {
        val r = normalize(ref)
        val h = normalize(hyp)
        val n = r.size
        if (n < 2) return 0.0 to 0.0
        val d = Array(n + 1) { IntArray(h.size + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..n) for (j in 1..h.size) {
            val cost = if (r[i - 1] == h[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        }
        val edits = IntArray(n + 1) // edits attributed to each ref index (insertions → boundary before i)
        var i = n; var j = h.size
        while (i > 0 || j > 0) {
            val diag = if (i > 0 && j > 0) d[i - 1][j - 1] + (if (r[i - 1] == h[j - 1]) 0 else 1) else Int.MAX_VALUE
            val up = if (i > 0) d[i - 1][j] + 1 else Int.MAX_VALUE
            val left = if (j > 0) d[i][j - 1] + 1 else Int.MAX_VALUE
            when {
                diag <= up && diag <= left -> { if (r[i - 1] != h[j - 1]) edits[i - 1]++; i--; j-- }
                up <= left -> { edits[i - 1]++; i-- }
                else -> { edits[i]++; j-- }
            }
        }
        val mid = n / 2
        val first = (0 until mid).sumOf { edits[it] }.toDouble() / mid
        val second = (mid..n).sumOf { edits[it] }.toDouble() / (n - mid)
        return first to second
    }
}
