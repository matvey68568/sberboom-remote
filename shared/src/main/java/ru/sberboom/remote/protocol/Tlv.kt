package ru.sberboom.remote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Бинарный TLV-кодек протокола колонки (Google proto-wire без схемы):
 * varint + length-delimited + fixed32(float).
 *
 * Порт custom_components/sboom_ha/_tlv.py — поведение намеренно совпадает
 * байт-в-байт, чтобы кадры приложения были идентичны кадрам интеграции HA.
 */
object Tlv {
    const val VARINT = 0
    const val LEN = 2
    const val FIXED32 = 5

    fun varint(value: Long): ByteArray {
        require(value >= 0) { "varint must be non-negative" }
        var n = value
        val out = ByteArrayOutputStream(10)
        while (n > 0x7f) {
            out.write(((n and 0x7f) or 0x80).toInt())
            n = n ushr 7
        }
        out.write((n and 0x7f).toInt())
        return out.toByteArray()
    }

    fun field(tag: Int, kind: Int, payload: ByteArray): ByteArray {
        val key = varint(((tag.toLong() shl 3) or kind.toLong()))
        return when (kind) {
            // payload для varint — уже varint-кодированное значение (см. int()).
            VARINT, FIXED32 -> key + payload
            LEN -> key + varint(payload.size.toLong()) + payload
            else -> throw IllegalArgumentException("unsupported kind: $kind")
        }
    }

    fun int(tag: Int, value: Int): ByteArray = field(tag, VARINT, varint(value.toLong()))

    fun bytes(tag: Int, value: ByteArray): ByteArray = field(tag, LEN, value)

    fun string(tag: Int, value: String): ByteArray = field(tag, LEN, value.toByteArray(Charsets.UTF_8))

    fun float(tag: Int, value: Float): ByteArray {
        val buf = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(value)
        return field(tag, FIXED32, buf.array())
    }

    /** Пустое сообщение-запрос: `field(tag, 2, field(1, 2, ""))`. */
    fun emptyRequest(op: Int): ByteArray = bytes(op, bytes(1, ByteArray(0)))

    /** Рекурсивный декодер: varint → Int, length-delim → String | Map | hex, fixed32 → Float. */
    fun decode(data: ByteArray): MutableMap<Int, Any> {
        val out = mutableMapOf<Int, Any>()
        var i = 0
        val n = data.size
        while (i < n) {
            val keyRes = readVarint(data, i) ?: return out
            val key = keyRes.first
            i = keyRes.second
            val tag = (key ushr 3).toInt()
            when (key and 0x7L) {
                0L -> {
                    val v = readVarint(data, i) ?: return out
                    out[tag] = v.first.toInt()
                    i = v.second
                }
                2L -> {
                    val lenRes = readVarint(data, i) ?: return out
                    var len = lenRes.first.toInt()
                    i = lenRes.second
                    if (len < 0 || i + len > n) return out
                    val payload = data.copyOfRange(i, i + len)
                    i += len
                    val text = strictUtf8(payload)
                    if (text != null && isPrintable(text)) {
                        out[tag] = text
                        continue
                    }
                    val nested = decode(payload)
                    out[tag] = if (nested.isNotEmpty()) nested else payload.toHex()
                }
                5L -> {
                    if (i + 4 > n) return out
                    val buf = ByteBuffer.wrap(data, i, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    out[tag] = buf.float
                    i += 4
                }
                else -> return out
            }
        }
        return out
    }

    /** Декодер с сохранением повторяющихся тегов (proto `repeated`). */
    fun decodeRepeated(data: ByteArray): MutableMap<Int, MutableList<Any>> {
        val out = mutableMapOf<Int, MutableList<Any>>()
        var i = 0
        val n = data.size
        while (i < n) {
            val keyRes = readVarint(data, i) ?: return out
            val key = keyRes.first
            i = keyRes.second
            val tag = (key ushr 3).toInt()
            when (key and 0x7L) {
                0L -> {
                    val v = readVarint(data, i) ?: return out
                    out.getOrPut(tag) { mutableListOf() }.add(v.first.toInt())
                    i = v.second
                }
                2L -> {
                    val lenRes = readVarint(data, i) ?: return out
                    val len = lenRes.first.toInt()
                    i = lenRes.second
                    if (len < 0 || i + len > n) return out
                    out.getOrPut(tag) { mutableListOf() }.add(data.copyOfRange(i, i + len))
                    i += len
                }
                5L -> {
                    if (i + 4 > n) return out
                    out.getOrPut(tag) { mutableListOf() }.add(data.copyOfRange(i, i + 4))
                    i += 4
                }
                else -> return out
            }
        }
        return out
    }

    fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var pos = start
        while (true) {
            if (pos >= data.size) return null
            val b = data[pos].toInt() and 0xff
            pos++
            value = value or ((b.toLong() and 0x7f) shl shift)
            if (b and 0x80 == 0) return value to pos
            shift += 7
            if (shift > 63) return null
        }
    }

    private fun strictUtf8(payload: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun isPrintable(s: String): Boolean =
        s.all { ch -> !ch.isISOControl() || ch == '\n' || ch == '\t' }

    fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }
}
