package com.asawaaah.fil.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.asawaaah.fil.data.settings.ThemeMode

// Warm "paper" palette: deep teal ink with an amber accent.
private val Light = lightColorScheme(
    primary = Color(0xFF1F4E5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBE6EE),
    onPrimaryContainer = Color(0xFF07202A),
    secondary = Color(0xFFB36B1E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB8),
    onSecondaryContainer = Color(0xFF2D1600),
    tertiary = Color(0xFF5B5F8F),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEDE7DD),
    onSurfaceVariant = Color(0xFF5E5850),
    surfaceContainer = Color(0xFFF3EEE6),
    surfaceContainerHigh = Color(0xFFEDE7DD),
    surfaceContainerLow = Color(0xFFF7F3EC),
    outline = Color(0xFF8F877C),
    outlineVariant = Color(0xFFD9D1C5),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FCFE0),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF1F4E5F),
    onPrimaryContainer = Color(0xFFCBE6EE),
    secondary = Color(0xFFFFB86B),
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF6A3C00),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = Color(0xFFC3C5FF),
    background = Color(0xFF121416),
    onBackground = Color(0xFFE4E2DE),
    surface = Color(0xFF121416),
    onSurface = Color(0xFFE4E2DE),
    surfaceVariant = Color(0xFF2A2D30),
    onSurfaceVariant = Color(0xFFB9B4AC),
    surfaceContainer = Color(0xFF1C1F21),
    surfaceContainerHigh = Color(0xFF26292C),
    surfaceContainerLow = Color(0xFF17191B),
    outline = Color(0xFF8A857E),
    outlineVariant = Color(0xFF3A3D40),
    error = Color(0xFFF2B8B5),
)

private val base = Typography()
private val AppTypography = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun FilTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}

/** Body text style of the reader, from the user's settings. */
fun readerBodyStyle(scale: Float, serif: Boolean, color: Color): TextStyle = TextStyle(
    fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
    fontSize = (18 * scale).sp,
    lineHeight = (18 * scale * 1.6f).sp,
    color = color,
)
