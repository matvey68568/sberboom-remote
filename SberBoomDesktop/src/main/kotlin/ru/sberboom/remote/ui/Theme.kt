package ru.sberboom.remote.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Тема оформления в стилистике Material 3 Expressive.
 *
 * Отличия от «обычного» M3: насыщенная палитра с контейнерными ролями
 * (`surfaceContainer*`, `secondaryContainer`, `tertiary`), крупные радиусы,
 * увеличенная типографика с плотным трекингом и пружинная анимация вместо
 * линейных переходов (см. [Motion]).
 *
 * Цвета ниже — не константы, а геттеры: чтение [ThemeMode.light] внутри
 * композиции подписывает экран на смену темы, поэтому переключение мгновенно
 * перерисовывает всё приложение без перезапуска.
 */
object ThemeMode {
    var light by mutableStateOf(false)
}

val SberGreen: Color
    get() = if (ThemeMode.light) Color(0xFF0B8A4D) else Color(0xFF3BE089)

val Ink: Color
    get() = if (ThemeMode.light) Color(0xFFF4F2F7) else Color(0xFF07090C)

val Surface1: Color
    get() = if (ThemeMode.light) Color(0xFFFFFFFF) else Color(0xFF11151B)

val Surface2: Color
    get() = if (ThemeMode.light) Color(0xFFE8E5EF) else Color(0xFF1A1F27)

val Muted: Color
    get() = if (ThemeMode.light) Color(0xFF4B5560) else Color(0xFFA9B3BF)

/** Мелкий текст-акцент (статус, подписи): на светлой теме заметно темнее. */
val AccentText: Color
    get() = if (ThemeMode.light) Color(0xFF06693A) else Color(0xFF3BE089)

/** Затемнение под модальными панелями: на светлой теме скрим мягче. */
val Scrim: Color
    get() = if (ThemeMode.light) Color.Black.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.55f)

/**
 * Пружины Expressive: заметный отскок (низкий damping) и высокая жёсткость,
 * поэтому движение «догоняет» палец и слегка перелетает цель.
 */
object Motion {
    /** Нажатия, чипы, переключатели — резко и с отскоком. */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.45f, stiffness = 620f)

    /** Панели и появление экранов — мягко, без «желейности». */
    fun <T> smooth() = spring<T>(dampingRatio = 0.82f, stiffness = 260f)

    /** Смена содержимого (трек, обложка) — быстро и точно. */
    fun <T> snappy() = spring<T>(dampingRatio = 0.72f, stiffness = 1100f)

    /** Стандартная кривая Expressive — для сдвигов и поворотов. */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Медленнее у концов — для ambient-пульсаций. */
    val gentle: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

private fun darkScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF3BE089),
    onPrimary = Color(0xFF00301A),
    primaryContainer = Color(0xFF0E4A2B),
    onPrimaryContainer = Color(0xFFB7F7D2),
    inversePrimary = Color(0xFF0B8A4D),
    secondary = Color(0xFFB9C7FF),
    onSecondary = Color(0xFF1B2A5B),
    secondaryContainer = Color(0xFF232C4A),
    onSecondaryContainer = Color(0xFFDCE2FF),
    tertiary = Color(0xFFFFB4C8),
    onTertiary = Color(0xFF56102B),
    tertiaryContainer = Color(0xFF3D1A28),
    onTertiaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF07090C),
    onBackground = Color(0xFFEDF1F4),
    surface = Color(0xFF0F1319),
    onSurface = Color(0xFFEDF1F4),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = Color(0xFFA9B3BF),
    surfaceTint = Color(0xFF3BE089),
    surfaceDim = Color(0xFF07090C),
    surfaceBright = Color(0xFF1C222B),
    surfaceContainerLowest = Color(0xFF040608),
    surfaceContainerLow = Color(0xFF0C1015),
    surfaceContainer = Color(0xFF11151B),
    surfaceContainerHigh = Color(0xFF171C23),
    surfaceContainerHighest = Color(0xFF1E242D),
    inverseSurface = Color(0xFFEDF1F4),
    inverseOnSurface = Color(0xFF11151B),
    outline = Color(0xFF2A313B),
    outlineVariant = Color(0xFF1F252E),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color.Black,
)

private fun lightScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF0B8A4D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEFDD),
    onPrimaryContainer = Color(0xFF04301A),
    inversePrimary = Color(0xFF3BE089),
    secondary = Color(0xFF3D5BA9),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3FF),
    onSecondaryContainer = Color(0xFF152449),
    tertiary = Color(0xFFA13A5C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF3D0D1E),
    background = Color(0xFFF4F2F7),
    onBackground = Color(0xFF111820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111820),
    surfaceVariant = Color(0xFFE8E5EF),
    onSurfaceVariant = Color(0xFF4B5560),
    surfaceTint = Color(0xFF0B8A4D),
    surfaceDim = Color(0xFFDDDAE4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8FD),
    surfaceContainer = Color(0xFFF2F0F7),
    surfaceContainerHigh = Color(0xFFE5E1EC),
    surfaceContainerHighest = Color(0xFFD9D5E2),
    inverseSurface = Color(0xFF111820),
    inverseOnSurface = Color(0xFFF4F2F7),
    outline = Color(0xFFA9AFBC),
    outlineVariant = Color(0xFFD4D8E0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black,
)

/** Expressive-шкала: крупнее заголовки, плотнее трекинг, жирнее акценты. */
private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp),
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

/** Крупные радиусы Expressive: «мягкие» карточки и почти пилюли для кнопок. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
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
        shapes = AppShapes,
        content = content,
    )
}
