package ru.sberboom.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.sberboom.remote.platform.KnownSpeaker
import ru.sberboom.remote.platform.Settings
import ru.sberboom.remote.protocol.Proto
import java.util.UUID
import java.util.prefs.Preferences

/**
 * Настройки Windows-версии: java.util.prefs (реестр HKCU\Software\JavaSoft\Prefs).
 * Формат ключей совпадает с Android-версией — токены по хостам, список колонок.
 */
class Prefs : Settings {

    private val prefs: Preferences = Preferences.userRoot().node("ru/sberboom/remote")

    private fun setStr(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
    }

    override var host: String?
        get() = prefs.get("host", null)
        set(value) = setStr("host", value)

    override var port: Int
        get() = prefs.getInt("port", Proto.DEFAULT_PORT)
        set(value) = prefs.putInt("port", value)

    override var deviceName: String?
        get() = prefs.get("device_name", null)
        set(value) = setStr("device_name", value)

    override var deviceModel: String?
        get() = prefs.get("device_model", null)
        set(value) = setStr("device_model", value)

    override var deviceFirmware: String?
        get() = prefs.get("device_firmware", null)
        set(value) = setStr("device_firmware", value)

    override var clientName: String
        get() = prefs.get("client_name", Settings.DEFAULT_CLIENT_NAME) ?: Settings.DEFAULT_CLIENT_NAME
        set(value) = prefs.put("client_name", value)

    override var lightTheme: Boolean
        get() = prefs.getBoolean("light_theme", false)
        set(value) = prefs.putBoolean("light_theme", value)

    override var historyJson: String?
        get() = prefs.get("history", null)
        set(value) = setStr("history", value)

    override var karaokeFill: Boolean
        get() = prefs.getBoolean("karaoke_fill", true)
        set(value) = prefs.putBoolean("karaoke_fill", value)

    override val clientId: String
        get() {
            prefs.get("client_id", null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.put("client_id", id)
            return id
        }

    // ───────────────────────────── токены ─────────────────────────────

    private fun readTokens(): JSONObject =
        prefs.get("tokens", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    override fun tokenFor(host: String): String? =
        readTokens().optString(host).takeIf { it.isNotEmpty() }

    override fun setToken(host: String, token: String?) {
        val map = readTokens()
        if (token.isNullOrEmpty()) map.remove(host) else map.put(host, token)
        setStr("tokens", map.toString())
    }

    // ───────────────────────────── колонки ─────────────────────────────

    override val knownSpeakers: List<KnownSpeaker>
        get() {
            val raw = prefs.get("speakers", null) ?: return emptyList()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                KnownSpeaker(
                    host = o.optString("host"),
                    port = o.optInt("port", Proto.DEFAULT_PORT),
                    name = o.optString("name").takeIf { it.isNotEmpty() },
                    model = o.optString("model").takeIf { it.isNotEmpty() },
                    firmware = o.optString("firmware").takeIf { it.isNotEmpty() },
                )
            }
        }

    override fun rememberSpeaker(speaker: KnownSpeaker) {
        writeSpeakers(knownSpeakers.filterNot { it.host == speaker.host }.let { listOf(speaker) + it }.take(8))
    }

    override fun forget() {
        val host = host
        if (host != null) {
            setToken(host, null)
            writeSpeakers(knownSpeakers.filterNot { it.host == host })
        }
        prefs.remove("device_name")
        prefs.remove("device_model")
        prefs.remove("device_firmware")
    }

    private fun writeSpeakers(list: List<KnownSpeaker>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("name", s.name ?: "")
                    .put("model", s.model ?: "")
                    .put("firmware", s.firmware ?: ""),
            )
        }
        setStr("speakers", arr.toString())
    }
}
