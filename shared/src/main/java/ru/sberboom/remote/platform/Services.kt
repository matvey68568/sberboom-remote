package ru.sberboom.remote.platform

import ru.sberboom.remote.net.FoundSpeaker
import ru.sberboom.remote.protocol.Proto

/** Колонка, к которой уже подключались (для быстрого переключения). */
data class KnownSpeaker(
    val host: String,
    val port: Int = Proto.DEFAULT_PORT,
    val name: String? = null,
    val model: String? = null,
    val firmware: String? = null,
) {
    val detail: String
        get() = listOfNotNull(host, model, firmware?.let { "fw $it" }).joinToString(" · ")
}

/**
 * Хранилище настроек. Android-реализация — SharedPreferences,
 * Windows-реализация — java.util.prefs.
 */
interface Settings {
    var host: String?
    var port: Int
    var deviceName: String?
    var deviceModel: String?
    var deviceFirmware: String?
    var clientName: String
    var karaokeFill: Boolean

    /** Стабильный id клиента — колонка запоминает его по токену. */
    val clientId: String

    /** PIN-токен конкретной колонки (у каждой свой). */
    fun tokenFor(host: String): String?
    fun setToken(host: String, token: String?)

    /** История прослушивания — JSON-массив, формат общий для обеих платформ. */
    var historyJson: String?

    val knownSpeakers: List<KnownSpeaker>
    fun rememberSpeaker(speaker: KnownSpeaker)

    /** Забыть текущую колонку: токен, запись в списке, реквизиты. */
    fun forget()

    companion object {
        const val DEFAULT_CLIENT_NAME = "SberBoom Remote"
    }
}

/** Поиск колонок в локальной сети (mDNS / TCP-скан). */
interface DiscoveryService {
    suspend fun discover(timeoutMs: Long = 6_000): List<FoundSpeaker>
}

/** Цвет «ambient glow» из обложки. */
interface CoverService {
    fun cached(url: String): Int?
    fun ambientColor(url: String): Int?
}
