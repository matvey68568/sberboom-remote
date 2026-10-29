package ru.sberboom.remote.notify

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ru.sberboom.remote.MainActivity
import ru.sberboom.remote.R
import ru.sberboom.remote.SberApp

/**
 * Плитка в шторке быстрых настроек: показать состояние и переключить паузу.
 * Долгое нажатие открывает приложение.
 */
@RequiresApi(Build.VERSION_CODES.N)
class PlaybackTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val repo = (application as? SberApp)?.repo ?: return
        job?.cancel()
        job = scope.launch {
            repo.state.collect { state ->
                val tile = qsTile ?: return@collect
                val playing = state.track?.playing == true
                tile.state = when {
                    !state.isConnected -> Tile.STATE_UNAVAILABLE
                    playing -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.label = state.track?.title?.take(30) ?: "SberBoom"
                tile.subtitle = when {
                    !state.isConnected -> "нет связи"
                    playing -> "играет"
                    else -> "пауза"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.icon = Icon.createWithResource(this@PlaybackTileService, R.drawable.ic_stat_boom)
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val repo = (application as? SberApp)?.repo
        if (repo == null || !repo.state.value.isConnected) {
            openApp()
            return
        }
        repo.playPause()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
