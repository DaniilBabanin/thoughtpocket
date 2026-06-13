package com.soundscript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.soundscript.AppPreferences
import com.soundscript.ModelManager
import kotlinx.coroutines.launch

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
        }
    }
}
