package ru.sberboom.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import ru.sberboom.remote.platform.CoverService
import java.awt.Color
import java.util.concurrent.TimeUnit

/**
 * Цвет «ambient glow» из обложки — тот же алгоритм, что в Android-версии,
 * но на ImageIO и java.awt.Color.
 */
object CoverArt : CoverService {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val cache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 64
    }

    override fun cached(url: String): Int? = synchronized(cache) { cache[url] }

    override fun ambientColor(url: String): Int? {
        cached(url)?.let { return it }

        val bytes = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        }.getOrNull() ?: return null

        val image = runCatching { javax.imageio.ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
        val width = 24
        val height = 24

        var r = 0f
        var g = 0f
        var b = 0f
        var weightSum = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = image.getRGB(x * image.width / width, y * image.height / height)
                val rr = (px shr 16) and 0xff
                val gg = (px shr 8) and 0xff
                val bb = px and 0xff
                val mx = maxOf(rr, gg, bb).toFloat()
                val mn = minOf(rr, gg, bb).toFloat()
                val sat = if (mx == 0f) 0f else (mx - mn) / mx
                val weight = 0.15f + sat * sat
                r += rr * weight
                g += gg * weight
                b += bb * weight
                weightSum += weight
            }
        }
        if (weightSum <= 0f) return null

        val hsb = Color.RGBtoHSB(
            (r / weightSum).toInt().coerceIn(0, 255),
            (g / weightSum).toInt().coerceIn(0, 255),
            (b / weightSum).toInt().coerceIn(0, 255),
            null,
        )
        val hue = hsb[0]
        val sat = (hsb[1] * 1.35f + 0.1f).coerceAtMost(1f)
        val bri = hsb[2].coerceIn(0.45f, 0.7f)
        val argb = Color.HSBtoRGB(hue, sat, bri)

        synchronized(cache) { cache[url] = argb }
        return argb
    }
}
