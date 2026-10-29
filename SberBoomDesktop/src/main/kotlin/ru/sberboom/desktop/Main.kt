package ru.sberboom.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.ui.SberBoomRoot
import ru.sberboom.remote.ui.SberBoomTheme

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val repo = remember {
        PlayerRepository(
            prefs = Prefs(),
            discovery = Discovery(),
            cover = CoverArt,
            scope = scope,
        )
    }
    val icon = painterResource("icon.png")

    var windowVisible by remember { mutableStateOf(true) }
    val state = rememberWindowState(width = 560.dp, height = 940.dp)

    LaunchedEffect(Unit) { repo.autoConnect() }

    /** Горячие клавиши не конфликтуют с вводом текста: только Ctrl+… и медиа-клавиши. */
    fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed
        return when {
            event.key == Key.MediaPlayPause -> { repo.playPause(); true }
            event.key == Key.MediaNext -> { repo.next(); true }
            event.key == Key.MediaPrevious -> { repo.prev(); true }
            ctrl && event.key == Key.Spacebar -> { repo.playPause(); true }
            ctrl && event.key == Key.DirectionRight -> { repo.next(); true }
            ctrl && event.key == Key.DirectionLeft -> { repo.prev(); true }
            ctrl && event.key == Key.DirectionUp -> { repo.setVolume(repo.state.value.volume + 5); true }
            ctrl && event.key == Key.DirectionDown -> { repo.setVolume(repo.state.value.volume - 5); true }
            ctrl && event.key == Key.M -> { repo.toggleMute(); true }
            else -> false
        }
    }

    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "SberBoom Remote",
        icon = icon,
        state = state,
    ) {
        MenuBar {
            Menu("Колонка") {
                Item("Показать окно") { windowVisible = true }
                Separator()
                Item("Играть / Пауза") { repo.playPause() }
                Item("Следующий трек") { repo.next() }
                Item("Предыдущий трек") { repo.prev() }
                Separator()
                Item("Сопряжение заново") { repo.pairNow() }
                Item("Обновить состояние") { repo.refresh() }
                Separator()
                Item("Ctrl+Space — пауза", enabled = false) {}
                Item("Ctrl+←/→ — трек", enabled = false) {}
                Item("Ctrl+↑/↓ — громкость", enabled = false) {}
                Separator()
                Item("Выход") { exitApplication() }
            }
        }

        Box(Modifier.fillMaxSize().onPreviewKeyEvent { handleKey(it) }) {
            SberBoomTheme {
                SberBoomRoot(repo)
            }
        }
    }

    Tray(
        icon = icon,
        tooltip = "SberBoom Remote",
        onAction = { windowVisible = true },
    ) {
        Item("Открыть") { windowVisible = true }
        Separator()
        Item(if (repo.state.value.track?.playing == true) "Пауза" else "Играть") { repo.playPause() }
        Item("Следующий трек") { repo.next() }
        Item("Предыдущий трек") { repo.prev() }
        Separator()
        Item("Выход") { exitApplication() }
    }
}
