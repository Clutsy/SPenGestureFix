package com.spengesturefix

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Wheel accent selected by the user. The dashboard reads it so section icons
 * and highlights can follow the same color as the Air Command wheel.
 */
val LocalWheelAccent = staticCompositionLocalOf { Color(0xFF29B6F6) }

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
    background = Color(0xFF0A0A0E),
    onBackground = Color(0xFFE8E7ED),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFE8E7ED),
    surfaceVariant = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainer = Color(0xFF17171E),
    surfaceContainerHigh = Color(0xFF1E1E26),
    outline = Color(0xFF918F9D),
    outlineVariant = Color(0xFF3A3A44),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** Pure-black AMOLED variant: saves battery on the Note 3 OLED panel. */
private val SpenAmoledColors = SpenDarkColors.copy(
    background = Color.Black,
    surface = Color(0xFF0B0B0E),
    surfaceContainer = Color(0xFF101014),
    surfaceContainerHigh = Color(0xFF16161C),
    surfaceVariant = Color(0xFF17171C)
)

private val SpenLightColors = lightColorScheme(
    primary = Color(0xFF5A50C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF1B1150),
    secondary = Color(0xFF2A6A60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9F2E5),
    onSecondaryContainer = Color(0xFF003831),
    tertiary = Color(0xFF8E4965),
    background = Color(0xFFFDF7FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFDF7FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE5E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF787681),
    outlineVariant = Color(0xFFC9C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/**
 * AMOLED is the historical default of this app, so the parameter defaults to
 * true and existing call sites keep compiling. MainActivity passes the real
 * persisted preference; activities that only render media keep pure black.
 */
@Composable
fun SpenFixTheme(
    amoled: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = when {
            amoled -> SpenAmoledColors
            else -> SpenDarkColors
        },
        typography = Typography(),
        content = content
    )
}
