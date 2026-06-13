package com.example.myplan.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.myplan.data.SettingsManager

private val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Color.White, primaryContainer = AccentLight,
    surface = Color.White, onSurface = TextDark,
    background = Bg, onBackground = TextDark, outline = Divider
)

private val DarkScheme = darkColorScheme(
    primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFF3D2A20),
    surface = Color(0xFF2C1F14), onSurface = Color(0xFFF0E0D0),
    background = Color(0xFF1A1410), onBackground = Color(0xFFF0E0D0),
    outline = Color(0xFF3D3028)
)

@Composable
fun MyPlanTheme(context: Context? = null, content: @Composable () -> Unit) {
    val useDarkTheme = when (context?.let { SettingsManager.isDarkMode(it) }) {
        "开启" -> true
        "关闭" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}
