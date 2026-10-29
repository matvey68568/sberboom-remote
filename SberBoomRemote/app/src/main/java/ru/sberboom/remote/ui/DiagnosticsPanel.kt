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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.sberboom.remote.data.Connection
import ru.sberboom.remote.data.PlayerUiState

/**
 * Диагностика: что за колонка, как идёт связь и что пошло не так.
 * Всё, что видно — реальные данные из состояния, без «примерных» значений.
 */
@Composable
fun DiagnosticsPanel(
    state: PlayerUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    val rows = buildList {
        add("Состояние" to connectionLabel(state.connection))
        state.host?.let { add("Адрес" to it) }
        state.deviceName?.let { add("Колонка" to it) }
        state.deviceModel?.let { add("Модель" to it) }
        state.deviceVendor?.let { add("Производитель" to it) }
        state.deviceFirmware?.let { add("Прошивка" to it) }
        state.deviceSerial?.let { add("Серийник" to it) }
        state.latencyMs?.let { add("Отклик" to "$it мс") }
        state.device?.networkIp?.let { add("IP по мнению колонки" to it) }
        state.device?.connectionType?.let { add("Интерфейс" to it) }
        state.device?.timezoneId?.let { add("Часовой пояс" to it) }
        state.track?.source?.let { add("Источник" to it.name) }
        state.track?.provider?.let { add("Провайдер" to it) }
        state.track?.buffering?.let { add("Буферизация" to it.toString()) }
        add("История" to "${state.history.size} треков")
        state.sleepUntilMs?.let {
            val left = ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
            add("Таймер сна" to "осталось $left мин")
        }
    }

    val report = buildString {
        appendLine("SberBoom Remote · диагностика")
        rows.forEach { (k, v) -> appendLine("$k: $v") }
        state.track?.let { t ->
            appendLine("Трек: ${t.title ?: "—"} — ${t.artistLine.ifBlank { "—" }}")
            appendLine("Позиция: ${t.positionSec}/${t.durationSec ?: 0} c, playing=${t.playing}")
        }
        state.notice?.let { appendLine("Последнее сообщение: $it") }
    }

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
                    Icon(Icons.Rounded.MonitorHeart, null, tint = SberGreen, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Диагностика", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.host ?: "нет подключения",
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = onRefresh) { Text("Обновить", color = Muted) }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Закрыть", tint = Muted)
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            k,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                            modifier = Modifier.width(150.dp),
                        )
                        Text(
                            v,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                    Text("Скопировать отчёт", color = SberGreen)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

private fun connectionLabel(connection: Connection): String = when (connection) {
    Connection.Idle -> "не подключено"
    Connection.Connecting -> "подключаемся"
    Connection.Pairing -> "ждём нажатия «+» на колонке"
    Connection.Connected -> "подключено"
    Connection.Reconnecting -> "переподключаемся"
    is Connection.Failed -> "ошибка: ${connection.message}"
}
