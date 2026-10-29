package ru.sberboom.remote

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.data.Prefs
import ru.sberboom.remote.net.CoverArt
import ru.sberboom.remote.net.Discovery
import ru.sberboom.remote.notify.MediaNotification

class SberApp : Application() {

    lateinit var repo: PlayerRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        repo = PlayerRepository(
            prefs = Prefs(this),
            discovery = Discovery(this),
            cover = CoverArt,
            scope = appScope,
        )
        // Медиа-уведомление и экран блокировки живут, пока живо приложение.
        MediaNotification.attach(this, repo)
        registerWifiCallback()
        repo.autoConnect()
    }

    /**
     * Вернулись в Wi-Fi (вышли из VPN-туннеля, переключились с мобильной сети) —
     * поднимаем сессию, если она не жива: колонка доступна только по локальной сети.
     */
    private fun registerWifiCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!repo.state.value.isConnected) repo.autoConnect()
                }
            })
        }
    }
}
