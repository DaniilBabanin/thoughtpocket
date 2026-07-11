package com.thoughtpocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.ui.theme.GlassCard
import com.thoughtpocket.ui.theme.GlassTextField
import com.thoughtpocket.ui.theme.ReachShapes
import com.thoughtpocket.ui.theme.SectionTitle
import com.thoughtpocket.service.CodeRunState
import com.thoughtpocket.service.CoderRunService
import kotlinx.coroutines.launch

/**
 * Coder results screen: progress + output only (the whole point — no
 * debugging in the user's face); follow-up prompts on the warm model; code,
 * per-attempt log and re-run live behind Details. Leaving the screen ends the
 * session (frees the 5.6 GB model, lets Gemma features resume).
 */
@Composable
fun CodeRunScreen(noteId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by CodeRunState.status.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    var followUp by remember { mutableStateOf("") }
    var undo by remember { mutableStateOf<Note?>(null) }
    var inserted by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    fun exit() {
        CoderRunService.end(context)
        onBack()
    }
    BackHandler(enabled = showDetails) { showDetails = false }
    BackHandler(enabled = !showDetails) { exit() }

    if (showDetails) {
        CodeDetailsScreen(onBack = { showDetails = false })
        return
    }

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
            if (status.turns.isNotEmpty() || status.phase == CodeRunState.Phase.FAILED) {
                TextButton(onClick = { showDetails = true }) { Text("Details") }
            }
        }

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
            }
        }

        if (status.phase == CodeRunState.Phase.DONE && status.result.isNotBlank()) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Result", Modifier.padding(bottom = 6.dp))
                SelectionContainer {
                    Text(status.result.trimEnd(), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            FilledTonalButton(
                enabled = !inserted,
                onClick = {
                    val turn = status.turns.lastOrNull() ?: return@FilledTonalButton
                    scope.launch {
                        val dao = NotesDb.get(context).notes()
                        val n = dao.getById(noteId) ?: return@launch
                        undo = n
                        val section = "\n\n**${turn.instruction.trim()}**\n\n```\n${turn.output.trimEnd()}\n```"
                        dao.update(n.copy(markdown = n.markdown.ifBlank { n.text } + section))
                        inserted = true
                    }
                },
                shape = ReachShapes.field, modifier = Modifier.fillMaxWidth(),
            ) { Text(if (inserted) "Added to note" else "Add result to note") }
        }

        // Follow-up: model is still warm — same session, prior turn as context.
        if (status.phase == CodeRunState.Phase.DONE) {
            GlassTextField(
                value = followUp,
                onValueChange = { followUp = it },
                placeholder = "Follow up — e.g. now show it per week…",
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    IconButton(
                        onClick = {
                            CoderRunService.followUp(context, followUp.trim())
                            followUp = ""; inserted = false
                        },
                        enabled = followUp.isNotBlank(), modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Run follow-up", tint = cs.primary) }
                },
            )
        }
    }

    if (undo != null && inserted) {
        UndoSnackbar(
            message = "Result added to note",
            onUndo = {
                undo?.let { snap -> scope.launch { NotesDb.get(context).notes().update(snap) } }
                undo = null; inserted = false
            },
            onDismiss = { undo = null },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
    }
}

/** Everything the default view hides: per-attempt log, the working script, edit + re-run. */
@Composable
private fun CodeDetailsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by CodeRunState.status.collectAsState()
    val cs = MaterialTheme.colorScheme
    val lastTurn = status.turns.lastOrNull()
    var editedCode by remember(lastTurn?.code) { mutableStateOf(lastTurn?.code ?: "") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            SectionTitle("Details", Modifier.weight(1f))
        }

        if (lastTurn != null) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Script", Modifier.padding(bottom = 6.dp))
                GlassTextField(
                    value = editedCode,
                    onValueChange = { editedCode = it },
                    placeholder = "",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 6,
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { CoderRunService.rerunEdited(context, editedCode) },
                    enabled = editedCode.isNotBlank(),
                    shape = ReachShapes.field, modifier = Modifier.fillMaxWidth(),
                ) { Text("Run edited script") }
            }
        }

        val log = (status.turns.flatMap { it.attemptLog } + status.failedAttempts).ifEmpty {
            if (status.phase == CodeRunState.Phase.FAILED) listOf("" to status.result) else emptyList()
        }
        if (log.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Attempts", Modifier.padding(bottom = 6.dp))
                log.forEachIndexed { i, (code, err) ->
                    Text("Attempt ${i + 1}: ${if (err.isBlank()) "OK" else err}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (err.isBlank()) cs.primary else cs.onSurfaceVariant)
                    if (code.isNotBlank()) {
                        SelectionContainer {
                            Text(code, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
