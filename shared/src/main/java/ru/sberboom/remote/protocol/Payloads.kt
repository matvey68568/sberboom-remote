package ru.sberboom.remote.protocol

import org.json.JSONObject

enum class Source { MUSIC, RADIO, BLUETOOTH, PODCAST, WAVE, UNKNOWN }

/** Реквизиты колонки из GET_METADATA (op=10), поле 2 — то, что отдаёт сама колонка. */
data class DeviceInfo(
    val firmware: String? = null,
    val model: String? = null,
    val name: String? = null,
    val vendor: String? = null,
    val serial: String? = null,
)

/** Метаданные текущего трека — порт TrackInfo интеграции. */
data class Track(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val artistIds: List<String> = emptyList(),
    val album: String? = null,
    val trackId: String? = null,
    val releaseId: String? = null,
    val playlistTitle: String? = null,
    val playlistType: String? = null,
    val source: Source = Source.UNKNOWN,
    val stationName: String? = null,
    val provider: String? = null,
    val durationSec: Int? = null,
    val positionSec: Int = 0,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: String? = null,
    val explicit: Boolean = false,
    val liked: Boolean = false,
    val childMode: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float? = null,
    val hasLyrics: Boolean? = null,
    val receivedAtMs: Long = System.currentTimeMillis(),
) {
    val artistLine: String
        get() = if (artists.isEmpty()) "" else artists.joinToString(", ")

    /** Позиция с учётом прошедшего с момента получения времени. */
    fun livePositionSec(nowMs: Long = System.currentTimeMillis()): Int {
        if (!playing) return positionSec
        val elapsed = ((nowMs - receivedAtMs) / 1000.0 * (speed ?: 1f)).toInt()
        val pos = positionSec + elapsed
        return if (durationSec != null && durationSec > 0) pos.coerceIn(0, durationSec) else pos
    }
}

/** Bluetooth-устройство колонки — спаренное (op=19). */
data class BtDevice(
    val mac: String,
    val name: String = "",
    val connected: Boolean = false,
)

/** Элемент очереди воспроизведения (op=17) — только id и explicit-флаг. */
data class QueueItem(
    val trackId: String,
    val explicit: Boolean = false,
)

/** Состояние колонки (volume/mute) из GET_STATE. */
data class SpeakerState(
    val volumePercent: Int? = null,
    val muted: Boolean? = null,
    val device: DeviceState? = null,
)

/** Небольшой срез подсистем GET_STATE — то, что показываем в панели «Колонка». */
data class DeviceState(
    val networkIp: String? = null,
    val connectionType: String? = null,
    val activeApp: String? = null,
    val foregroundApp: String? = null,
    val appStackCount: Int = 0,
    val ageMode: String? = null,
    val multiProfile: Boolean? = null,
    val childVoiceExplicit: Boolean? = null,
    val timezoneId: String? = null,
    val timezoneOffsetSec: Int? = null,
    val deviceUnixtime: Double? = null,
    val firmwareChannel: String? = null,
    val alarmsCount: Int? = null,
    val alarmsEnabled: Int = 0,
    val alarmTimes: List<String> = emptyList(),
    val timersCount: Int? = null,
    val timersLeftSec: List<Int> = emptyList(),
    val remindersCount: Int = 0,
    val nextReminder: String? = null,
    val alarmRinging: Boolean = false,
    val ledBrightness: Int? = null,
    val ledOn: Boolean? = null,
    val stereoPairActive: Boolean? = null,
    val stereoPairChannel: String? = null,
    val stereoPairDevice: String? = null,
    val multiroomMode: String? = null,
    val sbercastEnabled: Boolean? = null,
    val sbercastDevicesCount: Int = 0,
    val soundbarGroup: Boolean = false,
    val assistantCharacter: String? = null,
    val autoVolume: Boolean? = null,
    val proactivityNotification: Boolean? = null,
    val sleepState: String? = null,
    val morningShowIn: Boolean? = null,
    val morningShowFrom: Boolean? = null,
    val isSubscription: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Int? = null,
    val locationSource: String? = null,
)

