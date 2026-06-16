package com.thoughtpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.thoughtpocket.service.RecordingService
import androidx.compose.runtime.collectAsState
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.ModelManager
import com.thoughtpocket.notesToMarkdown
import com.thoughtpocket.ai.Embedder
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.data.NotesDb
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.thoughtpocket.ui.theme.GlassCard
import com.thoughtpocket.ui.theme.GlassTextField
import com.thoughtpocket.ui.theme.ReachChip
import com.thoughtpocket.ui.theme.ReachRadio
import com.thoughtpocket.ui.theme.ReachShapes
import com.thoughtpocket.ui.theme.ReachSwitch
import com.thoughtpocket.ui.theme.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(bottomSpace: Dp, reduceMotion: Boolean, onReduceMotion: (Boolean) -> Unit) {
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
    var reformatAppended by remember { mutableStateOf(prefs.reformatAppendedNotes) }
    var liveTranscribe by remember { mutableStateOf(prefs.liveTranscription) }
    var liveNotif by remember { mutableStateOf(prefs.liveTranscribeNotification) }
    var saveAudio by remember { mutableStateOf(prefs.saveAudio) }
    var saveFolder by remember { mutableStateOf(prefs.saveAudioFolder) }
    var importFolder by remember { mutableStateOf(prefs.importFolder) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val pickSaveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            prefs.saveAudioFolder = uri.toString(); saveFolder = uri.toString(); prefs.saveAudio = true; saveAudio = true
        }
    }
    val pickImportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.importFolder = uri.toString(); importFolder = uri.toString()
            context.startForegroundService(RecordingService.importIntent(context)); importMsg = "Importing… new notes will appear as files finish."
        }
    }
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

    // All filesystem checks cached, keyed on the tick counters, so they never run during scroll.
    val installedTranscription = remember(installedTick) {
        models.filter { ModelManager.isDownloaded(context, it) }.map { it.id }.toSet()
    }
    val installedGemma = remember(aiTick) {
        LlmEngine.Downloadable.entries.filter { LlmEngine.isInstalled(context, it) }.map { it.name }.toSet()
    }
    val installedModels = remember(aiTick) { LlmEngine.installed(context) }
    val installedSizes = remember(aiTick) { installedModels.associate { it.name to it.length() / 1_000_000 } }
    val geckoReady = remember(geckoTick) { Embedder.isReady(context) }
    val needsSetup = remember(installedTick, aiTick, geckoTick) {
        ModelManager.listInstalled(context).isEmpty() ||
            LlmEngine.Downloadable.entries.any { !LlmEngine.isInstalled(context, it) } ||
            !Embedder.isReady(context)
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomSpace),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )

        if (needsSetup || setupStatus != null) {
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
                enabled = setupStatus == null,
                shape = ReachShapes.field,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(setupStatus ?: "Download all models (~6.4 GB)")
            }
        } else {
            // Tonal "installed" status pill (the design's `.btn.done`).
            Row(
                Modifier.fillMaxWidth()
                    .clip(ReachShapes.field)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("On-device · all models installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }

        // Interface.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Interface", Modifier.padding(bottom = 4.dp))
            SwitchRow("Reduce animations", "Turn off card reveals, glow and pulse motion.", reduceMotion, onReduceMotion)
        }

        // Transcription model.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Transcription model", Modifier.padding(bottom = 4.dp))
            models.forEach { m ->
                val installed = m.id in installedTranscription
                val pct = progress[m.id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReachRadio(
                        selected = selected == m.id,
                        enabled = installed,
                        onClick = { selected = m.id; prefs.selectedModelId = m.id },
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName)
                        Text(
                            "${m.approxSizeMb} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        pct != null -> CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(24.dp))
                        installed -> Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
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
        }

        // Language.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Language", Modifier.padding(bottom = 4.dp))
            GlassTextField(
                value = language,
                onValueChange = { language = it; prefs.language = it.trim() },
                placeholder = "e.g. en, de, fr",
                label = "ISO code — blank = auto-detect",
                flat = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                "Translate to English",
                "Transcribe non-English speech but output it in English.",
                translate,
            ) { translate = it; prefs.translateToEnglish = it }
            SwitchRow(
                "Live transcription",
                "Show text as you speak. Turn off to save battery on long recordings.",
                liveTranscribe,
            ) { liveTranscribe = it; prefs.liveTranscription = it }
            SwitchRow(
                "Live transcript in notification",
                "Show the transcription in the notification shade while recording.",
                liveNotif,
            ) { liveNotif = it; prefs.liveTranscribeNotification = it }
            Text(
                "Transcription runs fully on-device. A model downloads once over the internet, then everything is offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // AI model (Gemma).
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("AI model (Gemma)", Modifier.padding(bottom = 4.dp))
            Text(
                "On-device LLM (LiteRT-LM) for AI tagging, formatting and Q&A. Download one below (once, then offline), or import a .litertlm file. Runs on GPU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Downloadable Gemma bundles (Nextcloud). E2B = default/fast, E4B = deeper.
            LlmEngine.Downloadable.entries.forEach { d ->
                val installed = d.name in installedGemma
                val pct = llmPct[d.name]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(LlmEngine.prettyName(d.filename))
                        Text(
                            "${d.approxSizeMb} MB · on-device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        pct != null -> CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(24.dp))
                        installed -> Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Search model (Gecko)")
                    Text(
                        "Powers semantic relate, search & clusters. ${Embedder.SIZE_MB} MB, on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    geckoPct != null -> CircularProgressIndicator(progress = { geckoPct!! / 100f }, modifier = Modifier.size(24.dp))
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

            SwitchRow("Auto-tag new notes", "Tag each note with Gemma right after transcription.", autoTag) {
                autoTag = it; prefs.autoTag = it
            }
            SwitchRow(
                "Auto-format as Markdown",
                "Rewrite each note as Markdown (lists, tickable checklists) with Gemma E4B after transcription.",
                autoMarkdown,
            ) { autoMarkdown = it; prefs.autoMarkdown = it }
            SwitchRow(
                "Reformat after adding recordings",
                "When you record more into an open note, append the raw transcript, then reformat it and refresh tags once the recordings finish.",
                reformatAppended,
            ) { reformatAppended = it; prefs.reformatAppendedNotes = it }

            if (installedModels.size > 1) {
                // Resolve from the cached list — LlmEngine.resolve() re-lists+stats the model dir (disk I/O).
                val tagResolved = remember(installedModels, tagModel) { resolveName(installedModels, tagModel, "E2B") }
                val analysisResolved = remember(installedModels, analysisModel) { resolveName(installedModels, analysisModel, "4b") }
                SectionTitle("Tagging model", Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    installedModels.forEach { f ->
                        ReachChip(LlmEngine.prettyName(f.name), tagResolved == f.name, {
                            tagModel = f.name; prefs.tagModelFilename = f.name; LlmEngine.release()
                        })
                    }
                }
                SectionTitle("Analysis model", Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    installedModels.forEach { f ->
                        ReachChip(LlmEngine.prettyName(f.name), analysisResolved == f.name, {
                            analysisModel = f.name; prefs.analysisModelFilename = f.name; LlmEngine.release()
                        })
                    }
                }
            }

            // Installed models — sizes + remove.
            if (installedModels.isNotEmpty()) {
                SectionTitle("Installed models", Modifier.padding(top = 4.dp))
            }
            installedModels.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(LlmEngine.prettyName(f.name), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${installedSizes[f.name] ?: 0} MB · ${f.name.takeLast(40)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { LlmEngine.release(); f.delete(); aiTick++ }) { Text("Remove") }
                }
            }

            if (importingModel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Text("  Importing…")
                }
            } else {
                Button(onClick = { pickGemma.launch(arrayOf("*/*")) }, shape = ReachShapes.field) {
                    Text("Import .litertlm / .task file")
                }
            }
            aiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Audio files: save recordings to a folder + import audio from a folder.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Audio files", Modifier.padding(bottom = 4.dp))
            SwitchRow(
                "Save recordings to a folder",
                if (saveAudio && saveFolder.isNotEmpty()) "Keeping a WAV of every recording in ${folderLabel(saveFolder)} (survives reinstall)."
                else "Keep a playable WAV of every recording in a folder you choose.",
                saveAudio,
            ) { on ->
                if (on) { if (saveFolder.isEmpty()) pickSaveFolder.launch(null) else { prefs.saveAudio = true; saveAudio = true } }
                else { prefs.saveAudio = false; saveAudio = false }
            }
            if (saveAudio && saveFolder.isNotEmpty()) {
                TextButton(onClick = { pickSaveFolder.launch(null) }) { Text("Change folder") }
            }

            SectionTitle("Import audio from a folder", Modifier.padding(top = 10.dp, bottom = 4.dp))
            Text(
                if (importFolder.isEmpty()) "Pick a folder of recordings (m4a, mp3, wav, opus…); each becomes a transcribed note dated by the file. Drop more in and tap Scan."
                else "Importing from ${folderLabel(importFolder)}. Tap Scan after dropping in new files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickImportFolder.launch(null) }, shape = ReachShapes.field) {
                    Text(if (importFolder.isEmpty()) "Choose folder…" else "Change folder")
                }
                if (importFolder.isNotEmpty()) {
                    Button(onClick = {
                        context.startForegroundService(RecordingService.importIntent(context)); importMsg = "Importing…"
                    }, shape = ReachShapes.field) { Text("Scan now") }
                }
            }
            importMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }
        }

        // Export.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Export", Modifier.padding(bottom = 4.dp))
            Text(
                "Save all notes as one Markdown file (titles, tags, checklists). Pick where it goes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { exportNotes.launch("thoughtpocket-notes.md") },
                enabled = notes.isNotEmpty(),
                shape = ReachShapes.field,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text("Export ${notes.size} notes")
            }
        }
    }
}

/** Pure (no-disk) resolve over an already-loaded file list — safe to call in composition. */
private fun resolveName(files: List<File>, explicit: String, fallbackContains: String): String? =
    (files.firstOrNull { it.name == explicit }
        ?: files.firstOrNull { it.name.contains(fallbackContains, ignoreCase = true) }
        ?: files.firstOrNull())?.name

/** Human-ish folder name from a SAF tree URI (e.g. ".../tree/primary%3AMusic%2FMemos" → "Memos"). */
private fun folderLabel(treeUriStr: String): String =
    Uri.decode(treeUriStr).substringAfterLast(':').substringAfterLast('/').ifBlank { "the selected folder" }

/** Title + description on the left, a Reach switch on the right. */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ReachSwitch(checked = checked, onChange = onChange)
    }
}
