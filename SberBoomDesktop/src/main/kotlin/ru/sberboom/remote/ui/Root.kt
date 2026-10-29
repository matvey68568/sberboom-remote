package ru.sberboom.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import ru.sberboom.remote.data.Connection
import ru.sberboom.remote.data.PlayerRepository

@Composable
fun SberBoomRoot(repo: PlayerRepository) {
    val state by repo.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var forceConnect by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.noticeId) {
        val text = state.notice
        if (text != null) {
            snackbar.showSnackbar(text)
            repo.clearNotice()
        }
    }

    LaunchedEffect(state.connection) {
        if (state.connection == Connection.Connected) forceConnect = false
    }

    val setupScreens = state.connection == Connection.Idle ||
        state.connection == Connection.Pairing ||
        state.connection is Connection.Failed
    val needsSetup = forceConnect || (state.track == null && setupScreens && !state.isConnected)

    SberBoomTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    when {
                        showSearch -> SearchScreen(
                            repo = repo,
                            onBack = { showSearch = false },
                        )

                        needsSetup -> ConnectScreen(
                            repo = repo,
                            connection = state.connection,
                            host = state.host,
                            onCancel = { forceConnect = false },
                        )

                        else -> PlayerScreen(
                            repo = repo,
                            state = state,
                            onOpenSearch = { showSearch = true },
                            onOpenConnect = { forceConnect = true },
                        )
                    }
                }
            }
        }
    }
}

/** Индикатор состояния соединения — точка + подпись. */
@Composable
fun ConnectionBadge(connection: Connection, modifier: Modifier = Modifier) {
    val (color, label) = when (connection) {
        Connection.Connected -> SberGreen to "На связи"
        Connection.Connecting -> Color(0xFFFFC857) to "Подключение"
        Connection.Pairing -> Color(0xFF8AB4FF) to "Сопряжение"
        Connection.Reconnecting -> Color(0xFFFFC857) to "Переподключение"
        Connection.Idle -> Muted to "Не подключено"
        is Connection.Failed -> MaterialTheme.colorScheme.error to "Ошибка"
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val pulse = if (connection == Connection.Connected) {
            val transition = rememberInfiniteTransition(label = "dot")
            transition.animateFloat(
                initialValue = 0.75f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
                label = "dotAlpha",
            ).value
        } else {
            1f
        }
        Box(
            Modifier
                .size(8.dp)
                .alpha(pulse)
                .background(color, CircleShape),
        )
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Карточка «нажмите + на колонке» с пульсацией. */
@Composable
fun PairingCard(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pair")
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pairScale",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF121A22), RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .scale(scale)
                .size(96.dp)
                .background(SberGreen.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Speaker,
                contentDescription = null,
                tint = SberGreen,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Нажмите «+» на колонке",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Один раз подтвердите доступ кнопкой громкости «+» на корпусе SberBoom. " +
                "Токен сохранится на телефоне, дальше подключение автоматическое.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = SberGreen,
            )
            Spacer(Modifier.size(8.dp))
            Text("Жду подтверждения…", style = MaterialTheme.typography.labelLarge, color = SberGreen)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = onCancel) {
            Text("Отменить", color = Muted)
        }
    }
}

@Composable
fun ConnectionLostBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Нет связи с колонкой", style = MaterialTheme.typography.bodyMedium)
        }
        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
fun ConnectedBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Wifi, null, tint = SberGreen, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Muted)
    }
}

@Composable
fun AnimatedVisibilityCompat(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible) { content() }
}
