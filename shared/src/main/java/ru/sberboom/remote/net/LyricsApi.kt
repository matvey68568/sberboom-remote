package ru.sberboom.remote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Насколько раньше показывать строку: позиция трека приходит по сети с задержкой,
 * поэтому без поправки караоке отставало от звука.
 */
const val LEAD_SEC = 0.35f

/** Строка текста с привязкой ко времени трека. */
data class LyricLine(val timeSec: Float, val text: String)

/** Текст песни: синхронизированные строки и/или plain-текст. */
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val plain: String? = null,
    val source: String = "lrclib",
    val instrumental: Boolean = false,
) {
    val isSynced: Boolean get() = lines.isNotEmpty()

    /** Индекс активной строки для позиции (последняя, чьё время ≤ позиции). */
    fun indexAt(positionSec: Float): Int {
        if (lines.isEmpty()) return -1
        var active = -1
        for (i in lines.indices) {
            if (lines[i].timeSec <= positionSec + LEAD_SEC) active = i else break
        }
        return active
    }
}

/**
 * Тексты песен: Lrclib.net (primary, без auth) → NetEase Cloud Music (резерв
 * для synced-текстов). Порт lyrics_client.py интеграции sboom_ha.
 *
 * Работает по названию+исполнителю, поэтому одинаково годится и для каталожных
 * треков Звука, и для Bluetooth/радио, где каталожного id нет.
 */
class LyricsApi {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(
        title: String?,
        artist: String?,
        album: String? = null,
        durationSec: Int? = null,
    ): Lyrics? = withContext(Dispatchers.IO) {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return@withContext null

        val fromLrclib = runCatching { lrclib(title, artist, album, durationSec) }.getOrNull()
        // synced или инструментал — сразу годится; plain-only придержим как резерв.
        if (fromLrclib != null && (fromLrclib.isSynced || fromLrclib.instrumental)) return@withContext fromLrclib

        val fromNetease = runCatching { netease(title, artist, durationSec) }.getOrNull()
        if (fromNetease?.isSynced == true) return@withContext fromNetease

        fromLrclib ?: fromNetease
    }

    // ───────────────────────────── Lrclib ─────────────────────────────

    private fun lrclib(title: String, artist: String, album: String?, durationSec: Int?): Lyrics? {
        // Первый заход — с альбомом и длительностью, второй — только название+артист.
        val attempts = buildList {
            add(Triple(album, durationSec, true))
            if (album != null || durationSec != null) add(Triple(null, null, false))
        }
        for ((alb, dur, _) in attempts) {
            val url = HttpUrl.Builder()
                .scheme("https").host("lrclib.net")
                .addPathSegment("api").addPathSegment("get")
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply {
                    if (alb != null) addQueryParameter("album_name", alb)
                    if (dur != null && dur > 0) addQueryParameter("duration", dur.toString())
                }
                .build()

            val body = client.newCall(
                Request.Builder().url(url).header("User-Agent", USER_AGENT).build(),
            ).execute().use { resp ->
                if (resp.code == 404) return@use null
                if (!resp.isSuccessful) return@use null
                JSONObject(resp.body?.string().orEmpty())
            } ?: continue

            val synced = body.optString("syncedLyrics").takeIf { it.isNotBlank() }
            val plain = body.optString("plainLyrics").takeIf { it.isNotBlank() }
            val instrumental = body.optBoolean("instrumental", false)
            if (synced == null && plain == null && !instrumental) continue
            return Lyrics(
                lines = synced?.let { Lrc.parse(it) } ?: emptyList(),
                plain = plain,
                source = "lrclib",
                instrumental = instrumental,
            )
        }
        return null
    }

    // ───────────────────────────── NetEase ─────────────────────────────

