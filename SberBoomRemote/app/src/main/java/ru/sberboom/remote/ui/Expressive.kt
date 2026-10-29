package ru.sberboom.remote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Нажатие «продавливает» элемент: масштаб уходит к [pressed] пружиной и
 * возвращается с небольшим перелётом — характерное движение Expressive.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressed: Float = 0.94f,
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = Motion.bouncy(),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Чип-переключатель Expressive: пилюля, которая при выборе заливается
 * контейнерным цветом и меняет цвет иконки пружинной анимацией.
 */
@Composable
fun ExpressiveChip(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    selected: Boolean = false,
    contentDescription: String? = null,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    activeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        targetValue = if (selected) activeContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = Motion.smooth(),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.snappy(),
        label = "chipContent",
    )
    Row(
        modifier = modifier
            .height(40.dp)
            .pressScale(interaction)
            .background(container, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (label == null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription, tint = content, modifier = Modifier.size(18.dp))
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/**
 * Волнистый индикатор прогресса — главный визуальный акцент Expressive.
 *
 * Играющий трек: по прошедшей части бежит синусоида, непрошедшая часть —
 * ровная линия; на паузе волна схлопывается в линию. Тап и перетаскивание
 * по полосе работают как перемотка.
 */
@Composable
fun WavyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    animated: Boolean = true,
    onSeek: ((Float) -> Unit)? = null,
    onSeekFinished: ((Float) -> Unit)? = null,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = (dragFraction ?: progress).coerceIn(0f, 1f)

    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
        ),
        label = "wavePhase",
    )
    val amplitude by animateFloatAsState(
        targetValue = if (animated) 1f else 0f,
        animationSpec = Motion.smooth(),
        label = "waveAmplitude",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (dragFraction != null) 1.35f else 1f,
        animationSpec = Motion.bouncy(),
        label = "thumbScale",
    )

    val seekable = onSeek != null
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(
                if (!seekable) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(onSeek) {
                            detectTapGestures { offset ->
                                val f = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeek.invoke(f)
                                onSeekFinished?.invoke(f)
                            }
                        }
                        .pointerInput(onSeek, onSeekFinished) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    dragFraction?.let { onSeekFinished?.invoke(it) }
                                    dragFraction = null
                                },
                                onDragCancel = { dragFraction = null },
                                onHorizontalDrag = { change, _ ->
                                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                },
                            )
                        }
                },
            ),
    ) {
        val cy = size.height / 2f
        val thickness = 7.dp.toPx()
        val waveLength = 26.dp.toPx()
        val maxAmp = 5.5.dp.toPx()
        val activeWidth = size.width * shown
        val thumbRadius = 7.dp.toPx() * thumbScale
        val thumbX = activeWidth.coerceIn(thumbRadius, size.width - thumbRadius)

        // прошедшая часть — ровная линия под волной
        drawLine(
            color = activeColor.copy(alpha = 0.35f),
            start = Offset(0f, cy),
            end = Offset(activeWidth, cy),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )

        // непрошедшая часть
        drawLine(
            color = inactiveColor,
            start = Offset(activeWidth + thickness / 2f, cy),
            end = Offset(size.width - thickness / 2f, cy),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )

        if (activeWidth > 1f) {
            val path = Path()
            val step = 1.dp.toPx()
            val amp = maxAmp * amplitude
            var x = 0f
            path.moveTo(0f, cy)
            while (x <= activeWidth) {
                // у конца волна затухает, чтобы не обрывалась на полуслове
                val edge = (activeWidth - x).coerceAtMost(waveLength / 2f) / (waveLength / 2f)
                val y = cy + sin((x / waveLength) * 2 * PI.toFloat() + phase) * amp * edge
                path.lineTo(x, y)
                x += step
            }
            drawPath(
                path = path,
                color = activeColor,
                style = Stroke(width = thickness, cap = StrokeCap.Round),
            )
        }

        // «бегунок»
        drawCircle(color = activeColor, radius = thumbRadius, center = Offset(thumbX, cy))
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = thumbRadius * 0.4f,
            center = Offset(thumbX, cy),
        )
    }
}

/** Карточка-секция Expressive: крупный радиус, контейнерный фон, без обводки. */
@Composable
fun ExpressiveSection(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(container, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

/** Крупный заголовок секции с «воздухом» Expressive. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
