package ru.sberboom.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.sberboom.desktop.compose.NetImage
import kotlinx.coroutines.delay
import ru.sberboom.remote.data.Connection
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.data.PlayerUiState
import ru.sberboom.remote.protocol.Source
import ru.sberboom.remote.protocol.Track

@Composable
fun PlayerScreen(
    repo: PlayerRepository,
    state: PlayerUiState,
    onOpenSearch: () -> Unit,
    onOpenConnect: () -> Unit,
) {
    val track = state.track
    val ambient = state.ambientColor?.let { Color(it) } ?: SberGreen
    var menuOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var showBt by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showDevice by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }

    // Очередь: для Bluetooth-источника её нет — подбираем похожие треки в Звуке.
    val openQueue: () -> Unit = {
        showQueue = true
        if (track?.source == Source.BLUETOOTH) repo.loadSimilar() else repo.loadQueue()
        repo.refreshBtDevices()
    }

    LaunchedEffect(track?.playing) {
        if (track?.playing == true) {
            while (true) {
                delay(1_000)
                tick = System.currentTimeMillis()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink),
    ) {
        // Ambient-свечение из цвета обложки — «эхо» LED-кольца колонки.
        Box(
            Modifier
                .fillMaxWidth()
                .height(440.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(ambient.copy(alpha = 0.42f), ambient.copy(alpha = 0.08f), Color.Transparent),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // ───── шапка ─────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(Surface1, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Speaker, null, tint = SberGreen, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.deviceName ?: "SberBoom",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionBadge(state.connection)
                        if (state.host != null) {
                            Text(
                                "  ·  ${state.host}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Muted,
                            )
                        }
                    }
                    val modelLine = listOfNotNull(
                        state.deviceModel,
                        state.deviceFirmware?.let { "fw $it" },
                    ).joinToString(" · ")
                    if (modelLine.isNotEmpty()) {
                        Text(
                            modelLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = Muted.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Rounded.Search, "Поиск в Звуке", tint = MaterialTheme.colorScheme.onBackground)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, "Ещё", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Колонка: состояние") }, onClick = {
                            menuOpen = false
                            showDevice = true
                            repo.refresh()
                        })
                        DropdownMenuItem(text = { Text("Обновить состояние") }, onClick = {
                            menuOpen = false
                            repo.refresh()
                        })
                        DropdownMenuItem(text = { Text("Сопряжение заново") }, onClick = {
                            menuOpen = false
                            repo.pairNow()
                        })
                        DropdownMenuItem(text = { Text("Сменить колонку") }, onClick = {
                            menuOpen = false
                            onOpenConnect()
                        })
                        DropdownMenuItem(text = { Text("Найти пульт") }, onClick = {
                            menuOpen = false
                            repo.findRemote()
                        })
                        DropdownMenuItem(text = { Text("Текст песни") }, onClick = {
                            menuOpen = false
                            showLyrics = true
                            if (state.lyrics.isEmpty() && !state.lyricsLoading) repo.reloadLyrics()
                        })
                        DropdownMenuItem(text = { Text("Очередь / что дальше") }, onClick = {
                            menuOpen = false
                            openQueue()
                        })
                        DropdownMenuItem(text = { Text("Bluetooth и устройства") }, onClick = {
                            menuOpen = false
                            showBt = true
                            repo.refreshBtDevices()
                        })
                        DropdownMenuItem(text = { Text("Отключиться") }, onClick = {
                            menuOpen = false
                            repo.disconnect()
                        })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state.connection is Connection.Failed || state.connection == Connection.Reconnecting) {
                ConnectionLostBanner(onRetry = { repo.connect(state.host ?: return@ConnectionLostBanner) })
                Spacer(Modifier.height(12.dp))
            }

            // ───── обложка ─────
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable {
                        openQueue()
                    }
                    .background(
                        Brush.linearGradient(listOf(Surface2, Surface1)),
                    ),
            ) {
                if (state.coverUrl != null) {
                    NetImage(
                        model = state.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }

                val label = sourceLabel(track)
                if (label != null) {
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                        if (track?.explicit == true) {
                            Spacer(Modifier.width(6.dp))
                            Text("18+", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFB4B4))
                        }
                    }
                }

                if (track?.buffering == true) {
                    CircularProgressIndicator(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ───── название ─────
            Text(
                track?.title ?: if (state.isConnected) "Ничего не играет" else "Колонка не на связи",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                track?.artistLine?.takeIf { it.isNotBlank() }
                    ?: track?.stationName
                    ?: "Выберите трек через поиск — или скажите Алисе",
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track?.album?.let { album ->
                Spacer(Modifier.height(2.dp))
                Text(
                    album,
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Текущая строка текста — тап открывает караоке.
            val lyricLine = run {
                val pos = (track?.livePositionSec(tick) ?: 0).toFloat()
                state.lyrics.getOrNull(state.lyrics.indexOfLast { it.timeSec <= pos + 0.15f })?.text
            }
            if (!lyricLine.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    lyricLine,
                    style = MaterialTheme.typography.labelLarge,
                    color = SberGreen.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showLyrics = true },
                )
            }

            Spacer(Modifier.height(18.dp))

            // ───── прогресс ─────
            val duration = track?.durationSec ?: 0
            if (duration > 0) {
                var dragging by remember(track?.trackId) { mutableStateOf<Float?>(null) }
                val position = dragging ?: (track?.livePositionSec(tick) ?: 0).toFloat()
                Slider(
                    value = position.coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { repo.seekTo(it.toInt()) }
                        dragging = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = SberGreen,
                        activeTrackColor = SberGreen,
                        inactiveTrackColor = Surface2,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(position.toInt()), style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(fmtTime(duration), style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            } else {
                Text(
                    "Прямой эфир · позиция трека недоступна",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ───── транспорт ─────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { repo.setShuffle(!(track?.shuffle ?: false)) }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        "Перемешать",
                        tint = if (track?.shuffle == true) SberGreen else Muted,
                    )
                }
                IconButton(onClick = { repo.prev() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Предыдущий", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(38.dp))
                }
                FilledIconButton(
                    onClick = { repo.playPause() },
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = SberGreen,
                        contentColor = Ink,
                    ),
                ) {
                    Icon(
                        if (track?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        "Играть / пауза",
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { repo.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Следующий", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { repo.cycleRepeat() }) {
                    val repeat = PlayerRepository.repeatCanonical(track?.repeat)
                    Icon(
                        if (repeat == "track") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Повтор",
                        tint = if (repeat == "off") Muted else SberGreen,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ───── лайк / скорость / пульт / BT ─────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    showLyrics = true
                    if (state.lyrics.isEmpty() && !state.lyricsLoading) repo.reloadLyrics()
                }) {
                    Icon(
                        Icons.Rounded.Lyrics,
                        "Текст песни",
                        tint = if (state.lyrics.isNotEmpty()) SberGreen else Muted,
                    )
                }
                IconButton(onClick = { repo.toggleLike() }) {
                    Icon(Icons.Rounded.ThumbUp, "Нравится", tint = if (track?.liked == true) SberGreen else Muted)
                }
                IconButton(onClick = { repo.dislike() }) {
                    Icon(Icons.Rounded.ThumbDown, "Не нравится", tint = Muted)
                }
                Box {
                    TextButton(onClick = { speedOpen = true }) {
                        Text(
                            "${formatSpeed(track?.speed ?: 1f)}×",
                            style = MaterialTheme.typography.labelLarge,
                            color = Muted,
                        )
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate ->
                            DropdownMenuItem(
                                text = { Text("${formatSpeed(rate)}×") },
                                onClick = {
                                    speedOpen = false
                                    repo.setSpeed(rate)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { repo.findRemote() }) {
                    Icon(Icons.Rounded.SettingsRemote, "Найти пульт", tint = Muted)
                }
                IconButton(onClick = {
                    showBt = true
                    repo.refreshBtDevices()
                }) {
                    Icon(Icons.Rounded.Bluetooth, "Bluetooth-сопряжение", tint = Muted)
                }
            }

            Spacer(Modifier.height(10.dp))

            // ───── громкость ─────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { repo.toggleMute() }) {
                    Icon(
                        if (state.muted || state.volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        "Звук",
                        tint = if (state.muted) MaterialTheme.colorScheme.error else Muted,
                    )
                }
                Slider(
                    value = state.volume.toFloat(),
                    onValueChange = { repo.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = SberGreen,
                        activeTrackColor = SberGreen,
                        inactiveTrackColor = Surface2,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${state.volume}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                    modifier = Modifier.width(44.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        if (showQueue) {
            QueuePanel(
                queue = state.queue,
                loading = state.queueLoading,
                currentTrackId = track?.trackId,
                isBluetooth = track?.source == Source.BLUETOOTH,
                btDeviceName = state.connectedBtName,
                similar = state.similar,
                similarLoading = state.similarLoading,
                onPlay = { entry ->
                    repo.playFromQueue(entry)
                    showQueue = false
                },
                onPlaySimilar = { item ->
                    repo.play(item)
                    showQueue = false
                },
                onLoadSimilar = { repo.loadSimilar() },
                onRefresh = { if (track?.source == Source.BLUETOOTH) repo.loadSimilar() else repo.loadQueue() },
                onDismiss = { showQueue = false },
            )
        }

        if (showBt) {
            BluetoothPanel(
                devices = state.btDevices,
                loading = state.btLoading,
                onPairing = { repo.enableBluetoothPairing() },
                onConnect = { repo.btCommand(it, BT_CMD_CONNECT) },
                onDisconnect = { repo.btCommand(it, BT_CMD_DISCONNECT) },
                onForget = { repo.btCommand(it, BT_CMD_REMOVE) },
                onRefresh = { repo.refreshBtDevices() },
                onDismiss = { showBt = false },
            )
        }

        if (showLyrics) {
            val positionSec = (track?.livePositionSec(tick) ?: 0).toFloat()
            val activeIndex = state.lyrics.indexOfLast { it.timeSec <= positionSec + 0.15f }
            val lineStart = state.lyrics.getOrNull(activeIndex)?.timeSec
            val nextStart = state.lyrics.getOrNull(activeIndex + 1)?.timeSec
            val progress = if (lineStart != null) {
                val span = ((nextStart ?: (lineStart + 4f)) - lineStart).coerceAtLeast(0.3f)
                ((positionSec - lineStart) / span).coerceIn(0f, 1f)
            } else {
                0f
            }
            LyricsPanel(
                title = track?.title,
                artist = track?.artistLine?.takeIf { it.isNotBlank() } ?: track?.stationName,
                coverUrl = state.coverUrl,
                lines = state.lyrics,
                plain = state.lyricsPlain,
                source = state.lyricsSource,
                instrumental = state.lyricsInstrumental,
                loading = state.lyricsLoading,
                activeIndex = activeIndex,
                karaokeFill = state.karaokeFill,
                activeProgress = progress,
                onToggleFill = { repo.toggleKaraokeFill() },
                onReload = { repo.reloadLyrics() },
                onDismiss = { showLyrics = false },
            )
        }

        if (showDevice) {
            DevicePanel(
                device = state.device,
                deviceName = state.deviceName,
                model = state.deviceModel,
                firmware = state.deviceFirmware,
                vendor = state.deviceVendor,
                serial = state.deviceSerial,
                latencyMs = state.latencyMs,
                host = state.host,
                onRefresh = { repo.refresh() },
                onDismiss = { showDevice = false },
            )
        }
    }
}

// Команды BT-устройству (поле cmd в op=20) — как в const.py интеграции.
private const val BT_CMD_CONNECT = 0
private const val BT_CMD_DISCONNECT = 1
private const val BT_CMD_REMOVE = 2

private fun fmtTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString().replace('.', ',')

private fun sourceLabel(track: Track?): String? {
    if (track == null) return null
    return when {
        track.source == Source.BLUETOOTH -> "Bluetooth · ${track.artistLine}".trim()
        track.source == Source.RADIO -> track.stationName ?: "Радио"
        track.playlistTitle != null -> listOfNotNull(
            track.playlistTitle,
            if (track.provider == "zvuk") "Sber Звук" else null,
        ).joinToString(" · ")
        track.provider == "zvuk" -> "Sber Звук"
        else -> null
    }
}
