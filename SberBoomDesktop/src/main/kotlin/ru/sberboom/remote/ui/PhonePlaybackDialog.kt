package ru.sberboom.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * «Играть с телефона»: колонка как Bluetooth-акустика.
 *
 * Единственный путь слушать то, чего нет в каталоге Звука (и что не требует
 * подписки Сбера): телефон играет сам, колонка принимает звук по BT.
 * Включает режим сопряжения на колонке (op=22) и объясняет шаги.
 */
@Composable
fun PhonePlaybackDialog(
    onEnablePairing: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Играть с телефона") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Колонка будет работать как Bluetooth-акустика: звук пойдёт с телефона, " +
                        "подписка Сбера не нужна.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Text(
                    listOf(
                        "1. Нажмите «Включить сопряжение» — колонка станет видимой.",
                        "2. Откройте Bluetooth в телефоне и выберите SberBoom.",
                        "3. Включите музыку в любом приложении.",
                    ).joinToString(separator = System.lineSeparator()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onEnablePairing()
                onDismiss()
            }) {
                Text("Включить сопряжение", color = SberGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = Muted) }
        },
    )
}
