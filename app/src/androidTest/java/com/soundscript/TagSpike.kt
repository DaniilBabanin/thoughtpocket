package com.soundscript

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.soundscript.AppPreferences
import com.soundscript.ai.LlmEngine
import com.soundscript.ai.MarkdownEngine
import com.soundscript.ai.TaggingEngine
import com.soundscript.ai.TitleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Diagnose why auto-tag returns empty: mimics the service sequence (title -> tag -> markdown)
 *  and logs the RAW tag-model output alongside the parsed tags. Logs `TAG`. */
class TagSpike {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val texts = listOf(
        "Add a reminder to meet Rodrigo today at 15 o'clock.",   // the untagged note
        "Grocery run I still need milk eggs and bread",
        "Standup notes the auth migration is done QA found a flaky test",
    )

    private fun tagPrompt(text: String) =
        "You label voice notes with short topic tags. Read the note and reply with ONLY " +
            "3 to 5 concise lowercase tags (one or two words each), comma-separated. No explanation.\n\n" +
            "Note:\n\"\"\"\n$text\n\"\"\""

    @Test
    fun tag() = runBlocking<Unit> {
        Log.i("TAG", "models=${LlmEngine.installed(ctx).map { it.name }}, autoTag=${AppPreferences(ctx).autoTag}")
        val model = LlmEngine.resolve(ctx, AppPreferences(ctx).tagModelFilename, "E2B")
        Log.i("TAG", "tag model=${model?.name}")
        for (t in texts) {
            // Mirror the service order so any sequencing effect shows up.
            val title = TitleEngine.suggest(ctx, t).getOrElse { "ERR:${it.message}" }
            val raw = LlmEngine.generate(ctx, tagPrompt(t), model).getOrElse { "ERR:${it.message}" }
            val tags = TaggingEngine.suggestTags(ctx, t).getOrElse { listOf("ERR:${it.message}") }
            val md = MarkdownEngine.toMarkdown(ctx, t).getOrElse { "ERR:${it.message}" }
            Log.i("TAG", "text='${t.take(45)}'")
            Log.i("TAG", "  title='$title' tags=$tags md.ok=${md.isNotBlank()}")
            Log.i("TAG", "  RAW_TAGS=${raw.take(250).replace("\n", " | ")}")
        }
    }
}
