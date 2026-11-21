package com.bandstudio.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColors = darkColorScheme(
    primary = Color(0xFF8EA8FF),
    onPrimary = Color(0xFF0B1330),
    background = Color(0xFF101217),
    onBackground = Color.White,
    surface = Color(0xFF181B22),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF222632),
    onSurfaceVariant = Color(0xFFE3E6EE),
    outline = Color(0xFF4A5062),
)

@Composable
fun BandStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioColors) {
        // Nền + màu chữ gốc: chữ không chỉ định màu sẽ là trắng (trước đây mặc định đen trên nền đen).
        Surface(color = StudioColors.background, contentColor = Color.White, content = content)
    }
}
