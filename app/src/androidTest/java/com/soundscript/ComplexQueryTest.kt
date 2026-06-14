package com.soundscript

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscript.ai.Embedder
import com.soundscript.ai.LlmEngine
import com.soundscript.ai.NotesAnalysis
import com.soundscript.data.Note
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — complex queries over the whole corpus: checklist-aware ("which groceries did I buy
 * last week" → reads "- [x]" within a time window), still-open ("- [ ]"), and topic synthesis.
 * Answered via [NotesAnalysis.ask] (RAG over Markdown + per-note dates). In-memory; logs `CQ`.
 *   tools/run-scaletest.sh ; adb shell am instrument -e class com.soundscript.ComplexQueryTest \
 *     -w com.soundscript.test/androidx.test.runner.AndroidJUnitRunner ; adb logcat -d -s CQ
 * Needs Gemma E4B + the Gecko embedder installed.
 */
class ComplexQueryTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val day = 86_400_000L

    private fun asset(name: String) = testCtx.assets.open(name).bufferedReader().use { it.readText() }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    @Test
    fun complex() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val arr = JSONArray(asset("corpus.json"))
        val raw = ArrayList<Note>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            raw.add(Note(id = (i + 1).toLong(), createdAt = now - o.getInt("daysAgo") * day,
                text = o.getString("text"), markdown = o.optString("markdown", ""),
                tags = o.getJSONArray("tags").strings()))
        }
        val t0 = SystemClock.elapsedRealtime()
        val notes = raw.map { it.copy(embedding = Embedder.embed(target, it.text)) }
        Log.i("CQ", "corpus ${notes.size} notes embedded in ${SystemClock.elapsedRealtime() - t0}ms, " +
            "models=${LlmEngine.installed(target).map { it.name }}")

        val failures = ArrayList<String>()
        val complex = JSONObject(asset("queries.json")).getJSONArray("complex")
        for (i in 0 until complex.length()) {
            val c = complex.getJSONObject(i)
            val q = c.getString("q")
            val kind = c.getString("kind")
            val expect = c.getJSONArray("expect").strings()
            val absent = c.optJSONArray("absent")?.strings() ?: emptyList()

            val t = SystemClock.elapsedRealtime()
            val ans = NotesAnalysis.ask(target, notes, q).getOrElse { "ERROR:${it.message}" }
            val ms = SystemClock.elapsedRealtime() - t
            val lc = ans.lowercase()

            val hits = expect.filter { lc.contains(it.lowercase()) }
            val leaks = absent.filter { lc.contains(it.lowercase()) }
            val ok = when (kind) {
                "contains_all" -> hits.size == expect.size
                "all_and_none" -> hits.size == expect.size && leaks.isEmpty()
                "contains_any" -> hits.size >= c.optInt("min", 1)
                else -> false
            }
            if (!ok) failures.add("$kind '$q' hits=${hits.size}/${expect.size} leaks=$leaks")
            Log.i("CQ", "[$kind] '$q' ${if (ok) "PASS" else "FAIL"} ${ms}ms " +
                "hits=$hits miss=${expect - hits.toSet()} leaks=$leaks")
            Log.i("CQ", "    ans=${ans.take(400).replace("\n", " | ")}")
        }

        Log.i("CQ", "==== ${failures.size} failures ====")
        failures.forEach { Log.i("CQ", "  FAIL $it") }
        assertTrue("complex-query failures: $failures", failures.isEmpty())
    }
}
