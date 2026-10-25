package com.teaminpact.mobileprompt.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kyulolong point green, also the default highlight color and the icon accent. */
val PointGreen = Color(0xFF8FFF00)

private val DarkScheme = darkColorScheme(
    primary = PointGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2E4A00),
    onPrimaryContainer = PointGreen,
    secondary = Color(0xFFB8C7A6),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFECEEF0),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFECEEF0),
    surfaceVariant = Color(0xFF1C1F25),
    onSurfaceVariant = Color(0xFFB9BFC8),
    surfaceContainer = Color(0xFF171A1F),
    surfaceContainerHigh = Color(0xFF1F232A),
    surfaceContainerHighest = Color(0xFF262B33),
    error = Color(0xFFFF6B6B),
)

/** The app is dark-only, like the iOS build (it lives on top of a camera feed). */
@Composable
fun MobilePromptTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
