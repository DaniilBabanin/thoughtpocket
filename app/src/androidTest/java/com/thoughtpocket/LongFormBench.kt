package com.thoughtpocket

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Long-form transcription baseline: feeds full presidential-speech audio (JFK Rice "We choose to go to the
 * Moon", 17:40; Eisenhower farewell, 15:30 — both U.S. public domain) through each on-device engine and
 * reports accuracy + speed + the failure modes that only surface at length.
 *
 * What this measures — and what it does NOT. These are clean, formal oratory, the OPPOSITE of the app's
 * messy ADHD-capture distribution. So it gives (a) a LONG-AUDIO STRESS test (OOM / repetition loops /
 * truncation / Moonshine chunk-boundary splits over 15–18 min) and (b) a CLEAN-SPEECH ACCURACY CEILING. It
 * is NOT the representative WER the fine-tune ship-gate needs (that still owes a messy-capture set). WER is a
 * FLOOR: the references are audio-aligned Wikisource transcripts, but published transcripts still differ
 * from delivery (ad-libs; number formatting like "1970" vs "nineteen seventy") and that diff lands in WER as
 * transcript-mismatch, not engine error. Read the dumped hypotheses (below) to attribute it.
 *
 * Fixtures: 16 kHz mono s16le PCM pushed to <externalFilesDir>/bench/<id>.pcm — too big to commit; see
 * tools/fetch-bench-audio.sh + tools/push-bench.sh. References committed at androidTest/assets/longform/.
 * Whisper ggml models load from the internal models dir (push via run-as — tools/push-bench.sh); Moonshine
 * reuses its installed dirs. Run each engine in its OWN invocation so peak RSS isolates per model:
 *
 *   adb shell am instrument -w -e class com.thoughtpocket.LongFormBench#whisperBase \
 *     com.thoughtpocket.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s LONGBENCH
 *
 * Each run logs a `RESULT` line (grep LONGBENCH) and dumps the full hypothesis to <externalFilesDir>/bench/
 * out/<id>__<engine>.txt so WER can be cross-checked with an independent offline ruler and the transcript
 * eyeballed for alignment.
 */
class LongFormBench {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private fun now() = SystemClock.elapsedRealtime()

    private data class Fixture(val id: String, val title: String, val audioSec: Int)
    private val fixtures = listOf(
        Fixture("jfk_rice", "JFK — We choose to go to the Moon (Rice, 1962)", 1060),
        Fixture("eisenhower_farewell", "Eisenhower — Farewell Address (1961)", 930),
    )

    // One @Test per model → run separately so peak RSS isolates per process (the whole class in one run
    // accumulates peaks across models). Whisper runs CPU + VAD like the final pass, but at BEAM (the quality
    // ceiling) — production defaults to greedy, so these are the upper-accuracy / upper-cost numbers.
    @Test fun whisperBase() = fixtures.forEach { runWhisper(it, ModelManager.BuiltInModel.BASE_Q5) }
    @Test fun whisperSmall() = fixtures.forEach { runWhisper(it, ModelManager.BuiltInModel.SMALL_Q5) }
    @Test fun moonshineBase() = fixtures.forEach { runMoonshine(it, ModelManager.StreamingModel.MOONSHINE_BASE) }
    @Test fun moonshineTiny() = fixtures.forEach { runMoonshine(it, ModelManager.StreamingModel.MOONSHINE_TINY) }

    // ---- engines ----

    private fun runWhisper(fx: Fixture, model: ModelManager.BuiltInModel) = runBlocking {
        val pcm = loadPcm(fx) ?: return@runBlocking
        val modelFile = ModelManager.fileFor(ctx, model)
        if (!modelFile.exists()) { Log.e(TAG, "[${fx.id}/${model.name}] whisper model missing at $modelFile — push it"); return@runBlocking }
        val prefs = benchPrefs()
        try {
            // Warm up on 1 s (pays model load + JIT) so the timed call is steady-state compute.
            // BEAM = the quality ceiling; production defaults to greedy (AppPreferences.highQuality=false),
            // which is faster + slightly less accurate. useVad=true mirrors the final pass (trims silence).
            WhisperTranscriber.transcribe(ctx, model, pcm.copyOfRange(0, 16_000), prefs, highQuality = BEAM, useVad = true)
            val t0 = now()
            val hyp = WhisperTranscriber.transcribe(ctx, model, pcm, prefs, highQuality = BEAM, useVad = true)
            val fullMs = now() - t0
            report(fx, "whisper-${model.name.lowercase()}", hyp, fullMs, modelFile.length() / 1_000_000)
        } finally { WhisperTranscriber.release() }
    }

    private fun runMoonshine(fx: Fixture, model: ModelManager.StreamingModel) = runBlocking {
        val pcm = loadPcm(fx) ?: return@runBlocking
        if (!ModelManager.isDownloaded(ctx, model)) { Log.e(TAG, "[${fx.id}/${model.name}] moonshine not installed"); return@runBlocking }
        val prefs = benchPrefs()
        try {
            MoonshineTranscriber.transcribe(ctx, model, pcm.copyOfRange(0, 16_000), prefs, highQuality = false, useVad = false) // warm
            val t0 = now()
            val hyp = MoonshineTranscriber.transcribe(ctx, model, pcm, prefs, highQuality = false, useVad = false)
            val fullMs = now() - t0
            report(fx, "moonshine-${model.name.removePrefix("MOONSHINE_").lowercase()}", hyp, fullMs, model.approxSizeMb.toLong())
        } finally { MoonshineTranscriber.release() }
    }

    // ---- reporting ----

    private fun report(fx: Fixture, engine: String, hyp: String, fullMs: Long, modelMb: Long) {
        val ref = loadRef(fx)
        val s = Wer.score(ref, hyp)
        val (wer1, wer2) = Wer.halves(ref, hyp)   // drift: 1st-half vs 2nd-half WER (artifact-free)
        val rtf = fullMs.toDouble() / (fx.audioSec * 1000.0)
        val maxRepeat = maxConsecutiveNgramRepeat(Wer.normalize(hyp), 4)
        val lenRatio = if (s.refLen > 0) s.hypLen.toDouble() / s.refLen else 0.0
        dump(fx.id, engine, hyp)
        Log.i(TAG, "RESULT ${fx.id} $engine | WER=${pct(s.wer)} " +
            "(sub=${s.sub} del=${s.del} ins=${s.ins} ref=${s.refLen} hyp=${s.hypLen} lenRatio=${"%.2f".format(lenRatio)}) " +
            "| full=${fullMs}ms rtf=${"%.3f".format(rtf)} model=${modelMb}MB peakRss=${vmHwmMb()}MB " +
            "maxRepeat4gram=$maxRepeat drift(1st/2nd half WER)=${pct(wer1)}/${pct(wer2)}")
    }

    /** Whisper repetition-loop signature: max times any 4-gram repeats back-to-back (≥3 ⇒ a loop). */
    private fun maxConsecutiveNgramRepeat(toks: List<String>, n: Int): Int {
        if (toks.size < 2 * n) return 0
        var best = 0
        var i = 0
        while (i + n <= toks.size) {
            var reps = 1
            while (i + (reps + 1) * n <= toks.size && (0 until n).all { toks[i + it] == toks[i + reps * n + it] }) reps++
            if (reps > best) best = reps
            i++
        }
        return best
    }

    private fun vmHwmMb(): Long = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1L
    }.getOrDefault(-1L)

    private fun pct(d: Double) = "%.1f%%".format(d * 100)

    // ---- fixtures ----

    // Internal app storage: the only location both `adb push … run-as cp` and the app can reliably share
    // on Android 11+ (shell-pushed files under the app's *external* dir are invisible to the app's per-app
    // mount). Same path the whisper ggml use. PCM in via tools/push-bench.sh; hypotheses out, pulled by run-as.
    private fun benchDir() = File(ctx.filesDir, "bench").apply { mkdirs() }

    private fun loadPcm(fx: Fixture): FloatArray? {
        val f = File(benchDir(), "${fx.id}.pcm")
        if (!f.exists()) { Log.e(TAG, "PCM missing: $f — run tools/push-bench.sh"); return null }
        val b = f.readBytes()
        return FloatArray(b.size / 2) { i ->
            val lo = b[2 * i].toInt() and 0xFF
            val hi = b[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    private fun loadRef(fx: Fixture): String =
        testCtx.assets.open("longform/${fx.id}.txt").use { it.readBytes().toString(Charsets.UTF_8) }

    private fun dump(id: String, engine: String, hyp: String) {
        val out = File(benchDir(), "out").apply { mkdirs() }
        File(out, "${id}__$engine.txt").writeText(hyp)
    }

    /** Deterministic bench settings: English, no translate, beam ("quality" final pass). */
    private fun benchPrefs() = AppPreferences(ctx).apply {
        language = "en"; translateToEnglish = false; highQuality = true
    }

    companion object {
        private const val TAG = "LONGBENCH"
        private const val BEAM = true   // highQuality=beam search: the accuracy ceiling (prod default = greedy)
    }
}
