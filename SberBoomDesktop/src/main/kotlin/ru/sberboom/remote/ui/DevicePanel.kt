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
import androidx.compose.material.icons.rounded.Speaker
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
import androidx.compose.ui.unit.dp
import ru.sberboom.remote.protocol.DeviceState
import kotlin.math.abs
import kotlin.math.roundToInt

private data class InfoItem(val title: String, val rows: List<Pair<String, String>>)

/**
 * Панель «Колонка»: подсистемы из GET_STATE (op=12) — будильники, таймеры,
 * напоминания, LED, стереопара/multiroom, сеть, профиль, локация.
 *
 * Только чтение: протокол колонки не предоставляет команд на изменение этих
 * параметров (в HA-интеграции они тоже read-only).
 */
@Composable
fun DevicePanel(
    device: DeviceState?,
    deviceName: String?,
    model: String?,
    firmware: String?,
    host: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    vendor: String? = null,
    serial: String? = null,
    latencyMs: Long? = null,
) {
    val sections = remember(device, model, firmware, vendor, serial, latencyMs) {
        buildSections(device, deviceName, model, firmware, host, vendor, serial, latencyMs)
    }

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
                    Icon(Icons.Rounded.Speaker, null, tint = SberGreen, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Колонка", style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(model, firmware?.let { "fw $it" }).joinToString(" · ")
                            .ifEmpty { deviceName ?: "состояние устройства" },
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

            Spacer(Modifier.height(12.dp))

            if (device == null) {
                Text(
                    "Колонка ещё не прислала состояние — нажмите «Обновить».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
                ) {
                    sections.forEach { section ->
                        item(key = "s-${section.title}") {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = SberGreen.copy(alpha = 0.9f),
                            )
                        }
                        items(section.rows, key = { "${section.title}-${it.first}" }) { (label, value) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Surface2.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Muted,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                    item(key = "note") {
                        Text(
                            "Только чтение: протокол колонки не даёт команд менять будильники, " +
                                "яркость кольца и связку устройств — это делает приложение «Дом с Алисой» " +
                                "или голос.",
                            style = MaterialTheme.typography.labelMedium,
                            color = Muted.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

private fun buildSections(
    d: DeviceState?,
    name: String?,
    model: String?,
    firmware: String?,
    host: String?,
    vendor: String? = null,
    serial: String? = null,
    latencyMs: Long? = null,
): List<InfoItem> {
    if (d == null) return emptyList()
    val out = mutableListOf<InfoItem>()

    out += InfoItem(
        "Устройство",
        listOfNotNull(
            name?.let { "Название" to it },
            model?.let { "Модель" to it },
            vendor?.let { "Производитель" to it },
            firmware?.let { "Прошивка" to it },
            serial?.let { "Серийник" to it },
            d.firmwareChannel?.let { "Канал" to it },
            (d.networkIp ?: host)?.let { "IP" to it },
            d.connectionType?.let { "Сеть" to it },
            latencyMs?.let { "Отклик" to "$it мс" },
            d.timezoneId?.let { "Часовой пояс" to it },
            d.deviceUnixtime?.let { "Часы колонки" to clockSkewLabel(it) },
            d.foregroundApp?.let { "На экране" to it },
            d.activeApp?.let { "Играет" to it },
            if (d.appStackCount > 0) "Приложений в стеке" to d.appStackCount.toString() else null,
            d.sleepState?.let { "Состояние" to it },
        ),
    )

    val alarms = buildList {
        if (d.alarmsCount != null) add("Всего" to "${d.alarmsCount} (вкл. ${d.alarmsEnabled})")
        if (d.alarmTimes.isNotEmpty()) add("Будильники" to d.alarmTimes.joinToString("\n"))
        if (d.alarmRinging) add("Сейчас" to "звонит будильник")
    }
    if (alarms.isNotEmpty()) out += InfoItem("Будильники", alarms)

    val timers = buildList {
        if (d.timersCount != null) add("Таймеров" to d.timersCount.toString())
        if (d.timersLeftSec.isNotEmpty()) {
            add("Осталось" to d.timersLeftSec.joinToString(", ") { fmtDuration(it) })
        }
        if (d.remindersCount > 0) add("Напоминаний" to d.remindersCount.toString())
        d.nextReminder?.let { add("Ближайшее" to prettyIso(it)) }
    }
    if (timers.isNotEmpty()) out += InfoItem("Таймеры и напоминания", timers)

    val light = buildList {
        d.ledOn?.let { add("Кольцо" to if (it) "включено" else "выключено") }
        d.ledBrightness?.let { add("Яркость кольца" to "$it%") }
        d.autoVolume?.let { add("Автогромкость" to yesNo(it)) }
        d.assistantCharacter?.let { add("Персона" to it) }
        d.ageMode?.let { add("Профиль" to if (it == "child") "детский" else "взрослый") }
        d.multiProfile?.let { add("Мультипрофиль" to yesNo(it)) }
        d.childVoiceExplicit?.let { add("Явный детский голос" to yesNo(it)) }
    }
    if (light.isNotEmpty()) out += InfoItem("Свет и звук", light)

    val link = buildList {
        d.stereoPairActive?.let { add("Стереопара" to if (it) "активна" else "нет") }
        d.stereoPairChannel?.let { add("Канал" to if (it == "left") "левый" else if (it == "right") "правый" else it) }
        d.stereoPairDevice?.let { add("Парное устройство" to it) }
        d.multiroomMode?.let { add("Multiroom" to it) }
        d.sbercastEnabled?.let { add("SberCast" to yesNo(it)) }
        if (d.sbercastDevicesCount > 0) add("Устройств рядом" to d.sbercastDevicesCount.toString())
        if (d.soundbarGroup) add("Саундбар" to "в группе")
    }
    if (link.isNotEmpty()) out += InfoItem("Связка устройств", link)

    val misc = buildList {
        d.isSubscription?.let { add("Подписка на устройстве" to yesNo(it)) }
        d.morningShowIn?.let { add("Утреннее шоу" to if (it) "идёт" else "нет") }
        d.morningShowFrom?.let { add("Запущено из шоу" to yesNo(it)) }
        d.proactivityNotification?.let { add("Проактивные подсказки" to yesNo(it)) }
        d.latitude?.let { lat ->
            d.longitude?.let { lon ->
                add("Координаты" to "%.4f, %.4f".format(lat, lon))
            }
        }
        d.locationSource?.let { add("Геолокация" to it) }
        d.locationAccuracy?.let { add("Точность" to "$it м") }
    }
    if (misc.isNotEmpty()) out += InfoItem("Прочее", misc)

    return out
}

private fun yesNo(value: Boolean): String = if (value) "да" else "нет"

/** Расхождение часов колонки с телефоном — диагностика (как сенсор skew в HA). */
private fun clockSkewLabel(deviceUnixTime: Double): String {
    val skew = deviceUnixTime - System.currentTimeMillis() / 1000.0
    val seconds = skew.roundToInt()
    return when {
        abs(seconds) <= 2 -> "синхронно"
        seconds > 0 -> "+$seconds с"
        else -> "$seconds с"
    }
}

private fun fmtDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun prettyIso(iso: String): String =
    iso.replace("T", " ").removeSuffix("Z").take(16).ifEmpty { iso }
