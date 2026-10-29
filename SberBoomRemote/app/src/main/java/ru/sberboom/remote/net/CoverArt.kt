package ru.sberboom.remote.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import ru.sberboom.remote.platform.CoverService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Цвет «ambient glow» из обложки — как в панели интеграции HA. */
object CoverArt : CoverService {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val cache = LruCache<String, Int>(64)

    override fun cached(url: String): Int? = cache.get(url)

    /** Блокирующий вызов — только из Dispatchers.IO. */
    override fun ambientColor(url: String): Int? {
        cache.get(url)?.let { return it }
        val bytes = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        }.getOrNull() ?: return null

        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        val small = runCatching { Bitmap.createScaledBitmap(bitmap, 24, 24, true) }.getOrElse { bitmap }
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)

        var r = 0f
        var g = 0f
        var b = 0f
        var weightSum = 0f
        for (p in pixels) {
            val rr = (p shr 16) and 0xff
            val gg = (p shr 8) and 0xff
            val bb = p and 0xff
            val mx = maxOf(rr, gg, bb).toFloat()
            val mn = minOf(rr, gg, bb).toFloat()
            val sat = if (mx == 0f) 0f else (mx - mn) / mx
            val weight = 0.15f + sat * sat
            r += rr * weight
            g += gg * weight
            b += bb * weight
            weightSum += weight
        }
        if (weightSum <= 0f) return null

        val hsv = FloatArray(3)
        Color.RGBToHSV(
            (r / weightSum).toInt().coerceIn(0, 255),
            (g / weightSum).toInt().coerceIn(0, 255),
            (b / weightSum).toInt().coerceIn(0, 255),
            hsv,
        )
        hsv[1] = (hsv[1] * 1.35f + 0.1f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.7f)
        val color = Color.HSVToColor(hsv)
        cache.put(url, color)
        return color
    }
}
