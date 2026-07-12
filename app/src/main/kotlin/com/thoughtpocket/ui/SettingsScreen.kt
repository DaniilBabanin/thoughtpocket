package com.thoughtpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.thoughtpocket.CoderModelManager
import com.thoughtpocket.service.RecordingService
import androidx.compose.runtime.collectAsState
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.ModelDownloads
import com.thoughtpocket.ModelManager
import com.thoughtpocket.coercePasses
import com.thoughtpocket.finalPassEligible
import com.thoughtpocket.firstPassEligible
import com.thoughtpocket.notesToMarkdown
import com.thoughtpocket.ai.Embedder
import com.thoughtpocket.ai.LlmEngine
import com.thoughtpocket.data.NotesDb
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.thoughtpocket.NotesSyncEngine
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
fun SettingsScreen(bottomSpace: Dp, reduceMotion: Boolean, onReduceMotion: (Boolean) -> Unit, onOpenLicenses: () -> Unit, wide: Boolean) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val models = remember { ModelManager.BuiltInModel.entries.toList() }

    // Two-pass transcription: an instant first pass (live) and a quality final pass, each with its own model.
    var firstEnabled by remember { mutableStateOf(prefs.firstPassEnabled) }
    var firstModel by remember { mutableStateOf(prefs.firstPassModelId) }
    var finalEnabled by remember { mutableStateOf(prefs.finalPassEnabled) }
    var finalModel by remember { mutableStateOf(prefs.finalPassModelId) }
    var highQuality by remember { mutableStateOf(prefs.highQuality) }
    var language by remember { mutableStateOf(prefs.language) }
    var translate by remember { mutableStateOf(prefs.translateToEnglish) }
    // Downloads run in a process-lifetime scope so they survive leaving Settings; we just observe.
    val dl by ModelDownloads.progress.collectAsState()
    val dlDone by ModelDownloads.completed.collectAsState()

    var aiTick by remember { mutableStateOf(0) }
    var importingModel by remember { mutableStateOf(false) }
    var tagModel by remember { mutableStateOf(prefs.tagModelFilename) }
    var analysisModel by remember { mutableStateOf(prefs.analysisModelFilename) }
    var autoTag by remember { mutableStateOf(prefs.autoTag) }
    var autoMarkdown by remember { mutableStateOf(prefs.autoMarkdown) }
    var reformatAppended by remember { mutableStateOf(prefs.reformatAppendedNotes) }
    var liveNotif by remember { mutableStateOf(prefs.liveTranscribeNotification) }
    var saveAudio by remember { mutableStateOf(prefs.saveAudio) }
    var saveFolder by remember { mutableStateOf(prefs.saveAudioFolder) }
    var importFolder by remember { mutableStateOf(prefs.importFolder) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var syncEnabled by remember { mutableStateOf(prefs.syncEnabled) }
    var syncFolder by remember { mutableStateOf(prefs.syncFolder) }
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
    val pickSyncFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            prefs.syncFolder = uri.toString(); syncFolder = uri.toString()
            prefs.syncEnabled = true; syncEnabled = true
            NotesSyncEngine.requestSync()
        }
    }
    var aiError by remember { mutableStateOf<String?>(null) }
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
    val installedTranscription = remember(dlDone) {
        models.filter { ModelManager.isDownloaded(context, it) }.map { it.id }.toSet()
    }
    val installedMoonshine = remember(dlDone) {
        ModelManager.StreamingModel.entries.filter { ModelManager.isDownloaded(context, it) }.map { it.id }.toSet()
    }
    val installedGemma = remember(aiTick, dlDone) {
        LlmEngine.Downloadable.entries.filter { LlmEngine.isInstalled(context, it) }.map { it.name }.toSet()
    }
    val installedModels = remember(aiTick, dlDone) { LlmEngine.installed(context) }
    val installedSizes = remember(aiTick, dlDone) { installedModels.associate { it.name to it.length() / 1_000_000 } }
    val geckoReady = remember(dlDone) { Embedder.isReady(context) }
    val needsSetup = remember(aiTick, dlDone) {
        ModelManager.listInstalled(context).isEmpty() ||
            LlmEngine.Downloadable.entries.any { !LlmEngine.isInstalled(context, it) } ||
            !Embedder.isReady(context)
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .then(if (wide) Modifier.widthIn(max = 640.dp) else Modifier)   // tablet: cap + center
                .align(Alignment.TopCenter)
                .statusBarsPadding().verticalScroll(rememberScrollState())
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

        if (needsSetup) {
            val downloadingAll = ModelDownloads.ALL in dl
            Button(
                onClick = { ModelDownloads.all(context) },
                enabled = !downloadingAll,
                shape = ReachShapes.field,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (downloadingAll) "Downloading models…" else "Download all models (~6.4 GB)")
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

        // Instant transcription — the live first pass (streams words while recording).
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Instant transcription (first pass)", Modifier.padding(bottom = 4.dp))
            SwitchRow(
                "Stream words as you speak",
                "A live preview while recording. Moonshine is fastest (instant, English-only); a Whisper model also works (slower preview, no extra download).",
                firstEnabled,
            ) { want ->
                val (f, fin) = coercePasses(want, finalEnabled, keepFinalOnConflict = true)
                firstEnabled = f; finalEnabled = fin
                prefs.firstPassEnabled = f; prefs.finalPassEnabled = fin
            }
            if (firstEnabled) {
                (ModelManager.StreamingModel.entries.toList<ModelManager.ModelEntry>() + models)
                    .filter { it.firstPassEligible }.forEach { m ->
                    ModelChoiceRow(
                        name = m.displayName, sizeMb = m.approxSizeMb,
                        selected = firstModel == m.id,
                        installed = m.id in installedMoonshine || m.id in installedTranscription,
                        pct = dl[m.id],
                        onSelect = { firstModel = m.id; prefs.firstPassModelId = m.id },
                        onDownload = {
                            if (m is ModelManager.StreamingModel) ModelDownloads.moonshine(context, m)
                            else (m as? ModelManager.BuiltInModel)?.let { ModelDownloads.whisper(context, it) }
                        },
                    )
                }
                SwitchRow(
                    "Live transcript in notification",
                    "Show the live preview in the notification shade while recording.",
                    liveNotif,
                ) { liveNotif = it; prefs.liveTranscribeNotification = it }
            }
        }

        // Final transcript — the quality batch pass that owns the durable note.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Final transcript (final pass)", Modifier.padding(bottom = 4.dp))
            SwitchRow(
                "Re-transcribe on stop for the best note",
                "One high-quality batch pass over the whole recording when you stop (Whisper). Off = the instant transcript becomes the note.",
                finalEnabled,
            ) { want ->
                val (f, fin) = coercePasses(firstEnabled, want, keepFinalOnConflict = false)
                firstEnabled = f; finalEnabled = fin
                prefs.firstPassEnabled = f; prefs.finalPassEnabled = fin
            }
            if (finalEnabled) {
                models.filter { it.finalPassEligible }.forEach { m ->
                    ModelChoiceRow(
                        name = m.displayName, sizeMb = m.approxSizeMb,
                        selected = finalModel == m.id, installed = m.id in installedTranscription, pct = dl[m.id],
                        onSelect = { finalModel = m.id; prefs.finalPassModelId = m.id },
                        onDownload = { ModelDownloads.whisper(context, m) },
                    )
                }
                SwitchRow(
                    "Higher quality (beam search)",
                    "Slower but more accurate on noisy or accented speech.",
                    highQuality,
                ) { highQuality = it; prefs.highQuality = it }
            }
            Text(
                "Use one or both. Instant-only saves the live transcript; final-only is the classic high-quality note; both shows a live preview and saves the best.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
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
                val pct = dl[d.name]
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
                        else -> IconButton(onClick = { ModelDownloads.gemma(context, d) }) {
                            Icon(Icons.Filled.Download, "Download")
                        }
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
                val geckoPct = dl["gecko"]
                when {
                    geckoPct != null -> CircularProgressIndicator(progress = { geckoPct / 100f }, modifier = Modifier.size(24.dp))
                    geckoReady -> Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
                    else -> IconButton(onClick = { ModelDownloads.gecko(context) }) {
                        Icon(Icons.Filled.Download, "Download")
                    }
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

        // Two-way notes ↔ folder sync (point it at a Nextcloud-synced folder for backup + multi-device).
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Sync notes to a folder", Modifier.padding(bottom = 4.dp))
            SwitchRow(
                "Sync notes to a folder",
                if (syncEnabled && syncFolder.isNotEmpty())
                    "Mirroring each note as a Markdown file in ${folderLabel(syncFolder)}, two-way. Point Nextcloud (or similar) at it to back up and sync across devices."
                else
                    "Keep one Markdown file per note in a folder you choose — two-way, so notes dropped in are picked up. Point Nextcloud at it for multi-device sync.",
                syncEnabled,
            ) { on ->
                if (on) {
                    if (syncFolder.isEmpty()) pickSyncFolder.launch(null)
                    else { prefs.syncEnabled = true; syncEnabled = true; NotesSyncEngine.requestSync() }
                } else { prefs.syncEnabled = false; syncEnabled = false }
            }
            if (syncEnabled && syncFolder.isNotEmpty()) {
                TextButton(onClick = { pickSyncFolder.launch(null) }) { Text("Change folder") }
            }
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

        // Experimental: "Code this" on-device coding agent (Ornith GGUF + llama.cpp).
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle("Experimental", Modifier.padding(bottom = 4.dp))
            var coderOn by remember { mutableStateOf(prefs.experimentalCoder) }
            var coderTick by remember { mutableStateOf(0) }
            SwitchRow(
                "Code this",
                "A local coding AI writes and runs small Python scripts over a note (slow — minutes per answer, fully offline).",
                coderOn,
            ) { coderOn = it; prefs.experimentalCoder = it }
            if (coderOn) {
                var showCode by remember { mutableStateOf(prefs.coderShowCode) }
                SwitchRow(
                    "Show code details",
                    "Coding items show their script: view, edit, rerun, revert.",
                    showCode,
                ) { showCode = it; prefs.coderShowCode = it }
                val coderModels = remember(coderTick, dlDone) { CoderModelManager.installedModels(context) }
                CoderModelManager.BuiltInCoderModel.entries.forEach { m ->
                    val installed = coderModels.any { it.name == m.filename }
                    val pct = dl[m.id]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName)
                            Text(
                                "${m.approxSizeMb} MB · needs ~6 GB free",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            pct != null -> CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(24.dp))
                            installed -> Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
                            else -> IconButton(onClick = { ModelDownloads.coder(context, m) }) {
                                Icon(Icons.Filled.Download, "Download")
                            }
                        }
                    }
                }
                // Bring-your-own GGUF (BYO models use the same ChatML prompt — Qwen-family works best).
                var importingCoder by remember { mutableStateOf(false) }
                val pickCoder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch {
                        importingCoder = true
                        CoderModelManager.importFromUri(context, uri)
                            .onFailure { aiError = it.message }
                        importingCoder = false; coderTick++
                    }
                }
                TextButton(onClick = { pickCoder.launch(arrayOf("*/*")) }, enabled = !importingCoder) {
                    if (importingCoder) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                    Text("Import a .gguf model…")
                }
                // Model choice + removal (a 5.6 GB file needs a delete affordance).
                if (coderModels.isNotEmpty()) {
                    val chosen = CoderModelManager.selectedModel(context)?.name
                    coderModels.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = f.name == chosen, onClick = {
                                prefs.coderModelFilename = f.name; coderTick++
                            })
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${f.length() / 1_000_000} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                CoderModelManager.delete(context, f)
                                if (prefs.coderModelFilename == f.name) prefs.coderModelFilename = ""
                                coderTick++
                            }) { Icon(Icons.Filled.Delete, "Delete ${f.name}") }
                        }
                    }
                }
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
                LicenseItem("sherpa-onnx (k2-fsa)", "© Xiaomi Corporation")
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("BSD 3-Clause")
                LicenseItem("Protocol Buffers (protobuf-javalite)", "© Google Inc.")
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("MIT License")
                LicenseItem("whisper.cpp (incl. ggml)", "© Georgi Gerganov and the ggml-org contributors")
                LicenseItem("llama.cpp", "© Georgi Gerganov and the ggml-org contributors")
                LicenseItem("ONNX Runtime", "© Microsoft Corporation")
                LicenseItem("Silero VAD", "© Silero Team")
                LicenseItem("Chaquopy", "© Chaquo Ltd")
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("Python Software Foundation License")
                LicenseItem("CPython & the Python standard library (embedded via Chaquopy)", "© Python Software Foundation")
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("On-device AI models")
                LicenseItem("Gemma", "© Google — Gemma Terms of Use")
                LicenseItem("Gecko / Universal Sentence Encoder embeddings", "© Google — Apache License 2.0")
                LicenseItem("Moonshine (English models)", "© Useful Sensors, Inc. (Moonshine AI) — MIT License")
                LicenseItem("Ornith 1.0 (coding model, experimental)", "© DeepReinforce — MIT License")
            }
            ExpandableLicense("Apache License 2.0 — full text", APACHE_2_0)
            ExpandableLicense("BSD 3-Clause — full text", BSD_3_CLAUSE)
            ExpandableLicense("MIT License — full text", MIT_LICENSE)
            ExpandableLicense("Python Software Foundation License — full text", PSF_LICENSE)
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

