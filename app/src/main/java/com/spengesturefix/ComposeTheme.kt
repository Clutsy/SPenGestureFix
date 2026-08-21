package com.spengesturefix

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SpenDarkColors = darkColorScheme(
    primary = Color(0xFFB9B3FF),
    onPrimary = Color(0xFF29215F),
    primaryContainer = Color(0xFF40377A),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Color(0xFF86D8CC),
    onSecondary = Color(0xFF003831),
    secondaryContainer = Color(0xFF155049),
    onSecondaryContainer = Color(0xFFA2F2E5),
    tertiary = Color(0xFFFFB5CB),
    onTertiary = Color(0xFF5A1030),
    background = Color.Black,
    onBackground = Color(0xFFE8E7ED),
    surface = Color(0xFF0B0B0E),
    onSurface = Color(0xFFE8E7ED),
    surfaceVariant = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9D),
    outlineVariant = Color(0xFF46464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun SpenFixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SpenDarkColors,
        typography = Typography(),
        content = content
    )
}
