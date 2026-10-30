package ru.sberboom.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sberboom.remote.protocol.Envelope
import ru.sberboom.remote.protocol.Payloads
import ru.sberboom.remote.protocol.Requests
import ru.sberboom.remote.protocol.Source
import ru.sberboom.remote.protocol.Tlv
import java.io.File

/**
 * Проверка порта протокола:
 *  1) байт-в-байт совпадение кадров с Python-реализацией HA-интеграции
 *     (эталоны сгенерированы tools/gen_fixtures.py);
 *  2) разбор реального захвата GET_METADATA (tests/fixtures/track_metadata.json);
 *  3) разбор state-кадра с радио и Bluetooth.
 */
class ProtocolTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun emptyRequestsMatchPython() {
        assertEquals(ProtocolFixtures.GET_STATE, hex(Requests.GET_STATE))
        assertEquals(ProtocolFixtures.GET_METADATA, hex(Requests.GET_METADATA))
        assertEquals(ProtocolFixtures.KEEP_ALIVE, hex(Requests.KEEP_ALIVE))
        assertEquals(ProtocolFixtures.PAIR_INIT, hex(Requests.PAIR_INIT))
    }

    @Test
    fun commandsMatchPython() {
        assertEquals(ProtocolFixtures.SET_VOLUME_75, hex(Requests.volume(75)))
        assertEquals(ProtocolFixtures.MEDIA_PLAY, hex(Requests.media(4)))
        assertEquals(ProtocolFixtures.MEDIA_PAUSE, hex(Requests.media(5)))
        assertEquals(ProtocolFixtures.SEEK_120, hex(Requests.seek(120)))
        assertEquals(ProtocolFixtures.SPEED_1_5, hex(Requests.speed(1.5f)))
    }

    @Test
    fun clampsMatchPythonSemantics() {
        // Прошивка ограничивает громкость, отрицательный seek кодируется как 0.
        assertEquals(hex(Requests.volume(100)), hex(Requests.volume(150)))
        assertEquals(hex(Requests.seek(0)), hex(Requests.seek(-5)))
        // Скорость за clamp — 2.0.
        assertEquals(hex(Requests.speed(2f)), hex(Requests.speed(5f)))
    }

    @Test
    fun envelopeMatchesPython() {
        val withToken = Envelope.request(
            ProtocolFixtures.MSG_ID,
            Requests.GET_STATE,
            ProtocolFixtures.TOKEN,
            ProtocolFixtures.CLIENT_NAME,
            ProtocolFixtures.CLIENT_ID,
        )
        assertEquals(ProtocolFixtures.ENVELOPE_GET_STATE, hex(withToken))

        val withoutToken = Envelope.request(
            ProtocolFixtures.MSG_ID,
            Requests.GET_STATE,
            null,
            ProtocolFixtures.CLIENT_NAME,
            ProtocolFixtures.CLIENT_ID,
        )
        assertEquals(ProtocolFixtures.ENVELOPE_GET_STATE_NO_TOKEN, hex(withoutToken))
    }

    @Test
    fun serverActionEnvelopeStructure() {
        val body = Envelope.serverActionJson("DEEPLINK", "{\"deeplink\":\"staros://music?tid=84279897&pt=track\"}")
        val env = Envelope.serverAction(ProtocolFixtures.MSG_ID, body, ProtocolFixtures.TOKEN, ProtocolFixtures.CLIENT_NAME, ProtocolFixtures.CLIENT_ID)
        val decoded = Tlv.decode(env)
        assertEquals(1, decoded[1])
        assertEquals(ProtocolFixtures.MSG_ID, decoded[2])
        assertEquals(ProtocolFixtures.TOKEN, decoded[3])
        val action = decoded[4] as Map<*, *>
        val json = JSONObject(action[1] as String)
        assertEquals("DEEPLINK", json.optString("name"))
        assertEquals("staros://music?tid=84279897&pt=track", json.optJSONObject("payload")!!.optString("deeplink"))
        assertEquals(6, (json.optJSONObject("smartAppInfo")!!.length()))
    }

    @Test
    fun decodesOwnEnvelope() {
        val decoded = Tlv.decode(
            Envelope.request(ProtocolFixtures.MSG_ID, Requests.volume(75), ProtocolFixtures.TOKEN, "n", "c"),
        )
        assertEquals(2, decoded[1])
        assertEquals(ProtocolFixtures.MSG_ID, decoded[2])
        val requestData = decoded[5] as Map<*, *>
        val volumeOp = requestData[14] as Map<*, *>
        assertEquals(75, volumeOp[1])
    }

    @Test
    fun parsesRealTrackCapture() {
        val json = File("src/test/resources/track_metadata.json").readText()
        val track = Payloads.parseTrack(json.toByteArray(Charsets.UTF_8))
        assertNotNull(track)
        track!!
        assertEquals("Thunderstruck", track.title)
        assertEquals(listOf("Back In Black"), track.artists)
        assertEquals("Back in Black", track.album)
        assertEquals("12058909", track.releaseId)
        assertEquals("74563601", track.trackId)
        assertEquals(294, track.durationSec)
        assertEquals("Персональная волна", track.playlistTitle)
        assertEquals("zvuk", track.provider)
        assertEquals(Source.MUSIC, track.source)
        assertTrue(track.playing)
        assertEquals(false, track.shuffle)
        assertEquals("none", track.repeat)
        assertEquals(1.0f, track.speed)
        assertEquals(false, track.liked)
    }

    @Test
    fun parsesRadioFromState() {
        val state = """
            {"volume":{"muted":false,"percent":42},
             "background_apps":[
               {"app_info":{"systemName":"music"},
                "state":{"player":{"playing":true,"mode":"radio","position":12,"duration":0,
                  "info":{"title":"","stationName":"Персональная волна","provider":"zvuk",
                          "playlistTitle":"Персональная волна","playlistType":"endless",
                          "artists":[{"name":"Кино"}]}}}}
             ]}
        """.trimIndent()

        val parsed = Payloads.parseState(state.toByteArray())
        assertNotNull(parsed)
        assertEquals(42, parsed!!.volumePercent)
        assertEquals(false, parsed.muted)

        val track = Payloads.trackFromRawState(state.toByteArray())
        assertNotNull(track)
        assertEquals(Source.RADIO, track!!.source)
        assertEquals("Персональная волна", track.stationName)
        assertEquals(12, track.positionSec)
        assertNull(track.durationSec)
        assertEquals("zvuk", track.provider)
    }

    @Test
    fun parsesRealFrameFromSpeaker() {
        // Настоящий кадр op=12 от колонки (hex в ресурсах): Звук на паузе с треком
        // «ночь», Bluetooth-источник — «Это был дождь», playing=false у обоих.
        // Ожидание: выбирается источник, чьё состояние обновилось последним.
        val hex = javaClass.classLoader!!.getResourceAsStream("state-bt.hex")!!
            .readBytes().toString(Charsets.UTF_8).trim()
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val track = Payloads.trackFromRawState(raw)
        assertNotNull(track)
        assertEquals(Source.BLUETOOTH, track!!.source)
        assertEquals("Это был дождь", track.title)
        assertEquals("Noize MC", track.artistLine)
    }

    @Test
    fun parsesRealBluetoothState() {
        // Живой ответ op=12 при воспроизведении с телефона по Bluetooth:
        // первым идёт приложение Звука (не играет), у Bluetooth-источника нет trackId,
        // альбом лежит в releases[].name.
        val state = """
            {"background_apps":[
              {"app_info":{"systemName":"music"},
               "state":{"player":{"playing":false,"position":80,"duration":137,
                 "info":{"title":"ночь","artists":[{"id":210005025,"name":"ooes"}],
                         "provider":"zvuk","trackId":"175449063"}}}},
              {"app_info":{"systemName":"bluetooth_media_control"},
               "state":{"player":{"playing":true,"position":49,"duration":173,
                 "info":{"title":"Game Over","artists":[{"name":"Noize MC, Staisha"}],
                         "provider":"bluetooth","releases":[{"name":"Неразбериха"}]}}}}
            ]}
        """.trimIndent()

        val track = Payloads.trackFromRawState(state.toByteArray())
        assertNotNull(track)
        assertEquals(Source.BLUETOOTH, track!!.source)
        assertEquals("Game Over", track.title)
        assertEquals("Noize MC, Staisha", track.artistLine)
        assertEquals("Неразбериха", track.album)
        assertEquals(173, track.durationSec)
        assertEquals(49, track.positionSec)
    }

    @Test
    fun parsesBluetoothTrack() {
        val state = """
            {"volume":{"muted":true,"percent":30},
             "background_apps":[
               {"app_info":{"systemName":"bluetooth_media_control"},
                "state":{"player":{"playing":true,"position":5,
                  "info":{"title":"Song","artists":"Some Artist","releases":"Album"}}}}
             ]}
        """.trimIndent()

        val track = Payloads.trackFromRawState(state.toByteArray())
        assertNotNull(track)
        assertEquals(Source.BLUETOOTH, track!!.source)
        assertEquals("Song", track.title)
        assertEquals("Some Artist", track.artistLine)
        assertEquals("Album", track.album)

        val speaker = Payloads.parseState(state.toByteArray())
        assertEquals(true, speaker?.muted)
        assertEquals(30, speaker?.volumePercent)
    }

    @Test
    fun stateFallbackOnBrokenJson() {
        // Обрезанный JSON → regex-fallback по volume (как в Python).
        val broken = "{\"volume\":{\"muted\":true,\"percent\":17}, \"alarm\": {".toByteArray()
        val parsed = Payloads.parseState(broken) ?: Payloads.parseState("x{\"volume\":{\"muted\":true,\"percent\":17}".toByteArray())
        assertNotNull(parsed)
        assertEquals(17, parsed!!.volumePercent)
        assertEquals(true, parsed.muted)
    }

    @Test
    fun decodesRepeatedFields() {
        val payload = Tlv.string(1, "aa:bb") + Tlv.string(1, "cc:dd") + Tlv.int(2, 7)
        val repeated = Tlv.decodeRepeated(payload)
        assertEquals(2, repeated[1]?.size)
        assertEquals("aa:bb", String(repeated[1]!![0] as ByteArray))
        assertEquals(7, repeated[2]?.first())
    }

    @Test
    fun deeplinkBuilding() {
        assertEquals("staros://music?tid=84279897&pt=track", ru.sberboom.remote.net.ZvukApi.deeplink("track", "84279897"))
        assertEquals("staros://music?pid=1234&pt=artist", ru.sberboom.remote.net.ZvukApi.deeplink("artist", "1234"))
        assertNull(ru.sberboom.remote.net.ZvukApi.deeplink("track", "12;drop table"))
        assertNull(ru.sberboom.remote.net.ZvukApi.deeplink("bogus", "1234"))

        val parsed = ru.sberboom.remote.net.ZvukApi.parseZvukUrl("https://zvuk.com/track/84279897?foo=1")
        assertEquals("track" to "84279897", parsed)
        assertEquals("podcast" to "555", ru.sberboom.remote.net.ZvukApi.parseZvukUrl("zvuk.com/abook/555"))
        assertNull(ru.sberboom.remote.net.ZvukApi.parseZvukUrl("https://example.com/track/1"))
    }

    @Test
    fun parsesPairedBtDevices() {
        // Кадр собран Python-кодом интеграции и проверен ЕЁ парсером
        // (tools/gen_fixtures.py -> parse_paired_bt), здесь — наш порт.
        val devices = Payloads.parsePairedBt(ProtocolFixtures.PAIRED_BT.hexToBytes())
        assertEquals(2, devices.size)
        assertEquals("AA:BB:CC:DD:EE:01", devices[0].mac)
        assertEquals("iPhone Matvey", devices[0].name)
        assertEquals(true, devices[0].connected)
        assertEquals("AA:BB:CC:DD:EE:02", devices[1].mac)
        assertEquals("MacBook Pro", devices[1].name)
        assertEquals(false, devices[1].connected)
    }

    @Test
    fun btCommandFrame() {
        // python: field(20, 2, field(1, 2, mac) + field(2, 0, 1))
        assertEquals(
            "a201150a1141413a42423a43433a44443a45453a30311001",
            hex(ru.sberboom.remote.protocol.Requests.btCommand("AA:BB:CC:DD:EE:01", 1)),
        )
    }

    @Test
    fun parsesQueueFrame() {
        // Кадр собран Python-кодом интеграции и проверен её parse_queue()
        // (tools/gen_fixtures.py), здесь — наш порт.
        val queue = Payloads.parseQueue(ProtocolFixtures.QUEUE.hexToBytes())
        assertEquals(6, queue.size)
        assertEquals("74563601", queue[0].trackId)
        assertEquals("74563606", queue[5].trackId)
        assertEquals(false, queue[0].explicit)
    }

    @Test
    fun echoRequestMatchesLiveDeviceBehaviour() {
        // Живая колонка на {8: {1: {1: n}}} отвечает {8: {2: {1: n}}} — то есть эхо.
        // Проверяем форму запроса (op=8, вложенное поле 1 → поле 1 = nonce).
        assertEquals("42040a020801", hex(Requests.echo(1)))
        assertEquals("42040a020802", hex(Requests.echo(2)))
    }

    @Test
    fun parsesDeviceInfoFromMetadata() {
        // Структура снята с живой SberBoom Mini (прошивка 1.104.35):
        // envelope[5][10][2] = {1: {1: fw, 2: model, 3: name, 4: vendor, 5: serial}}
        val info = Tlv.string(1, "1.104.35") +
            Tlv.string(2, "sberboom-mini") +
            Tlv.string(3, "SberBoom Mini") +
            Tlv.string(4, "SberDevices") +
            Tlv.string(5, "SMD00V00AA000000")
        val meta = Tlv.int(1, 1) +
            Tlv.bytes(2, Tlv.bytes(1, info) + Tlv.string(4, "{\"spaceshipLaunchUUID\":\"c46bd3a9\"}")) +
            Tlv.string(3, "1793291835505")
        val frame = Tlv.int(1, 2) + Tlv.string(2, "req-1") + Tlv.bytes(5, Tlv.bytes(10, meta))

        val parsed = Payloads.parseDeviceInfo(frame)
        assertNotNull(parsed)
        assertEquals("1.104.35", parsed!!.firmware)
        assertEquals("sberboom-mini", parsed.model)
        assertEquals("SberBoom Mini", parsed.name)
        assertEquals("SberDevices", parsed.vendor)
        assertEquals("SMD00V00AA000000", parsed.serial)
        // Пустой/чужой кадр — null, а не исключение.
        assertNull(Payloads.parseDeviceInfo(ByteArray(0)))
        assertNull(Payloads.parseDeviceInfo(ProtocolFixtures.GET_STATE.hexToBytes()))
    }

    @Test
    fun parsesRealDeviceInfoCapture() {
        // Реальный кадр op=10 с SberBoom Mini (серийник обезличен, длина сохранена).
        val bytes = String(checkNotNull(javaClass.classLoader!!.getResourceAsStream("metadata_10.hex")).readBytes()).trim().hexToBytes()
        val info = Payloads.parseDeviceInfo(bytes)
        assertNotNull(info)
        assertEquals("1.104.35", info!!.firmware)
        assertEquals("sberboom-mini", info.model)
        assertEquals("SberBoom Mini", info.name)
        assertEquals("SberDevices", info.vendor)
        assertEquals("SMD00V00AA000000", info.serial)
    }

    @Test
    fun parsesRealStateCapture() {
        // Реальный кадр op=12 (3570 байт) — проверяем, что порт читает живой state.
        val bytes = String(checkNotNull(javaClass.classLoader!!.getResourceAsStream("state_12.hex")).readBytes()).trim().hexToBytes()
        val state = Payloads.parseState(bytes)
        assertNotNull(state)
        assertNotNull(state!!.device)
        assertNotNull(state.device!!.networkIp)
        assertNotNull(state.device!!.connectionType)
        val volume = state.volumePercent
        assertNotNull(volume)
        assertTrue(volume!! in 0..100)
        // В этом кадре нет информации об устройстве — парсер не должен падать.
        assertNull(Payloads.parseDeviceInfo(bytes))
    }

    @Test
    fun livePositionExtrapolates() {
        val track = ru.sberboom.remote.protocol.Track(
            positionSec = 10,
            durationSec = 100,
            playing = true,
            speed = 2f,
            receivedAtMs = 1_000,
        )
        assertEquals(14, track.livePositionSec(nowMs = 3_000))
        assertEquals(10, track.copy(playing = false).livePositionSec(nowMs = 3_000))
        // Не выходим за длительность.
        assertEquals(100, track.livePositionSec(nowMs = 999_000))
    }
}
