package com.soundscript

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import com.soundscript.ai.Embedder
import com.soundscript.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.util.Optional

/**
 * Spike: does Gecko 110m (AI Edge RAG SDK) beat USE on the scale corpus? Test-only — the app
 * still uses USE. Push the model first:
 *   adb push tmp-gecko/gecko.tflite /sdcard/Android/data/com.soundscript/files/gecko/gecko.tflite
 *   adb push tmp-gecko/sentencepiece.model /sdcard/Android/data/com.soundscript/files/gecko/sentencepiece.model
 * then: tools/run-scaletest.sh gecko ; adb logcat -d -s SCALE
 */
class GeckoSpike {
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = 86_400_000L
    private val topic = HashMap<Long, String>()

    private fun asset(name: String) = testCtx.assets.open(name).bufferedReader().use { it.readText() }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun precision(top: List<Note>, want: String) = top.count { topic[it.id] == want }.toDouble() / top.size

    // Gecko uses asymmetric task types: queries get RETRIEVAL_QUERY, notes get RETRIEVAL_DOCUMENT.
    private var model: GeckoEmbeddingModel? = null
    private suspend fun embed(text: String, query: Boolean): FloatArray? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        val task = if (query) EmbedData.TaskType.RETRIEVAL_QUERY else EmbedData.TaskType.RETRIEVAL_DOCUMENT
        val req = EmbeddingRequest.create(listOf(EmbedData.create(text.take(4000), task, query)))
        runCatching { model!!.getEmbeddings(req).get().toFloatArray() }
            .onFailure { Log.w("SCALE", "GECKO embed fail: ${it.cause ?: it}") }.getOrNull()
    }

    @Test
    fun gecko() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val arr = JSONArray(asset("corpus.json"))
        val raw = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val id = (i + 1).toLong()
            raw.add(Note(id = id, createdAt = now - o.getInt("daysAgo") * day,
                text = o.getString("text"), tags = o.getJSONArray("tags").strings()))
            topic[id] = o.getString("topic")
        }
        val base = target.getExternalFilesDir("gecko")!!.absolutePath
        model = GeckoEmbeddingModel("$base/gecko.tflite", Optional.of("$base/sentencepiece.model"), false)

        var t = SystemClock.elapsedRealtime()
        val notes = raw.map { it.copy(embedding = embed(it.text, query = false)) }
        val embMs = SystemClock.elapsedRealtime() - t
        val ok = notes.count { it.embedding != null }
        Log.i("SCALE", "GECKO EMBED $ok/${notes.size} in ${embMs}ms (${embMs / notes.size}ms/note) dim=${notes.firstOrNull { it.embedding != null }?.embedding?.size}")
        if (ok == 0) { Log.i("SCALE", "GECKO embed produced nothing — aborting"); return@runBlocking }

        val mean = Embedder.mean(notes.mapNotNull { it.embedding })!!

        // ---- search (raw vs centered) ----
        val topicQ = JSONObject(asset("queries.json")).getJSONArray("topic")
        val precs = ArrayList<Double>()
        for (i in 0 until topicQ.length()) {
            val tq = topicQ.getJSONObject(i)
            val q = tq.getString("q"); val want = tq.getString("topic"); val k = tq.getInt("k")
            val qv = embed(q, query = true)!!
            val rawTop = notes.sortedByDescending { Embedder.cosine(qv, it.embedding!!) }.take(k)
            val cenTop = notes.sortedByDescending { Embedder.cosineCentered(qv, it.embedding!!, mean) }.take(k)
            precs.add(precision(rawTop, want))
            Log.i("SCALE", "GECKO SEARCH want=$want raw=${"%.2f".format(precision(rawTop, want))} cen=${"%.2f".format(precision(cenTop, want))} -> ${rawTop.map { topic[it.id] }}")
        }
        Log.i("SCALE", "GECKO search meanPrecision(raw)=${"%.2f".format(precs.average())}  [USE baseline 0.50]")

        // ---- relate ----
        for (want in listOf("groceries", "rust", "travel", "home", "family")) {
            val seed = notes.first { topic[it.id] == want }
            val rel = notes.filter { it.id != seed.id }
                .sortedByDescending { Embedder.cosine(seed.embedding!!, it.embedding!!) }.take(5)
            Log.i("SCALE", "GECKO RELATE $want same=${rel.count { topic[it.id] == want }}/5 -> ${rel.map { topic[it.id] }}")
        }

        // ---- cluster threshold sweep (raw + centered), weighted purity ----
        val cen = notes.map { n -> FloatArray(mean.size) { n.embedding!![it] - mean[it] } }
        fun sweep(useCen: Boolean, thr: Float): Pair<Int, Double> {
            val n = notes.size; val parent = IntArray(n) { it }
            fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
            for (a in 0 until n) for (b in a + 1 until n) {
                val s = if (useCen) Embedder.cosine(cen[a], cen[b]) else Embedder.cosine(notes[a].embedding!!, notes[b].embedding!!)
                if (s >= thr) parent[find(a)] = find(b)
            }
            val groups = (0 until n).groupBy { find(it) }.values.map { g -> g.map { notes[it] } }.filter { it.size >= 2 }
            val cov = groups.sumOf { it.size }
            val wp = if (cov == 0) 0.0 else groups.sumOf { c -> c.groupingBy { topic[it.id] }.eachCount().values.max() }.toDouble() / cov
            return groups.size to wp
        }
        for (thr in listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f)) {
            val (cr, wr) = sweep(false, thr); val (cc, wc) = sweep(true, thr)
            Log.i("SCALE", "GECKO CLUSTER thr=$thr raw{n=$cr wp=${"%.2f".format(wr)}} cen{n=$cc wp=${"%.2f".format(wc)}}  [USE cen@0.40 wp=0.50]")
        }
        model = null
    }

    /** GPU throughput + a deep dive into why the grocery query mis-ranks. */
    @Test
    fun gpuGrocery() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val arr = JSONArray(asset("corpus.json"))
        val raw = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val id = (i + 1).toLong()
            raw.add(Note(id = id, createdAt = now - o.getInt("daysAgo") * day,
                text = o.getString("text"), tags = o.getJSONArray("tags").strings()))
            topic[id] = o.getString("topic")
        }
        val base = target.getExternalFilesDir("gecko")!!.absolutePath

        // ---- GPU attempt ----
        model = runCatching { GeckoEmbeddingModel("$base/gecko.tflite", Optional.of("$base/sentencepiece.model"), true) }
            .onFailure { Log.w("SCALE", "GPU GECKO ctor failed: ${it.message}") }.getOrNull()
        var t = SystemClock.elapsedRealtime()
        val warm = if (model != null) embed("warm up the model", false) else null
        val gpuOk = warm != null
        Log.i("SCALE", "GPU GECKO warmupOk=$gpuOk ${SystemClock.elapsedRealtime() - t}ms dim=${warm?.size}")
        if (gpuOk) {
            t = SystemClock.elapsedRealtime()
            raw.take(30).forEach { embed(it.text, false) }
            Log.i("SCALE", "GPU GECKO throughput=${(SystemClock.elapsedRealtime() - t) / 30}ms/note  [CPU ~321ms]")
        } else {
            Log.i("SCALE", "GPU unusable — using CPU for grocery probe")
            model = GeckoEmbeddingModel("$base/gecko.tflite", Optional.of("$base/sentencepiece.model"), false)
        }

        // ---- embed all docs, probe grocery queries ----
        val notes = raw.map { it.copy(embedding = embed(it.text, false)) }.filter { it.embedding != null }
        val mean = Embedder.mean(notes.mapNotNull { it.embedding })!!
        val queries = listOf(
            "things to buy at the store", "groceries", "milk and eggs",
            "grocery shopping list", "food to buy this week", "what to buy at the supermarket"
        )
        for (q in queries) {
            val qv = embed(q, true)!!
            val top = notes.sortedByDescending { Embedder.cosineCentered(qv, it.embedding!!, mean) }.take(5)
            Log.i("SCALE", "GROCERY q='$q' p@5=${"%.2f".format(precision(top, "groceries"))} -> ${top.map { topic[it.id] }}")
        }
        // top-10 breakdown for the failing query
        val qv = embed("things to buy at the store", true)!!
        notes.map { it to Embedder.cosineCentered(qv, it.embedding!!, mean) }
            .sortedByDescending { it.second }.take(10)
            .forEach { (n, s) -> Log.i("SCALE", "  TOP ${"%.3f".format(s)} ${topic[n.id]} :: ${n.text.take(48)}") }
        model = null
    }
}
