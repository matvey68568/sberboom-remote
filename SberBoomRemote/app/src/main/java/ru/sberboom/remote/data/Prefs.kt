package ru.sberboom.remote.data

import android.content.Context
import ru.sberboom.remote.platform.KnownSpeaker
import ru.sberboom.remote.platform.Settings
import ru.sberboom.remote.protocol.Proto
import java.util.UUID

/** Сохранённое состояние подключения (SharedPreferences). */
class Prefs(context: Context) : Settings {
    private val sp = context.getSharedPreferences("sboom", Context.MODE_PRIVATE)

    override var host: String?
        get() = sp.getString("host", null)
        set(value) = sp.edit().putString("host", value).apply()

    override var port: Int
        get() = sp.getInt("port", Proto.DEFAULT_PORT)
        set(value) = sp.edit().putInt("port", value).apply()

    /**
     * PIN-токены по хостам: у каждой колонки свой. Старый одиночный токен
     * миграцией переносится в карту под сохранённый хост.
     */
    override fun tokenFor(host: String): String? = readTokens().optString(host).takeIf { it.isNotEmpty() }

    override fun setToken(host: String, token: String?) {
        val map = readTokens()
        if (token.isNullOrEmpty()) map.remove(host) else map.put(host, token)
        sp.edit().putString("tokens", map.toString()).apply()
    }

    private fun readTokens(): org.json.JSONObject {
        val map = sp.getString("tokens", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            ?: org.json.JSONObject()
        if (!sp.getBoolean("tokens_migrated", false)) {
            val legacy = sp.getString("token", null)
            val savedHost = sp.getString("host", null)
            if (!legacy.isNullOrEmpty() && savedHost != null && !map.has(savedHost)) {
                map.put(savedHost, legacy)
            }
            sp.edit()
                .putString("tokens", map.toString())
                .putBoolean("tokens_migrated", true)
                .remove("token")
                .apply()
        }
        return map
    }

    /** Сохранённые колонки — для переключателя без повторного поиска. */
    override val knownSpeakers: List<KnownSpeaker>
        get() {
            val raw = sp.getString("speakers", null) ?: return emptyList()
            val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
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
        val list = knownSpeakers.toMutableList()
        list.removeAll { it.host == speaker.host }
        list.add(0, speaker)
        val arr = org.json.JSONArray()
        list.take(8).forEach { s ->
            arr.put(
                org.json.JSONObject()
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("name", s.name ?: "")
                    .put("model", s.model ?: "")
                    .put("firmware", s.firmware ?: ""),
            )
        }
        sp.edit().putString("speakers", arr.toString()).apply()
    }

    override var deviceName: String?
        get() = sp.getString("device_name", null)
        set(value) = sp.edit().putString("device_name", value).apply()

    /** Модель/платформа из mDNS TXT (`type`), напр. sberboom-mini. */
    override var deviceModel: String?
        get() = sp.getString("device_model", null)
        set(value) = sp.edit().putString("device_model", value).apply()

    /** Версия прошивки из mDNS TXT (`v`). */
    override var deviceFirmware: String?
        get() = sp.getString("device_firmware", null)
        set(value) = sp.edit().putString("device_firmware", value).apply()

    override var clientName: String
        get() = sp.getString("client_name", DEFAULT_CLIENT_NAME) ?: DEFAULT_CLIENT_NAME
        set(value) = sp.edit().putString("client_name", value).apply()

    /** Стабильный id клиента — колонка запоминает его по токену. */
    override val clientId: String
        get() {
            sp.getString("client_id", null)?.let { return it }
            val id = UUID.randomUUID().toString()
            sp.edit().putString("client_id", id).apply()
            return id
        }

    override fun forget() {
        val host = host
        if (host != null) {
            setToken(host, null)
            val list = knownSpeakers.filterNot { it.host == host }
            val arr = org.json.JSONArray()
            list.forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("host", s.host).put("port", s.port)
                        .put("name", s.name ?: "").put("model", s.model ?: "")
                        .put("firmware", s.firmware ?: ""),
                )
            }
            sp.edit().putString("speakers", arr.toString()).apply()
        }
        sp.edit().remove("device_name").remove("device_model").remove("device_firmware").apply()
    }

    /** История прослушивания (JSON) — общий формат с Windows-версией. */
    override var historyJson: String?
        get() = sp.getString("history", null)
        set(value) {
            if (value == null) sp.edit().remove("history").apply()
            else sp.edit().putString("history", value).apply()
        }

    /** Громкая караоке-заливка активной строки. */
    override var karaokeFill: Boolean
        get() = sp.getBoolean("karaoke_fill", true)
        set(value) = sp.edit().putBoolean("karaoke_fill", value).apply()

    companion object {
        const val DEFAULT_CLIENT_NAME = "SberBoom Remote"
    }
}
