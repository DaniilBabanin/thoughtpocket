package com.soundscript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.soundscript.AppPreferences
import com.soundscript.ModelManager
import com.soundscript.ai.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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
    var selectedLlm by remember { mutableStateOf(prefs.llmModelFilename) }
    var autoTag by remember { mutableStateOf(prefs.autoTag) }
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
                prefs.llmModelFilename = imported; selectedLlm = imported; LlmEngine.release()
            }
            importingModel = false; aiTick++
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
            Text(
                "Transcription runs fully on-device. A model downloads once over the internet, then everything is offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider()

            Text("AI model (Gemma)", style = MaterialTheme.typography.titleMedium)
            Text(
                "On-device LLM (LiteRT-LM) for AI tagging — more features later. Import a Gemma .litertlm model, e.g. one downloaded in AI Edge Gallery. Runs on GPU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
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

            // Installed models (downloaded or imported) — select active / remove.
            val installedModels = remember(aiTick) { LlmEngine.installed(context) }
            installedModels.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedLlm.ifEmpty { installedModels.first().name } == f.name,
                        onClick = { selectedLlm = f.name; prefs.llmModelFilename = f.name; LlmEngine.release() },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${f.length() / 1_000_000} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    TextButton(onClick = {
                        LlmEngine.release(); f.delete()
                        if (selectedLlm == f.name) { selectedLlm = ""; prefs.llmModelFilename = "" }
                        aiTick++
                    }) { Text("Remove") }
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
        }
    }
}
