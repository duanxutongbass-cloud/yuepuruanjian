package com.ensemble.scorelibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Local-only paper workspace. Never applies a dark filter to PDF or annotation pixels. */
object ScorePalette {
    val Navigation = Color(0xFF202824)
    val NavigationSelected = Color(0xFF39493E)
    val Paper = Color.White
    val Workspace = Color(0xFFF4F5F1)
    val Accent = Color(0xFF365A42)
    val Ink = Color(0xFF202824)
}

@Composable
fun ScoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = ScorePalette.Accent, onPrimary = Color.White,
        primaryContainer = Color(0xFFE0EBDD), onPrimaryContainer = ScorePalette.Ink,
        secondary = Color(0xFF536456), background = ScorePalette.Workspace,
        surface = ScorePalette.Paper, onSurface = ScorePalette.Ink,
        surfaceVariant = Color(0xFFEAEDE6), onSurfaceVariant = Color(0xFF59625B),
        outline = Color(0xFF778078), outlineVariant = Color(0xFFDDE2D9)
    ), content = content)
}