/**
 * Парсеры payload'ов колонки. Колонка отдаёт JSON внутри бинарной TLV-обёртки;
 * ищем сбалансированный JSON-объект в тексте кадра (как в _parsers.py) — это
 * устойчиво к бинарному префиксу и частично битым кадрам.
 */
object Payloads {

    // ─────────────────────────── scanning ───────────────────────────

    /** Сбалансированный JSON-объект, начиная с `{` на позиции start (учёт строк). */
    fun extractObject(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val ch = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun parseState(raw: ByteArray): SpeakerState? {
        val s = String(raw, Charsets.UTF_8)
        val data = findStateJson(s)
        if (data != null) return fromStateJson(data)
        // Fallback: битый JSON — regex по volume.
        val m = VOLUME_RE.find(s) ?: return null
        return SpeakerState(
            volumePercent = m.groupValues[2].toIntOrNull(),
            muted = m.groupValues[1] == "true",
        )
    }

    /** Найти JSON-объект состояния в тексте кадра (с маркерными ключами). */
    fun findStateJson(s: String): JSONObject? {
        var idx = s.indexOf('{')
        var first = true
        repeat(8) {
            if (idx < 0) return null
            val candidate = extractObject(s, idx)
            if (candidate != null) {
                val parsed = runCatching { JSONObject(candidate) }.getOrNull()
                if (parsed != null && (first || parsed.has("volume") || parsed.has("background_apps"))) {
                    return parsed
                }
            }
            first = false
            idx = s.indexOf('{', idx + 1)
        }
        return null
    }

    /** Трек из кадра GET_STATE — для радио/Bluetooth, где get_metadata пуст. */
    fun trackFromRawState(raw: ByteArray): Track? =
        findStateJson(String(raw, Charsets.UTF_8))?.let { trackFromState(it) }

    /**
     * Список спаренных Bluetooth-устройств (op=19).
     *
     * Структура: envelope[5] → [op=19] → repeated-сообщения, каждое
     * `{1: mac, 2: name, 3: connected}`. Порт _parse_bt_devices из интеграции.
     */
    fun parsePairedBt(raw: ByteArray): List<BtDevice> {
        val env = Tlv.decodeRepeated(raw)
        val body = env[5]?.firstOrNull() as? ByteArray ?: return emptyList()
        val opField = Tlv.decodeRepeated(body)[Proto.OP_GET_PAIRED_BT] ?: return emptyList()
        val opPayload = opField.firstOrNull() as? ByteArray ?: return emptyList()
        val devRaws = Tlv.decodeRepeated(opPayload)[1] ?: return emptyList()
        return devRaws.mapNotNull { devRaw ->
            val bytes = devRaw as? ByteArray ?: return@mapNotNull null
            val fields = Tlv.decode(bytes)
            val mac = fields[1] as? String ?: return@mapNotNull null
            BtDevice(
                mac = mac,
                name = fields[2] as? String ?: "",
                connected = ((fields[3] as? Int) ?: 0) != 0,
            )
        }
    }


    /**
     * Очередь воспроизведения (op=17).
     *
     * Формат: envelope[5] → [17] → поле 5 = JSON-массив
     * `[{"explicit":bool,"trackId":int}, ...]`. Порт parse_queue интеграции.
     */
    fun parseQueue(raw: ByteArray): List<QueueItem> {
        val decoded = Tlv.decode(raw)
        val body = decoded[5] as? Map<*, *>
        val inner = body?.get(Proto.OP_GET_PLAYING_QUEUE) as? Map<*, *>
        val fromTlv = (inner?.get(5) as? String)?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
        // Короткая очередь: varint длины попадает в printable-диапазон и TLV-декодер
        // отдаёт payload строкой — тогда ищем JSON-массив в тексте кадра напрямую.
        val array = fromTlv ?: findTrackArray(String(raw, Charsets.UTF_8) + String(raw, Charsets.ISO_8859_1))
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = num(item, "trackId") ?: return@mapNotNull null
            QueueItem(trackId = id, explicit = item.optBoolean("explicit", false))
        }
    }