    private fun netease(title: String, artist: String, durationSec: Int?): Lyrics? {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://music.163.com/",
        )
        val searchUrl = HttpUrl.Builder()
            .scheme("https").host("music.163.com")
            .addPathSegment("api").addPathSegment("search").addPathSegment("get")
            .addQueryParameter("s", "$title $artist")
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "10")
            .addQueryParameter("offset", "0")
            .build()

        val search = getJson(searchUrl, headers) ?: return null
        val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val songId = pickSong(songs, title, artist, durationSec) ?: return null

        val lyricUrl = HttpUrl.Builder()
            .scheme("https").host("music.163.com")
            .addPathSegment("api").addPathSegment("song").addPathSegment("lyric")
            .addQueryParameter("id", songId.toString())
            .addQueryParameter("lv", "1")
            .addQueryParameter("kv", "-1")
            .addQueryParameter("tv", "-1")
            .build()

        val lyricJson = getJson(lyricUrl, headers) ?: return null
        var lrc = lyricJson.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (lrc.isBlank()) return null
        // Убираем служебные строки-кредиты («[00:00.00] 作曲 : …»).
        lrc = lrc.split('\n')
            .filterNot { Lrc.isCredit(it) }
            .joinToString("\n")

        val lines = Lrc.parse(lrc)
        if (lines.isEmpty()) {
            return Lyrics(plain = lrc.trim().takeIf { it.isNotEmpty() }, source = "netease")
        }
        return Lyrics(
            lines = lines,
            plain = lines.mapNotNull { it.text.takeIf { t -> t.isNotEmpty() } }.joinToString("\n"),
            source = "netease",
        )
    }

    private fun getJson(url: HttpUrl, headers: Map<String, String>): JSONObject? = runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            JSONObject(resp.body?.string().orEmpty())
        }
    }.getOrNull()

    private fun pickSong(
        songs: org.json.JSONArray,
        title: String,
        artist: String,
        durationSec: Int?,
    ): Long? {
        val wantTrack = norm(title)
        val wantArtist = norm(artist)
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            if (!song.has("id")) continue
            val name = norm(song.optString("name"))
            if (name.isEmpty() || (wantTrack !in name && name !in wantTrack)) continue

            val artists = song.optJSONArray("artists") ?: org.json.JSONArray()
            val artistNames = (0 until artists.length())
                .mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotEmpty() } }
                .map { norm(it) }
            if (wantArtist.isNotEmpty() && artistNames.isNotEmpty() &&
                artistNames.none { it.isNotEmpty() && (wantArtist.contains(it) || it.contains(wantArtist)) }
            ) {
                continue
            }

            val durMs = song.optDouble("duration", 0.0)
            if (durationSec != null && durMs > 0 && kotlin.math.abs(durMs / 1000.0 - durationSec) > 7) continue
            return song.optLong("id").takeIf { it > 0 }
        }
        return null
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        private const val USER_AGENT = "SberBoomRemote/1.0 (Android)"
    }
}

/** Разбор LRC-таймстампов. */
object Lrc {

    private val TIMESTAMP = Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\]")
    private val WORD_TAG = Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>")
    private val CREDIT = Regex("^\\s*(作曲|作词|编曲|制作人|出品|混音|母带|录音|吉他|贝斯|鼓|键盘)\\s*[:：]")

    /** LRC → отсортированные строки; мультитаймстампы (`[00:10.00][01:30.00]Припев`) поддерживаются. */
    fun parse(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.split('\n')) {
            val line = raw.trimEnd('\r')
            var end = 0
            val stamps = ArrayList<Float>()
            for (m in TIMESTAMP.findAll(line)) {
                if (m.range.first != end) break
                val (mm, ss, cc) = m.destructured
                val fraction = if (cc.length == 3) cc.toFloat() / 1000f else cc.toFloat() / 100f
                stamps.add(mm.toInt() * 60f + ss.toInt() + fraction)
                end = m.range.last + 1
            }
            if (stamps.isEmpty()) continue
            val text = WORD_TAG.replace(line.substring(end), "").trim()
            for (ts in stamps) out.add(LyricLine(ts, text))
        }
        out.sortBy { it.timeSec }
        return out
    }

    /** Строка-LRC с кредитами («作曲 : Имя») — не текст песни. */
    fun isCredit(line: String): Boolean = CREDIT.containsMatchIn(TIMESTAMP.replace(line, "").trim())
}
