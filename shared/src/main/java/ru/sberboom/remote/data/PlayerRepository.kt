package ru.sberboom.remote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.sberboom.remote.net.LyricLine
import ru.sberboom.remote.net.Lyrics
import ru.sberboom.remote.net.LyricsApi
import ru.sberboom.remote.net.SpeakerClient
import ru.sberboom.remote.net.ZvukApi
import ru.sberboom.remote.protocol.DeviceState
import ru.sberboom.remote.net.FoundSpeaker
import ru.sberboom.remote.platform.CoverService
import ru.sberboom.remote.platform.DiscoveryService
import ru.sberboom.remote.platform.KnownSpeaker
import ru.sberboom.remote.platform.Settings
import ru.sberboom.remote.protocol.DeviceInfo
import ru.sberboom.remote.protocol.BtDevice
import ru.sberboom.remote.protocol.Envelope
import ru.sberboom.remote.protocol.Payloads
import ru.sberboom.remote.protocol.Proto
import ru.sberboom.remote.protocol.Requests
import ru.sberboom.remote.protocol.Source
import ru.sberboom.remote.protocol.SpeakerState
import ru.sberboom.remote.protocol.Tlv
import ru.sberboom.remote.protocol.Track
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data object Pairing : Connection
    data object Connected : Connection
    data object Reconnecting : Connection
    data class Failed(val message: String) : Connection
}

data class PlayerUiState(
    val connection: Connection = Connection.Idle,
    val host: String? = null,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val deviceFirmware: String? = null,
    val deviceVendor: String? = null,
    val deviceSerial: String? = null,
    val latencyMs: Long? = null,
    val track: Track? = null,
    val volume: Int = 40,
    val muted: Boolean = false,
    val coverUrl: String? = null,
    val ambientColor: Int? = null,
    val device: DeviceState? = null,
    val notice: String? = null,
    val noticeId: Int = 0,
    val busy: Boolean = false,
    val btDevices: List<BtDevice> = emptyList(),
    val btLoading: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val queueLoading: Boolean = false,
    val similar: List<ZvukApi.Item> = emptyList(),
    val similarLoading: Boolean = false,
    val connectedBtName: String? = null,
    val lyrics: List<LyricLine> = emptyList(),
    val lyricsPlain: String? = null,
    val lyricsSource: String? = null,
    val lyricsInstrumental: Boolean = false,
    val lyricsLoading: Boolean = false,
    val karaokeFill: Boolean = true,
    /** Светлая тема оформления. */
    val lightTheme: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    /** Время, когда таймер сна остановит колонку (мс), null — таймер выключен. */
    val sleepUntilMs: Long? = null,
) {
    val isConnected: Boolean get() = connection == Connection.Connected
}

/** Прослушанный трек для экрана «Недавнее». */
data class HistoryEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val atMs: Long,
)

/** Элемент очереди колонки, обогащённый метаданными каталога Звука. */
data class QueueEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val explicit: Boolean,
    val durationSec: Int?,
)

/**
 * Оркестратор состояния: соединение с колонкой, оптимистичные команды,
 * push-обновления, поллинг громкости, обложки и обогащение из каталога Звука.
 */
