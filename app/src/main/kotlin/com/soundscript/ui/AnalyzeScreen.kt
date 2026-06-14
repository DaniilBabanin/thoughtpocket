package com.soundscript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soundscript.ai.Clusters
import com.soundscript.ai.NotesAnalysis
import com.soundscript.data.NotesDb
import kotlinx.coroutines.launch

private const val DAY = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnalyzeScreen(onBack: () -> Unit) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Ask your notes") },
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Scope", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(sel == "All", { sel = "All" }, { Text("All") })
                FilterChip(sel == "week", { sel = "week" }, { Text("Last 7 days") })
                FilterChip(sel == "month", { sel = "month" }, { Text("Last month") })
                allTags.forEach { t ->
                    FilterChip(sel == "tag:$t", { sel = "tag:$t" }, { Text("#$t") })
                }
                clusters.forEachIndexed { i, c ->
                    FilterChip(sel == "cluster:$i", { sel = "cluster:$i" }, { Text("◆ ${c.label} (${c.notes.size})") })
                }
            }
            Text(
                "${scoped.size} note${if (scoped.size == 1) "" else "s"} in scope",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { run("Summarize these notes into a concise digest of the key points.") },
                    label = { Text("Summarize") },
                )
                AssistChip(
                    onClick = { run("Identify and group the main themes and topics across these notes.") },
                    label = { Text("Themes") },
                )
                AssistChip(
                    onClick = { run("Extract every action item, task and reminder as a checklist.") },
                    label = { Text("Action items") },
                )
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Ask anything about these notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(onClick = { run(prompt) }, enabled = !running && scoped.isNotEmpty()) {
                Text(if (running) "Thinking…" else "Ask")
            }

            result?.let {
                HorizontalDivider()
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
