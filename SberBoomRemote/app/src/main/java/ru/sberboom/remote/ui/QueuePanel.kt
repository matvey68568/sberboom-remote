package ru.sberboom.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.sberboom.remote.data.QueueEntry
import ru.sberboom.remote.net.ZvukApi

/**
 * «Что играет дальше».
 *
 * Из каталога Звука (op=17) — реальная очередь колонки с обложками: текущий трек
 * подсвечен, тап переключает на трек.
 *
 * Для Bluetooth-источника очередь недоступна (её знает только телефон-источник),
 * поэтому показываем подключённое устройство и похожие треки Звука.
 */
@Composable
fun QueuePanel(
    queue: List<QueueEntry>,
    loading: Boolean,
    currentTrackId: String?,
    isBluetooth: Boolean,
    btDeviceName: String?,
    similar: List<ZvukApi.Item>,
    similarLoading: Boolean,
    onPlay: (QueueEntry) -> Unit,
    onPlaySimilar: (ZvukApi.Item) -> Unit,
    onLoadSimilar: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(580.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Surface1)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(SberGreen.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isBluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.QueueMusic,
                        null,
                        tint = SberGreen,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isBluetooth) "Bluetooth-источник" else "Очередь воспроизведения",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        when {
                            isBluetooth -> btDeviceName?.let { "Играет с устройства: $it" }
                                ?: "Играет с внешнего устройства"

                            queue.isEmpty() -> "Пока пусто"
                            else -> "${queue.size} треков впереди"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onRefresh) {
                    Text(if (loading) "Обновляю…" else "Обновить", color = Muted)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Закрыть", tint = Muted)
                }
            }

            Spacer(Modifier.height(10.dp))

            if (isBluetooth) {
                BluetoothBlock(
                    similar = similar,
                    similarLoading = similarLoading,
                    onLoadSimilar = onLoadSimilar,
                    onPlaySimilar = onPlaySimilar,
                )
            } else when {
                loading && queue.isEmpty() -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SberGreen)
                }

                queue.isEmpty() -> Text(
                    "Колонка не отдаёт очередь: она появляется, когда играет плейлист, волна или альбом.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
                ) {
                    itemsIndexed(queue, key = { index, item -> "$index-${item.trackId}" }) { index, entry ->
                        QueueRow(
                            index = index + 1,
                            entry = entry,
                            isCurrent = entry.trackId == currentTrackId,
                            onClick = { onPlay(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    entry: QueueEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (isCurrent) "▶" else index.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) SberGreen else Muted,
            modifier = Modifier.width(24.dp),
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.coverUrl != null) {
                AsyncImage(
                    model = entry.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) SberGreen else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.artist.isNotEmpty()) {
                Text(
                    entry.artist + if (entry.explicit) "  ·  18+" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Rounded.PlayArrow,
            "Включить",
            tint = if (isCurrent) SberGreen else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Тело панели для Bluetooth-источника: очередь ему недоступна, поэтому —
 * честное объяснение, подключённое устройство и похожие треки из Звука.
 */
@Composable
private fun BluetoothBlock(
    similar: List<ZvukApi.Item>,
    similarLoading: Boolean,
    onLoadSimilar: () -> Unit,
    onPlaySimilar: (ZvukApi.Item) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "По Bluetooth очередь знает только телефон-источник: колонка отдаёт лишь текущий " +
                "трек — название, исполнителя, обложку и позицию (они уже в плеере).",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onLoadSimilar, shape = RoundedCornerShape(14.dp)) {
            Text(if (similarLoading) "Подбираю…" else "Похожие треки в Звуке", color = SberGreen)
        }
        Spacer(Modifier.height(12.dp))

        when {
            similarLoading && similar.isEmpty() -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SberGreen)
            }

            similar.isEmpty() -> Text(
                "Нажмите «Похожие треки» — подберём по исполнителю текущего трека. Запуск " +
                    "переключит колонку с Bluetooth на Sber Звук.",
                style = MaterialTheme.typography.labelMedium,
                color = Muted.copy(alpha = 0.85f),
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            ) {
                itemsIndexed(similar, key = { index, item -> "similar-$index-${item.id}" }) { _, item ->
                    SimilarRow(item) { onPlaySimilar(item) }
                }
            }
        }
    }
}

@Composable
private fun SimilarRow(item: ZvukApi.Item, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle + if (item.explicit) "  ·  18+" else "",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Rounded.PlayArrow,
            "Включить",
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}
