package ru.sberboom.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sberboom.desktop.compose.NetImage
import ru.sberboom.remote.net.LyricLine
import kotlin.math.roundToInt

/**
 * Караоке-экран: текст песни с подсветкой текущей строки.
 *
 * Текст ищется по названию+исполнителю (Lrclib → NetEase), поэтому работает и
 * для каталога Звука, и для Bluetooth/радио, где каталожного id нет.
 */
@Composable
fun LyricsPanel(
    title: String?,
    artist: String?,
    coverUrl: String?,
    lines: List<LyricLine>,
    plain: String?,
    source: String?,
    instrumental: Boolean,
    loading: Boolean,
    activeIndex: Int,
    karaokeFill: Boolean,
    activeProgress: Float,
    onToggleFill: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex, lines.size) {
        if (activeIndex >= 0 && lines.isNotEmpty()) {
            listState.animateScrollToItem(activeIndex.coerceAtMost(lines.lastIndex))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        if (coverUrl != null) {
            NetImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Ink.copy(alpha = 0.55f), Ink.copy(alpha = 0.92f), Ink),
                        ),
                    ),
            )
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Закрыть", tint = MaterialTheme.colorScheme.onBackground)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title ?: "Текст песни",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        artist?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = Muted,
                                maxLines = 1,
                            )
                        }
                        source?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "·  ${it}",
                                style = MaterialTheme.typography.labelMedium,
                                color = SberGreen.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleFill) {
                    Icon(
                        Icons.Rounded.Lyrics,
                        if (karaokeFill) "Выключить заливку строки" else "Включить заливку строки",
                        tint = if (karaokeFill) SberGreen else Muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            when {
                loading -> Centered {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SberGreen)
                    Spacer(Modifier.height(12.dp))
                    Text("Ищу текст…", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }

                instrumental -> Centered {
                    Text("Инструментальная композиция", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Для этого трека текст не предусмотрен",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }

                lines.isNotEmpty() -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 120.dp, bottom = 180.dp),
                ) {
                    itemsIndexed(lines) { index, line ->
                        val isActive = index == activeIndex
                        val text = line.text.ifEmpty { "♪" }
                        val activeStyle = MaterialTheme.typography.titleLarge.copy(
                            fontSize = if (isActive) 26.sp else 21.sp,
                            lineHeight = if (isActive) 34.sp else 28.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (isActive && karaokeFill) {
                            // Посимвольная заливка: пропетая часть — акцентным цветом.
                            val cut = (text.length * activeProgress.coerceIn(0f, 1f))
                                .roundToInt()
                                .coerceIn(0, text.length)
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = SberGreen)) { append(text.substring(0, cut)) }
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) {
                                        append(text.substring(cut))
                                    }
                                },
                                style = activeStyle,
                                modifier = Modifier.padding(vertical = 7.dp),
                            )
                        } else {
                            Text(
                                text,
                                style = activeStyle,
                                color = when {
                                    isActive -> MaterialTheme.colorScheme.onBackground
                                    index < activeIndex -> Muted.copy(alpha = 0.55f)
                                    else -> Muted.copy(alpha = 0.9f)
                                },
                                modifier = Modifier.padding(vertical = 7.dp),
                            )
                        }
                    }
                }

                !plain.isNullOrBlank() -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp),
                ) {
                    Text(
                        "Текст без синхронизации",
                        style = MaterialTheme.typography.labelMedium,
                        color = SberGreen.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        plain,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                else -> Centered {
                    Text("Текст не найден", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Lrclib и NetEase не знают этот трек. Можно попробовать ещё раз — " +
                            "иногда текст появляется позже.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onReload, shape = RoundedCornerShape(14.dp)) {
                        Text("Ещё раз", color = SberGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
