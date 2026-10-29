package ru.sberboom.desktop.compose

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.TimeUnit

/**
 * Замена coil.AsyncImage из Android-версии: качает картинку по URL через OkHttp,
 * декодирует Skia и кэширует в памяти.
 */
@Composable
fun NetImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
) {
    val url = model as? String
    val bitmap by produceState<ImageBitmap?>(initialValue = url?.let { ImageCache.get(it) }, key1 = url) {
        if (url == null) {
            value = null
            return@produceState
        }
        value = ImageCache.get(url) ?: ImageCache.load(url)
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = modifier.graphicsLayer(alpha = alpha.coerceIn(0f, 1f)),
            contentScale = contentScale,
        )
    }
}

/** Небольшой LRU-кэш декодированных обложек. */
private object ImageCache {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 64
    }

    fun get(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            }
        }.getOrNull() ?: return@withContext null

        val image = runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            ?: return@withContext null
        synchronized(cache) { cache[url] = image }
        image
    }
}
