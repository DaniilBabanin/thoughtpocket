package com.soundscript.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soundscript.ai.TaggingEngine
import com.soundscript.data.Note
import com.soundscript.data.NotesDb
import com.soundscript.service.RecordState
import com.soundscript.service.RecordingService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { NotesDb.get(context).notes() }
    val notes by dao.all().collectAsState(initial = emptyList())
    val status by RecordState.status.collectAsState()
    val partial by RecordState.partial.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }

    val allTags = notes.flatMap { it.tags }.distinct().sorted()
    val shown = filter?.let { f -> notes.filter { f in it.tags } } ?: notes

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
            if (allTags.isNotEmpty()) {
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
                    Text("No notes yet. Tap Record.", color = MaterialTheme.colorScheme.outline)
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
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    val when_ = DateUtils.getRelativeTimeSpanString(note.createdAt).toString()
    val tagLine = if (note.tags.isEmpty()) when_ else "$when_ · ${note.tags.joinToString(" ") { "#$it" }}"
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(note.text.substringBefore('\n'), maxLines = 2, overflow = TextOverflow.Ellipsis)
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

    // Related = other notes sharing at least one tag, most shared tags first.
    val related = if (n.tags.isEmpty()) emptyList() else allNotes
        .filter { it.id != n.id && it.tags.any { t -> t in n.tags } }
        .sortedByDescending { it.tags.count { t -> t in n.tags } }
        .take(5)

    var text by remember(n.id) { mutableStateOf(n.text) }
    var tags by remember(n.id) { mutableStateOf(n.tags) }
    var newTag by remember(n.id) { mutableStateOf("") }
    var suggesting by remember(n.id) { mutableStateOf(false) }
    var suggestions by remember(n.id) { mutableStateOf<List<String>>(emptyList()) }
    var aiError by remember(n.id) { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Note") },
                actions = {
                    IconButton(onClick = {
                        scope.launch { dao.update(n.copy(text = text, tags = tags)); onBack() }
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
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Transcript") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
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
                related.forEach { rn ->
                    val shared = rn.tags.filter { it in n.tags }
                    ListItem(
                        modifier = Modifier.clickable { onOpen(rn.id) },
                        headlineContent = {
                            Text(rn.text.substringBefore('\n'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(shared.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall)
                        },
                    )
                }
            }
        }
    }
}
