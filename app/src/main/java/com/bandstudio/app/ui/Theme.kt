package com.bandstudio.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColors = darkColorScheme(
    primary = Color(0xFF8EA8FF),
    onPrimary = Color(0xFF0B1330),
    background = Color(0xFF101217),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF181B22),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF222632),
    onSurfaceVariant = Color(0xFFB4B9C6),
    outline = Color(0xFF4A5062),
)

@Composable
fun BandStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioColors, content = content)
}
