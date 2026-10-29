package ru.sberboom.remote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val SberGreen = Color(0xFF35D07F)
val Ink = Color(0xFF07090C)
val Surface1 = Color(0xFF11151B)
val Surface2 = Color(0xFF1A1F27)
val Muted = Color(0xFFA9B3BF)

private val DarkScheme = darkColorScheme(
    primary = SberGreen,
    onPrimary = Color(0xFF00230F),
    primaryContainer = Color(0xFF123D25),
    onPrimaryContainer = Color(0xFFB9F5D3),
    secondary = Color(0xFF8ED8B4),
    onSecondary = Color(0xFF00210F),
    background = Ink,
    onBackground = Color(0xFFEDF1F4),
    surface = Surface1,
    onSurface = Color(0xFFEDF1F4),
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Color(0xFF2A313B),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun SberBoomTheme(content: @Composable () -> Unit) {
    // Приложение всегда тёмное: обложка + ambient-свечение — основа дизайна.
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content,
    )
}
