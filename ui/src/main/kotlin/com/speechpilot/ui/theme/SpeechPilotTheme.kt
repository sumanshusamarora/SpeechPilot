package com.speechpilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SpeechPilotLightColors = lightColorScheme(
    primary = Color(0xFF3558D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF102E94),
    secondary = Color(0xFF9A39B8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8D7FF),
    onSecondaryContainer = Color(0xFF5E0A77),
    tertiary = Color(0xFF2CA6DD),
    background = Color(0xFFF6F8FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAF0FF),
    onSurfaceVariant = Color(0xFF4D5775),
    error = Color(0xFFB3261E)
)

private val SpeechPilotDarkColors = darkColorScheme(
    primary = Color(0xFFB8C5FF),
    onPrimary = Color(0xFF06238B),
    primaryContainer = Color(0xFF203FAF),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFE9B3FF),
    onSecondary = Color(0xFF5B0072),
    secondaryContainer = Color(0xFF7A1F97),
    onSecondaryContainer = Color(0xFFF8D7FF),
    tertiary = Color(0xFF8CDFFF),
    background = Color(0xFF0F1320),
    surface = Color(0xFF161B2C),
    surfaceVariant = Color(0xFF27304A),
    onSurfaceVariant = Color(0xFFC5CBE3)
)

@Composable
fun SpeechPilotTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) SpeechPilotDarkColors else SpeechPilotLightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
