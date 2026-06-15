package com.soundscript.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand accent: turquoise #00b3a4 (white reads on it). Used app-wide unless dynamic color is on.
private val Turquoise = Color(0xFF00B3A4)

// "Reach" canvas tokens (from the design reference). Neutrals stay near-black/light-gray so the
// glass + accent-glow read correctly; only the accent follows dynamic color when enabled.
private val ReachDark = darkColorScheme(
    primary = Turquoise,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF00504A),
    onPrimaryContainer = Color(0xFFA6F3EA),
    secondary = Color(0xFF6FD3C6),
    onSecondary = Color(0xFF00382F),
    tertiary = Color(0xFF8FD0E0),
    background = Color(0xFF0C0C12),
    onBackground = Color(0xFFECEAF2),
    surface = Color(0xFF1A1A24),
    onSurface = Color(0xFFECEAF2),
    surfaceVariant = Color(0xFF26262F),
    onSurfaceVariant = Color(0xFF8F8EA0),
    outline = Color(0xFF45454F),
    outlineVariant = Color(0xFF2A2A33),
    error = Color(0xFFFF6B5E),
    errorContainer = Color(0xFFD8493F),
    onErrorContainer = Color(0xFFFFFFFF),
)

private val ReachLight = lightColorScheme(
    primary = Turquoise,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F3EA),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF316B63),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF3D6373),
    background = Color(0xFFEEF1F5),
    onBackground = Color(0xFF15151B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15151B),
    surfaceVariant = Color(0xFFE2E6EC),
    onSurfaceVariant = Color(0xFF585C69),
    outline = Color(0xFFB6BAC4),
    outlineVariant = Color(0xFFD2D6DE),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** When true, non-essential motion is suppressed (Settings → Reduce animations). */
val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun SoundScriptTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: the app ships the turquoise "Reach" identity. Flip on to mirror Material You.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) ReachDark else ReachLight
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Keep the Reach neutral canvas; borrow only the accent roles from the wallpaper palette.
        val ctx = LocalContext.current
        val dyn = if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        base.copy(
            primary = dyn.primary, onPrimary = dyn.onPrimary,
            primaryContainer = dyn.primaryContainer, onPrimaryContainer = dyn.onPrimaryContainer,
            secondary = dyn.secondary, onSecondary = dyn.onSecondary, tertiary = dyn.tertiary,
        )
    } else base

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge: transparent bars so the accent glow reaches behind them.
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !darkTheme
            insets.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = ReachTypography, content = content)
}
