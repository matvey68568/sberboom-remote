package ru.sberboom.remote.net

/**
 * Найденная колонка. Модель общая для Android и Windows: заполняется
 * платформенным сервисом поиска (mDNS / TCP-скан).
 */
data class FoundSpeaker(
    val host: String,
    val port: Int,
    val name: String? = null,
    /** TXT `type` — модель/платформа, напр. sberboom-r2 или sberboom-mini. */
    val model: String? = null,
    /** TXT `v` — версия прошивки (StarOS). */
    val firmware: String? = null,
    val via: String = "mDNS",
) {
    /** Подпись под именем: IP · transport · модель · прошивка. */
    val detail: String
        get() = listOfNotNull(
            "$host:$port",
            via,
            model,
            firmware?.let { "fw $it" },
        ).joinToString(" · ")
}
