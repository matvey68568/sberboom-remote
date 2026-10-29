package ru.sberboom.remote

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.sberboom.remote.data.Connection
import ru.sberboom.remote.data.PlayerRepository
import ru.sberboom.remote.data.PlayerUiState
import ru.sberboom.remote.protocol.DeviceState
import ru.sberboom.remote.protocol.Source
import ru.sberboom.remote.protocol.Track
import ru.sberboom.remote.data.HistoryEntry
import ru.sberboom.remote.ui.ConnectScreen
import ru.sberboom.remote.ui.DiagnosticsPanel
import ru.sberboom.remote.ui.HistoryPanel
import ru.sberboom.remote.ui.SleepTimerDialog
import ru.sberboom.remote.ui.PlayerScreen
import ru.sberboom.remote.ui.SberBoomTheme
import ru.sberboom.remote.ui.ThemeMode
import ru.sberboom.remote.ui.PhonePlaybackDialog
import ru.sberboom.remote.ui.SearchScreen
import java.io.File

/**
 * Рендер экранов в JVM (Robolectric + native graphics) и сохранение PNG.
 * Даёт реальную проверку, что Compose-разметка собирается и рисуется без падений.
 */
@Composable
private fun TestSurface(content: @Composable () -> Unit) {
    SberBoomTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun repo(): PlayerRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return PlayerRepository(
            prefs = ru.sberboom.remote.data.Prefs(context),
            discovery = ru.sberboom.remote.net.Discovery(context),
            cover = ru.sberboom.remote.net.CoverArt,
            scope = CoroutineScope(Dispatchers.Main),
        )
    }

    @Test
    fun rendersPlayerScreen() {
        val state = PlayerUiState(
            connection = Connection.Connected,
            host = "192.168.1.61",
            deviceName = "SberBoom Home",
            deviceModel = "sberboom-r2",
            deviceFirmware = "1.103.45",
            track = Track(
                title = "Thunderstruck",
                artists = listOf("AC/DC"),
                album = "The Razors Edge",
                trackId = "74563601",
                releaseId = "12058909",
                playlistTitle = "Персональная волна",
                playlistType = "endless",
                source = Source.MUSIC,
                provider = "zvuk",
                durationSec = 294,
                positionSec = 68,
                // играет — чтобы на скриншоте была видна волнистая полоса Expressive
                playing = true,
                shuffle = true,
                repeat = "playlist",
                liked = true,
                speed = 1f,
            ),
            volume = 62,
            muted = false,
            ambientColor = 0xFF2B7A5B.toInt(),
            device = DeviceState(networkIp = "192.168.1.61", ageMode = "adult"),
        )
        compose.setContent {
            TestSurface {
                PlayerScreen(repo = repo(), state = state, onOpenSearch = {}, onOpenConnect = {})
            }
        }
        compose.waitForIdle()
        save("player")
    }

    @Test
    fun rendersConnectScreen() {
        val repository = repo()
        compose.setContent {
            TestSurface {
                ConnectScreen(
                    repo = repository,
                    connection = Connection.Idle,
                    host = null,
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        save("connect")
    }

    @Test
    fun rendersPairingState() {
        val repository = repo()
        compose.setContent {
            TestSurface {
                ConnectScreen(
                    repo = repository,
                    connection = Connection.Pairing,
                    host = "192.168.1.61",
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        save("pairing")
    }

    @Test
    fun rendersBluetoothPanel() {
        val repository = repo()
        compose.setContent {
            TestSurface {
                ru.sberboom.remote.ui.BluetoothPanel(
                    devices = listOf(
                        ru.sberboom.remote.protocol.BtDevice("AA:BB:CC:DD:EE:01", "iPhone Matvey", connected = true),
                        ru.sberboom.remote.protocol.BtDevice("AA:BB:CC:DD:EE:02", "MacBook Pro", connected = false),
                    ),
                    loading = false,
                    onPairing = {},
                    onConnect = {},
                    onDisconnect = {},
                    onForget = {},
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        save("bluetooth")
        // панель работает и с пустым списком
        runCatching { repository.refreshBtDevices() }
    }

    @Test
    fun rendersLyricsPanel() {
        compose.setContent {
            TestSurface {
                ru.sberboom.remote.ui.LyricsPanel(
                    title = "Thunderstruck",
                    artist = "AC/DC",
                    coverUrl = null,
                    lines = listOf(
                        ru.sberboom.remote.net.LyricLine(0f, "Thunder, thunder, thunder, thunder"),
                        ru.sberboom.remote.net.LyricLine(6f, "I was caught in the middle of a railroad track"),
                        ru.sberboom.remote.net.LyricLine(12f, "I looked round and I knew there was no turning back"),
                        ru.sberboom.remote.net.LyricLine(18f, "My mind raced and I thought what could I do"),
                    ),
                    plain = null,
                    source = "lrclib",
                    instrumental = false,
                    loading = false,
                    activeIndex = 1,
                    karaokeFill = true,
                    activeProgress = 0.45f,
                    onToggleFill = {},
                    onReload = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        save("lyrics")
    }

    @Test
    fun rendersDevicePanel() {
        val device = DeviceState(
            networkIp = "192.168.1.61",
            connectionType = "wifi",
            activeApp = "music",
            foregroundApp = "music",
            appStackCount = 5,
            ageMode = "adult",
            multiProfile = true,
            timezoneId = "Europe/Moscow",
            timezoneOffsetSec = 10800,
            deviceUnixtime = System.currentTimeMillis() / 1000.0 + 3,
            firmwareChannel = "OpenBeta",
            alarmsCount = 3,
            alarmsEnabled = 2,
            alarmTimes = listOf("07:00 · по будням", "09:30 · по выходным"),
            timersCount = 1,
            timersLeftSec = listOf(272),
            remindersCount = 4,
            nextReminder = "2026-10-30T09:00:00Z",
            ledBrightness = 70,
            ledOn = true,
            stereoPairActive = false,
            multiroomMode = "solo",
            sbercastEnabled = true,
            sbercastDevicesCount = 2,
            assistantCharacter = "Сбер",
            autoVolume = true,
            proactivityNotification = false,
            sleepState = "working",
            morningShowIn = false,
            isSubscription = true,
            latitude = 55.7558,
            longitude = 37.6173,
            locationAccuracy = 120,
            locationSource = "wifi",
        )
        compose.setContent {
            TestSurface {
                ru.sberboom.remote.ui.DevicePanel(
                    device = device,
                    deviceName = "SberBoom Home",
                    model = "sberboom-r2",
                    firmware = "1.104.35",
                    host = "192.168.1.61",
                    onRefresh = {},
                    onDismiss = {},
                    vendor = "SberDevices",
                    serial = "SMD00V00AA000000",
                    latencyMs = 12,
                )
            }
        }
        compose.waitForIdle()
        save("device")
    }

    @Test
    fun rendersQueuePanel() {
        compose.setContent {
            TestSurface {
                ru.sberboom.remote.ui.QueuePanel(
                    queue = listOf(
                        ru.sberboom.remote.data.QueueEntry("74563601", "Thunderstruck", "AC/DC", null, false, 294),
                        ru.sberboom.remote.data.QueueEntry("74563602", "Back in Black", "AC/DC", null, false, 255),
                        ru.sberboom.remote.data.QueueEntry("74563603", "Hells Bells", "AC/DC", null, true, 312),
                    ),
                    loading = false,
                    currentTrackId = "74563601",
                    isBluetooth = false,
                    btDeviceName = null,
                    similar = emptyList(),
                    similarLoading = false,
                    onPlay = {},
                    onPlaySimilar = {},
                    onLoadSimilar = {},
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        save("queue")
    }

    @Test
    fun rendersBluetoothQueuePanel() {
        compose.setContent {
            TestSurface {
                ru.sberboom.remote.ui.QueuePanel(
                    queue = emptyList(),
                    loading = false,
                    currentTrackId = null,
                    isBluetooth = true,
                    btDeviceName = "iPhone Matvey",
                    similar = listOf(
                        ru.sberboom.remote.net.ZvukApi.Item(
                            id = "74563601",
                            type = "track",
                            title = "Thunderstruck",
                            subtitle = "AC/DC",
                            coverUrl = null,
                        ),
                        ru.sberboom.remote.net.ZvukApi.Item(
                            id = "33957705",
                            type = "track",
                            title = "Back In Black",
                            subtitle = "AC/DC · Альбом",
                            coverUrl = null,
                        ),
                    ),
                    similarLoading = false,
                    onPlay = {},
                    onPlaySimilar = {},
                    onLoadSimilar = {},
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        save("queue_bt")
    }

    @Test
    fun rendersSearchScreen() {
        val repository = repo()
        compose.setContent {
            TestSurface {
                SearchScreen(repo = repository, onBack = {})
            }
        }
        compose.waitForIdle()
        save("search")
    }

    @Test
    fun rendersHistoryPanel() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            HistoryEntry("137593089", "93", "Vtornik", null, now - 60_000),
            HistoryEntry("183032438", "Thunderstruck", "AC/DC", null, now - 3_600_000),
            HistoryEntry("999001", "Персональная волна", "Sber Звук", null, now - 86_400_000),
        )
        compose.setContent {
            TestSurface {
                HistoryPanel(entries = entries, onPlay = {}, onClear = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        save("history")
    }

    @Test
    fun rendersDiagnosticsPanel() {
        val state = PlayerUiState(
            connection = Connection.Connected,
            host = "192.168.1.61",
            deviceName = "SberBoom Mini",
            deviceModel = "sberboom-mini",
            deviceFirmware = "1.104.35",
            deviceVendor = "SberDevices",
            deviceSerial = "SMD00V00AA000000",
            latencyMs = 12,
            volume = 54,
            history = List(7) { HistoryEntry("$it", "Трек $it", "Артист", null, 0L) },
            sleepUntilMs = System.currentTimeMillis() + 25 * 60_000,
        )
        compose.setContent {
            TestSurface {
                DiagnosticsPanel(state = state, onRefresh = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        save("diagnostics")
    }

    @Test
    fun rendersSleepTimerDialog() {
        compose.setContent {
            TestSurface {
                SleepTimerDialog(
                    activeUntilMs = System.currentTimeMillis() + 20 * 60_000,
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        save("sleep_timer")
    }

    @Test
    fun rendersPlayerLightTheme() {
        ThemeMode.light = true
        try {
            val state = PlayerUiState(
                connection = Connection.Connected,
                host = "192.168.1.61",
                deviceName = "SberBoom Mini",
                deviceModel = "sberboom-mini",
                deviceFirmware = "1.104.35",
                lightTheme = true,
                track = Track(
                    title = "последнее лето",
                    artists = listOf("ooes"),
                    album = "мои (твои) тёмные желания",
                    trackId = "60117",
                    durationSec = 137,
                    positionSec = 16,
                    playing = true,
                ),
                volume = 42,
                latencyMs = 12,
            )
            compose.setContent {
                TestSurface {
                    PlayerScreen(
                        repo = repo(),
                        state = state,
                        onOpenSearch = {},
                        onOpenConnect = {},
                    )
                }
            }
            compose.waitForIdle()
            save("player_light")
        } finally {
            ThemeMode.light = false
        }
    }

    @Test
    fun rendersPhonePlaybackDialog() {
        compose.setContent {
            TestSurface {
                PhonePlaybackDialog(onEnablePairing = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        save("phone_playback")
    }
}
