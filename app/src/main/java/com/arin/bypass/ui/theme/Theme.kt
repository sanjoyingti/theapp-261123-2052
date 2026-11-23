package com.arin.bypass.ui.theme

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

private val Dark = darkColorScheme(
    primary = Color(0xFF4CC2FF),
    onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0xFF0B3A56),
    onPrimaryContainer = Color(0xFFCFE8FF),
    secondary = Color(0xFF9FD4A8),
    onSecondary = Color(0xFF073313),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE3E7EE),
    surface = Color(0xFF121A2B),
    onSurface = Color(0xFFE3E7EE),
    surfaceVariant = Color(0xFF1C2537),
    onSurfaceVariant = Color(0xFFB6C0D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3B0905),
    outline = Color(0xFF3A4557)
)

private val Light = lightColorScheme(
    primary = Color(0xFF00639A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE8FF),
    onPrimaryContainer = Color(0xFF001E31),
    secondary = Color(0xFF2F6B3D),
    onSecondary = Color.White,
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF11151C),
    surface = Color.White,
    onSurface = Color(0xFF11151C),
    surfaceVariant = Color(0xFFE6EBF2),
    onSurfaceVariant = Color(0xFF445062),
    error = Color(0xFFB3261E),
    onError = Color.White,
    outline = Color(0xFFB7C1CE)
)

val Mono = FontFamily.Monospace

val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontFamily = Mono)
)

@Composable
fun ArinTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        content = content
    )
}
