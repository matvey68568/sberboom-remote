package ru.sberboom.remote.protocol

/** Сборка WS-конвертов и запросов к колонке. */
object Envelope {

    /**
     * Обычный запрос-конверт:
     *  1=type(2), 2=id, 3=token?, 5=request_data, 6=token_type, 7=client_name,
     *  10=is_request(1), 11=client_id.
     */
    fun request(
        msgId: String,
        requestData: ByteArray,
        token: String?,
        clientName: String,
        clientId: String,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Tlv.int(1, 2))
        out.write(Tlv.string(2, msgId))
        if (!token.isNullOrEmpty()) out.write(Tlv.string(3, token))
        out.write(Tlv.bytes(5, requestData))
        out.write(Tlv.int(6, Proto.TOKEN_TYPE_PIN))
        out.write(Tlv.string(7, clientName))
        out.write(Tlv.int(10, 1))
        out.write(Tlv.string(11, clientId))
        return out.toByteArray()
    }

    /**
     * ServerAction-конверт (DEEPLINK и прочие действия «как из мобильного
     * приложения»): type=1, полезная нагрузка в поле 4 (а не в поле 5).
     */
    fun serverAction(
        msgId: String,
        bodyJson: String,
        token: String?,
        clientName: String,
        clientId: String,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Tlv.int(1, 1))
        out.write(Tlv.string(2, msgId))
        if (!token.isNullOrEmpty()) out.write(Tlv.string(3, token))
        out.write(Tlv.bytes(4, Tlv.bytes(1, bodyJson.toByteArray(Charsets.UTF_8))))
        out.write(Tlv.int(6, Proto.TOKEN_TYPE_PIN))
        out.write(Tlv.string(7, clientName))
        out.write(Tlv.int(10, 1))
        out.write(Tlv.string(11, clientId))
        return out.toByteArray()
    }

    /** Тело ServerAction-команды: {name, payload, smartAppInfo, type} (как у интеграции). */
    fun serverActionJson(name: String, payload: String): String = buildString {
        append("{\"name\":")
        append(org.json.JSONObject.quote(name))
        append(",\"payload\":")
        append(payload)
        append(",\"smartAppInfo\":{")
        append("\"appVersionId\":\"\",\"applicationId\":\"\",\"frontendEndpoint\":\"\",")
        append("\"frontendType\":\"\",\"projectId\":\"\",\"systemName\":\"\"")
        append("},\"type\":1}")
    }
}

/** Готовые тела запросов (request_data). */
object Requests {
    val GET_STATE = Tlv.emptyRequest(Proto.OP_GET_STATE)
    val GET_METADATA = Tlv.emptyRequest(Proto.OP_GET_META_DATA)
    val GET_QUEUE = Tlv.emptyRequest(Proto.OP_GET_PLAYING_QUEUE)
    val GET_PAIRED_BT = Tlv.emptyRequest(Proto.OP_GET_PAIRED_BT)
    val BT_DISCOVERABLE = Tlv.emptyRequest(Proto.OP_BT_DISCOVERABLE)
    val KEEP_ALIVE = Tlv.emptyRequest(Proto.OP_KEEP_ALIVE)
    val PAIR_INIT = Tlv.emptyRequest(Proto.OP_PIN_CONNECT)

    fun media(action: Int): ByteArray = Tlv.bytes(Proto.OP_MEDIA_COMMAND, Tlv.int(1, action))

    fun volume(percent: Int): ByteArray =
        Tlv.bytes(Proto.OP_SET_VOLUME, Tlv.int(1, percent.coerceIn(0, Proto.VOLUME_MAX)))

    fun seek(positionSec: Int): ByteArray =
        Tlv.bytes(Proto.OP_SET_TRACK_POS, Tlv.int(1, positionSec.coerceAtLeast(0)))

    fun speed(rate: Float): ByteArray =
        Tlv.bytes(Proto.OP_SET_PLAYBACK_SPEED, Tlv.float(1, rate.coerceIn(Proto.SPEED_MIN, Proto.SPEED_MAX)))

    fun btCommand(mac: String, cmd: Int): ByteArray = Tlv.bytes(
        Proto.OP_BT_DEVICE_COMMAND,
        Tlv.string(1, mac) + Tlv.int(2, cmd),
    )

    /**
     * Эхо-запрос (op=8): колонка возвращает присланное вложенное сообщение
     * в поле 2 ответа — `{8: {2: {1: n}}}`. Используется для замера RTT.
     */
    fun echo(nonce: Int = 1): ByteArray =
        Tlv.bytes(Proto.OP_ECHO, Tlv.bytes(1, Tlv.int(1, nonce)))
}
