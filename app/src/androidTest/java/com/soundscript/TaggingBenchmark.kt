package com.soundscript

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscript.ai.LlmEngine
import com.soundscript.ai.TaggingEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * On-device benchmark of AI tagging across every installed Gemma model and a set of
 * synthetic notes of varying size and theme. Logs `BENCH` lines (read via logcat):
 *   adb shell am instrument -w -e class com.soundscript.TaggingBenchmark \
 *     com.soundscript.test/androidx.test.runner.AndroidJUnitRunner
 * or ./gradlew :app:connectedDebugAndroidTest, then `adb logcat -d -s BENCH`.
 */
class TaggingBenchmark {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // (theme/size, text) — sizes span ~5 to ~200 words; themes are deliberately varied.
    private val notes = listOf(
        "reminder/tiny" to
            "Call the dentist tomorrow morning.",
        "grocery/short" to
            "Shopping list for the week: cucumber, tomato, apple, sausage, milk, bread and some coffee.",
        "work/medium" to
            "Standup: the backend team finished the auth migration and merged it to main. " +
            "QA found a flaky test in the payments module that we need to fix before the release on Friday. " +
            "I'll pair with Sara on the rate limiter and we should demo the new dashboard to the client next week.",
        "travel/medium" to
            "Lisbon trip in October: book the flights before prices go up, find a hotel near Alfama, " +
            "reserve a table at the seafood place Ana recommended, and plan a day trip to Sintra. " +
            "Remember to renew the travel insurance and download offline maps.",
        "tech/long" to
            "Idea for the notes app architecture: keep everything on device for privacy. Whisper handles " +
            "transcription, a small Gemma model does tagging. Store notes in Room and embed each note with a " +
            "local embedding model so we can cluster related notes and surface them by similarity. Later add a " +
            "weekly digest that summarizes the last seven days, and a graph view that links notes sharing tags. " +
            "Watch out for battery and thermals when the LLM runs; prefer GPU and cache the compiled program.",
        "health/long" to
            "Morning journal: ran 5k along the river, felt strong, pace around five minutes per kilometer. " +
            "Slept badly though, maybe too much coffee yesterday. Knee was a little sore on the downhill so I " +
            "should stretch more and book a physio check. Diet was decent, lots of vegetables, but I snacked late. " +
            "Plan: strength session tomorrow, early night, and meal prep on Sunday to avoid the late snacking.",
    )

    @Test
    fun benchmark() = runBlocking<Unit> {
        val models = LlmEngine.installed(ctx)
        Log.i("BENCH", "==== models: ${models.map { it.name }} ====")
        for (model in models) {
            AppPreferences(ctx).llmModelFilename = model.name
            LlmEngine.release()

            val t0 = SystemClock.elapsedRealtime()
            val warm = TaggingEngine.suggestTags(ctx, "warm up the model")
            val loadMs = SystemClock.elapsedRealtime() - t0
            Log.i("BENCH", "LOAD model=${model.name} backend=${LlmEngine.activeBackend} " +
                "loadMs=$loadMs warmupOk=${warm.isSuccess}")

            for ((theme, text) in notes) {
                val t = SystemClock.elapsedRealtime()
                val tags = TaggingEngine.suggestTags(ctx, text)
                    .getOrElse { listOf("ERROR:${it.message}") }
                val ms = SystemClock.elapsedRealtime() - t
                Log.i("BENCH", "RESULT model=${model.name} theme=$theme words=${text.split(" ").size} " +
                    "ms=$ms tags=$tags")
            }
            LlmEngine.release()
        }
        Log.i("BENCH", "==== done ====")
    }
}
