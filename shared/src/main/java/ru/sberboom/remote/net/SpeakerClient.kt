package ru.sberboom.remote.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import ru.sberboom.remote.protocol.Envelope
import ru.sberboom.remote.protocol.Proto
import ru.sberboom.remote.protocol.Requests
import ru.sberboom.remote.protocol.Tlv
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Клиент локального WebSocket-соединения с колонкой:
 * wss://<host>:20000/ , бинарные TLV-кадры, сертификат самоподписанный.
 *
 * Порт api.py: тот же конверт, та же корреляция request/response по полю 2,
 * тот же pair-handshake через физическую кнопку «+».
 */
class SpeakerClient(
    val host: String,
    val port: Int = Proto.DEFAULT_PORT,
    private val clientId: String,
    private val clientName: String,
    var token: String? = null,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private var ws: WebSocket? = null
    private val open = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Throwable?>()
    private val intentional = AtomicBoolean(false)
    private var pairHandler: ((Map<Int, Any>) -> Unit)? = null

    /** Несолиситированные push-кадры (смена трека, громкости и т.п.). */
    var onUnsolicited: ((ByteArray) -> Unit)? = null

    val isConnected: Boolean get() = ws != null && !closed.isCompleted

    // ───────────────────────────── transport ─────────────────────────────

    private fun httpClient(): OkHttpClient = sharedHttp

    suspend fun connect(timeoutMs: Long = 15_000) {
        val request = Request.Builder()
            .url("wss://$host:$port/")
            .header("User-Agent", Proto.USER_AGENT)
            .build()
        ws = httpClient().newWebSocket(request, listener)
        try {
            withTimeout(timeoutMs) { open.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            close()
            throw TimeoutError("не удалось открыть соединение")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Приостановиться до обрыва соединения. */
    suspend fun awaitClose(): Throwable? = closed.await()

    fun close() {
        intentional.set(true)
        ws?.cancel()
        ws = null
        failPending(ConnectionError("соединение закрыто"))
        if (!closed.isCompleted) closed.complete(null)
        if (!open.isCompleted) open.completeExceptionally(ConnectionError("отменено"))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!open.isCompleted) open.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleFrame(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text.toByteArray(Charsets.UTF_8))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            finish(t)
        }
    }

    private fun finish(error: Throwable?) {
        ws = null
        failPending(error ?: ConnectionError("соединение потеряно"))
        if (!closed.isCompleted) closed.complete(error)
        if (!open.isCompleted) open.completeExceptionally(error ?: ConnectionError("не удалось подключиться"))
    }

    private fun failPending(error: Throwable) {
        for ((_, d) in pending) if (!d.isCompleted) d.completeExceptionally(error)
        pending.clear()
    }

    private fun handleFrame(bytes: ByteArray) {
        val parsed = Tlv.decode(bytes)
        val msgId = parsed[2] as? String
        if (msgId != null) {
            val waiter = pending.remove(msgId)
            if (waiter != null) {
                if (!waiter.isCompleted) waiter.complete(bytes)
                return
            }
        }
        val pair = pairHandler
        if (pair != null) {
            pair(parsed)
            return
        }
        onUnsolicited?.invoke(bytes)
    }

    // ───────────────────────────── requests ─────────────────────────────

    suspend fun request(requestData: ByteArray, timeoutMs: Long = 6_000): ByteArray =
        sendRaw(Envelope.request(newMsgId(), requestData, token, clientName, clientId), timeoutMs)

    /** Дождаться ответа с таймаутом, превращённым в обычное исключение. */
    private suspend fun waitFor(deferred: CompletableDeferred<ByteArray>, timeoutMs: Long): ByteArray =
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw TimeoutError("колонка не ответила")
        }

    /** Отправить готовый конверт и дождаться ответа с тем же msg_id. */
    suspend fun sendRaw(envelope: ByteArray, timeoutMs: Long = 6_000): ByteArray {
        val msgId = msgIdOf(envelope)
        val deferred = CompletableDeferred<ByteArray>()
        pending[msgId] = deferred
        try {
            send(envelope)
            val reply = waitFor(deferred, timeoutMs)
            // status=4 — колонка не приняла PIN-токен: он отозван или принадлежит
            // другой сессии (колонка выдаёт токен на одну сессию и отзывает при
            // повторном сопряжении).
            if (Tlv.decode(reply)[Proto.ENVELOPE_FIELD_STATUS] == Proto.STATUS_UNAUTHORIZED) {
                throw AuthError("колонка не приняла токен")
            }
            return reply
        } finally {
            pending.remove(msgId)
        }
    }

    /**
     * ServerAction: в конверт кладётся ГОТОВОЕ тело команды
     * (см. [Envelope.serverActionJson]) — как в _deeplink.py.
     */
    suspend fun sendServerAction(bodyJson: String, timeoutMs: Long = 8_000): ByteArray {
        val msgId = newMsgId()
        val envelope = Envelope.serverAction(msgId, bodyJson, token, clientName, clientId)
        val deferred = CompletableDeferred<ByteArray>()
        pending[msgId] = deferred
        try {
            send(envelope)
            return waitFor(deferred, timeoutMs)
        } finally {
            pending.remove(msgId)
        }
    }

    /** Fire-and-forget: команды, на которые колонка не отвечает. */
    fun fire(requestData: ByteArray) {
        send(Envelope.request(newMsgId(), requestData, token, clientName, clientId))
    }

    private fun send(envelope: ByteArray) {
        val socket = ws ?: throw ConnectionError("нет соединения с колонкой")
        if (!socket.send(envelope.toByteString())) throw ConnectionError("кадр не отправлен")
    }

    suspend fun requestStatus(requestData: ByteArray, timeoutMs: Long = 6_000): Int? {
        val raw = request(requestData, timeoutMs)
        val decoded = Tlv.decode(raw)
        val status = decoded[3]
        return if (status is Int) status else null
    }

    suspend fun getState(): ru.sberboom.remote.protocol.SpeakerState? =
        ru.sberboom.remote.protocol.Payloads.parseState(request(Requests.GET_STATE))

    suspend fun getMetadata(): ru.sberboom.remote.protocol.Track? =
        ru.sberboom.remote.protocol.Payloads.parseTrack(request(Requests.GET_METADATA))

    // ───────────────────────────── pairing ─────────────────────────────

    /**
     * PIN-сопряжение: колонка просит нажать «+» на корпусе.
     * Возвращает pin_access_token.
     */
    suspend fun pair(
        onWaiting: (session: String?) -> Unit = {},
        timeoutMs: Long = Proto.PAIR_TIMEOUT_MS,
    ): String {
        val token = CompletableDeferred<String>()
        pairHandler = handler@{ parsed ->
            val reqData = parsed[5] as? Map<*, *> ?: return@handler
            val pinResp = reqData[Proto.OP_PIN_CONNECT] as? Map<*, *>
            if (pinResp != null) {
                when ((pinResp[1] as? Number)?.toInt()) {
                    Proto.PAIR_AUTHORIZED -> (pinResp[2] as? String)?.let { tok ->
                        if (tok.length >= 16) token.complete(tok)
                    }
                    Proto.PAIR_WAITING -> onWaiting(pinResp[2] as? String)
                    Proto.PAIR_BUSY -> token.completeExceptionally(PairError("сессия сопряжения уже активна"))
                    Proto.PAIR_DISABLED -> token.completeExceptionally(PairError("сопряжение выключено на колонке"))
                }
            }
            val confirm = reqData[6] as? Map<*, *>
            if (confirm != null) {
                when ((confirm[1] as? Number)?.toInt()) {
                    Proto.PAIR_CONFIRM_OK -> (confirm[2] as? String)?.let { tok ->
                        if (tok.length >= 16) token.complete(tok)
                    }
                    Proto.PAIR_CONFIRM_REJECTED -> token.completeExceptionally(PairError("колонка отклонила сопряжение"))
                }
            }
        }
        try {
            fire(Requests.PAIR_INIT)
            val tok = try {
                withTimeout(timeoutMs) { token.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw TimeoutError("нажатие «+» на колонке не получено")
            }
            this.token = tok
            return tok
        } finally {
            pairHandler = null
        }
    }

    private fun msgIdOf(envelope: ByteArray): String {
        val decoded = Tlv.decode(envelope)
        return decoded[2] as? String ?: ""
    }

    private fun newMsgId(): String = UUID.randomUUID().toString()

    class ConnectionError(message: String) : RuntimeException(message)
    class PairError(message: String) : RuntimeException(message)
    class TimeoutError(message: String) : RuntimeException(message)
    /** PIN-токен отозван: нужна новая пара (нажатие «+» на колонке). */
    class AuthError(message: String) : RuntimeException(message)

    companion object {
        /** Колонка отдаёт самоподписанный сертификат — проверку выключаем. */
        private val trustAll: X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }

        @Suppress("DEPRECATION")
        val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