    /** Сбалансированный JSON-массив, начиная с `[` (учёт строк). */
    private fun balancedArray(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val ch = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Найти в тексте кадра массив объектов с `trackId`. */
    private fun findTrackArray(s: String): org.json.JSONArray? {
        var idx = s.indexOf('[')
        var tries = 0
        while (idx >= 0 && tries < 32) {
            val sub = balancedArray(s, idx)
            if (sub != null) {
                val arr = runCatching { org.json.JSONArray(sub) }.getOrNull()
                if (arr != null && arr.length() > 0 && arr.optJSONObject(0)?.has("trackId") == true) return arr
            }
            tries++
            idx = s.indexOf('[', idx + 1)
        }
        return null
    }

    private val VOLUME_RE = Regex("\"volume\":\\s*\\{\\s*\"muted\":\\s*(true|false)\\s*,\\s*\"percent\":\\s*(\\d+)")

    private fun fromStateJson(data: JSONObject): SpeakerState {
        var volume: Int? = null
        var muted: Boolean? = null
        data.optJSONObject("volume")?.let { v ->
            if (!v.isNull("percent")) {
                val p = v.opt("percent")
                if (p is Number) volume = p.toInt()
            }
            if (v.has("muted")) muted = v.optBoolean("muted", false)
        }

        var device: DeviceState? = null
        if (data.has("background_apps") || data.has("network") || data.has("user_settings") || data.has("alarm")) {
            val led = data.optJSONObject("capabilities_state")?.optJSONObject("led_display")
            val alarm = data.optJSONObject("alarm")
            val network = data.optJSONObject("network")
            val settings = data.optJSONObject("user_settings")
            val tm = data.optJSONObject("time")
            val timesync = data.optJSONObject("timesync")
            val multiroomObj = data.optJSONObject("multiroom")
            val multiroom = multiroomObj?.optJSONObject("stereoPair")
            val assistant = data.optJSONObject("assistant")
            val sbercast = data.optJSONObject("sbercast")
            val groups = data.optJSONObject("deviceGroups")
            val sleep = data.optJSONObject("deviceSleep")
            val morning = data.optJSONObject("morning_show")
            val subscr = data.optJSONObject("subscrDeviceInfo")
            val proactivity = data.optJSONObject("proactivityNotification")
            val location = data.optJSONObject("location")
            val currentApp = data.optJSONObject("current_app")
            val offsetSec = tm?.let { if (it.has("timezone_offset_sec")) it.optInt("timezone_offset_sec") else null }

            var activeApp: String? = null
            var stackCount = 0
            data.optJSONArray("background_apps")?.let { apps ->
                stackCount = apps.length()
                for (i in 0 until apps.length()) {
                    val app = apps.optJSONObject(i) ?: continue
                    val player = app.optJSONObject("state")?.optJSONObject("player")
                    if (activeApp == null && player?.optBoolean("playing") == true) {
                        activeApp = app.optJSONObject("app_info")?.let { str(it, "systemName") }
                    }
                }
            }

            val segments = data.optJSONArray("device_segments")

            // Будильники: время + дни недели из iCalendar-описания.
            val alarmsArr = alarm?.optJSONArray("alarms")
            val alarmTimes = ArrayList<String>()
            var alarmsEnabled = 0
            if (alarmsArr != null) {
                for (i in 0 until alarmsArr.length()) {
                    val a = alarmsArr.optJSONObject(i) ?: continue
                    if (!a.optBoolean("enabled", true)) continue
                    alarmsEnabled++
                    val ics = str(a, "ics") ?: continue
                    val time = icsTimeOfDay(ics, offsetSec)
                    val days = icsDays(ics)
                    alarmTimes.add(listOfNotNull(time, days).joinToString(" · "))
                }
            }

            val timersLeft = alarm?.optJSONArray("timers")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val t = arr.optJSONObject(i) ?: return@mapNotNull null
                    if (t.has("timeLeftSec")) t.optInt("timeLeftSec") else null
                }
            }.orEmpty()

            // Напоминания: reminders.reminders.time_reminders — dict списков.
            var remindersCount = 0
            var nextReminder: String? = null
            data.optJSONObject("reminders")?.optJSONObject("reminders")
                ?.optJSONObject("time_reminders")?.let { tr ->
                    val keys = tr.keys()
                    while (keys.hasNext()) {
                        val list = tr.optJSONArray(keys.next()) ?: continue
                        remindersCount += list.length()
                        for (i in 0 until list.length()) {
                            val r = list.optJSONObject(i) ?: continue
                            val whenIso = str(r, "reminderTime") ?: continue
                            if (nextReminder == null || whenIso < nextReminder!!) nextReminder = whenIso
                        }
                    }
                }

            device = DeviceState(
                networkIp = network?.let { str(it, "ip") },
                connectionType = network?.let { str(it, "connection_type") },
                activeApp = activeApp,
                foregroundApp = currentApp?.optJSONObject("app_info")?.let { str(it, "systemName") },
                appStackCount = stackCount,
                ageMode = settings?.let { str(it, "age_mode") },
                multiProfile = settings?.let { if (it.has("multi_profile")) it.optBoolean("multi_profile") else null },
                childVoiceExplicit = settings?.let {
                    if (it.has("enable_child_voice_explicit")) it.optBoolean("enable_child_voice_explicit") else null
                },
                timezoneId = tm?.let { str(it, "timezone_id") },
                timezoneOffsetSec = offsetSec,
                deviceUnixtime = timesync?.let { if (it.has("unixtime")) it.optDouble("unixtime") else null },
                firmwareChannel = if (segments != null && segments.length() > 0) {
                    (0 until segments.length()).joinToString(", ") { segments.opt(it).toString() }
                } else null,
                alarmsCount = alarmsArr?.length()
                    ?: alarm?.let { if (it.has("alarmsCounter")) it.optInt("alarmsCounter") else null },
                alarmsEnabled = alarmsEnabled,
                alarmTimes = alarmTimes,
                timersCount = alarm?.optJSONArray("timers")?.length(),
                timersLeftSec = timersLeft,
                remindersCount = remindersCount,
                nextReminder = nextReminder,
                alarmRinging = alarm?.has("playing") == true && !alarm.isNull("playing"),
                ledBrightness = led?.let { if (it.has("brightness")) it.optInt("brightness") else null },
                ledOn = led?.let { if (it.has("turned_on")) it.optBoolean("turned_on") else null },
                stereoPairActive = multiroom?.let { if (it.has("active")) it.optBoolean("active") else null },
                stereoPairChannel = multiroom?.let { str(it, "channelFromConfig") },
                stereoPairDevice = multiroom?.let { str(it, "pairDeviceFromConfig") },
                multiroomMode = multiroomObj?.let { str(it, "mode") },
                sbercastEnabled = sbercast?.let { if (it.has("enabled")) it.optBoolean("enabled") else null },
                sbercastDevicesCount = sbercast?.optJSONArray("devices")?.length() ?: 0,
                soundbarGroup = groups?.has("soundBar") == true && !groups.isNull("soundBar"),
                assistantCharacter = assistant?.let { str(it, "character") },
                autoVolume = assistant?.let { if (it.has("auto_volume")) it.optBoolean("auto_volume") else null },
                proactivityNotification = proactivity?.let {
                    if (it.has("hasNotification")) it.optBoolean("hasNotification") else null
                },
                sleepState = sleep?.let { str(it, "systemState") },
                morningShowIn = morning?.let { if (it.has("in_show")) it.optBoolean("in_show") else null },
                morningShowFrom = morning?.let { if (it.has("from_show")) it.optBoolean("from_show") else null },
                isSubscription = subscr?.let {
                    if (it.has("isSubscrDevice")) it.optBoolean("isSubscrDevice") else null
                },
                latitude = location?.let { if (it.has("lat")) it.optDouble("lat") else null },
                longitude = location?.let { if (it.has("lon")) it.optDouble("lon") else null },
                locationAccuracy = location?.let { if (it.has("accuracy")) it.optDouble("accuracy").toInt() else null },
                locationSource = location?.let { str(it, "source") },
            )
        }
        return SpeakerState(volume, muted, device)
    }

    // ─────────────────────── iCalendar (будильники) ───────────────────────

    private val ICS_DTSTART = Regex("DTSTART:(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})Z")
    private val ICS_BYDAY = Regex("BYDAY=([A-Z,]+)")

    /** Время срабатывания из iCalendar-описания будильника, в поясе колонки. */
    private fun icsTimeOfDay(ics: String, offsetSec: Int?): String? {
        val m = ICS_DTSTART.find(ics) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val utc = runCatching {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        }.getOrNull() ?: return null
        val local = offsetSec?.let { utc.plusSeconds(it.toLong()) } ?: utc
        return "%02d:%02d".format(local.hour, local.minute)
    }

    /** Дни недели из RRULE BYDAY — «ежедневно» / «по будням» / «Пн,Ср». */
    private fun icsDays(ics: String): String? {
        val m = ICS_BYDAY.find(ics) ?: return null
        val days = m.groupValues[1].split(",").toSet()
        val short = mapOf(
            "MO" to "Пн", "TU" to "Вт", "WE" to "Ср", "TH" to "Чт",
            "FR" to "Пт", "SA" to "Сб", "SU" to "Вс",
        )
        return when {
            days.size == 7 -> "ежедневно"
            days == setOf("MO", "TU", "WE", "TH", "FR") -> "по будням"
            days == setOf("SA", "SU") -> "по выходным"
            else -> listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
                .filter { it in days }
                .mapNotNull { short[it] }
                .joinToString(",")
        }
    }

    // ─────────────────────────── device info ───────────────────────────

    /**
     * Реквизиты самой колонки из GET_METADATA (op=10), вложенное поле 2.
     *
     * Реальный кадр SberBoom Mini (прошивка 1.104.35):
     *   envelope[5][10][2] = {1: {1: fw, 2: model, 3: name, 4: vendor, 5: serial}}
     * В HA-интеграции это поле не разбирается — модель/прошивку там берут из mDNS.
     */
    fun parseDeviceInfo(raw: ByteArray): DeviceInfo? {
        val envelope = Tlv.decode(raw)
        val body = envelope[5] as? Map<*, *> ?: return null
        val inner = body[Proto.OP_GET_META_DATA] as? Map<*, *> ?: return null
        val holder = inner[2] as? Map<*, *> ?: return null
        val info = holder[1] as? Map<*, *> ?: return null
        fun str(key: Int) = (info[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val firmware = str(1) ?: return null
        return DeviceInfo(
            firmware = firmware,
            model = str(2),
            name = str(3),
            vendor = str(4),
            serial = str(5),
        )
    }

    // ─────────────────────────── track ───────────────────────────

    fun parseTrack(raw: ByteArray): Track? {
        val s = String(raw, Charsets.UTF_8)
        val found = findTrackJson(s) ?: return null
        val data = found.first
        val start = found.second
        val outer = if (!data.has("playing")) findOuterPlayer(s, start) else JSONObject()
        return trackFrom(data, outer)
    }

    private val TRACK_ID_RE = Regex("\"trackId\"\\s*:\\s*\"?\\d+\"?")

    private fun findTrackJson(s: String): Pair<JSONObject, Int>? {
        val m = TRACK_ID_RE.find(s) ?: return null
        val positions = ArrayList<Int>()
        for (i in 0 until m.range.first) if (s[i] == '{') positions.add(i)
        val candidates = positions.takeLast(50).reversed()
        for (start in candidates) {
            val obj = extractObject(s, start) ?: continue
            if (start + obj.length <= m.range.last + 1) continue
            val parsed = runCatching { JSONObject(obj) }.getOrNull() ?: continue
            if (parsed.has("trackId")) return parsed to start
        }
        return null
    }

    private val PLAYER_RE = Regex("\"player\"\\s*:\\s*\\{")

    private fun findOuterPlayer(s: String, start: Int): JSONObject {
        if (start <= 0) return JSONObject()
        val head = s.substring(0, start)
        val m = PLAYER_RE.findAll(head).lastOrNull() ?: return JSONObject()
        val open = m.range.last
        val obj = extractObject(s, open) ?: return JSONObject()
        return runCatching { JSONObject(obj) }.getOrElse { JSONObject() }
    }

    /** Трек из состояния (радио/Bluetooth: у них нет get_metadata/trackId). */
    fun trackFromState(data: JSONObject): Track? {
        val apps = data.optJSONArray("background_apps") ?: return null

        data class Candidate(
            val appName: String,
            val player: JSONObject,
            val info: JSONObject,
            val playing: Boolean,
            val updatedAt: Long,
        )

        val candidates = ArrayList<Candidate>()
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            val name = app.optJSONObject("app_info")?.let { str(it, "systemName") } ?: continue
            if (name != "bluetooth_media_control" && name != "music") continue
            val player = app.optJSONObject("state")?.optJSONObject("player") ?: continue
            val info = player.optJSONObject("info") ?: continue
            if (str(info, "title") == null && str(info, "trackId") == null && str(info, "stationName") == null) continue
            candidates.add(
                Candidate(
                    appName = name,
                    player = player,
                    info = info,
                    playing = player.optBoolean("playing"),
                    updatedAt = player.optLong("stateChangedTimestamp", 0L),
                ),
            )
        }

        // Выбор источника: сначала явно играющее приложение, иначе — то, чьё состояние
        // обновлялось последним. Без второго правила после перехода на Bluetooth
        // (оба приложения «на паузе») плеер показывал старый трек Звука.
        val chosen = candidates.firstOrNull { it.playing }
            ?: candidates.maxByOrNull { it.updatedAt }
            ?: return null

        val player = chosen.player
        val info = chosen.info
        val appName = chosen.appName

        val station = str(info, "stationName")
        val provider = str(info, "provider")
        val source = when {
            appName == "bluetooth_media_control" || provider == "bluetooth" -> Source.BLUETOOTH
            player.optString("mode") == "radio" || station != null -> Source.RADIO
            else -> sourceOf(str(info, "mediaSource"), station, provider, player.optString("mode"))
        }

        val albums = names(info.opt("releases"))
        val artists = names(info.opt("artists"))
        val duration = if (!player.isNull("duration")) player.optInt("duration") else 0

        return Track(
            title = str(info, "title"),
            artists = artists,
            album = albums.firstOrNull(),
            trackId = num(info, "trackId"),
            releaseId = firstReleaseId(info),
            playlistTitle = str(info, "playlistTitle"),
            playlistType = str(info, "playlistType"),
            source = source,
            stationName = station,
            provider = provider,
            durationSec = duration.takeIf { it > 0 },
            positionSec = if (player.has("position")) player.optInt("position") else 0,
            playing = player.optBoolean("playing"),
            shuffle = player.optBoolean("shuffle"),
            repeat = str(player, "repeatType"),
        )
    }

    private fun trackFrom(data: JSONObject, outer: JSONObject): Track {
        val artistsArr = data.optJSONArray("artists")
        val artists = ArrayList<String>()
        val artistIds = ArrayList<String>()
        if (artistsArr != null) {
            for (i in 0 until artistsArr.length()) {
                val a = artistsArr.optJSONObject(i) ?: continue
                str(a, "name")?.let { artists.add(it) }
                num(a, "id")?.let { artistIds.add(it) }
            }
        }
        val releasesArr = data.optJSONArray("releases")
        var album: String? = null
        var releaseId: String? = null
        if (releasesArr != null && releasesArr.length() > 0) {
            val r0 = releasesArr.optJSONObject(0)
            if (r0 != null) {
                album = str(r0, "name") ?: str(r0, "title")
                releaseId = num(r0, "id")
            }
        }

        val statusSrc = if (data.has("playing")) data else outer
        val playing = statusSrc.optBoolean("playing", false)
        val shuffle = statusSrc.optBoolean("shuffle", false)
        val repeat = str(statusSrc, "repeatType")

        // position: push → {tsMs, val}; state → int в player{}.
        var positionSec = 0
        val posObj = data.optJSONObject("position")
        when {
            posObj != null -> positionSec = posObj.optInt("val", 0)
            data.has("position") -> positionSec = data.optInt("position", 0)
            outer.has("position") -> positionSec = outer.optInt("position", 0)
        }

        val duration = when {
            !data.isNull("duration") -> data.optInt("duration", 0)
            !outer.isNull("duration") -> outer.optInt("duration", 0)
            else -> 0
        }

        val speed = runCatching {
            when {
                !data.isNull("playbackSpeedRate") -> data.optDouble("playbackSpeedRate").toFloat()
                !outer.isNull("playbackSpeedRate") -> outer.optDouble("playbackSpeedRate").toFloat()
                else -> null
            }
        }.getOrNull()

        val source = sourceOf(
            str(data, "mediaSource") ?: str(outer, "mediaSource"),
            str(data, "stationName"),
            str(data, "provider") ?: str(outer, "provider"),
            outer.optString("mode"),
        )

        return Track(
            title = str(data, "title"),
            artists = artists,
            artistIds = artistIds,
            album = album,
            trackId = num(data, "trackId"),
            releaseId = releaseId,
            playlistTitle = str(data, "playlistTitle") ?: str(outer, "playlistTitle"),
            playlistType = str(data, "playlistType") ?: str(outer, "playlistType"),
            source = source,
            stationName = str(data, "stationName"),
            provider = str(data, "provider") ?: str(outer, "provider"),
            durationSec = duration.takeIf { it > 0 },
            positionSec = positionSec,
            playing = playing,
            shuffle = shuffle,
            repeat = repeat,
            explicit = data.optBoolean("explicit", false),
            liked = data.optBoolean("like", false),
            childMode = (if (data.has("childMode")) data.optBoolean("childMode") else outer.optBoolean("childMode", false)),
            buffering = (if (data.has("playingPending")) data.optBoolean("playingPending") else outer.optBoolean("playingPending", false)),
            speed = speed,
            hasLyrics = if (data.has("hasLyrics")) data.optBoolean("hasLyrics") else null,
        )
    }

    fun sourceOf(mediaSource: String?, stationName: String?, provider: String?, mode: String?): Source {
        val ms = mediaSource?.uppercase()
        return when {
            provider == "bluetooth" || ms == "BLUETOOTH" -> Source.BLUETOOTH
            mode == "radio" || stationName != null || ms == "RADIO" -> Source.RADIO
            ms == "PODCAST" || ms == "ABOOK" -> Source.PODCAST
            ms == "MUSIC" -> Source.MUSIC
            else -> Source.UNKNOWN
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun names(value: Any?): List<String> = when (value) {
        is String -> if (value.isEmpty()) emptyList() else listOf(value)
        is org.json.JSONArray -> (0 until value.length()).mapNotNull { i ->
            value.optJSONObject(i)?.let { str(it, "name") ?: str(it, "title") }
        }
        else -> emptyList()
    }

    private fun firstReleaseId(o: JSONObject): String? {
        val arr = o.optJSONArray("releases") ?: return null
        if (arr.length() == 0) return null
        return num(arr.optJSONObject(0) ?: return null, "id")
    }

    fun str(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

    fun num(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            is String -> v.takeIf { it.isNotEmpty() }
            else -> null
        }
    }
}
