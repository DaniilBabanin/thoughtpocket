package com.soundscript.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.soundscript.ui.theme.SoundScriptTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SoundScriptTheme { AppRoot() } }
    }
}

private sealed interface Route {
    data object List : Route
    data class Detail(val id: Long) : Route
    data object Settings : Route
}

@Composable
private fun AppRoot() {
    var route by remember { mutableStateOf<Route>(Route.List) }
    when (val r = route) {
        is Route.List -> NotesListScreen(
            onOpen = { route = Route.Detail(it) },
            onSettings = { route = Route.Settings },
        )
        is Route.Detail -> NoteDetailScreen(id = r.id, onBack = { route = Route.List })
        is Route.Settings -> SettingsScreen(onBack = { route = Route.List })
    }
}
