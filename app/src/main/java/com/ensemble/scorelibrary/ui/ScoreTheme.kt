package com.ensemble.scorelibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Local-only paper workspace. Never applies a dark filter to PDF or annotation pixels. */
object ScorePalette {
    val Navigation = Color(0xFF202824)
    val NavigationSelected = Color(0xFF39493E)
    val Paper = Color.White
    val Workspace = Color(0xFF232825)
    val Accent = Color(0xFF365A42)
    val Ink = Color(0xFFE9EEE9)
}

@Composable
fun ScoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFBDD1BC), onPrimary = Color(0xFF233724),
        primaryContainer = Color(0xFF3D5141), onPrimaryContainer = ScorePalette.Ink,
        secondary = Color(0xFFBBC9BB), background = ScorePalette.Workspace,
        surface = Color(0xFF2D332F), onSurface = ScorePalette.Ink,
        surfaceVariant = Color(0xFF3D463F), onSurfaceVariant = Color(0xFFC1CAC2),
        outline = Color(0xFF778078), outlineVariant = Color(0xFF465048)
    ), content = content)
}
