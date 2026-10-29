package ru.sberboom.remote

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.data.Prefs
import ru.sberboom.remote.net.CoverArt
import ru.sberboom.remote.net.Discovery

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
        repo.autoConnect()
    }
}
