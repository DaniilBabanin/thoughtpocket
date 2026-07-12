package com.thoughtpocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.data.CodeRun
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.service.CodeRunState
import com.thoughtpocket.service.CoderRunService
import com.thoughtpocket.ui.theme.GlassCard
import com.thoughtpocket.ui.theme.GlassTextField
import com.thoughtpocket.ui.theme.ReachShapes
import com.thoughtpocket.ui.theme.SectionTitle
import kotlinx.coroutines.launch

/**
 * A note's coding items: every "Code this" task persists as a row (Room —
 * source of truth). Tap an item to view its result and iterate on it
 * (follow-ups update the item in place); a new prompt makes a new item;
 * long-press deletes (undoable). The script editor (edit / rerun / revert
 * to the model's code) only appears with Settings → "Show code details" on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodeRunScreen(noteId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    val runsDao = remember { NotesDb.get(context).codeRuns() }
    val runs by runsDao.byNote(noteId).collectAsState(initial = emptyList())
    val status by CodeRunState.status.collectAsState()
    val cs = MaterialTheme.colorScheme

    var selectedId by remember { mutableStateOf<Long?>(null) }
    var newTask by remember { mutableStateOf("") }
    var deleted by remember { mutableStateOf<CodeRun?>(null) }
    var noteUndo by remember { mutableStateOf<Note?>(null) }

    // Entering the screen shows the previous (newest) result; a finishing run
    // selects its own item.
    LaunchedEffect(status.activeRunId, runs.firstOrNull()?.id) {
        if (status.activeRunId >= 0) selectedId = status.activeRunId
        else if (selectedId == null || runs.none { it.id == selectedId }) selectedId = runs.firstOrNull()?.id
    }

    fun exit() {
        CoderRunService.end(context)
        onBack()
    }
    BackHandler { exit() }

    val working = status.phase in listOf(
        CodeRunState.Phase.STARTING, CodeRunState.Phase.GENERATING,
        CodeRunState.Phase.RUNNING, CodeRunState.Phase.FIXING,
    )

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { exit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                SectionTitle("Code this", Modifier.weight(1f))
            }

            // New task = new coding item on this note.
            GlassTextField(
                value = newTask,
                onValueChange = { newTask = it },
                placeholder = "New task — calculate, analyze, transform…",
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    IconButton(
                        onClick = { CoderRunService.run(context, noteId, newTask.trim()); newTask = "" },
                        enabled = newTask.isNotBlank() && !working,
                        modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = cs.primary) }
                },
            )

            if (working) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (status.phase) {
                                    CodeRunState.Phase.STARTING -> "Loading coding model…"
                                    CodeRunState.Phase.GENERATING -> "Writing script…"
                                    CodeRunState.Phase.FIXING -> "Fixing an error (attempt ${status.attempt}/3)…"
                                    else -> "Running…"
                                }
                            )
                            if (status.tokenCount > 0 && status.phase != CodeRunState.Phase.RUNNING) {
                                Text(
                                    "${status.tokenCount} tokens — this can take a few minutes",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { CoderRunService.cancel(context) }) {
                            Icon(Icons.Filled.Close, "Cancel", tint = cs.error)
                        }
                    }
                }
            }

            if (status.phase == CodeRunState.Phase.FAILED) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Couldn't finish", color = cs.error)
                    Text(status.result, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (prefs.coderShowCode && status.failedAttempts.isNotEmpty()) {
                        status.failedAttempts.forEachIndexed { i, (_, err) ->
                            Text("Attempt ${i + 1}: $err", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            if (runs.isEmpty() && !working) {
                Text(
                    "No coding tasks on this note yet.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }

            runs.forEach { run ->
                val selected = run.id == selectedId
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { selectedId = if (selected) null else run.id },
                            onLongClick = {
                                deleted = run
                                scope.launch { runsDao.delete(run.id) }
                            },
                        ),
                ) {
                    Text(run.instruction, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    if (!selected) {
                        Text(
                            run.output.trim().lineSequence().firstOrNull().orEmpty().take(80),
                            maxLines = 1, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Text(run.output.trimEnd(), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val dao = NotesDb.get(context).notes()
                                    val n = dao.getById(noteId) ?: return@launch
                                    noteUndo = n
                                    val section = "\n\n**${run.instruction.trim()}**\n\n```\n${run.output.trimEnd()}\n```"
                                    dao.update(n.copy(markdown = n.markdown.ifBlank { n.text } + section))
                                }
                            },
                            shape = ReachShapes.field, modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add result to note") }

                        var followUp by remember(run.id) { mutableStateOf("") }
                        GlassTextField(
                            value = followUp,
                            onValueChange = { followUp = it },
                            placeholder = "Iterate — e.g. now show it per week…",
                            modifier = Modifier.fillMaxWidth(),
                            trailing = {
                                IconButton(
                                    onClick = { CoderRunService.followUp(context, run.id, followUp.trim()); followUp = "" },
                                    enabled = followUp.isNotBlank() && !working,
                                    modifier = Modifier.size(28.dp),
                                ) { Icon(Icons.AutoMirrored.Filled.Send, "Iterate", tint = cs.primary) }
                            },
                        )

                        if (prefs.coderShowCode) {
                            var editedCode by remember(run.id, run.code) { mutableStateOf(run.code) }
                            SectionTitle("Script", Modifier.padding(top = 6.dp))
                            GlassTextField(
                                value = editedCode,
                                onValueChange = { editedCode = it },
                                placeholder = "",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 6,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { CoderRunService.rerunEdited(context, run.id, editedCode) },
                                    enabled = editedCode.isNotBlank() && !working,
                                    shape = ReachShapes.field, modifier = Modifier.weight(1f),
                                ) { Text("Run script") }
                                if (run.code != run.originalCode) {
                                    TextButton(onClick = {
                                        scope.launch { runsDao.update(run.copy(code = run.originalCode)) }
                                    }) { Text("Revert edits") }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (deleted != null) {
            UndoSnackbar(
                message = "Coding task deleted",
                onUndo = {
                    deleted?.let { d -> scope.launch { runsDao.insert(d.copy(id = 0)) } }
                    deleted = null
                },
                onDismiss = { deleted = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
        if (noteUndo != null && deleted == null) {
            UndoSnackbar(
                message = "Result added to note",
                onUndo = {
                    noteUndo?.let { snap -> scope.launch { NotesDb.get(context).notes().update(snap) } }
                    noteUndo = null
                },
                onDismiss = { noteUndo = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}
