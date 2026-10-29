package ru.sberboom.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.sberboom.remote.data.Connection
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.net.FoundSpeaker

@Composable
fun ConnectScreen(
    repo: PlayerRepository,
    connection: Connection,
    host: String?,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<FoundSpeaker>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf(host ?: "") }
    val error = (connection as? Connection.Failed)?.message

    suspend fun scan() {
        scanning = true
        devices = runCatching { repo.discover(6_000) }.getOrDefault(emptyList())
        scanning = false
        scanned = true
    }

    LaunchedEffect(Unit) {
        if (devices.isEmpty() && !scanning) scan()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Speaker,
                null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "SberBoom",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Локальное управление колонкой по Wi-Fi. Без облака, аккаунта и токенов Сбера — " +
                "только ваш телефон и колонка в одной сети.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        Spacer(Modifier.height(24.dp))

        when (connection) {
            Connection.Pairing -> PairingCard(onCancel = { repo.disconnect(); onCancel() })

            Connection.Connecting, Connection.Reconnecting -> Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SberGreen)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Подключаюсь", style = MaterialTheme.typography.titleMedium)
                    Text(host ?: ip, style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }

            is Connection.Failed -> Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(24.dp))
                    .padding(20.dp),
            ) {
                Text(
                    "Не удалось подключиться",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = {
                        val target = ip.ifBlank { host ?: return@Button }
                        repo.connect(target.trim())
                    }) { Text("Повторить") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { repo.pairNow() }) { Text("Сопряжение") }
                }
            }

            else -> Unit
        }

        if (connection !is Connection.Pairing) {
            // Сохранённые колонки — подключение в один тап, без повторного поиска.
            val saved = repo.knownSpeakers
            if (saved.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Мои колонки", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saved.forEach { known ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                                .clickable {
                                    ip = known.host
                                    repo.connect(
                                        host = known.host,
                                        port = known.port,
                                        name = known.name,
                                        model = known.model,
                                        firmware = known.firmware,
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    known.name ?: "SberBoom",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    known.detail,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Muted,
                                    maxLines = 1,
                                )
                            }
                            Text("Подключить", style = MaterialTheme.typography.labelMedium, color = SberGreen)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Колонки в сети", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { scope.launch { scan() } }, enabled = !scanning) {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (scanning) "Ищу…" else "Найти")
                }
            }
            Spacer(Modifier.height(10.dp))

            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Сканирую mDNS и порт 20000 в подсети…"
                    else if (scanned) "Ничего не нашлось. Введите IP колонки вручную — она в приложении «Дом с Алисой» или в роутере."
                    else "Готово к поиску",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                                .clickable {
                                    ip = device.host
                                    repo.connect(
                                        host = device.host,
                                        port = device.port,
                                        name = device.name,
                                        model = device.model,
                                        firmware = device.firmware,
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.name ?: "SberBoom", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    device.detail,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Muted,
                                )
                            }
                            Text("Подключить", style = MaterialTheme.typography.labelMedium, color = SberGreen)
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text("Или введите адрес вручную", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("IP-адрес колонки") },
                placeholder = { Text("192.168.1.61") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { repo.connect(ip.trim()) },
                enabled = ip.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text("Подключиться", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "При первом подключении колонка попросит подтвердить доступ кнопкой «+» на корпусе. " +
                    "Это делает только владелец устройства — удалённо привязаться нельзя.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Start,
            )

            if (host != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { repo.forgetDevice() }) {
                    Text("Забыть сохранённую колонку", color = Muted)
                }
            }
        }
    }
}
