package ru.sberboom.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.ui.SberBoomRoot
import ru.sberboom.remote.ui.SberBoomTheme

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

    LaunchedEffect(Unit) { repo.autoConnect() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "SberBoom Remote",
        state = rememberWindowState(width = 560.dp, height = 940.dp),
    ) {
        SberBoomTheme {
            SberBoomRoot(repo)
        }
    }
}
