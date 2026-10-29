package ru.sberboom.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    var showHistory by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var phoneOpen by remember { mutableStateOf(false) }
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
                        listOf(
                            ambient.copy(alpha = if (ThemeMode.light) 0.18f else 0.42f),
                            ambient.copy(alpha = if (ThemeMode.light) 0.04f else 0.08f),
                            Color.Transparent,
                        ),
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
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Speaker,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
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
                            color = Muted.copy(alpha = if (ThemeMode.light) 1f else 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ExpressiveChip(
                    icon = Icons.Rounded.Search,
                    onClick = onOpenSearch,
                    contentDescription = "Поиск в Звуке",
                )
                Box {
                    ExpressiveChip(
                        icon = Icons.Rounded.MoreVert,
                        onClick = { menuOpen = true },
                        contentDescription = "Ещё",
                    )
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
                        DropdownMenuItem(
                            text = {
                                val left = state.sleepUntilMs?.let {
                                    ((it - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
                                }
                                Text(if (left != null) "Таймер сна: $left мин" else "Таймер сна")
                            },
                            onClick = {
                                menuOpen = false
                                sleepOpen = true
                            },
                        )
                        DropdownMenuItem(text = { Text("Играть с телефона (Bluetooth)") }, onClick = {
                            menuOpen = false
                            phoneOpen = true
                        })
                        DropdownMenuItem(
                            text = { Text(if (state.lightTheme) "Тёмная тема" else "Светлая тема") },
                            onClick = {
                                menuOpen = false
                                repo.setLightTheme(!state.lightTheme)
                            },
                        )
                        DropdownMenuItem(text = { Text("Недавнее") }, onClick = {
                            menuOpen = false
                            showHistory = true
                        })
                        DropdownMenuItem(text = { Text("Диагностика связи") }, onClick = {
                            menuOpen = false
                            showDiagnostics = true
                            repo.refresh()
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
            val coverInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pressScale(coverInteraction, pressed = 0.97f)
                    .clip(RoundedCornerShape(36.dp))
                    .clickable(interactionSource = coverInteraction, indication = null) {
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
                style = MaterialTheme.typography.headlineMedium,
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
                    color = Muted.copy(alpha = if (ThemeMode.light) 1f else 0.8f),
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

            // ───── прогресс: волнистая полоса Expressive ─────
            val duration = track?.durationSec ?: 0
            if (duration > 0) {
                var dragging by remember(track?.trackId) { mutableStateOf<Float?>(null) }
                val position = dragging ?: (track?.livePositionSec(tick) ?: 0).toFloat()
                WavyProgress(
                    progress = (position / duration).coerceIn(0f, 1f),
                    animated = track?.playing == true,
                    onSeek = { f -> dragging = (f * duration).toInt().toFloat() },
                    onSeekFinished = { f ->
                        repo.seekTo((f * duration).toInt())
                        dragging = null
                    },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        fmtTime((dragging ?: position).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                    )
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

            // ───── транспорт: плавающая пилюля ─────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(44.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveChip(
                        icon = Icons.Rounded.Shuffle,
                        onClick = { repo.setShuffle(!(track?.shuffle ?: false)) },
                        selected = track?.shuffle == true,
                        contentDescription = "Перемешать",
                    )
                    IconButton(onClick = { repo.prev() }, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            "Предыдущий",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    PlayButton(
                        playing = track?.playing == true,
                        onClick = { repo.playPause() },
                    )
                    IconButton(onClick = { repo.next() }, modifier = Modifier.size(52.dp)) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            "Следующий",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    val repeat = PlayerRepository.repeatCanonical(track?.repeat)
                    ExpressiveChip(
                        icon = if (repeat == "track") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        onClick = { repo.cycleRepeat() },
                        selected = repeat != "off",
                        contentDescription = "Повтор",
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ───── лайк / скорость / пульт / BT: чипы ─────
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpressiveChip(
                    icon = Icons.Rounded.Lyrics,
                    onClick = {
                        showLyrics = true
                        if (state.lyrics.isEmpty() && !state.lyricsLoading) repo.reloadLyrics()
                    },
                    selected = state.lyrics.isNotEmpty(),
                    contentDescription = "Текст песни",
                )
                ExpressiveChip(
                    icon = Icons.Rounded.ThumbUp,
                    onClick = { repo.toggleLike() },
                    selected = track?.liked == true,
                    contentDescription = "Нравится",
                )
                ExpressiveChip(
                    icon = Icons.Rounded.ThumbDown,
                    onClick = { repo.dislike() },
                    contentDescription = "Не нравится",
                )
                Box {
                    ExpressiveChip(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        onClick = { speedOpen = true },
                        label = "${formatSpeed(track?.speed ?: 1f)}×",
                        contentDescription = "Скорость воспроизведения",
                    )
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
                ExpressiveChip(
                    icon = Icons.Rounded.SettingsRemote,
                    onClick = { repo.findRemote() },
                    contentDescription = "Найти пульт",
                )
                ExpressiveChip(
                    icon = Icons.Rounded.Bluetooth,
                    onClick = {
                        showBt = true
                        repo.refreshBtDevices()
                    },
                    contentDescription = "Bluetooth-сопряжение",
                )
            }

            Spacer(Modifier.height(10.dp))

            // ───── громкость ─────
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpressiveChip(
                    icon = if (state.muted || state.volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    onClick = { repo.toggleMute() },
                    selected = state.muted,
                    contentDescription = "Звук",
                )
                Slider(
                    value = state.volume.toFloat(),
                    onValueChange = { repo.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = SberGreen,
                        activeTrackColor = SberGreen,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
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

        if (showHistory) {
            HistoryPanel(
                entries = state.history,
                onPlay = { entry ->
                    repo.playHistory(entry)
                    showHistory = false
                },
                onClear = { repo.clearHistory() },
                onDismiss = { showHistory = false },
            )
        }

        if (showDiagnostics) {
            DiagnosticsPanel(
                state = state,
                onRefresh = { repo.refresh() },
                onDismiss = { showDiagnostics = false },
            )
        }

        if (phoneOpen) {
            PhonePlaybackDialog(
                onEnablePairing = { repo.enableBluetoothPairing() },
                onDismiss = { phoneOpen = false },
            )
        }

        if (sleepOpen) {
            SleepTimerDialog(
                activeUntilMs = state.sleepUntilMs,
                onPick = { minutes ->
                    repo.setSleepTimer(minutes)
                    sleepOpen = false
                },
                onDismiss = { sleepOpen = false },
            )
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

/**
 * Главная кнопка Expressive: не круг, а «скворикс» — квадрат со скруглёнными
 * углами, который на нажатие проседает и возвращается с перелётом.
 */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(68.dp)
            .pressScale(interaction, pressed = 0.9f),
        shape = RoundedCornerShape(24.dp),
        color = SberGreen,
        contentColor = Ink,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                "Играть / пауза",
                modifier = Modifier.size(38.dp),
            )
        }
    }
}
