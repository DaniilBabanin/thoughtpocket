package com.thoughtpocket.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.ModelManager
import com.thoughtpocket.WhisperEngine
import com.thoughtpocket.stripNonSpeech
import com.thoughtpocket.ai.Embedder
import com.thoughtpocket.ai.InteractEngine
import com.thoughtpocket.ai.InteractOp
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.ai.MarkdownEngine
import com.thoughtpocket.ai.ReminderEngine
import com.thoughtpocket.ai.TaggingEngine
import com.thoughtpocket.ai.TitleEngine
import com.thoughtpocket.ai.TransformEngine
import com.thoughtpocket.ai.addItem
import com.thoughtpocket.ai.bulletsToChecklist
import com.thoughtpocket.ai.canonicalizeTags
import com.thoughtpocket.ai.openTasks
import com.thoughtpocket.ai.preserveChecked
import com.thoughtpocket.ai.renameItem
import com.thoughtpocket.ai.setItemChecked
import com.thoughtpocket.audio.MicRecorder
import com.thoughtpocket.data.Note
import com.thoughtpocket.data.NotesDb
import com.thoughtpocket.service.RecordState
import com.thoughtpocket.service.RecordingService
import com.thoughtpocket.ui.theme.GlassCard
import com.thoughtpocket.ui.theme.GlassTextField
import com.thoughtpocket.ui.theme.glass
import com.thoughtpocket.ui.theme.GreetingStyle
import com.thoughtpocket.ui.theme.LocalReduceMotion
import com.thoughtpocket.ui.theme.ReachCheck
import com.thoughtpocket.ui.theme.ReachChip
import com.thoughtpocket.ui.theme.ReachShapes
import com.thoughtpocket.ui.theme.RecordOrb
import com.thoughtpocket.ui.theme.SectionLabel
import com.thoughtpocket.ui.theme.SectionTitle
import com.thoughtpocket.ui.theme.ShimmerLines
import com.thoughtpocket.ui.theme.rememberReveal
import com.thoughtpocket.ui.theme.revealItem
import com.thoughtpocket.ui.theme.WaveBars
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True if a Gemma model is installed; otherwise shows a toast pointing the user to Settings. */
internal fun llmReadyOrToast(context: Context): Boolean {
    if (LlmEngine.isModelInstalled(context)) return true
    Toast.makeText(context, "Download the AI model in Settings first", Toast.LENGTH_LONG).show()
    return false
}

/** Playful home-screen flavor text — notes humor, in the spirit of a working-spinner quip. */
private val NOTE_QUIPS = listOf(
    "nothing slips today",
    "out of your head, into your pocket",
    "your brain, but searchable",
    "catching thoughts before they bolt",
    "where shower thoughts live",
    "down before it slips",
    "hoarding your best ideas",
    "remember everything, effortlessly",
    "a home for half-formed plans",
    "your second brain, caffeinated",
    "mental printout in progress",
    "thoughts captured, chaos contained",
    "quick — get it down",
    "externalizing your working memory",
    "every fleeting idea, kept",
    "say it before you forget it",
    "your overflow drive for thoughts",
    "the good ideas, saved",
    // Working-spinner style — short/invented gerunds (à la Claude Code), notes-flavored.
    "pocketing…",
    "jotting…",
    "scribbling…",
    "noodling…",
    "stashing…",
    "squirreling…",
    "marinating…",
    "percolating…",
    "memoizing…",
    "braindumping…",
    "thinkering…",
    "synapsing…",
    "unforgetting…",
    "crystallizing…",
)

private fun noteQuip(): String = NOTE_QUIPS.random()

