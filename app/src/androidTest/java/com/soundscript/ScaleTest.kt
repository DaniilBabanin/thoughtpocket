package com.soundscript

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscript.ai.Clusters
import com.soundscript.ai.Embedder
import com.soundscript.ai.LlmEngine
import com.soundscript.ai.NotesAnalysis
import com.soundscript.ai.TaggingEngine
import com.soundscript.ai.TitleEngine
import com.soundscript.data.Note
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scale test: load the ~300-note ground-truth corpus and exercise every feature for
 * correctness + latency. In-memory (no DB) so the user's real notes are untouched.
 * Logs `SCALE` lines:
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.soundscript.ScaleTest
 *   adb logcat -d -s SCALE
 * Needs a Gemma model installed (tagging/title/RAG). Embeddings use the bundled USE model.
 */
class ScaleTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val day = 86_400_000L

    private fun asset(name: String) = testCtx.assets.open(name).bufferedReader().use { it.readText() }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private val topic = HashMap<Long, String>()

    /** Load corpus, embed all notes; returns (notes, corpus-mean). Fills [topic]. */
    private suspend fun embedCorpus(): Pair<List<Note>, FloatArray> {
        val now = System.currentTimeMillis()
        val arr = JSONArray(asset("corpus.json"))
        val raw = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = (i + 1).toLong()
            raw.add(Note(id = id, createdAt = now - o.getInt("daysAgo") * day,
                text = o.getString("text"), tags = o.getJSONArray("tags").strings()))
            topic[id] = o.getString("topic")
        }
        val t = SystemClock.elapsedRealtime()
        val notes = raw.map { it.copy(embedding = Embedder.embed(target, it.text)) }
        Log.i("SCALE", "EMBED ${notes.size} in ${SystemClock.elapsedRealtime() - t}ms")
        return notes to Embedder.mean(notes.mapNotNull { it.embedding })!!
    }

    private fun precision(top: List<Note>, want: String) =
        top.count { topic[it.id] == want }.toDouble() / top.size

    /** Diagnostic only (no LLM): raw vs centered search ranking + cluster threshold sweep. */
    @Test
    fun tuning() = runBlocking<Unit> {
        val (notes, mean) = embedCorpus()
        val centered = notes.map { n -> FloatArray(n.embedding!!.size) { n.embedding[it] - mean[it] } }
        val idx = notes.indices.associateBy { notes[it].id }

        val topicQ = JSONObject(asset("queries.json")).getJSONArray("topic")
        for (i in 0 until topicQ.length()) {
            val tq = topicQ.getJSONObject(i)
            val query = tq.getString("q"); val want = tq.getString("topic"); val k = tq.getInt("k")
            val qv = Embedder.embed(target, query)!!
            val qc = FloatArray(qv.size) { qv[it] - mean[it] }
            val rawTop = notes.sortedByDescending { Embedder.cosine(qv, it.embedding!!) }.take(k)
            val cenTop = notes.sortedByDescending { Embedder.cosine(qc, centered[idx[it.id]!!]) }.take(k)
            Log.i("SCALE", "SEARCH want=$want raw=${"%.2f".format(precision(rawTop, want))} cen=${"%.2f".format(precision(cenTop, want))}" +
                " rawTop=${rawTop.map { topic[it.id] }} cenTop=${cenTop.map { topic[it.id] }}")
        }

        // Cluster threshold sweep (centered cosine on precomputed centered vectors).
        fun groups(thr: Float): List<List<Note>> {
            val n = notes.size; val parent = IntArray(n) { it }
            fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
            for (a in 0 until n) for (b in a + 1 until n)
                if (Embedder.cosine(centered[a], centered[b]) >= thr) parent[find(a)] = find(b)
            return (0 until n).groupBy { find(it) }.values.map { g -> g.map { notes[it] } }
        }
        for (thr in listOf(0.35f, 0.40f, 0.45f, 0.50f, 0.55f)) {
            val multi = groups(thr).filter { it.size >= 2 }
            val purity = if (multi.isEmpty()) 0.0 else
                multi.map { g -> g.groupingBy { topic[it.id] }.eachCount().values.max().toDouble() / g.size }.average()
            Log.i("SCALE", "CLUSTER thr=$thr clusters=${multi.size} covered=${multi.sumOf { it.size }}/${notes.size} avgPurity=${"%.2f".format(purity)}")
        }
    }

    @Test
    fun scale() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val arr = JSONArray(asset("corpus.json"))
        val topic = HashMap<Long, String>()
        val raw = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = (i + 1).toLong()
            raw.add(Note(id = id, createdAt = now - o.getInt("daysAgo") * day,
                text = o.getString("text"), tags = o.getJSONArray("tags").strings()))
            topic[id] = o.getString("topic")
        }
        Log.i("SCALE", "corpus: ${raw.size} notes, ${topic.values.toSet().size} topics, models=${LlmEngine.installed(target).map { it.name }}")

        val failures = ArrayList<String>()

        // ---- embed all (perf) ----
        var t = SystemClock.elapsedRealtime()
        val notes = raw.map { it.copy(embedding = Embedder.embed(target, it.text)) }
        val embMs = SystemClock.elapsedRealtime() - t
        val okEmb = notes.count { it.embedding != null }
        Log.i("SCALE", "EMBED $okEmb/${notes.size} in ${embMs}ms (${embMs / notes.size}ms/note)")
        if (okEmb != notes.size) failures.add("embed $okEmb/${notes.size}")
        val mean = Embedder.mean(notes.mapNotNull { it.embedding })!!

        // ---- search precision@k (centered cosine, mirrors the app) ----
        // NOTE: USE retrieves distinctive topics (rust/python) perfectly but mishandles short/
        // generic notes; we gate on MEAN precision as a regression guard. Raise the bar when
        // the embedder is upgraded to EmbeddingGemma. Per-query numbers are logged for tracking.
        val q = JSONObject(asset("queries.json"))
        val topicQ = q.getJSONArray("topic")
        val precs = ArrayList<Double>()
        for (i in 0 until topicQ.length()) {
            val tq = topicQ.getJSONObject(i)
            val query = tq.getString("q"); val want = tq.getString("topic"); val k = tq.getInt("k")
            val qv = Embedder.embed(target, query)!!
            t = SystemClock.elapsedRealtime()
            val top = notes.sortedByDescending { Embedder.cosineCentered(qv, it.embedding!!, mean) }.take(k)
            val ms = SystemClock.elapsedRealtime() - t
            val prec = top.count { topic[it.id] == want }.toDouble() / k
            precs.add(prec)
            Log.i("SCALE", "SEARCH q='$query' want=$want p@$k=${"%.2f".format(prec)} ${ms}ms -> ${top.map { topic[it.id] }}")
        }
        val meanPrec = precs.average()
        Log.i("SCALE", "SEARCH meanPrecision=${"%.2f".format(meanPrec)} (USE baseline; raise after EmbeddingGemma)")
        if (meanPrec < 0.4) failures.add("search meanPrecision ${"%.2f".format(meanPrec)}")

        // ---- relate (raw cosine, mirrors detail screen) ----
        for (want in listOf("groceries", "rust", "travel")) {
            val seed = notes.first { topic[it.id] == want }
            val rel = notes.filter { it.id != seed.id }
                .sortedByDescending { Embedder.cosine(seed.embedding!!, it.embedding!!) }.take(5)
            val same = rel.count { topic[it.id] == want }
            Log.i("SCALE", "RELATE $want top5 same=$same/5 -> ${rel.map { topic[it.id] }}")
            if (same < 3) failures.add("relate $want $same/5")
        }

        // ---- clusters purity ----
        // weightedPurity = fraction of clustered notes that sit with their topic's majority
        // (honest — a big impure blob drags it down). avgPurity (unweighted) is for reference.
        t = SystemClock.elapsedRealtime()
        val clusters = Clusters.build(notes)
        val clMs = SystemClock.elapsedRealtime() - t
        val maxes = clusters.map { c -> c.notes.groupingBy { topic[it.id] }.eachCount().values.max() }
        val covered = clusters.sumOf { it.notes.size }
        val weightedPurity = if (covered == 0) 0.0 else maxes.sum().toDouble() / covered
        val avgPurity = if (clusters.isEmpty()) 0.0 else clusters.indices.map { maxes[it].toDouble() / clusters[it].notes.size }.average()
        Log.i("SCALE", "CLUSTERS ${clusters.size} clusters, $covered/${notes.size} notes, weightedPurity=${"%.2f".format(weightedPurity)} avgPurity=${"%.2f".format(avgPurity)} in ${clMs}ms")
        clusters.forEach { c -> Log.i("SCALE", "  cluster '${c.label}' n=${c.notes.size} ${c.notes.groupingBy { topic[it.id] }.eachCount()}") }
        if (weightedPurity < 0.45) failures.add("cluster weightedPurity ${"%.2f".format(weightedPurity)}")

        // ---- tagging + titles (sample; uses tag model E2B) ----
        for (sample in listOf(notes.first { topic[it.id] == "rust" }, notes.first { topic[it.id] == "travel" })) {
            t = SystemClock.elapsedRealtime()
            val tags = TaggingEngine.suggestTags(target, sample.text).getOrElse { listOf("ERR") }
            val title = TitleEngine.suggest(target, sample.text).getOrElse { "ERR" }
            val ms = SystemClock.elapsedRealtime() - t
            Log.i("SCALE", "TAG/TITLE ${topic[sample.id]} ${ms}ms tags=$tags title='$title'")
            if (tags.isEmpty() || title.isBlank()) failures.add("tag/title ${topic[sample.id]}")
        }

        // ---- RAG factual Q&A (uses analysis model E4B) ----
        val factual = q.getJSONArray("factual")
        for (i in 0 until factual.length()) {
            val fq = factual.getJSONObject(i)
            val query = fq.getString("q")
            val expects = fq.getJSONArray("expectContains").strings()
            t = SystemClock.elapsedRealtime()
            val ans = NotesAnalysis.ask(target, notes, query).getOrElse { "ERROR:${it.message}" }
            val ms = SystemClock.elapsedRealtime() - t
            val hit = expects.any { ans.contains(it, ignoreCase = true) }
            Log.i("SCALE", "RAG q='$query' ${ms}ms ${if (hit) "PASS" else "FAIL"} expect=$expects ans=${ans.take(180).replace("\n", " ")}")
            if (!hit) failures.add("rag '$query'")
        }

        Log.i("SCALE", "==== ${failures.size} failures ====")
        failures.forEach { Log.i("SCALE", "  FAIL $it") }
        assertTrue("scale failures: $failures", failures.isEmpty())
    }
}
