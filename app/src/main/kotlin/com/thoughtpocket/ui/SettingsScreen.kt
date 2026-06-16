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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun SettingsScreen(bottomSpace: Dp, reduceMotion: Boolean, onReduceMotion: (Boolean) -> Unit, onOpenLicenses: () -> Unit) {
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

        // About.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("About", Modifier.padding(bottom = 4.dp))
            Text(
                "ThoughtPocket is built on open-source software.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenLicenses, shape = ReachShapes.field, modifier = Modifier.padding(top = 6.dp)) {
                Text("Open-source licenses")
            }
        }
    }
}

/** Static attribution screen — the bundled libraries and their licenses. Reached from Settings → About. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Open-source licenses", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "ThoughtPocket is built with the open-source software below. Thanks to the authors.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Apache License 2.0")
                LicenseItem("Jetpack Compose · AndroidX (Core, Activity, Lifecycle) · Material 3", "© Google / The Android Open Source Project")
                LicenseItem("Room", "© Google")
                LicenseItem("Kotlin & kotlinx.coroutines", "© JetBrains s.r.o. and contributors")
                LicenseItem("Google AI Edge — LiteRT-LM", "© Google")
                LicenseItem("Google AI Edge — On-device RAG (localagents)", "© Google")
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("BSD 3-Clause")
                LicenseItem("Protocol Buffers (protobuf-javalite)", "© Google Inc.")
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("On-device AI models")
                LicenseItem("Gemma", "© Google — Gemma Terms of Use")
                LicenseItem("Gecko / Universal Sentence Encoder embeddings", "© Google — Apache License 2.0")
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Apache License 2.0 — full text")
                Text(APACHE_2_0, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("BSD 3-Clause — full text")
                Text(BSD_3_CLAUSE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LicenseItem(name: String, by: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(by, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private const val APACHE_2_0 = """Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

"License" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.

"Licensor" shall mean the copyright owner or entity authorized by the copyright owner that is granting the License.

"Legal Entity" shall mean the union of the acting entity and all other entities that control, are controlled by, or are under common control with that entity. For the purposes of this definition, "control" means (i) the power, direct or indirect, to cause the direction or management of such entity, whether by contract or otherwise, or (ii) ownership of fifty percent (50%) or more of the outstanding shares, or (iii) beneficial ownership of such entity.

"You" (or "Your") shall mean an individual or Legal Entity exercising permissions granted by this License.

"Source" form shall mean the preferred form for making modifications, including but not limited to software source code, documentation source, and configuration files.

"Object" form shall mean any form resulting from mechanical transformation or translation of a Source form, including but not limited to compiled object code, generated documentation, and conversions to other media types.

"Work" shall mean the work of authorship, whether in Source or Object form, made available under the License, as indicated by a copyright notice that is included in or attached to the work (an example is provided in the Appendix below).

"Derivative Works" shall mean any work, whether in Source or Object form, that is based on (or derived from) the Work and for which the editorial revisions, annotations, elaborations, or other modifications represent, as a whole, an original work of authorship. For the purposes of this License, Derivative Works shall not include works that remain separable from, or merely link (or bind by name) to the interfaces of, the Work and Derivative Works thereof.

"Contribution" shall mean any work of authorship, including the original version of the Work and any modifications or additions to that Work or Derivative Works thereof, that is intentionally submitted to Licensor for inclusion in the Work by the copyright owner or by an individual or Legal Entity authorized to submit on behalf of the copyright owner. For the purposes of this definition, "submitted" means any form of electronic, verbal, or written communication sent to the Licensor or its representatives, including but not limited to communication on electronic mailing lists, source code control systems, and issue tracking systems that are managed by, or on behalf of, the Licensor for the purpose of discussing and improving the Work, but excluding communication that is conspicuously marked or otherwise designated in writing by the copyright owner as "Not a Contribution."

"Contributor" shall mean Licensor and any individual or Legal Entity on behalf of whom a Contribution has been received by Licensor and subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable (except as stated in this section) patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work, where such license applies only to those patent claims licensable by such Contributor that are necessarily infringed by their Contribution(s) alone or by combination of their Contribution(s) with the Work to which such Contribution(s) was submitted. If You institute patent litigation against any entity (including a cross-claim or counterclaim in a lawsuit) alleging that the Work or a Contribution incorporated within the Work constitutes direct or contributory patent infringement, then any patent licenses granted to You under this License for that Work shall terminate as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, and in Source or Object form, provided that You meet the following conditions:

(a) You must give any other recipients of the Work or Derivative Works a copy of this License; and

(b) You must cause any modified files to carry prominent notices stating that You changed the files; and

(c) You must retain, in the Source form of any Derivative Works that You distribute, all copyright, patent, trademark, and attribution notices from the Source form of the Work, excluding those notices that do not pertain to any part of the Derivative Works; and

(d) If the Work includes a "NOTICE" text file as part of its distribution, then any Derivative Works that You distribute must include a readable copy of the attribution notices contained within such NOTICE file, excluding those notices that do not pertain to any part of the Derivative Works, in at least one of the following places: within a NOTICE text file distributed as part of the Derivative Works; within the Source form or documentation, if provided along with the Derivative Works; or, within a display generated by the Derivative Works, if and wherever such third-party notices normally appear. The contents of the NOTICE file are for informational purposes only and do not modify the License. You may add Your own attribution notices within Derivative Works that You distribute, alongside or as an addendum to the NOTICE text from the Work, provided that such additional attribution notices cannot be construed as modifying the License.

You may add Your own copyright statement to Your modifications and may provide additional or different license terms and conditions for use, reproduction, or distribution of Your modifications, or for any such Derivative Works as a whole, provided Your use, reproduction, and distribution of the Work otherwise complies with the conditions stated in this License.

5. Submission of Contributions. Unless You explicitly state otherwise, any Contribution intentionally submitted for inclusion in the Work by You to the Licensor shall be under the terms and conditions of this License, without any additional terms or conditions. Notwithstanding the above, nothing herein shall supersede or modify the terms of any separate license agreement you may have executed with Licensor regarding such Contributions.

6. Trademarks. This License does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor, except as required for reasonable and customary use in describing the origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or agreed to in writing, Licensor provides the Work (and each Contributor provides its Contributions) on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied, including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE. You are solely responsible for determining the appropriateness of using or redistributing the Work and assume any risks associated with Your exercise of permissions under this License.

8. Limitation of Liability. In no event and under no legal theory, whether in tort (including negligence), contract, or otherwise, unless required by applicable law (such as deliberate and grossly negligent acts) or agreed to in writing, shall any Contributor be liable to You for damages, including any direct, indirect, special, incidental, or consequential damages of any character arising as a result of this License or out of the use or inability to use the Work (including but not limited to damages for loss of goodwill, work stoppage, computer failure or malfunction, or any and all other commercial damages or losses), even if such Contributor has been advised of the possibility of such damages.

9. Accepting Warranty or Additional Liability. While redistributing the Work or Derivative Works thereof, You may choose to offer, and charge a fee for, acceptance of support, warranty, indemnity, or other liability obligations and/or rights consistent with this License. However, in accepting such obligations, You may act only on Your own behalf and on Your sole responsibility, not on behalf of any other Contributor, and only if You agree to indemnify, defend, and hold each Contributor harmless for any liability incurred by, or claims asserted against, such Contributor by reason of your accepting any such warranty or additional liability.

END OF TERMS AND CONDITIONS"""

private const val BSD_3_CLAUSE = """BSD 3-Clause License

Copyright (c) Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."""
