package com.soundscript.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soundscript.AppPreferences
import com.soundscript.ModelManager
import com.soundscript.WhisperEngine
import com.soundscript.ai.Embedder
import com.soundscript.ai.InteractEngine
import com.soundscript.ai.InteractOp
import com.soundscript.ai.LlmEngine
import com.soundscript.ai.MarkdownEngine
import com.soundscript.ai.ReminderEngine
import com.soundscript.ai.TaggingEngine
import com.soundscript.ai.TitleEngine
import com.soundscript.ai.addItem
import com.soundscript.ai.openTasks
import com.soundscript.ai.setItemChecked
import com.soundscript.audio.MicRecorder
import com.soundscript.data.Note
import com.soundscript.data.NotesDb
import com.soundscript.service.RecordState
import com.soundscript.service.RecordingService
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit, onAnalyze: () -> Unit, onTasks: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { NotesDb.get(context).notes() }
    val notes by dao.all().collectAsState(initial = emptyList())
    // Embed any notes still missing a vector (one-time, cheap) so semantic relate works app-wide.
    LaunchedEffect(Unit) { Embedder.backfillMissing(context, dao) }
    val status by RecordState.status.collectAsState()
    val partial by RecordState.partial.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var queryVec by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { queryVec = null } else { delay(200); queryVec = Embedder.embed(context, query, query = true) }
    }

    // "Give me a random task" — surface a random unchecked checklist item across all notes.
    val scope = rememberCoroutineScope()
    var showRandom by remember { mutableStateOf(false) }
    var randomTask by remember { mutableStateOf<Pair<Note, String>?>(null) }
    fun pickTask(exclude: Pair<Long, String>? = null) {
        val tasks = openTasks(notes).filterNot { exclude != null && it.first.id == exclude.first && it.second == exclude.second }
        randomTask = if (tasks.isEmpty()) null else tasks[Random.nextInt(tasks.size)]
        showRandom = true
    }

    val allTags = notes.flatMap { it.tags }.distinct().sorted()
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

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            context.startForegroundService(RecordingService.startIntent(context))
        }
    }

    fun toggle() {
        when (status.state) {
            RecordState.State.RECORDING -> context.startService(RecordingService.stopIntent(context))
            RecordState.State.IDLE -> {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    context.startForegroundService(RecordingService.startIntent(context))
                } else {
                    val needed = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permLauncher.launch(needed.toTypedArray())
                }
            }
            RecordState.State.TRANSCRIBING -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SoundScript") },
                actions = {
                    IconButton(onClick = onTasks) { Icon(Icons.Filled.Checklist, "Action items") }
                    IconButton(onClick = { pickTask() }) { Icon(Icons.Filled.Casino, "Random task") }
                    IconButton(onClick = onAnalyze) { Icon(Icons.Filled.Insights, "Ask your notes") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            val recording = status.state == RecordState.State.RECORDING
            val transcribing = status.state == RecordState.State.TRANSCRIBING
            ExtendedFloatingActionButton(
                onClick = { toggle() },
                icon = { Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, null) },
                text = { Text(if (recording) "Stop" else if (transcribing) "Transcribing…" else "Record") },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search notes by meaning") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val s = status.state
            if (s == RecordState.State.RECORDING || s == RecordState.State.TRANSCRIBING) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (s == RecordState.State.RECORDING) "Listening…" else "Transcribing…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (partial.isNotBlank()) {
                            Text(partial, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (allTags.isNotEmpty() && q.isEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    }
                    items(allTags) { t ->
                        FilterChip(
                            selected = filter == t,
                            onClick = { filter = if (filter == t) null else t },
                            label = { Text(t) },
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (q.isEmpty()) "No notes yet. Tap Record." else "No matches.",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { note ->
                        NoteRow(note) { onOpen(note.id) }
                    }
                }
            }
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
                            color = MaterialTheme.colorScheme.outline,
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

/** Every open "- [ ]" item across all notes, one tap to mark done or jump to its note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionItemsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = remember { NotesDb.get(context).notes() }
    val notes by dao.all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // Recent notes first; openTasks() flattens every unchecked checklist item to (note, label).
    val tasks = remember(notes) { openTasks(notes.sortedByDescending { it.createdAt }) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Action items") },
            )
        }
    ) { pad ->
        if (tasks.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing open 🎉", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(tasks) { (note, label) ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(note.id) },
                        leadingContent = {
                            Checkbox(
                                checked = false,
                                onCheckedChange = {
                                    scope.launch {
                                        dao.update(note.copy(markdown = setItemChecked(note.markdown, label, true)))
                                    }
                                },
                            )
                        },
                        headlineContent = { Text(label) },
                        supportingContent = {
                            Text(
                                note.title.ifBlank { note.text.substringBefore('\n') },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
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

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    val when_ = DateUtils.getRelativeTimeSpanString(note.createdAt).toString()
    val tagLine = if (note.tags.isEmpty()) when_ else "$when_ · ${note.tags.joinToString(" ") { "#$it" }}"
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                note.title.ifBlank { note.text.substringBefore('\n') },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(tagLine, style = MaterialTheme.typography.bodySmall) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var showRaw by remember(n.id) { mutableStateOf(n.markdown.isBlank()) }
    var title by remember(n.id) { mutableStateOf(n.title) }
    var titling by remember(n.id) { mutableStateOf(false) }
    var tags by remember(n.id) { mutableStateOf(n.tags) }
    var newTag by remember(n.id) { mutableStateOf("") }
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

    // Snapshot the current note, then apply an AI change to the markdown (undoable).
    fun applyMarkdown(newMd: String) {
        if (newMd == markdown) return
        undo = n
        markdown = newMd
        showRaw = false
        scope.launch { dao.update(n.copy(markdown = newMd)) }
    }
    fun doUndo() {
        val snap = undo ?: return
        markdown = snap.markdown; text = snap.text; title = snap.title; tags = snap.tags
        showRaw = snap.markdown.isBlank()
        scope.launch { dao.update(snap) }
        undo = null
    }
    fun runCommand() {
        val cmd = command.trim()
        if (cmd.isBlank() || interacting) return
        if (!llmReadyOrToast(context)) return
        scope.launch {
            interacting = true; aiError = null
            InteractEngine.interpret(context, markdown, cmd).onSuccess { op ->
                when (op) {
                    is InteractOp.Suggest -> InteractEngine.suggestAdditions(context, markdown.ifBlank { text })
                        .onSuccess { itemSuggestions = it }.onFailure { aiError = it.message }
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

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listening = true  // re-tap to actually start; keeps the flow simple
    }
    fun micToggle() {
        if (listening) { micRec?.stop(); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val r = MicRecorder(); micRec = r; listening = true; aiError = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val entry = ModelManager.entryById(context, prefs.selectedModelId)
                        ?.takeIf { ModelManager.isDownloaded(context, it) }
                        ?: ModelManager.listInstalled(context).firstOrNull()
                    entry != null && WhisperEngine.load(ModelManager.fileFor(context, entry), useGpu = false).isSuccess
                }
                if (!loaded) { aiError = "No transcription model — see Settings"; return@launch }
                r.start()
                r.runUntilStopped()   // returns when micToggle() calls stop()
                val txt = WhisperEngine.transcribe(
                    pcm16k = r.snapshot(), language = prefs.language.ifBlank { null },
                    translate = prefs.translateToEnglish, threads = prefs.resolvedThreads(), highQuality = false,
                )
                command = txt.trim().ifBlank { command }
            } catch (t: Throwable) {
                aiError = "Voice input failed: ${t.message}"
            } finally {
                listening = false; micRec = null
            }
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Note") },
                actions = {
                    IconButton(onClick = {
                        scope.launch { dao.update(n.copy(text = text, title = title, tags = tags, markdown = markdown)); onBack() }
                    }) { Icon(Icons.Filled.Check, "Save") }
                    IconButton(onClick = {
                        scope.launch { dao.delete(n); onBack() }
                    }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (undo != null) {
                AssistChip(
                    onClick = { doUndo() },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)) },
                    label = { Text("Undo last AI change") },
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (titling) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                titling = true
                                TitleEngine.suggest(context, text).onSuccess { if (it.isNotBlank()) title = it }
                                titling = false
                            }
                        }) { Icon(Icons.Filled.AutoAwesome, "Suggest title (AI)") }
                    }
                },
            )
            // Formatted Markdown (interactive checklist) — primary view once generated.
            if (markdown.isNotBlank()) {
                MarkdownView(
                    markdown = markdown,
                    modifier = Modifier.fillMaxWidth(),
                    onToggle = { md ->
                        markdown = md
                        scope.launch { dao.update(n.copy(markdown = md)) }
                    },
                )
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "Hide transcript" else "Show transcript")
                }
            }
            if (showRaw) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Transcript") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            }
            Button(
                onClick = {
                    if (!llmReadyOrToast(context)) return@Button
                    scope.launch {
                        formatting = true; aiError = null
                        MarkdownEngine.toMarkdown(context, text)
                            .onSuccess { markdown = it; dao.update(n.copy(markdown = it)) }
                            .onFailure { aiError = it.message ?: "Formatting failed" }
                        formatting = false
                    }
                },
                enabled = !formatting,
            ) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (formatting) "Formatting…"
                    else if (markdown.isBlank()) "Format as Markdown (AI)" else "Reformat (AI)"
                )
            }
            OutlinedButton(onClick = { addToCalendar() }, enabled = !calBusy) {
                if (calBusy) CircularProgressIndicator(Modifier.size(18.dp))
                else Icon(Icons.Filled.Event, null)
                Spacer(Modifier.width(8.dp))
                Text(if (calBusy) "Reading time…" else "Add to calendar")
            }

            HorizontalDivider()
            Text("Interact", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(if (listening) "Listening…" else "Tell me what to do…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        IconButton(onClick = { micToggle() }) {
                            Icon(
                                if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                                if (listening) "Stop" else "Voice command",
                            )
                        }
                    },
                )
                if (interacting) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(start = 8.dp))
                } else {
                    IconButton(onClick = { runCommand() }, enabled = command.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
            Button(
                onClick = {
                    if (!llmReadyOrToast(context)) return@Button
                    scope.launch {
                        interacting = true; aiError = null
                        InteractEngine.suggestAdditions(context, markdown.ifBlank { text })
                            .onSuccess { itemSuggestions = it }.onFailure { aiError = it.message }
                        interacting = false
                    }
                },
                enabled = !interacting,
            ) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text("Suggest items (AI)")
            }
            if (itemSuggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemSuggestions.forEach { s ->
                        SuggestionChip(
                            onClick = {
                                applyMarkdown(addItem(markdown, s, top = false))
                                itemSuggestions = itemSuggestions - s
                            },
                            label = { Text(s) },
                        )
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { tags = tags - tag },
                        label = { Text(tag) },
                        trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("Add tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
                IconButton(onClick = {
                    val t = newTag.trim()
                    if (t.isNotEmpty() && t !in tags) { tags = tags + t; newTag = "" }
                }) { Icon(Icons.Filled.Add, "Add tag") }
            }

            Button(
                onClick = {
                    scope.launch {
                        suggesting = true; aiError = null
                        TaggingEngine.suggestTags(context, text)
                            .onSuccess { suggestions = it.filter { tag -> tag !in tags } }
                            .onFailure { aiError = it.message ?: "AI tagging failed" }
                        suggesting = false
                    }
                },
                enabled = !suggesting,
            ) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(if (suggesting) "Analyzing…" else "Suggest tags (AI)")
            }
            aiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        SuggestionChip(
                            onClick = { tags = tags + s; suggestions = suggestions - s },
                            label = { Text(s) },
                        )
                    }
                }
            }

            if (related.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text("Related notes", style = MaterialTheme.typography.titleSmall)
                related.forEach { (rn, score) ->
                    val sub = if (score > 0f) "${(score * 100).roundToInt()}% match"
                    else rn.tags.filter { it in n.tags }.joinToString(" ") { "#$it" }
                    ListItem(
                        modifier = Modifier.clickable { onOpen(rn.id) },
                        headlineContent = {
                            Text(
                                rn.title.ifBlank { rn.text.substringBefore('\n') },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(sub, style = MaterialTheme.typography.bodySmall)
                        },
                    )
                }
            }
        }
    }
}
