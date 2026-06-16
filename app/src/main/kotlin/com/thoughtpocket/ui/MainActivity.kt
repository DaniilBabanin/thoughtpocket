package com.thoughtpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thoughtpocket.AppPreferences
import com.thoughtpocket.service.RecordState
import com.thoughtpocket.service.RecordingService
import com.thoughtpocket.ui.theme.LocalReduceMotion
import com.thoughtpocket.ui.theme.ReachBackground
import com.thoughtpocket.ui.theme.ReachBottomBar
import com.thoughtpocket.ui.theme.ReachTab
import com.thoughtpocket.ui.theme.ThoughtPocketTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ThoughtPocketTheme { AppRoot() } }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var tab by remember { mutableStateOf(ReachTab.Home) }
    var detailId by remember { mutableStateOf<Long?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    var reduceMotion by remember { mutableStateOf(prefs.reduceAnimations) }

    val status by RecordState.status.collectAsState()
    val recording = status.state == RecordState.State.RECORDING

    // Record start/stop lives here so the docked orb works on every tab.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            context.startForegroundService(RecordingService.startIntent(context))
        }
    }
    // Only RECORDING blocks a new start — transcription runs in the background, so you can fire
    // recordings in quick succession.
    fun startRecord() {
        if (recording) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            context.startForegroundService(RecordingService.startIntent(context))
        } else {
            val needed = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permLauncher.launch(needed.toTypedArray())
        }
    }
    fun stopRecord() {
        if (recording) context.startService(RecordingService.stopIntent(context))
    }

    BackHandler(enabled = detailId != null) { detailId = null }
    BackHandler(enabled = showLicenses) { showLicenses = false }

    // Space the scrollable tab content must leave clear for the floating bar + orb.
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = navInset + 100.dp

    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        // Custom glass surfaces are Boxes, not Material Surfaces, so default text/icon color must be
        // set here — otherwise it falls back to black (black-on-dark).
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
    ReachBackground {
        Box(Modifier.fillMaxSize()) {
            val id = detailId
            if (showLicenses) {
                LicensesScreen(onBack = { showLicenses = false })
            } else if (id != null) {
                NoteDetailScreen(id = id, onBack = { detailId = null }, onOpen = { detailId = it })
            } else {
                when (tab) {
                    ReachTab.Home -> NotesListScreen(onOpen = { detailId = it }, bottomSpace = bottomSpace)
                    ReachTab.Tasks -> ActionItemsScreen(onOpen = { detailId = it }, bottomSpace = bottomSpace)
                    ReachTab.Ask -> AnalyzeScreen(bottomSpace = bottomSpace)
                    ReachTab.Settings -> SettingsScreen(
                        bottomSpace = bottomSpace,
                        reduceMotion = reduceMotion,
                        onReduceMotion = { reduceMotion = it; prefs.reduceAnimations = it },
                        onOpenLicenses = { showLicenses = true },
                    )
                }
                ReachBottomBar(
                    selected = tab,
                    onSelect = { tab = it },
                    recording = recording,
                    onStartRecord = { startRecord() },
                    onStopRecord = { stopRecord() },
                    // Collected inside the orb only — never recomposes the screens.
                    level = RecordState.amplitude,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 18.dp),
                )
            }
        }
    }
    }
}
