package com.digitronics.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.digitronics.chat.data.ThemeMode

// Teal brand palette, matching the web app (Tailwind teal-700 / teal-600 family).
private val Teal700 = Color(0xFF0F766E)
private val Teal600 = Color(0xFF0D9488)
private val Teal300 = Color(0xFF5EEAD4)
private val Teal100 = Color(0xFFCCFBF1)
private val Teal900 = Color(0xFF134E4A)

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Teal600,
    onSecondary = Color.White,
    background = Color(0xFFF8FAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7EFEE),
    onSurfaceVariant = Color(0xFF3F4948),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Teal300,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Teal700,
    onPrimaryContainer = Color.White,
    secondary = Teal300,
    background = Color(0xFF0B1514),
    surface = Color(0xFF111C1B),
    surfaceVariant = Color(0xFF1F2B2A),
    onSurfaceVariant = Color(0xFFBEC9C7),
    error = Color(0xFFFFB4AB),
)

/** Report-card tones; text on them stays the normal on-surface colour for contrast. */
object ToneColors {
    val good = Color(0xFF15803D)
    val warn = Color(0xFFB45309)
    val critical = Color(0xFFB91C1C)
}

@Composable
fun DigitronicsTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