class PlayerRepository(
    private val prefs: Settings,
    private val discovery: DiscoveryService,
    private val cover: CoverService,
    private val scope: CoroutineScope,
) {
    val zvuk = ZvukApi()
    private val lyricsApi = LyricsApi()

    private val _state = MutableStateFlow(
        PlayerUiState(
            host = prefs.host,
            deviceName = prefs.deviceName,
            deviceModel = prefs.deviceModel,
            deviceFirmware = prefs.deviceFirmware,
            karaokeFill = prefs.karaokeFill,
            lightTheme = prefs.lightTheme,
            history = readHistory(),
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var client: SpeakerClient? = null
    private var sessionJob: Job? = null
    private var pollJob: Job? = null
    private var volumeJob: Job? = null
    private var coverJob: Job? = null
    private var lastCoverKey: String? = null
    private var lastLyricsKey: String? = null
    private val lyricsCache = HashMap<String, Lyrics?>()
    private var lyricsJob: Job? = null
    private var userClosed = false
    private var noticeCounter = 0
    private var sleepJob: Job? = null

    val savedHost: String? get() = prefs.host
    val knownSpeakers: List<KnownSpeaker> get() = prefs.knownSpeakers
    val hasToken: Boolean get() = prefs.host?.let { prefs.tokenFor(it) } != null

    // ───────────────────────────── lifecycle ─────────────────────────────

    fun autoConnect() {
        val host = prefs.host ?: return
        if (prefs.tokenFor(host).isNullOrEmpty()) return
        connect(host, prefs.port, prefs.deviceName)
    }

    fun connect(
        host: String,
        port: Int = Proto.DEFAULT_PORT,
        name: String? = null,
        model: String? = null,
        firmware: String? = null,
    ) {
        userClosed = false
        prefs.host = host
        prefs.port = port
        if (name != null) prefs.deviceName = name
        if (model != null) prefs.deviceModel = model
        if (firmware != null) prefs.deviceFirmware = firmware
        prefs.rememberSpeaker(
            KnownSpeaker(
                host = host,
                port = port,
                name = name ?: prefs.deviceName,
                model = model ?: prefs.deviceModel,
                firmware = firmware ?: prefs.deviceFirmware,
            ),
        )
        _state.update { it.copy(deviceModel = model ?: it.deviceModel, deviceFirmware = firmware ?: it.deviceFirmware) }
        sessionJob?.cancel()
        pollJob?.cancel()
        client?.close()
        client = null
        sessionJob = scope.launch { runSession(host, port) }
    }

    fun disconnect() {
        userClosed = true
        sessionJob?.cancel()
        sessionJob = null
        pollJob?.cancel()
        pollJob = null
        client?.close()
        client = null
        _state.update { it.copy(connection = Connection.Idle, track = null, busy = false) }
    }

    /** Забыть устройство и токен (полный сброс). */
    fun forgetDevice() {
        disconnect()
        prefs.forget()
        _state.value = PlayerUiState(host = null)
    }

    /** Принудительное сопряжение: сбрасываем токен и проходим pair-flow заново. */
    fun pairNow() {
        val host = _state.value.host ?: prefs.host
        if (host == null) {
            notice("Сначала подключитесь к колонке по IP")
            return
        }
        prefs.setToken(host, null)
        connect(host, prefs.port, _state.value.deviceName)
    }

    suspend fun discover(timeoutMs: Long = 6_000): List<FoundSpeaker> = discovery.discover(timeoutMs)

    // ───────────────────────────── session ─────────────────────────────

    private suspend fun runSession(host: String, port: Int) {
        var attempt = 0
        var authFailures = 0
        while (coroutineContext.isActive && !userClosed) {
            _state.update { it.copy(connection = Connection.Connecting) }
            val c = SpeakerClient(
                host = host,
                port = port,
                clientId = prefs.clientId,
                clientName = prefs.clientName,
                token = prefs.tokenFor(host),
            )
            c.onUnsolicited = { raw -> scope.launch { handlePush(raw) } }
            try {
                c.connect()
                client = c

                if (prefs.tokenFor(host).isNullOrEmpty()) {
                    _state.update { it.copy(connection = Connection.Pairing) }
                    val token = c.pair()
                    prefs.setToken(host, token)
                    _state.update { it.copy(connection = Connection.Connecting) }
                }

                val ok = syncAll(c)
                if (!ok) throw SpeakerClient.AuthError("колонка не отдала состояние")

                _state.update { it.copy(connection = Connection.Connected) }
                attempt = 0
                authFailures = 0
                startPolling(c)
                c.awaitClose()
                if (!userClosed) _state.update { it.copy(connection = Connection.Reconnecting) }
            } catch (e: SpeakerClient.AuthError) {
                // Колонка выдаёт PIN-токен на одну сессию и отзывает его при повторном
                // сопряжении. Забываем мёртвый токен и сразу уходим в сопряжение —
                // пользователю достаточно один раз нажать «+».
                prefs.setToken(host, "")
                authFailures++
                if (!userClosed) {
                    _state.update {
                        it.copy(
                            connection = Connection.Failed(
                                "Токен колонки больше не действует — нажмите «+» на корпусе, чтобы сопрячься заново",
                            ),
                        )
                    }
                }
                if (authFailures > 2) break
                delay(500)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!userClosed) {
                    _state.update { it.copy(connection = Connection.Failed(friendly(e))) }
                }
            } finally {
                pollJob?.cancel()
                pollJob = null
                if (client === c) client = null
                c.close()
            }

            if (userClosed) break
            // Без токена повторять бессмысленно — колонка ждёт сопряжения.
            if (prefs.tokenFor(host).isNullOrEmpty()) break
            val delayMs = Proto.RECONNECT_BACKOFF_MS[minOf(attempt, Proto.RECONNECT_BACKOFF_MS.size - 1)]
            attempt++
            _state.update { it.copy(connection = Connection.Reconnecting) }
            delay(delayMs)
        }
    }

    /** Стартовая синхронизация: GET_STATE + GET_METADATA (он же включает push-поток). */
    private suspend fun syncAll(c: SpeakerClient): Boolean {
        val stateRaw = runCatching { c.request(Requests.GET_STATE) }.getOrNull() ?: return false
        val speaker = Payloads.parseState(stateRaw)
        if (speaker == null) return false
        applyVolume(speaker)
        _state.update { it.copy(device = speaker.device) }

        val mdRaw = runCatching { c.request(Requests.GET_METADATA) }.getOrNull()
        val track = mdRaw?.let { Payloads.parseTrack(it) } ?: Payloads.trackFromRawState(stateRaw)
        if (track != null) applyTrack(track)
        // Реквизиты самой колонки: модель/прошивка/вендор/серийник — из op=10.
        mdRaw?.let { Payloads.parseDeviceInfo(it) }?.let(::applyDeviceInfo)
        // Задержка связи: эхо-op 8 возвращает наш же payload.
        measureLatency(c)
        // Какое Bluetooth-устройство подключено — для подписи источника.
        refreshBtDevices()
        return true
    }

    private fun startPolling(c: SpeakerClient) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(5_000)
                ticks += 5
                try {
                    if (ticks % 25 == 0) c.fire(Requests.KEEP_ALIVE)

                    val raw = c.request(Requests.GET_STATE, 5_000)
                    val speaker = Payloads.parseState(raw)
                    if (speaker != null) {
                        applyVolume(speaker)
                        if (speaker.device != null) _state.update { it.copy(device = speaker.device) }
                    }

                    if (ticks % 20 == 0) {
                        val md = c.request(Requests.GET_METADATA, 5_000)
                        val track = Payloads.parseTrack(md) ?: Payloads.trackFromRawState(raw)
                        if (track != null) applyTrack(track)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SpeakerClient.AuthError) {
                    // Токен отозван на ходу: закрываем сокет, чтобы сессия
                    // пересоздалась и ушла в сопряжение.
                    c.close()
                    return@launch
                } catch (_: Exception) {
                    // Одиночный сбой запроса — соединение живо, продолжаем.
                }
            }
        }
    }

    private suspend fun handlePush(raw: ByteArray) {
        val track = Payloads.parseTrack(raw)
        if (track != null && (track.title != null || track.trackId != null || track.stationName != null)) {
            applyTrack(track)
            return
        }
        val speaker = Payloads.parseState(raw)
        if (speaker != null && (speaker.volumePercent != null || speaker.muted != null)) {
            applyVolume(speaker)
        }
    }

    private fun applyVolume(speaker: SpeakerState) {
        speaker.volumePercent?.let { v ->
            _state.update { it.copy(volume = v) }
        }
        speaker.muted?.let { m ->
            _state.update { it.copy(muted = m) }
        }
    }

    private fun applyTrack(incoming: Track) {
        val prev = _state.value.track
        val merged = if (prev == null) incoming else incoming.copy(
            liked = if (incoming.trackId != null && incoming.trackId == prev.trackId) {
                // Push может не нести like — оптимистичное значение сохраняем.
                prev.liked || incoming.liked
            } else incoming.liked,
            repeat = incoming.repeat ?: prev.repeat,
            speed = incoming.speed ?: prev.speed,
            album = incoming.album ?: if (incoming.trackId == prev.trackId) prev.album else null,
        )
        _state.update { it.copy(track = merged) }
        resolveCover(merged)
        loadLyrics(merged)
        if (merged.title != null && merged.trackId != prev?.trackId) rememberPlayed(merged)
    }

    /**
     * Реквизиты колонки из op=10: модель/прошивка/вендор/серийник.
     * Приходят от самой колонки, поэтому точнее mDNS TXT — перезаписывают сохранённое.
     */
    private fun applyDeviceInfo(info: DeviceInfo) {
        val model = info.model
        val firmware = info.firmware
        val name = info.name
        if (model != null && model != prefs.deviceModel) prefs.deviceModel = model
        if (firmware != null && firmware != prefs.deviceFirmware) prefs.deviceFirmware = firmware
        if (name != null && prefs.deviceName.isNullOrBlank()) prefs.deviceName = name
        _state.update {
            it.copy(
                deviceName = it.deviceName ?: name,
                deviceModel = model ?: it.deviceModel,
                deviceFirmware = firmware ?: it.deviceFirmware,
                deviceVendor = info.vendor ?: it.deviceVendor,
                deviceSerial = info.serial ?: it.deviceSerial,
            )
        }
    }

    /**
     * Задержка до колонки через эхо-op (8): устройство возвращает присланное
     * вложенное сообщение, поэтому RTT считается честно.
     */
    private suspend fun measureLatency(c: SpeakerClient) {
        runCatching {
            val started = System.nanoTime()
            c.request(Requests.echo(), 3_000)
            (System.nanoTime() - started) / 1_000_000
        }.onSuccess { ms ->
            _state.update { it.copy(latencyMs = ms) }
        }
    }

    // ───────────────────────────── сон и история ─────────────────────────────

    /**
     * Таймер сна: через N минут поставить колонку на паузу.
     * null или 0 — выключить. Живёт в клиенте, протоколу не нужен.
     */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null || minutes <= 0) {
            _state.update { it.copy(sleepUntilMs = null) }
            notice("Таймер сна выключен")
            return
        }
        val until = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepUntilMs = until) }
        notice("Колонка остановится через $minutes мин")
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            if (_state.value.track?.playing == true) send(Requests.media(Proto.MEDIA_PAUSE))
            _state.update { it.copy(sleepUntilMs = null) }
            notice("Таймер сна: воспроизведение остановлено")
        }
    }

    private fun rememberPlayed(track: Track) {
        val id = track.trackId ?: return
        val entry = HistoryEntry(
            trackId = id,
            title = track.title.orEmpty(),
            artist = track.artistLine,
            coverUrl = _state.value.coverUrl,
            atMs = System.currentTimeMillis(),
        )
        val updated = (listOf(entry) + _state.value.history.filterNot { it.trackId == id }).take(HISTORY_LIMIT)
        _state.update { it.copy(history = updated) }
        saveHistory(updated)
    }

    fun playHistory(entry: HistoryEntry) {
        val link = ZvukApi.deeplink("track", entry.trackId) ?: return
        playDeeplink(link)
    }

    /** Переключить тему: значение сразу уходит в настройки и в состояние. */
    fun setLightTheme(on: Boolean) {
        prefs.lightTheme = on
        _state.update { it.copy(lightTheme = on) }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        saveHistory(emptyList())
    }

    private fun readHistory(): List<HistoryEntry> {
        val raw = prefs.historyJson ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    trackId = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    coverUrl = o.optString("cover").takeIf { it.isNotEmpty() },
                    atMs = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(
                org.json.JSONObject()
                    .put("id", e.trackId)
                    .put("title", e.title)
                    .put("artist", e.artist)
                    .put("cover", e.coverUrl ?: "")
                    .put("at", e.atMs),
            )
        }
        prefs.historyJson = arr.toString()
    }

    // ───────────────────────────── commands ─────────────────────────────

    fun playPause() {
        val track = _state.value.track
        val playing = track?.playing == true
        send(Requests.media(if (playing) Proto.MEDIA_PAUSE else Proto.MEDIA_PLAY))
        _state.update { st ->
            val t = st.track ?: return@update st
            st.copy(
                track = t.copy(
                    playing = !playing,
                    positionSec = t.livePositionSec(),
                    receivedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun next() = send(Requests.media(Proto.MEDIA_NEXT))

    fun prev() = send(Requests.media(Proto.MEDIA_PREV))

    fun setVolume(percent: Int) {
        val value = percent.coerceIn(0, Proto.VOLUME_MAX)
        _state.update { it.copy(volume = value, muted = if (value > 0) false else it.muted) }
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(120)
            client?.let { runCatching { it.fire(Requests.volume(value)) } }
        }
    }

    fun toggleMute() {
        val muted = _state.value.muted
        send(Requests.media(if (muted) Proto.MEDIA_UNMUTE else Proto.MEDIA_MUTE))
        _state.update { it.copy(muted = !muted) }
    }

    fun toggleLike() {
        val track = _state.value.track ?: return
        val liked = !track.liked
        send(Requests.media(if (liked) Proto.MEDIA_LIKE else Proto.MEDIA_REMOVE_LIKE))
        _state.update { st -> st.copy(track = st.track?.copy(liked = liked)) }
    }

    fun dislike() {
        send(Requests.media(Proto.MEDIA_DISLIKE))
        notice("Дизлайк отправлен")
    }

    fun setShuffle(on: Boolean) {
        send(Requests.media(if (on) Proto.MEDIA_SHUFFLE_ON else Proto.MEDIA_SHUFFLE_OFF))
        _state.update { st -> st.copy(track = st.track?.copy(shuffle = on)) }
    }

    fun cycleRepeat() {
        val current = repeatCanonical(_state.value.track?.repeat)
        val next = when (current) {
            "playlist" -> "track"
            "track" -> "off"
            else -> "playlist"
        }
        val action = when (next) {
            "playlist" -> Proto.MEDIA_REPEAT_PLAYLIST
            "track" -> Proto.MEDIA_REPEAT_TRACK
            else -> Proto.MEDIA_REPEAT_NONE
        }
        send(Requests.media(action))
        _state.update { st -> st.copy(track = st.track?.copy(repeat = next)) }
    }

    fun seekTo(seconds: Int) {
        val pos = seconds.coerceAtLeast(0)
        send(Requests.seek(pos))
        _state.update { st ->
            st.copy(track = st.track?.copy(positionSec = pos, receivedAtMs = System.currentTimeMillis()))
        }
    }

    fun setSpeed(rate: Float) {
        val value = rate.coerceIn(Proto.SPEED_MIN, Proto.SPEED_MAX)
        send(Requests.speed(value))
        _state.update { st -> st.copy(track = st.track?.copy(speed = value)) }
        scope.launch {
            delay(500)
            refresh()
        }
    }

    fun enableBluetoothPairing() {
        send(Requests.BT_DISCOVERABLE)
        notice("Режим сопряжения Bluetooth включён — ищите колонку на телефоне")
    }

    /** Список спаренных Bluetooth-устройств колонки (op=19). */
    fun refreshBtDevices() {
        val c = client ?: return
        scope.launch {
            _state.update { it.copy(btLoading = true) }
            val list = runCatching { Payloads.parsePairedBt(c.request(Requests.GET_PAIRED_BT, 6_000)) }
                .getOrDefault(emptyList())
            _state.update {
                it.copy(
                    btDevices = list,
                    btLoading = false,
                    connectedBtName = list.firstOrNull { d -> d.connected }?.name
                        ?: list.firstOrNull { d -> d.connected }?.mac,
                )
            }
        }
    }

    /**
     * «Что дальше» для Bluetooth-источника: колонка очередь не отдаёт, поэтому
     * подбираем похожие треки в каталоге Звука по текущему исполнителю.
     */
    fun loadSimilar() {
        val track = _state.value.track ?: return
        val query = track.artistLine.ifBlank { track.title.orEmpty() }
        if (query.isBlank()) return
        scope.launch {
            _state.update { it.copy(similarLoading = true) }
            val items = runCatching { zvuk.similar(query, 10) }.getOrDefault(emptyList())
            _state.update { it.copy(similar = items, similarLoading = false) }
        }
    }

    /**
     * Команда BT-устройству (op=20): 0 — подключить, 1 — отключить, 2 — забыть.
     * Отдельной команды «выключить Bluetooth-модуль колонки» протокол не имеет.
     */
    fun btCommand(device: BtDevice, command: Int) {
        send(Requests.btCommand(device.mac, command))
        notice(
            when (command) {
                0 -> "Подключаю устройство…"
                1 -> "Отключаю устройство…"
                else -> "Убираю устройство из списка…"
            },
        )
        scope.launch {
            delay(1_200)
            refreshBtDevices()
        }
    }

    fun refresh() {
        scope.launch { client?.let { syncAll(it) } }
    }

    /** Очередь воспроизведения (op=17) с метаданными из каталога Звука. */
    fun loadQueue() {
        val c = client ?: return
        scope.launch {
            _state.update { it.copy(queueLoading = true) }
            val items = runCatching { Payloads.parseQueue(c.request(Requests.GET_QUEUE, 6_000)) }
                .getOrDefault(emptyList())
            val metas = if (items.isEmpty()) {
                emptyList()
            } else {
                runCatching { zvuk.tracks(items.map { it.trackId }.distinct().take(100)) }
                    .getOrDefault(emptyList())
            }
            val byId = metas.associateBy { it.id }
            val entries = items.map { item ->
                val meta = byId[item.trackId]
                QueueEntry(
                    trackId = item.trackId,
                    title = meta?.title ?: "Трек ${item.trackId}",
                    artist = meta?.artist.orEmpty(),
                    coverUrl = meta?.coverUrl,
                    explicit = item.explicit || (meta?.explicit ?: false),
                    durationSec = meta?.durationSec,
                )
            }
            _state.update { it.copy(queue = entries, queueLoading = false) }
        }
    }

    /** Включить выбранный трек из очереди — deeplink по каталожному id. */
    fun playFromQueue(entry: QueueEntry) {
        val link = ZvukApi.deeplink("track", entry.trackId)
        if (link == null) {
            notice("Не удалось собрать ссылку на трек")
            return
        }
        playDeeplink(link)
    }

    /** Проиграть staros:// deeplink (результат поиска Звука, ссылка, запрос). */
    fun playDeeplink(deeplink: String) {
        scope.launch {
            val c = client
            if (c == null) {
                notice("Нет соединения с колонкой")
                return@launch
            }
            _state.update { it.copy(busy = true) }
            try {
                val payload = JSONObject().put("deeplink", deeplink).toString()
                val body = Envelope.serverActionJson("DEEPLINK", payload)
                val response = c.sendServerAction(body)
                val field4 = Tlv.decode(response)[4]
                val accepted = field4?.toString()?.contains("OK") == true
                if (accepted) {
                    notice("Включаю на колонке")
                    delay(900)
                    client?.let { syncAll(it) }
                } else {
                    notice("Колонка отклонила запрос")
                }
            } catch (e: Exception) {
                notice("Не удалось запустить: ${e.message ?: "ошибка"}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Проиграть элемент каталога Звука. */
    fun play(item: ZvukApi.Item) {
        val link = ZvukApi.deeplink(item.pt ?: item.type, item.id)
        if (link == null) {
            notice("Не удалось собрать ссылку на этот элемент")
            return
        }
        playDeeplink(link)
    }

    /** Проиграть по ссылке zvuk.com (вставленной вручную). */
    fun playZvukUrl(text: String): Boolean {
        val parsed = ZvukApi.parseZvukUrl(text) ?: return false
        val link = ZvukApi.deeplink(parsed.first, parsed.second) ?: return false
        playDeeplink(link)
        return true
    }

    private fun send(data: ByteArray) {
        val c = client
        if (c == null) {
            notice("Нет соединения с колонкой")
            return
        }
        scope.launch {
            runCatching { c.fire(data) }.onFailure { notice("Команда не отправлена") }
        }
    }

    // ───────────────────────────── covers ─────────────────────────────

    private fun resolveCover(track: Track) {
        val key = track.trackId ?: "${track.artistLine}|${track.title}|${track.album}"
        if (key == lastCoverKey) return
        lastCoverKey = key
        coverJob?.cancel()
        coverJob = scope.launch {
            val url = zvukCover(track)
                ?: runCatching { zvuk.externalCover(track.title, track.artistLine) }.getOrNull()
            _state.update { it.copy(coverUrl = url) }
            if (url != null) {
                val cached = cover.cached(url)
                if (cached != null) {
                    _state.update { it.copy(ambientColor = cached) }
                } else {
                    val color = withContext(Dispatchers.IO) { runCatching { cover.ambientColor(url) }.getOrNull() }
                    if (color != null) _state.update { it.copy(ambientColor = color) }
                }
            }
        }
    }

    // ───────────────────────────── lyrics ─────────────────────────────

    /** Текст песни по названию+исполнителю (Lrclib → NetEase), с кэшем по треку. */
    private fun loadLyrics(track: Track) {
        val title = track.title?.takeIf { it.isNotBlank() } ?: return
        val artist = track.artistLine.takeIf { it.isNotBlank() } ?: return
        val key = "$artist|$title|${track.album ?: ""}"
        if (key == lastLyricsKey) return
        lastLyricsKey = key
        lyricsJob?.cancel()
        _state.update {
            it.copy(
                lyrics = emptyList(),
                lyricsPlain = null,
                lyricsSource = null,
                lyricsInstrumental = false,
                lyricsLoading = true,
            )
        }
        lyricsJob = scope.launch {
            val cached = if (lyricsCache.containsKey(key)) lyricsCache[key] else null
            val lyrics = cached ?: runCatching {
                lyricsApi.fetch(title, artist, track.album, track.durationSec)
            }.getOrNull().also { lyricsCache[key] = it }

            _state.update {
                it.copy(
                    lyrics = lyrics?.lines.orEmpty(),
                    lyricsPlain = lyrics?.plain,
                    lyricsSource = lyrics?.source,
                    lyricsInstrumental = lyrics?.instrumental == true,
                    lyricsLoading = false,
                )
            }
        }
    }

    /** Повторить поиск текста (кнопка «Ещё раз»). */
    fun reloadLyrics() {
        val key = lastLyricsKey
        if (key != null) lyricsCache.remove(key)
        lastLyricsKey = null
        _state.value.track?.let { loadLyrics(it) }
    }

    /** Караоке-заливка активной строки (посимвольно) — вкл/выкл. */
    fun toggleKaraokeFill() {
        val value = !_state.value.karaokeFill
        prefs.karaokeFill = value
        _state.update { it.copy(karaokeFill = value) }
    }

    private fun zvukCover(track: Track): String? {
        if (track.provider != "zvuk" && track.source != Source.MUSIC) return null
        track.releaseId?.let {
            return "${Proto.ZVUK_IMAGE_CDN}?type=release&id=$it&size=${Proto.COVER_SIZE}"
        }
        track.artistIds.firstOrNull()?.let {
            return "${Proto.ZVUK_IMAGE_CDN}?type=artist&id=$it&size=${Proto.COVER_SIZE}"
        }
        return null
    }

    // ───────────────────────────── misc ─────────────────────────────

    private fun notice(text: String) {
        noticeCounter++
        _state.update { it.copy(notice = text, noticeId = noticeCounter) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is SpeakerClient.TimeoutError -> "Колонка не ответила. Проверьте, что она в той же сети"
        is SpeakerClient.PairError -> e.message ?: "Ошибка сопряжения"
        is UnknownHostException -> "Адрес не найден"
        is ConnectException, is SocketTimeoutException -> "Не удалось подключиться к порту ${prefs.port}"
        is SSLException -> "Ошибка TLS-рукопожатия"
        is IOException -> "Сетевая ошибка: ${e.message ?: "нет связи"}"
        else -> e.message ?: "Неизвестная ошибка"
    }

    companion object {
        /** Канонизация repeat: none/off, playlist/all, track/one. */
        fun repeatCanonical(value: String?): String = when (value?.lowercase()) {
            "playlist", "all" -> "playlist"
            "track", "one" -> "track"
            else -> "off"
        }

        /** Сколько треков хранить в истории прослушивания. */
        const val HISTORY_LIMIT = 40
    }
}
