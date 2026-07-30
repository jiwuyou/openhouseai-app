package com.wuxianpi.assist

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E2),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF52634E),
    secondaryContainer = Color(0xFFD5E8CD),
    tertiary = Color(0xFF765A00),
    tertiaryContainer = Color(0xFFFFDF8F),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFF7F9F8),
    surfaceVariant = Color(0xFFDBE5E1),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81D5C6),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9EF2E2),
    secondary = Color(0xFFB9CCB3),
    secondaryContainer = Color(0xFF3B4B37),
    tertiary = Color(0xFFF0C144),
    tertiaryContainer = Color(0xFF594400),
    background = Color(0xFF101413),
    surface = Color(0xFF101413),
    surfaceVariant = Color(0xFF404945),
    error = Color(0xFFFFB4AB),
)

@Composable
fun WuxianPiAssistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
