package ru.sberboom.remote.protocol

/** Константы протокола SBoom (LAN, WSS :20000). Порт const.py интеграции. */
object Proto {
    const val DEFAULT_PORT = 20000
    const val USER_AGENT = "WebSocket++/0.8.2"

    // Опкоды операций (тег поля в request_data).
    const val OP_PIN_CONNECT = 4
    const val OP_GET_META_DATA = 10
    const val OP_GET_STATE = 12
    const val OP_FIND_REMOTE = 13
    const val OP_SET_VOLUME = 14
    const val OP_SET_TRACK_POS = 15
    const val OP_MEDIA_COMMAND = 16
    const val OP_GET_PLAYING_QUEUE = 17
    const val OP_KEEP_ALIVE = 18
    /** Эхо-сервер: возвращает присланное вложенное сообщение. Замер RTT. */
    const val OP_ECHO = 8
    const val OP_GET_PAIRED_BT = 19
    const val OP_BT_DEVICE_COMMAND = 20
    const val OP_GET_SCANNED_BT = 21
    const val OP_BT_DISCOVERABLE = 22
    const val OP_SET_PLAYBACK_SPEED = 23

    // Конверт ответа: field(3) = статус. 1 = ok, 4 = токен не принят.
    const val ENVELOPE_FIELD_STATUS = 3
    const val STATUS_OK = 1
    const val STATUS_UNAUTHORIZED = 4

    // Медиа-команды (поле action внутри op=16).
    const val MEDIA_MUTE = 0
    const val MEDIA_UNMUTE = 1
    const val MEDIA_NEXT = 2
    const val MEDIA_PREV = 3
    const val MEDIA_PLAY = 4
    const val MEDIA_PAUSE = 5
    const val MEDIA_LIKE = 6
    const val MEDIA_REMOVE_LIKE = 7
    const val MEDIA_HUMPED_MULTIROOM = 8
    const val MEDIA_SHUFFLE_ON = 9
    const val MEDIA_SHUFFLE_OFF = 10
    const val MEDIA_REPEAT_NONE = 11
    const val MEDIA_REPEAT_PLAYLIST = 12
    const val MEDIA_REPEAT_TRACK = 13
    const val MEDIA_DISLIKE = 14
    const val MEDIA_REMOVE_DISLIKE = 15

    // Тип токена (PIN-авторизация).
    const val TOKEN_TYPE_PIN = 1

    // Статусы pair-handshake.
    const val PAIR_WAITING = 1
    const val PAIR_AUTHORIZED = 2
    const val PAIR_BUSY = 3
    const val PAIR_DISABLED = 5
    const val PAIR_CONFIRM_OK = 1
    const val PAIR_CONFIRM_REJECTED = 2

    // Границы скорости воспроизведения.
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 2.0f

    // Прошивка ограничивает громкость сверху (~75).
    const val VOLUME_MAX = 100

    const val PAIR_TIMEOUT_MS = 120_000L
    val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000, 60_000)

    const val ZVUK_IMAGE_CDN = "https://cdn-image.zvuk.com/pic"
    const val COVER_SIZE = "600x600"
}
