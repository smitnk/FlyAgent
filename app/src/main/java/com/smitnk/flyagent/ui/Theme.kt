// Colour system adapted from Mobile Harness (github.com/techjarves/Mobile-Harness, ui/theme/Theme.kt), MIT License,
// Copyright (c) 2026 Mobile Harness Contributors.
package com.smitnk.flyagent.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val PocketOrange = Color(0xFFF28C52)
val PocketBlue = Color(0xFF8EA8FF)
val PocketGreen = Color(0xFF69D69E)
val PocketBackground = Color(0xFF0B0E14)
val PocketSurface = Color(0xFF131821)
val PocketSurfaceVariant = Color(0xFF1B222D)
val PocketOutline = Color(0xFF2A3240)

private val DarkColors = darkColorScheme(
    primary = PocketOrange, onPrimary = Color(0xFF241107), primaryContainer = Color(0xFF42281D), onPrimaryContainer = Color(0xFFFFDDCC),
    secondary = PocketBlue, onSecondary = Color(0xFF001F58), tertiary = PocketGreen, onTertiary = Color(0xFF00391E),
    background = PocketBackground, onBackground = Color(0xFFE6EDF3), surface = PocketSurface, onSurface = Color(0xFFE6EDF3),
    surfaceVariant = PocketSurfaceVariant, onSurfaceVariant = Color(0xFF9AA0A6), outline = PocketOutline, outlineVariant = Color(0xFF333B4A),
    surfaceContainer = PocketSurface, surfaceContainerHigh = PocketSurfaceVariant,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD85A20), onPrimary = Color.White, primaryContainer = Color(0xFFFFE0D2), onPrimaryContainer = Color(0xFF451A08),
    secondary = Color(0xFF3366CC), onSecondary = Color.White, tertiary = Color(0xFF1B8A5A), onTertiary = Color.White,
    background = Color(0xFFF6F8FA), onBackground = Color(0xFF1F2328), surface = Color.White, onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEAEFF5), onSurfaceVariant = Color(0xFF57606A), outline = Color(0xFFD0D7DE), outlineVariant = Color(0xFFD8DEE4),
)

@Composable
fun FlyTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !isDark; isAppearanceLightNavigationBars = !isDark }
    }
    MaterialTheme(colorScheme = if (isDark) DarkColors else LightColors, content = content)
}
