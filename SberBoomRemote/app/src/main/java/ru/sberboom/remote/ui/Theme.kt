package ru.sberboom.remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Тема оформления. Цвета ниже — не константы, а геттеры: чтение [ThemeMode.light]
 * внутри композиции подписывает экран на смену темы, поэтому переключение
 * мгновенно перерисовывает всё приложение без перезапуска.
 */
object ThemeMode {
    var light by mutableStateOf(false)
}

val SberGreen: Color
    get() = if (ThemeMode.light) Color(0xFF0B8A4D) else Color(0xFF35D07F)

val Ink: Color
    get() = if (ThemeMode.light) Color(0xFFF3F5F8) else Color(0xFF07090C)

val Surface1: Color
    get() = if (ThemeMode.light) Color(0xFFFFFFFF) else Color(0xFF11151B)

val Surface2: Color
    get() = if (ThemeMode.light) Color(0xFFE7ECF1) else Color(0xFF1A1F27)

val Muted: Color
    get() = if (ThemeMode.light) Color(0xFF4B5560) else Color(0xFFA9B3BF)

/** Мелкий текст-акцент (статус, подписи): на светлой теме заметно темнее. */
val AccentText: Color
    get() = if (ThemeMode.light) Color(0xFF06693A) else Color(0xFF35D07F)

/** Затемнение под модальными панелями: на светлой теме скрим мягче. */
val Scrim: Color
    get() = if (ThemeMode.light) Color.Black.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.55f)

private fun darkScheme(): androidx.compose.material3.ColorScheme = darkColorScheme(
    primary = Color(0xFF35D07F),
    onPrimary = Color(0xFF00230F),
    primaryContainer = Color(0xFF123D25),
    onPrimaryContainer = Color(0xFFB9F5D3),
    secondary = Color(0xFF8ED8B4),
    onSecondary = Color(0xFF00210F),
    background = Color(0xFF07090C),
    onBackground = Color(0xFFEDF1F4),
    surface = Color(0xFF11151B),
    onSurface = Color(0xFFEDF1F4),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = Color(0xFFA9B3BF),
    outline = Color(0xFF2A313B),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

private fun lightScheme(): androidx.compose.material3.ColorScheme = lightColorScheme(
    primary = Color(0xFF0B8A4D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5F5E4),
    onPrimaryContainer = Color(0xFF06301C),
    secondary = Color(0xFF2E7D5B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF3F5F8),
    onBackground = Color(0xFF111820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111820),
    surfaceVariant = Color(0xFFE7ECF1),
    onSurfaceVariant = Color(0xFF4B5560),
    outline = Color(0xFFC9D2DC),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
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

/**
 * Тема приложения: тёмная по умолчанию (обложка + ambient-свечение), светлая —
 * по выбору пользователя. Выбор хранится в настройках и переживает перезапуск.
 */
@Composable
fun SberBoomTheme(light: Boolean = ThemeMode.light, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (light) lightScheme() else darkScheme(),
        typography = AppTypography,
        content = content,
    )
}