/** A license whose long full text is collapsed behind a tappable header (keeps the screen short). */
@Composable
private fun ExpandableLicense(title: String, text: String) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(title, Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                if (expanded) "Collapse" else "Expand",
            )
        }
        if (expanded) Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
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

/** A selectable transcription-model row: radio + name/size + install/download/progress affordance. */
@Composable
private fun ModelChoiceRow(
    name: String,
    sizeMb: Int,
    selected: Boolean,
    installed: Boolean,
    pct: Int?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ReachRadio(selected = selected, enabled = installed, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(name)
            Text(
                "$sizeMb MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            pct != null -> CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.size(24.dp))
            installed -> Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
            else -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Download") }
        }
    }
}

private const val MIT_LICENSE = """MIT License

whisper.cpp (incl. ggml) — Copyright (c) Georgi Gerganov and the ggml-org contributors
ONNX Runtime — Copyright (c) Microsoft Corporation
Silero VAD — Copyright (c) Silero Team
Moonshine (English models) — Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

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

private const val PSF_LICENSE = """PYTHON SOFTWARE FOUNDATION LICENSE VERSION 2

1. This LICENSE AGREEMENT is between the Python Software Foundation ("PSF"), and the Individual or Organization ("Licensee") accessing and otherwise using this software ("Python") in source or binary form and its associated documentation.

