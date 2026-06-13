package com.example.myplan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentLight,
    surface = Color.White,
    onSurface = TextDark,
    background = Bg,
    onBackground = TextDark,
    outline = Divider
)

@Composable
fun MyPlanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColorScheme, typography = AppTypography, content = content)
}
