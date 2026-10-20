package com.ensemble.scorelibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Local-only paper workspace. Never applies a dark filter to PDF or annotation pixels. */
object ScorePalette {
    val Navigation = Color(0xFF1D222A)
    val NavigationSelected = Color(0xFF3B4C65)
    val Preview = Color(0xFF2C3440)
    val Search = Color(0xFF303844)
    val Paper = Color.White
    val Workspace = Color(0xFF232831)
    val Accent = Color(0xFFADC9F0)
    val Ink = Color(0xFFE8EDF5)
}

@Composable
fun ScoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFADC9F0), onPrimary = Color(0xFF182C46),
        primaryContainer = Color(0xFF3B4C65), onPrimaryContainer = ScorePalette.Ink,
        secondary = Color(0xFFC1CCDD), background = ScorePalette.Workspace,
        surface = Color(0xFF2C3440), onSurface = ScorePalette.Ink,
        surfaceVariant = Color(0xFF303844), onSurfaceVariant = Color(0xFFC4CDDA),
        outline = Color(0xFF8794A8), outlineVariant = Color(0xFF4B586C)
    ), content = content)
}
