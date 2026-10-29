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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import ru.sberboom.remote.protocol.BtDevice

/**
 * Панель Bluetooth колонки: список спаренных устройств (op=19) и команды
 * op=20 (подключить/отключить/забыть). Отдельной команды «выключить BT-модуль»
 * в протоколе колонки нет — это ограничение прошивки, о чём панель честно пишет.
 */
@Composable
fun BluetoothPanel(
    devices: List<BtDevice>,
    loading: Boolean,
    onPairing: () -> Unit,
    onConnect: (BtDevice) -> Unit,
    onDisconnect: (BtDevice) -> Unit,
    onForget: (BtDevice) -> Unit,
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
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Surface1)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(SberGreen.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Bluetooth, null, tint = SberGreen, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bluetooth колонки", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Устройства и режим сопряжения",
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Закрыть", tint = Muted)
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onPairing,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SberGreen, contentColor = Ink),
                ) {
                    Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Режим сопряжения", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRefresh) {
                    Text(if (loading) "Обновляю…" else "Обновить", color = Muted)
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                loading && devices.isEmpty() -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SberGreen)
                }

                devices.isEmpty() -> Text(
                    "К колонке пока ничего не подключено по Bluetooth. Нажмите «Режим сопряжения» " +
                        "и подключитесь с телефона или ноутбука как к обычной колонке.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        DeviceRow(
                            device = device,
                            onConnect = { onConnect(device) },
                            onDisconnect = { onDisconnect(device) },
                            onForget = { onForget(device) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Протокол колонки умеет только включать режим сопряжения и управлять уже " +
                    "спаренными устройствами. Выключить сам Bluetooth-модуль из приложения нельзя: " +
                    "это делает прошивка по таймауту, голосом («Алиса, выключи блютуз») или когда " +
                    "колонка переключается на другой источник — например, на музыку из этого приложения.",
                style = MaterialTheme.typography.labelMedium,
                color = Muted.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: BtDevice,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (device.connected) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(SberGreen, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    device.name.ifBlank { "Устройство" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                device.mac,
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                maxLines = 1,
            )
        }
        TextButton(onClick = { if (device.connected) onDisconnect() else onConnect() }) {
            Text(if (device.connected) "Отключить" else "Подключить", color = SberGreen)
        }
        TextButton(onClick = onForget) {
            Text("Забыть", color = Muted)
        }
    }
}
