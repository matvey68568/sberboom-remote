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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.sberboom.remote.data.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Русское склонение: 1 трек, 2 трека, 5 треков. */
private fun plural(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
}

/**
 * «Недавнее» — история прослушанного (до 40 треков), хранится локально.
 * Тап по строке запускает трек на колонке.
 */
@Composable
fun HistoryPanel(
    entries: List<HistoryEntry>,
    onPlay: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val today = remember { SimpleDateFormat("dd.MM", Locale.getDefault()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim)
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
                .fillMaxSize(0.86f)
                .background(Surface1, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
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
                    Icon(Icons.Rounded.History, null, tint = SberGreen, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Недавнее", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (entries.isEmpty()) "пока пусто" else "${entries.size} ${plural(entries.size)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                    )
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Очистить", color = Muted) }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Закрыть", tint = Muted)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text(
                    "Здесь появятся треки, которые играли на колонке — из Звука, волны или Bluetooth.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
                ) {
                    items(entries, key = { it.trackId + it.atMs }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onPlay(entry) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Surface2),
                            ) {
                                if (entry.coverUrl != null) {
                                    AsyncImage(
                                        model = entry.coverUrl,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title.ifBlank { "Без названия" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    entry.artist.ifBlank { "—" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "${today.format(Date(entry.atMs))} ${fmt.format(Date(entry.atMs))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}
