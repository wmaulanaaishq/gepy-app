package com.teknologigawai.gepy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GepyGreenAccent = Color(0xFF10B981)
val GepyAmberWarning = Color(0xFFF59E0B)
val GepyRedDanger = Color(0xFFEF4444)
val GepyBlueAccent = Color(0xFF1B64F2)
val GepyBluePillBg = Color(0xFFEFF6FF)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D0D0D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7F7F8),
    onPrimaryContainer = Color(0xFF0D0D0D),
    secondary = Color(0xFF0D0D0D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F2),
    onSecondaryContainer = Color(0xFF0D0D0D),
    background = Color(0xFFFFFFFF), // Pristine Clean White canvas
    onBackground = Color(0xFF0D0D0D), // High-contrast crisp typography
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF7F7F8), // Inset Grouped Card container
    onSurfaceVariant = Color(0xFF6E6E73), // Subtle caption text
    outline = Color(0xFFE5E5EA), // Hairline subtle divider
    error = GepyRedDanger
)

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF1A1C24),
    onPrimaryContainer = Color.White,
    secondary = GepyGreenAccent,
    onSecondary = Color.Black,
    background = Color(0xFF000000), // True OLED Black
    onBackground = Color.White,
    surface = Color(0xFF0B0C10),     // Obsidian Deep Surface
    onSurface = Color.White,
    surfaceVariant = Color(0xFF13151D),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF232530),     // Subtle glass border
    error = GepyRedDanger
)

/**
 * Tema Material 3 Gepy Native (`CyberpunkTheme` / Clean Monochrome Adaptive).
 */
@Composable
fun CyberpunkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
