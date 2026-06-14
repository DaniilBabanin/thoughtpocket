package com.soundscript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.provider.OpenableColumns
import androidx.compose.runtime.collectAsState
import com.soundscript.AppPreferences
import com.soundscript.ModelManager
import com.soundscript.notesToMarkdown
import com.soundscript.ai.Embedder
import com.soundscript.ai.LlmEngine
import com.soundscript.data.NotesDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val models = remember { ModelManager.BuiltInModel.entries.toList() }

    var selected by remember { mutableStateOf(prefs.selectedModelId) }
    var language by remember { mutableStateOf(prefs.language) }
    var translate by remember { mutableStateOf(prefs.translateToEnglish) }
    val progress = remember { mutableStateMapOf<String, Int>() }
    var installedTick by remember { mutableStateOf(0) }

    var aiTick by remember { mutableStateOf(0) }
    var importingModel by remember { mutableStateOf(false) }
    var tagModel by remember { mutableStateOf(prefs.tagModelFilename) }
    var analysisModel by remember { mutableStateOf(prefs.analysisModelFilename) }
    var autoTag by remember { mutableStateOf(prefs.autoTag) }
    var autoMarkdown by remember { mutableStateOf(prefs.autoMarkdown) }
    var liveNotif by remember { mutableStateOf(prefs.liveTranscribeNotification) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var geckoTick by remember { mutableStateOf(0) }
    var geckoPct by remember { mutableStateOf<Int?>(null) }
    val llmPct = remember { mutableStateMapOf<String, Int>() }
    var setupStatus by remember { mutableStateOf<String?>(null) }
    val pickGemma = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importingModel = true; aiError = null
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        ?.takeIf { it.endsWith(".litertlm") || it.endsWith(".task") }
                        ?: "imported.task"
                    val target = File(LlmEngine.llmDir(context), name)
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    target.name
                }.getOrElse { aiError = it.message; null }
            }
            if (imported != null) {
                prefs.llmModelFilename = imported; LlmEngine.release()
            }
            importingModel = false; aiTick++
        }
    }

    val notes by remember { NotesDb.get(context).notes() }.all().collectAsState(initial = emptyList())
    val exportNotes = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            aiError = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(notesToMarkdown(notes).toByteArray()) }
                        ?: error("Couldn't open the file for writing")
                }.exceptionOrNull()?.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Settings") },
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // One-tap setup: fetch the default transcription + AI + search models, skipping any
            // already installed. Re-reads tick state so it disables once everything is present.
            @Suppress("UNUSED_EXPRESSION") run { installedTick; aiTick; geckoTick }
            val needsSetup = ModelManager.listInstalled(context).isEmpty() ||
                LlmEngine.Downloadable.entries.any { !LlmEngine.isInstalled(context, it) } ||
                !Embedder.isReady(context)
            Button(
                onClick = {
                    scope.launch {
                        setupStatus = "Starting…"
                        if (ModelManager.listInstalled(context).isEmpty()) {
                            ModelManager.download(context, ModelManager.BuiltInModel.BASE_EN_Q5).collect { p ->
                                if (p in 0..99) setupStatus = "Transcription model… $p%"
                            }
                            installedTick++
                        }
                        for (d in LlmEngine.Downloadable.entries) {
                            if (LlmEngine.isInstalled(context, d)) continue
                            LlmEngine.download(context, d).collect { p ->
                                if (p in 0..99) setupStatus = "${LlmEngine.prettyName(d.filename)}… $p%"
                            }
                            aiTick++
                        }
                        if (!Embedder.isReady(context)) {
                            Embedder.download(context).collect { p ->
                                if (p in 0..99) setupStatus = "Search model… $p%"
                            }
                            geckoTick++
                        }
                        setupStatus = null
                    }
                },
                enabled = setupStatus == null && needsSetup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(setupStatus ?: if (needsSetup) "Download all models (~6.4 GB)" else "All models installed ✓")
            }

            HorizontalDivider()

            Text("Model", style = MaterialTheme.typography.titleMedium)
            models.forEach { m ->
                @Suppress("UNUSED_EXPRESSION") installedTick // re-read so this row recomputes
                val installed = ModelManager.isDownloaded(context, m)
                val pct = progress[m.id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selected == m.id,
                        enabled = installed,
                        onClick = { selected = m.id; prefs.selectedModelId = m.id },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName)
                        Text(
                            "${m.approxSizeMb} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    when {
                        pct != null -> CircularProgressIndicator(
                            progress = { pct / 100f }, modifier = Modifier.size(24.dp)
                        )
                        installed -> Icon(
                            Icons.Filled.CheckCircle, "Installed",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        else -> IconButton(onClick = {
                            scope.launch {
                                ModelManager.download(context, m).collect { p ->
                                    if (p in 0..99) progress[m.id] = p
                                    else if (p < 0) { progress.remove(m.id); installedTick++ }
                                }
                            }
                        }) { Icon(Icons.Filled.Download, "Download") }
                    }
                }
            }

            HorizontalDivider()

            Text("Language", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = language,
                onValueChange = { language = it; prefs.language = it.trim() },
                label = { Text("ISO code — blank = auto-detect") },
                placeholder = { Text("e.g. en, de, fr") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Translate to English")
                    Text(
                        "Transcribe non-English speech but output it in English.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = translate,
                    onCheckedChange = { translate = it; prefs.translateToEnglish = it },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Live transcript in notification")
                    Text(
                        "Show the transcription in the notification shade while recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = liveNotif,
                    onCheckedChange = { liveNotif = it; prefs.liveTranscribeNotification = it },
                )
            }
            Text(
                "Transcription runs fully on-device. A model downloads once over the internet, then everything is offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider()

            Text("AI model (Gemma)", style = MaterialTheme.typography.titleMedium)
            Text(
                "On-device LLM (LiteRT-LM) for AI tagging, formatting and Q&A. Download one below (once, then offline), or import a .litertlm file. Runs on GPU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            // Downloadable Gemma bundles (Nextcloud). E2B = default/fast, E4B = deeper.
            LlmEngine.Downloadable.entries.forEach { d ->
                @Suppress("UNUSED_EXPRESSION") aiTick // re-read so this row recomputes after a download
                val installed = LlmEngine.isInstalled(context, d)
                val pct = llmPct[d.name]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(LlmEngine.prettyName(d.filename))
                        Text(
                            "${d.approxSizeMb} MB · on-device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    when {
                        pct != null -> CircularProgressIndicator(
                            progress = { pct / 100f }, modifier = Modifier.size(24.dp)
                        )
                        installed -> Icon(
                            Icons.Filled.CheckCircle, "Installed",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        else -> IconButton(onClick = {
                            scope.launch {
                                llmPct[d.name] = 0
                                LlmEngine.download(context, d).collect { p ->
                                    if (p in 0..99) llmPct[d.name] = p
                                    else if (p < 0) { llmPct.remove(d.name); aiTick++ }
                                }
                            }
                        }) { Icon(Icons.Filled.Download, "Download") }
                    }
                }
            }
            // Semantic search/relate/cluster model (Gecko) — downloads once, then offline.
            val geckoReady = remember(geckoTick) { Embedder.isReady(context) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Search model (Gecko)")
                    Text(
                        "Powers semantic relate, search & clusters. ${Embedder.SIZE_MB} MB, on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                when {
                    geckoPct != null -> CircularProgressIndicator(
                        progress = { geckoPct!! / 100f }, modifier = Modifier.size(24.dp)
                    )
                    geckoReady -> Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
                    else -> IconButton(onClick = {
                        scope.launch {
                            geckoPct = 0
                            Embedder.download(context).collect { p ->
                                if (p in 0..99) geckoPct = p else if (p < 0) { geckoPct = null; geckoTick++ }
                            }
                        }
                    }) { Icon(Icons.Filled.Download, "Download") }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-tag new notes")
                    Text(
                        "Tag each note with Gemma right after transcription.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(checked = autoTag, onCheckedChange = { autoTag = it; prefs.autoTag = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-format as Markdown")
                    Text(
                        "Rewrite each note as Markdown (lists, tickable checklists) with Gemma E4B after transcription.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(checked = autoMarkdown, onCheckedChange = { autoMarkdown = it; prefs.autoMarkdown = it })
            }

            val installedModels = remember(aiTick) { LlmEngine.installed(context) }

            if (installedModels.size > 1) {
                val tagResolved = LlmEngine.resolve(context, tagModel, "E2B")?.name
                val analysisResolved = LlmEngine.resolve(context, analysisModel, "4b")?.name
                Text("Tagging model", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    installedModels.forEach { f ->
                        FilterChip(
                            selected = tagResolved == f.name,
                            onClick = { tagModel = f.name; prefs.tagModelFilename = f.name; LlmEngine.release() },
                            label = { Text(LlmEngine.prettyName(f.name)) },
                        )
                    }
                }
                Text("Analysis model", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    installedModels.forEach { f ->
                        FilterChip(
                            selected = analysisResolved == f.name,
                            onClick = { analysisModel = f.name; prefs.analysisModelFilename = f.name; LlmEngine.release() },
                            label = { Text(LlmEngine.prettyName(f.name)) },
                        )
                    }
                }
            }

            // Installed models — sizes + remove.
            if (installedModels.isNotEmpty()) {
                Text("Installed models", style = MaterialTheme.typography.titleSmall)
            }
            installedModels.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(LlmEngine.prettyName(f.name), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${f.length() / 1_000_000} MB · ${f.name.takeLast(40)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    TextButton(onClick = { LlmEngine.release(); f.delete(); aiTick++ }) { Text("Remove") }
                }
            }

            if (importingModel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("  Importing…")
                }
            } else {
                Button(onClick = { pickGemma.launch(arrayOf("*/*")) }) {
                    Text("Import .litertlm / .task file")
                }
            }
            aiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Text("Export", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save all notes as one Markdown file (titles, tags, checklists). Pick where it goes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Button(
                onClick = { exportNotes.launch("soundscript-notes.md") },
                enabled = notes.isNotEmpty(),
            ) {
                Text("Export ${notes.size} notes")
            }
        }
    }
}
