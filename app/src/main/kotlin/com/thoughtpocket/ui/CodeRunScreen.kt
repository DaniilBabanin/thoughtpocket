package com.thoughtpocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import com.thoughtpocket.ui.theme.ReachSwitch
import com.thoughtpocket.ui.theme.SectionTitle
import com.thoughtpocket.ui.theme.glass
import kotlinx.coroutines.launch

/**
 * A note's coding hub — the note itself only carries a "Code this" button that
 * opens this screen. Top: start a new task, with an explicit per-task "access
 * all notes" grant (off by default — without it the script sees only this
 * note). Then live progress, the focused item (result, iterate, and — with
 * Settings → "Show code details" on — the script editor), and the note's
 * coding items (newest first; tap to focus, long-press for rerun/delete).
 * Leaving while idle ends the coder session (frees the model).
 *
 * [focusRunId] is the tapped item, or -1 for none (opened from the note's
 * button); a run started here focuses whatever item CodeRunState resolves to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodeRunScreen(noteId: Long, focusRunId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    val runsDao = remember { NotesDb.get(context).codeRuns() }
    val runs by runsDao.byNote(noteId).collectAsState(initial = emptyList())
    val status by CodeRunState.status.collectAsState()
    val cs = MaterialTheme.colorScheme

    var noteUndo by remember { mutableStateOf<Note?>(null) }
    var inserted by remember { mutableStateOf(false) }
    var deletedRun by remember { mutableStateOf<CodeRun?>(null) }
    var task by remember { mutableStateOf("") }
    // Per-task grant, deliberately NOT persisted as a default — every new task
    // starts note-only unless the user flips it again.
    var allNotesAccess by remember { mutableStateOf(false) }
    var actionsOpenId by remember { mutableStateOf<Long?>(null) }

    // The focused item: an explicit tap wins; otherwise follow the active run
    // (a task just started here, or an in-place iterate).
    var focused by remember { mutableStateOf(focusRunId.takeIf { it >= 0 }) }
    val currentId = focused
        ?: if (status.noteId == noteId && status.activeRunId >= 0) status.activeRunId else -1L
    val item = runs.firstOrNull { it.id == currentId }

    // anyWorking gates starting new work (one model, one run at a time,
    // whatever the note); mine gates what this screen DISPLAYS.
    val anyWorking = status.phase in listOf(
        CodeRunState.Phase.STARTING, CodeRunState.Phase.GENERATING,
        CodeRunState.Phase.RUNNING, CodeRunState.Phase.FIXING,
    )
    val mine = status.noteId == noteId
    val working = anyWorking && mine

    // Leaving mid-run must NOT kill a multi-minute run — it finishes in the
    // service and the session ends on its idle timer. End eagerly only when idle.
    fun exit() {
        if (!anyWorking) CoderRunService.end(context)
        onBack()
    }
    BackHandler { exit() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { exit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                SectionTitle(item?.instruction?.take(60) ?: "Code this", Modifier.weight(1f))
            }

            GlassTextField(
                value = task,
                onValueChange = { task = it },
                placeholder = "Calculate, analyze, transform…",
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    IconButton(
                        onClick = {
                            CoderRunService.run(context, noteId, task.trim(), allNotesAccess)
                            task = ""; focused = null; inserted = false
                        },
                        enabled = task.isNotBlank() && !anyWorking, modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = cs.primary) }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Access all notes")
                    Text(
                        "Lets this task read every note, not just this one",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                ReachSwitch(checked = allNotesAccess, onChange = { allNotesAccess = it })
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
                                Text("${status.tokenCount} tokens — this can take a few minutes",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { CoderRunService.cancel(context) }) {
                            Icon(Icons.Filled.Close, "Cancel", tint = cs.error)
                        }
                    }
                    // Live script stream — turns a minutes-long spinner into progress.
                    if (prefs.coderShowCode && status.streamed.isNotBlank()) {
                        Text(
                            status.streamed.lines().takeLast(10).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            if (status.phase == CodeRunState.Phase.FAILED && mine) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("Couldn't finish", color = cs.error)
                    Text(status.result, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (prefs.coderShowCode) status.failedAttempts.forEachIndexed { i, (_, err) ->
                        Text("Attempt ${i + 1}: $err", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }

            if (item != null) {
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle("Result", Modifier.padding(bottom = 6.dp))
                    SelectionContainer {
                        Text(item.output.trimEnd(), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FilledTonalButton(
                    enabled = !inserted,
                    onClick = {
                        scope.launch {
                            val dao = NotesDb.get(context).notes()
                            val n = dao.getById(noteId) ?: return@launch
                            noteUndo = n
                            val section = "\n\n**${item.instruction.trim()}**\n\n```\n${item.output.trimEnd()}\n```"
                            dao.update(n.copy(markdown = n.markdown.ifBlank { n.text } + section))
                            inserted = true
                        }
                    },
                    shape = ReachShapes.field, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (inserted) "Added to note" else "Add result to note") }

                var followUp by remember(item.id) { mutableStateOf("") }
                GlassTextField(
                    value = followUp,
                    onValueChange = { followUp = it },
                    placeholder = "Iterate — e.g. now show it per week…",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        IconButton(
                            onClick = { CoderRunService.followUp(context, item.id, followUp.trim()); followUp = ""; inserted = false },
                            enabled = followUp.isNotBlank() && !anyWorking, modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, "Iterate", tint = cs.primary) }
                    },
                )

                if (prefs.coderShowCode) {
                    var editedCode by remember(item.id, item.code) { mutableStateOf(item.code) }
                    SectionTitle("Script", Modifier.padding(top = 6.dp))
                    GlassTextField(
                        value = editedCode, onValueChange = { editedCode = it }, placeholder = "",
                        modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 6,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { CoderRunService.rerunEdited(context, item.id, editedCode) },
                            enabled = editedCode.isNotBlank() && !anyWorking,
                            shape = ReachShapes.field, modifier = Modifier.weight(1f),
                        ) { Text("Run script") }
                        if (item.code != item.originalCode) {
                            TextButton(onClick = { scope.launch { runsDao.update(item.copy(code = item.originalCode)) } }) {
                                Text("Revert edits")
                            }
                        }
                    }
                }
            }

            if (runs.isNotEmpty()) {
                SectionTitle("Coding items", Modifier.padding(top = 4.dp))
                runs.forEach { run ->
                    val open = actionsOpenId == run.id
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .glass(ReachShapes.card)
                            .combinedClickable(
                                onClick = { if (open) actionsOpenId = null else focused = run.id },
                                onLongClick = { actionsOpenId = run.id },
                            ),
                    ) {
                        Column(Modifier.padding(14.dp).padding(horizontal = if (open) 44.dp else 0.dp)) {
                            Text(run.instruction, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                run.output.trim().lineSequence().firstOrNull().orEmpty().take(80),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                        if (open) {
                            // Left: rerun the stored script. Right: delete (with undo).
                            Box(
                                Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(40.dp)
                                    .clip(CircleShape).background(cs.secondaryContainer)
                                    .clickable(enabled = !anyWorking) {
                                        actionsOpenId = null
                                        CoderRunService.rerunEdited(context, run.id, run.code)
                                        focused = run.id
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Refresh, "Rerun", tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
                            Box(
                                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(40.dp)
                                    .clip(CircleShape).background(cs.errorContainer)
                                    .clickable {
                                        actionsOpenId = null
                                        deletedRun = run
                                        if (focused == run.id) focused = null
                                        scope.launch { runsDao.delete(run.id) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Delete, "Delete", tint = cs.onErrorContainer, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }

        if (noteUndo != null) {
            UndoSnackbar(
                message = "Result added to note",
                onUndo = { noteUndo?.let { s -> scope.launch { NotesDb.get(context).notes().update(s) } }; noteUndo = null; inserted = false },
                onDismiss = { noteUndo = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
        deletedRun?.let { d ->
            UndoSnackbar(
                message = "Coding task deleted",
                onUndo = {
                    scope.launch { runsDao.insert(d.copy(id = 0)) }
                    deletedRun = null
                },
                onDismiss = { deletedRun = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}
