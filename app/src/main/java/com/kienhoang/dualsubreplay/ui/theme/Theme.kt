package com.kienhoang.dualsubreplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DualSubColors = darkColorScheme(
    primary = Color(0xFF13C6D7),
    onPrimary = Color(0xFF001F23),
    secondary = Color(0xFF8CD9E2),
    background = Color(0xFF061416),
    onBackground = Color(0xFFE4F5F6),
    surface = Color(0xFF0C2023),
    onSurface = Color(0xFFE4F5F6),
    surfaceVariant = Color(0xFF173438),
    onSurfaceVariant = Color(0xFFB8CDD0),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DualSubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DualSubColors, content = content)
}
