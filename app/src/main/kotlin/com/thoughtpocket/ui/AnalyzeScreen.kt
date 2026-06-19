package com.thoughtpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thoughtpocket.ai.Clusters
import com.thoughtpocket.ai.NotesAnalysis
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.ui.theme.GlassCard
import com.thoughtpocket.ui.theme.GlassTextField
import com.thoughtpocket.ui.theme.ReachChip
import com.thoughtpocket.ui.theme.ReachShapes
import com.thoughtpocket.ui.theme.SectionTitle
import kotlinx.coroutines.launch

private const val DAY = 86_400_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyzeScreen(bottomSpace: Dp, wide: Boolean) {
    val context = LocalContext.current
    val dao = remember { NotesDb.get(context).notes() }
    val notes by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val allTags = notes.flatMap { it.tags }.distinct().sorted()
    val clusters = remember(notes) { Clusters.build(notes) }

    var sel by remember { mutableStateOf("All") } // All | week | month | tag:<t> | cluster:<i>
    var prompt by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val now = System.currentTimeMillis()
    val scoped = when {
        sel == "week" -> notes.filter { it.createdAt >= now - 7 * DAY }
        sel == "month" -> notes.filter { it.createdAt >= now - 30 * DAY }
        sel.startsWith("tag:") -> notes.filter { sel.removePrefix("tag:") in it.tags }
        sel.startsWith("cluster:") -> clusters.getOrNull(sel.removePrefix("cluster:").toInt())?.notes.orEmpty()
        else -> notes
    }

    fun run(q: String) {
        if (q.isBlank() || running) return
        if (!llmReadyOrToast(context)) return
        scope.launch {
            running = true; result = null
            result = NotesAnalysis.ask(context, scoped, q).getOrElse { "Error: ${it.message}" }
            running = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .then(if (wide) Modifier.widthIn(max = 640.dp) else Modifier)   // tablet: cap + center
                .align(Alignment.TopCenter)
                .statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomSpace),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Text(
            "Ask your notes",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )
        SectionTitle("Scope")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReachChip("All", sel == "All", { sel = "All" })
            ReachChip("Last 7 days", sel == "week", { sel = "week" })
            ReachChip("Last month", sel == "month", { sel = "month" })
            allTags.forEach { t -> ReachChip("#$t", sel == "tag:$t", { sel = "tag:$t" }) }
            clusters.forEachIndexed { i, c ->
                ReachChip("◆ ${c.label} · ${c.notes.size}", sel == "cluster:$i", { sel = "cluster:$i" })
            }
        }
        Text(
            "${scoped.size} note${if (scoped.size == 1) "" else "s"} in scope",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReachChip("Summarize", false, dashed = true, onClick = { run("Summarize these notes into a concise digest of the key points.") })
            ReachChip("Themes", false, dashed = true, onClick = { run("Identify and group the main themes and topics across these notes.") })
            ReachChip("Action items", false, dashed = true, onClick = { run("Extract every action item, task and reminder as a checklist.") })
        }

        GlassTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = "what did I promise Sam?",
            label = "Ask anything about these notes",
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { run(prompt) }, enabled = !running && scoped.isNotEmpty(), shape = ReachShapes.field, modifier = Modifier.fillMaxWidth()) {
            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            else Icon(Icons.Filled.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text(if (running) "Thinking…" else "Ask")
        }

        result?.let {
            SectionTitle("Answer")
            GlassCard(Modifier.fillMaxWidth()) {
                SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        }
    }
}