2. Subject to the terms and conditions of this License Agreement, PSF hereby grants Licensee a nonexclusive, royalty-free, world-wide license to reproduce, analyze, test, perform and/or display publicly, prepare derivative works, distribute, and otherwise use Python alone or in any derivative version, provided, however, that PSF's License Agreement and PSF's notice of copyright, i.e., "Copyright (c) 2001-2024 Python Software Foundation; All Rights Reserved" are retained in Python alone or in any derivative version prepared by Licensee.

3. In the event Licensee prepares a derivative work that is based on or incorporates Python or any part thereof, and wants to make the derivative work available to others as provided herein, then Licensee hereby agrees to include in any such work a brief summary of the changes made to Python.

4. PSF is making Python available to Licensee on an "AS IS" basis. PSF MAKES NO REPRESENTATIONS OR WARRANTIES, EXPRESS OR IMPLIED. BY WAY OF EXAMPLE, BUT NOT LIMITATION, PSF MAKES NO AND DISCLAIMS ANY REPRESENTATION OR WARRANTY OF MERCHANTABILITY OR FITNESS FOR ANY PARTICULAR PURPOSE OR THAT THE USE OF PYTHON WILL NOT INFRINGE ANY THIRD PARTY RIGHTS.

5. PSF SHALL NOT BE LIABLE TO LICENSEE OR ANY OTHER USERS OF PYTHON FOR ANY INCIDENTAL, SPECIAL, OR CONSEQUENTIAL DAMAGES OR LOSS AS A RESULT OF MODIFYING, DISTRIBUTING, OR OTHERWISE USING PYTHON, OR ANY DERIVATIVE THEREOF, EVEN IF ADVISED OF THE POSSIBILITY THEREOF.

6. This License Agreement will automatically terminate upon a material breach of its terms and conditions.

7. Nothing in this License Agreement shall be deemed to create any relationship of agency, partnership, or joint venture between PSF and Licensee.

8. By copying, installing or otherwise using Python, Licensee agrees to be bound by the terms and conditions of this License Agreement."""

private const val BSD_3_CLAUSE = """BSD 3-Clause License

Copyright (c) Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."""