/** Record with [rec] until stopped, then transcribe with the user's Whisper model. Throws if no model. */
private suspend fun recordAndTranscribe(context: Context, prefs: AppPreferences, rec: MicRecorder): String {
    val loaded = withContext(Dispatchers.IO) {
        val entry = ModelManager.entryById(context, prefs.selectedModelId)?.takeIf { ModelManager.isDownloaded(context, it) }
            ?: ModelManager.listInstalled(context).firstOrNull()
        entry != null && WhisperEngine.load(ModelManager.fileFor(context, entry), useGpu = false).isSuccess
    }
    if (!loaded) throw IllegalStateException("No transcription model — see Settings")
    rec.start()
    rec.runUntilStopped()   // returns when the caller stops the recorder
    return stripNonSpeech(
        WhisperEngine.transcribe(
            pcm16k = rec.readAll(), language = prefs.language.ifBlank { null },
            translate = prefs.translateToEnglish, threads = prefs.resolvedThreads(), highQuality = false,
            vadModelPath = WhisperEngine.ensureVadModel(context),
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesListScreen(onOpen: (Long) -> Unit, bottomSpace: Dp) {
    val context = LocalContext.current
    val dao = remember { NotesDb.get(context).notes() }
    val prefs = remember { AppPreferences(context) }
    val notes by dao.all().collectAsState(initial = emptyList())
    // Embed any notes still missing a vector (one-time, cheap) so semantic relate works app-wide.
    LaunchedEffect(Unit) { Embedder.backfillMissing(context, dao) }
    val status by RecordState.status.collectAsState()
    val partial by RecordState.partial.collectAsState()
    val pending by RecordState.pending.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var queryVec by remember { mutableStateOf<FloatArray?>(null) }
    val quip = remember { noteQuip() }   // one random notes-humor line per open
    LaunchedEffect(query) {
        if (query.isBlank()) { queryVec = null } else { delay(200); queryVec = Embedder.embed(context, query, query = true) }
    }

    // "Give me a random task" — surface a random unchecked checklist item (scoped to the active filter/search).
    val scope = rememberCoroutineScope()
    var showRandom by remember { mutableStateOf(false) }
    var randomTask by remember { mutableStateOf<Pair<Note, String>?>(null) }

    // Voice search: record → transcribe → drop the text into the query (does NOT create a new note).
    var listening by remember { mutableStateOf(false) }
    var micRec by remember { mutableStateOf<MicRecorder?>(null) }
    val voicePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun voiceSearch() {
        if (listening) { micRec?.stop(); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val r = MicRecorder.temp(context); micRec = r; listening = true
        scope.launch {
            try { recordAndTranscribe(context, prefs, r).takeIf { it.isNotBlank() }?.let { query = it } }
            catch (t: Throwable) { Toast.makeText(context, t.message ?: "Voice search failed", Toast.LENGTH_LONG).show() }
            finally { r.discard(); listening = false; micRec = null }
        }
    }

    // Most-used tags first (then alphabetical) so the common filters surface at the front.
    val allTags = notes.flatMap { it.tags }
        .groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
    // Corpus mean to subtract: USE cosines are compressed; centering restores contrast.
    val noteMean = remember(notes) { Embedder.mean(notes.mapNotNull { it.embedding }) }
    val q = query.trim()
    val shown = if (q.isEmpty()) {
        filter?.let { f -> notes.filter { f in it.tags } } ?: notes
    } else {
        // Semantic search: rank by centered query↔note cosine, keep lexical matches too. Ignores tag filter.
        val qv = queryVec
        notes.map { note ->
            val sem = if (qv != null && note.embedding != null && noteMean != null)
                Embedder.cosineCentered(qv, note.embedding, noteMean) else 0f
            val lex = note.text.contains(q, true) || note.title.contains(q, true) ||
                note.tags.any { it.contains(q, true) }
            Triple(note, sem, lex)
        }.filter { it.second >= 0.20f || it.third }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // Random task is drawn from what's currently shown — an active tag filter or search scopes it.
    fun pickTask(exclude: Pair<Long, String>? = null) {
        val tasks = openTasks(shown).filterNot { exclude != null && it.first.id == exclude.first && it.second == exclude.second }
        randomTask = if (tasks.isEmpty()) null else tasks[Random.nextInt(tasks.size)]
        showRandom = true
    }

    // Long-press a card → reveal Delete; delete is undoable via the snackbar.
    var actionsOpenId by remember { mutableStateOf<Long?>(null) }
    var deleted by remember { mutableStateOf<Note?>(null) }
    val recording = status.state == RecordState.State.RECORDING
    val transcribing = status.state == RecordState.State.TRANSCRIBING

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Header: greeting + brand, random-task die.
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(quip, style = GreetingStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ThoughtPocket", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                }
                IconButton(onClick = { pickTask() }) { Icon(Icons.Filled.Casino, "Random task") }
            }
            // Semantic search.
            GlassTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = if (listening) "Listening…" else "Search notes by meaning",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                leading = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailing = when {
                    listening -> {
                        { IconButton(onClick = { voiceSearch() }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.primary) } }
                    }
                    query.isNotEmpty() -> {
                        { IconButton(onClick = { query = "" }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, "Clear") } }
                    }
                    else -> {
                        { IconButton(onClick = { voiceSearch() }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Mic, "Voice search", tint = MaterialTheme.colorScheme.primary) } }
                    }
                },
            )
            // Live "listening" card while recording/transcribing (design `.listening`: accent-tinted text).
            if (recording || transcribing) {
                val accent = MaterialTheme.colorScheme.primary
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (recording) {
                            WaveBars()
                            Spacer(Modifier.width(13.dp))
                        }
                        Column {
                            Text(
                                if (recording) "LISTENING…" else "TRANSCRIBING…",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                color = accent,
                            )
                            if (recording && partial.isNotBlank()) {
                                Text(
                                    partial,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = lerp(MaterialTheme.colorScheme.onSurface, accent, 0.5f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            } else if (!recording && pending > 0) {
                                Text(
                                    "$pending recording${if (pending == 1) "" else "s"} finishing up…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            // Tag filters.
            if (allTags.isNotEmpty() && q.isEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { ReachChip("All", filter == null, { filter = null }) }
                    items(allTags) { t ->
                        ReachChip(t, filter == t, { filter = if (filter == t) null else t })
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (q.isEmpty()) "No notes yet. Tap the orb to record." else "No matches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SectionLabel("Recent", Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp))
                val reveal = rememberReveal()
                // Single column on phone (Adaptive → 1 col at phone width), multi-column on tablet.
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(280.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomSpace),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(shown, key = { _, it -> it.id }) { index, note ->
                        NoteCard(
                            note = note,
                            modifier = Modifier.revealItem(index) { reveal.value },
                            actionsOpen = actionsOpenId == note.id,
                            onClick = { if (actionsOpenId == note.id) actionsOpenId = null else onOpen(note.id) },
                            onLongClick = { actionsOpenId = note.id },
                            onDelete = {
                                actionsOpenId = null
                                deleted = note
                                scope.launch { dao.delete(note) }
                            },
                        )
                    }
                }
            }
        }

        deleted?.let { d ->
            UndoSnackbar(
                message = "Note deleted",
                onUndo = { scope.launch { dao.insert(d) }; deleted = null },
                onDismiss = { deleted = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSpace),
            )
        }
    }

    if (showRandom) {
        val rt = randomTask
        AlertDialog(
            onDismissRequest = { showRandom = false },
            title = { Text(if (rt == null) "No open tasks" else "Random task") },
            text = {
                if (rt == null) {
                    Text("Nothing left to do 🎉")
                } else {
                    Column {
                        Text(rt.second, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "from “${rt.first.title.ifBlank { rt.first.text.substringBefore('\n') }}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (rt != null) {
                    TextButton(onClick = {
                        scope.launch { dao.update(rt.first.copy(markdown = setItemChecked(rt.first.markdown, rt.second, true))) }
                        pickTask(exclude = rt.first.id to rt.second)
                    }) { Text("Mark done") }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (rt != null) {
                        TextButton(onClick = { pickTask(exclude = rt.first.id to rt.second) }) { Text("Another") }
                        TextButton(onClick = { showRandom = false; onOpen(rt.first.id) }) { Text("Open") }
                    }
                    TextButton(onClick = { showRandom = false }) { Text("Close") }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    actionsOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val when_ = DateUtils.getRelativeTimeSpanString(note.createdAt).toString()
    val tagLine = if (note.tags.isEmpty()) when_ else "$when_ · ${note.tags.joinToString(" ") { "#$it" }}"
    Box(
        modifier
            .fillMaxWidth()
            .glass(ReachShapes.card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    note.title.ifBlank { note.text.substringBefore('\n') },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tagLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (actionsOpen) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Glass snackbar with an UNDO action; auto-dismisses after a few seconds. */
@Composable
private fun UndoSnackbar(message: String, onUndo: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) { delay(3500); onDismiss() }
    val reduce = LocalReduceMotion.current
    val enter = remember { Animatable(if (reduce) 0f else 1f) }  // 1 = below, 0 = in place
    LaunchedEffect(Unit) { if (!reduce) enter.animateTo(0f, tween(300)) }
    GlassCard(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer { translationY = enter.value * 140.dp.toPx() },
        padding = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Text(
                "UNDO",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(ReachShapes.pill).clickable(onClick = onUndo).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Every open "- [ ]" item across all notes: tap to mark done, or use the ⋮ menu to add to calendar,
 *  rename, or open the source note. */
@Composable
fun ActionItemsScreen(onOpen: (Long) -> Unit, bottomSpace: Dp) {
    val context = LocalContext.current
    val dao = remember { NotesDb.get(context).notes() }
    val notes by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // Recent notes first; openTasks() flattens every unchecked checklist item to (note, label).
    val tasks = remember(notes) { openTasks(notes.sortedByDescending { it.createdAt }) }
    val sourceCount = remember(tasks) { tasks.map { it.first.id }.distinct().size }

    var menuFor by remember { mutableStateOf<String?>(null) }          // "<noteId>:<label>" of the open ⋮ menu
    var renaming by remember { mutableStateOf<Pair<Note, String>?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Add this single task to the calendar; the LLM pre-fills date/time/title if the task names one.
    fun addTaskToCalendar(note: Note, label: String) {
        scope.launch {
            val r = if (LlmEngine.isModelInstalled(context))
                ReminderEngine.extract(context, label, System.currentTimeMillis()).getOrNull() else null
            val intent = calendarInsertIntent(
                title = r?.title?.takeIf { it.isNotBlank() } ?: label,
                description = "From: ${note.title.ifBlank { note.text.substringBefore('\n') }}",
                startMillis = r?.startMillis,
                allDay = r?.allDay ?: false,
            )
            runCatching { context.startActivity(intent) }
                .onFailure { Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Action items",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp),
        )
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing open 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SectionLabel(
                "${tasks.size} open · across $sourceCount note${if (sourceCount == 1) "" else "s"}",
                Modifier.padding(start = 18.dp, bottom = 8.dp),
            )
            val reveal = rememberReveal()
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(280.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomSpace),
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(tasks) { index, t ->
                    val (note, label) = t
                    val key = "${note.id}:$label"
                    GlassCard(Modifier.fillMaxWidth().revealItem(index) { reveal.value }.clickable { onOpen(note.id) }, padding = 0.dp) {
                        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReachCheck(checked = false, onToggle = {
                                scope.launch { dao.update(note.copy(markdown = setItemChecked(note.markdown, label, true))) }
                            })
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "from ${note.title.ifBlank { note.text.substringBefore('\n') }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box {
                                IconButton(onClick = { menuFor = key }) {
                                    Icon(Icons.Filled.MoreVert, "Task actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(expanded = menuFor == key, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(
                                        text = { Text("Add to calendar") },
                                        leadingIcon = { Icon(Icons.Filled.Event, null) },
                                        onClick = { menuFor = null; addTaskToCalendar(note, label) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                                        onClick = { menuFor = null; renaming = note to label; renameText = label },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Open note") },
                                        onClick = { menuFor = null; onOpen(note.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { (note, label) ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename task") },
            text = {
                GlassTextField(value = renameText, onValueChange = { renameText = it }, placeholder = "Task", modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val nu = renameText.trim()
                    if (nu.isNotEmpty() && nu != label)
                        scope.launch { dao.update(note.copy(markdown = renameItem(note.markdown, label, nu))) }
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/** Native calendar "new event" intent — opens the user's calendar app pre-filled; no permissions. */
private fun calendarInsertIntent(title: String, description: String, startMillis: Long?, allDay: Boolean): Intent =
    Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.Events.DESCRIPTION, description.take(2000))
        if (startMillis != null) {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 3_600_000L)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = remember { NotesDb.get(context).notes() }
    val note by dao.byId(id).collectAsState(initial = null)
    val allNotes by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val n = note ?: return

    // Related = nearest notes by embedding cosine; falls back to shared tags until embedded.
    val related: List<Pair<Note, Float>> = remember(n.id, allNotes) {
        val mine = n.embedding
        val semantic = if (mine != null) allNotes
            .filter { it.id != n.id && it.embedding != null }
            .map { it to Embedder.cosine(mine, it.embedding!!) }
            .filter { it.second >= 0.35f }
            .sortedByDescending { it.second }
            .take(5)
        else emptyList()
        semantic.ifEmpty {
            if (n.tags.isEmpty()) emptyList()
            else allNotes
                .filter { it.id != n.id && it.tags.any { t -> t in n.tags } }
                .sortedByDescending { it.tags.count { t -> t in n.tags } }
                .take(5)
                .map { it to 0f }
        }
    }

    var text by remember(n.id) { mutableStateOf(n.text) }
    var markdown by remember(n.id) { mutableStateOf(n.markdown) }
    var formatting by remember(n.id) { mutableStateOf(false) }
    var showFormatted by remember(n.id) { mutableStateOf(n.markdown.isNotBlank()) }
    var title by remember(n.id) { mutableStateOf(n.title) }
    var titling by remember(n.id) { mutableStateOf(false) }
    var tags by remember(n.id) { mutableStateOf(n.tags) }
    var newTag by remember(n.id) { mutableStateOf("") }
    // Tags already used across the app — new tags are folded onto these so near-duplicates don't pile up.
    val corpusTags = remember(allNotes) { allNotes.flatMap { it.tags } }
    var suggesting by remember(n.id) { mutableStateOf(false) }
    var suggestions by remember(n.id) { mutableStateOf<List<String>>(emptyList()) }
    var aiError by remember(n.id) { mutableStateOf<String?>(null) }
    var calBusy by remember(n.id) { mutableStateOf(false) }

    // Interact (AI commands on the checklist) + single-level undo for AI changes.
    val prefs = remember { AppPreferences(context) }
    var undo by remember(n.id) { mutableStateOf<Note?>(null) }
    var command by remember(n.id) { mutableStateOf("") }
    var interacting by remember(n.id) { mutableStateOf(false) }
    var listening by remember(n.id) { mutableStateOf(false) }
    var micRec by remember(n.id) { mutableStateOf<MicRecorder?>(null) }
    var itemSuggestions by remember(n.id) { mutableStateOf<List<String>>(emptyList()) }
    var transforming by remember(n.id) { mutableStateOf(false) }

    // Snapshot the current note, then apply an AI change to the markdown (undoable).
    fun applyMarkdown(newMd: String) {
        if (newMd == markdown) return
        undo = n
        markdown = newMd
        showFormatted = true
        scope.launch { dao.update(n.copy(markdown = newMd)) }
    }
    // Snapshot the current note, then set its title from an AI command (undoable).
    fun applyTitle(newTitle: String) {
        if (newTitle.isBlank() || newTitle == title) return
        undo = n
        title = newTitle
        scope.launch { dao.update(n.copy(title = newTitle)) }
    }
    fun doUndo() {
        val snap = undo ?: return
        markdown = snap.markdown; text = snap.text; title = snap.title; tags = snap.tags
        showFormatted = snap.markdown.isNotBlank()
        scope.launch { dao.update(snap) }
        undo = null
    }
    // Rewrite the body following a free-form instruction (preset chip or typed command), applied
    // undoably. Caller owns the busy flag + coroutine; this just does the LLM call and the apply.
    suspend fun doRewrite(instruction: String) {
        TransformEngine.transformWith(context, markdown.ifBlank { text }, instruction)
            .onSuccess { out ->
                if (out.isNotBlank() && out != markdown) applyMarkdown(out)
                else aiError = "Transform produced no change"
            }
            .onFailure { aiError = it.message ?: "Transform failed" }
    }
    fun runCommand() {
        val cmd = command.trim()
        if (cmd.isBlank() || interacting || transforming) return
        if (!llmReadyOrToast(context)) return
        scope.launch {
            interacting = true; aiError = null
            InteractEngine.interpret(context, markdown, cmd).onSuccess { op ->
                when (op) {
                    is InteractOp.Suggest -> InteractEngine.suggestAdditions(context, markdown.ifBlank { text })
                        .onSuccess { itemSuggestions = it }.onFailure { aiError = it.message }
                    is InteractOp.Convert -> {
                        val newMd = bulletsToChecklist(markdown.ifBlank { text })
                        if (newMd.isNotBlank() && newMd != markdown) { applyMarkdown(newMd); command = "" }
                        else aiError = "Nothing to convert"
                    }
                    is InteractOp.SetTitle -> {
                        val t = op.title.trim()
                        if (t.isNotBlank() && t != title) { applyTitle(t); command = "" }
                        else aiError = "Couldn't set a title from: \"$cmd\""
                    }
                    is InteractOp.Rewrite -> doRewrite(cmd)
                    is InteractOp.Unknown -> aiError = "Didn't understand: \"$cmd\""
                    else -> {
                        val newMd = InteractEngine.apply(markdown, op)
                        if (newMd != null && newMd != markdown) { applyMarkdown(newMd); command = "" }
                        else aiError = "No matching item for: \"$cmd\""
                    }
                }
            }.onFailure { aiError = it.message }
            interacting = false
        }
    }

    // One-click "Add to calendar": the LLM pre-fills the time if the note names one ("remind me
    // Tuesday at 3"); either way we open the native calendar editor for the user to confirm.
    fun addToCalendar() {
        if (calBusy) return
        val src = text.ifBlank { markdown }
        if (src.isBlank()) return
        scope.launch {
            calBusy = true; aiError = null
            val r = if (LlmEngine.isModelInstalled(context))
                ReminderEngine.extract(context, src, System.currentTimeMillis()).getOrNull() else null
            val intent = calendarInsertIntent(
                title = r?.title?.takeIf { it.isNotBlank() } ?: title.ifBlank { src.substringBefore('\n') },
                description = src,
                startMillis = r?.startMillis,
                allDay = r?.allDay ?: false,
            )
            runCatching { context.startActivity(intent) }
                .onFailure { aiError = "No calendar app found" }
            calBusy = false
        }
    }

    fun reformat() {
        if (formatting) return
        if (!llmReadyOrToast(context)) return
        scope.launch {
            formatting = true; aiError = null
            MarkdownEngine.toMarkdown(context, text)
                .onSuccess {
                    // Keep items the user already ticked checked after the reformat.
                    val preserved = preserveChecked(markdown, it)
                    markdown = preserved; showFormatted = true; dao.update(n.copy(markdown = preserved))
                }
                .onFailure { aiError = it.message ?: "Formatting failed" }
            formatting = false
        }
    }

    // One-tap transform preset: pre-fill the command box with the instruction (so the user sees what's
    // running and can tweak it for a re-run), then rewrite the body (E4B), applied undoably. The chip
    // skips command-parsing — the preset is already an unambiguous instruction.
    fun applyTransform(preset: TransformEngine.Preset) {
        if (transforming || formatting || interacting) return
        if (!llmReadyOrToast(context)) return
        command = preset.instruction
        scope.launch {
            transforming = true; aiError = null
            doRewrite(preset.instruction)
            transforming = false
        }
    }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listening = true  // re-tap to actually start; keeps the flow simple
    }
    fun micToggle() {
        if (listening) { micRec?.stop(); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val r = MicRecorder.temp(context); micRec = r; listening = true; aiError = null
        scope.launch {
            try { command = recordAndTranscribe(context, prefs, r).ifBlank { command } }
            catch (t: Throwable) { aiError = "Voice input failed: ${t.message}" }
            finally { r.discard(); listening = false; micRec = null }
        }
    }

    // On open: embed the note if needed, and auto-title if still untitled. Single combined save.
    LaunchedEffect(n.id) {
        var changed = n
        if (changed.embedding == null && text.isNotBlank())
            Embedder.embed(context, text)?.let { changed = changed.copy(embedding = it) }
        if (changed.title.isBlank() && text.isNotBlank() && LlmEngine.isModelInstalled(context)) {
            titling = true
            TitleEngine.suggest(context, text).onSuccess {
                if (it.isNotBlank()) { title = it; changed = changed.copy(title = it) }
            }
            titling = false
        }
        if (changed !== n) dao.update(changed)
    }

    // Append-by-recording: the docked orb on this screen records into THIS note (background queue appends
    // the transcript; once the recordings finish it reformats + retags in one pass, if enabled in Settings).
    val recStatus by RecordState.status.collectAsState()
    val recording = recStatus.state == RecordState.State.RECORDING
    val recPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true)
            context.startForegroundService(RecordingService.startIntent(context, appendToNoteId = id))
    }
    fun startAppend() {
        if (recording) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            context.startForegroundService(RecordingService.startIntent(context, appendToNoteId = id))
        else recPerm.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
        )
    }
    fun stopAppend() { if (recording) context.startService(RecordingService.stopIntent(context)) }
    // While recording into this note show the raw transcript (it grows as clips land); resync the local
    // buffers from the DB as background appends + the on-finish reformat/retag land, so the screen reflects
    // them (and Save doesn't write back stale copies).
    LaunchedEffect(recording) { if (recording) showFormatted = false }
    LaunchedEffect(n.text) { if (n.text != text) text = n.text }
    LaunchedEffect(n.markdown) { if (n.markdown != markdown) markdown = n.markdown }
    LaunchedEffect(n.tags) { if (n.tags != tags) tags = n.tags }

    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Note", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { reformat() }, enabled = !formatting) {
                    Icon(Icons.Filled.AutoAwesome, "Reformat (AI)", tint = cs.primary)
                }
                IconButton(onClick = {
                    scope.launch { dao.update(n.copy(text = text, title = title, tags = tags, markdown = markdown)); onBack() }
                }) { Icon(Icons.Filled.Check, "Save") }
                IconButton(onClick = { scope.launch { dao.delete(n); onBack() } }) { Icon(Icons.Filled.Delete, "Delete") }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // AI title.
                GlassTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "Untitled",
                    label = "Title",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        if (titling) CircularProgressIndicator(Modifier.size(20.dp))
                        else IconButton(onClick = {
                            scope.launch {
                                titling = true
                                TitleEngine.suggest(context, text).onSuccess { if (it.isNotBlank()) title = it }
                                titling = false
                            }
                        }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.AutoAwesome, "Suggest title (AI)", tint = cs.primary) }
                    },
                )

                // Formatted / Transcript toggle (only once a Markdown version exists).
                if (markdown.isNotBlank() && !formatting) {
                    SegToggle("Formatted", "Transcript", showFormatted) { showFormatted = it }
                }
                when {
                    // Reformatting in progress → shimmering skeleton (design's reformat state).
                    formatting -> GlassCard(Modifier.fillMaxWidth()) { ShimmerLines(lines = 5) }
                    markdown.isNotBlank() && showFormatted -> GlassCard(Modifier.fillMaxWidth()) {
                        MarkdownView(
                            markdown = markdown,
                            modifier = Modifier.fillMaxWidth(),
                            onToggle = { md ->
                                markdown = md
                                scope.launch { dao.update(n.copy(markdown = md)) }
                            },
                        )
                    }
                    else -> GlassTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = "Transcript",
                        label = "Transcript",
                        singleLine = false,
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Interact.
                SectionTitle("Interact", Modifier.padding(top = 4.dp))
                GlassTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = if (listening) "Listening…" else "Tell me what to do…",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { micToggle() }, modifier = Modifier.size(28.dp)) {
                                Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Stop" else "Voice command", tint = cs.primary)
                            }
                            if (interacting) CircularProgressIndicator(Modifier.size(22.dp))
                            else IconButton(onClick = { runCommand() }, enabled = command.isNotBlank(), modifier = Modifier.size(28.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = cs.primary)
                            }
                        }
                    },
                )
                // One-tap transform presets — quick ways to reshape the body (undoable). A preset is
                // an unambiguous intent, so these skip command-parsing and call TransformEngine (E4B).
                if (transforming) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Text("Transforming…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        TransformEngine.Preset.entries.forEach { p ->
                            ReachChip(p.label, selected = false, onClick = { applyTransform(p) })
                        }
                    }
                }
                if (itemSuggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        itemSuggestions.forEach { s ->
                            ReachChip(s, selected = false, dashed = true, onClick = {
                                applyMarkdown(addItem(markdown, s, top = false))
                                itemSuggestions = itemSuggestions - s
                            })
                        }
                    }
                }
                FilledTonalButton(onClick = {
                    if (!llmReadyOrToast(context)) return@FilledTonalButton
                    scope.launch {
                        interacting = true; aiError = null
                        InteractEngine.suggestAdditions(context, markdown.ifBlank { text })
                            .onSuccess { itemSuggestions = it }.onFailure { aiError = it.message }
                        interacting = false
                    }
                }, enabled = !interacting, shape = ReachShapes.field, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Suggest items (AI)")
                }

                // Tags.
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        tags.forEach { tag ->
                            ReachChip("#$tag", selected = false, onClick = { tags = tags - tag })
                        }
                    }
                }
                GlassTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = "weekend…",
                    label = "Add tag",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        IconButton(onClick = {
                            val t = newTag.trim()
                            if (t.isNotEmpty()) {
                                val canonical = canonicalizeTags(listOf(t), corpusTags + tags).firstOrNull() ?: t
                                if (canonical !in tags) tags = tags + canonical
                                newTag = ""
                            }
                        }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Add, "Add tag", tint = cs.primary) }
                    },
                )
                FilledTonalButton(onClick = {
                    scope.launch {
                        suggesting = true; aiError = null
                        TaggingEngine.suggestTags(context, text)
                            .onSuccess { suggestions = canonicalizeTags(it, corpusTags + tags).filter { tag -> tag !in tags } }
                            .onFailure { aiError = it.message ?: "AI tagging failed" }
                        suggesting = false
                    }
                }, enabled = !suggesting, shape = ReachShapes.field, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                    Text(if (suggesting) "Analyzing…" else "Suggest tags (AI)")
                }
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        suggestions.forEach { s ->
                            ReachChip(s, selected = false, dashed = true, onClick = {
                                val canonical = canonicalizeTags(listOf(s), corpusTags + tags).firstOrNull() ?: s
                                if (canonical !in tags) tags = tags + canonical
                                suggestions = suggestions - s
                            })
                        }
                    }
                }
                aiError?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }

                // Related notes.
                if (related.isNotEmpty()) {
                    SectionTitle("Related notes", Modifier.padding(top = 8.dp))
                    related.forEach { (rn, score) ->
                        val sub = if (score > 0f) "${(score * 100).roundToInt()}% match"
                        else rn.tags.filter { it in n.tags }.joinToString(" ") { "#$it" }
                        Column(Modifier.fillMaxWidth().clickable { onOpen(rn.id) }.padding(vertical = 4.dp)) {
                            Text(
                                rn.title.ifBlank { rn.text.substringBefore('\n') },
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(96.dp)) // clear the sticky action bar
            }
        }

        // Sticky action bar: Reformat · Calendar.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { reformat() },
                enabled = !formatting,
                shape = ReachShapes.field,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                Text(if (formatting) "Formatting…" else if (markdown.isBlank()) "Format" else "Reformat")
            }
            FilledTonalButton(onClick = { addToCalendar() }, enabled = !calBusy, shape = ReachShapes.field, modifier = Modifier.weight(1f)) {
                if (calBusy) CircularProgressIndicator(Modifier.size(18.dp)) else Icon(Icons.Filled.Event, null)
                Spacer(Modifier.width(8.dp)); Text(if (calBusy) "Reading…" else "Calendar")
            }
        }

        // Floating record orb — adds more recordings to this note.
        RecordOrb(
            recording = recording,
            onStart = { startAppend() },
            onStop = { stopAppend() },
            level = RecordState.amplitude,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 92.dp),
        )

        if (undo != null) {
            UndoSnackbar(
                message = "AI change applied",
                onUndo = { doUndo() },
                onDismiss = { undo = null },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp),
            )
        }
    }
}

/** Two-segment glass toggle (Formatted / Transcript). */
@Composable
private fun SegToggle(left: String, right: String, leftSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().glass(ReachShapes.field).padding(4.dp)) {
        Seg(left, leftSelected, Modifier.weight(1f)) { onSelect(true) }
        Seg(right, !leftSelected, Modifier.weight(1f)) { onSelect(false) }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(cs.primary) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
        )
    }
}
