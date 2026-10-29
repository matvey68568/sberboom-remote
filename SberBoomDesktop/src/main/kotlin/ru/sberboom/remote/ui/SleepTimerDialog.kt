package ru.sberboom.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Таймер сна: остановить колонку через 15/30/60/90 минут (или выключить).
 * Реализован на клиенте — в протоколе колонки таймера нет.
 */
@Composable
fun SleepTimerDialog(
    activeUntilMs: Long?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val left = activeUntilMs?.let { ((it - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таймер сна") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (left != null) {
                        "Сейчас: остановка примерно через $left мин."
                    } else {
                        "Колонка встанет на паузу через выбранное время."
                    },
                    color = Muted,
                )
                listOf(15, 30, 60, 90).forEach { minutes ->
                    TextButton(
                        onClick = { onPick(minutes) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Через $minutes мин", color = SberGreen)
                    }
                }
                if (activeUntilMs != null) {
                    TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Выключить таймер", color = Muted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = Muted) }
        },
    )
}
