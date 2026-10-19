package com.example.myplan.ui.theme

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.myplan.data.SettingsManager

private val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = AccentLight, onPrimaryContainer = TextDark,
    secondary = TextSoft, onSecondary = Color.White,
    secondaryContainer = CardYellow, onSecondaryContainer = TextDark,
    tertiary = Color(0xFF426F7A), tertiaryContainer = CardBlue,
    background = Bg, onBackground = TextDark,
    surface = Color.White, onSurface = TextDark,
    surfaceVariant = CardYellow, onSurfaceVariant = TextSoft,
    surfaceContainer = CardYellow, surfaceContainerLow = Bg,
    surfaceContainerHigh = AccentLight,
    outline = Divider, outlineVariant = Divider,
    error = Delete, onError = Color.White,
    errorContainer = CardCoral, onErrorContainer = Delete,
    inverseSurface = TextDark, inverseOnSurface = Bg, inversePrimary = Color(0xFFBED09F)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFBED09F), onPrimary = Color(0xFF213018),
    primaryContainer = Color(0xFF39482E), onPrimaryContainer = Color(0xFFEDF1E5),
    secondary = Color(0xFFB6C2A9), onSecondary = Color(0xFF213018),
    secondaryContainer = Color(0xFF303A2A), onSecondaryContainer = Color(0xFFEDF1E5),
    tertiary = Color(0xFFB9D8DF), tertiaryContainer = Color(0xFF30434B),
    background = Color(0xFF20271F), onBackground = Color(0xFFEDF1E5),
    surface = Color(0xFF2C3529), onSurface = Color(0xFFEDF1E5),
    surfaceVariant = Color(0xFF303A2A), onSurfaceVariant = Color(0xFFB6C2A9),
    surfaceContainer = Color(0xFF303A2A), surfaceContainerLow = Color(0xFF20271F),
    surfaceContainerHigh = Color(0xFF39482E),
    outline = Color(0xFF46523F), outlineVariant = Color(0xFF46523F),
    error = Color(0xFFEFB09A), onError = Color(0xFF50362B),
    errorContainer = Color(0xFF50362B), onErrorContainer = Color(0xFFEFB09A),
    inverseSurface = Color(0xFFEDF1E5), inverseOnSurface = Color(0xFF20271F), inversePrimary = Accent
)

@Composable
fun MyPlanTheme(context: Context? = null, content: @Composable () -> Unit) {
    val prefs = remember(context) { context?.let(SettingsManager::getPrefs) }
    var preference by remember(prefs) { mutableStateOf(prefs?.getString("dark_mode", "system")) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { storage, key ->
            if (key == "dark_mode") preference = storage.getString(key, "system")
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = when (preference) {
        "开启" -> true
        "关闭" -> false
        else -> isSystemInDarkTheme()
    }
    SideEffect {
        (context as? ComponentActivity)?.let { activity ->
            val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        }
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
