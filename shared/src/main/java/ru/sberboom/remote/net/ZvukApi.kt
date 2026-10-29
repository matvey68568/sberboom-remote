package ru.sberboom.remote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Публичный анонимный API Sber Звук (zvuk.com) — порт zvuk_client.py:
 * токен из /api/tiny/profile, поиск через /api/tiny/search, обогащение
 * обложками и drill-down через GraphQL /api/v1/graphql.
 *
 * Авторизация не нужна: анонимный токен привязан к cookie-сессии.
 */
class ZvukApi {

    data class Item(
        val id: String,
        val type: String,
        val title: String,
        val subtitle: String,
        var coverUrl: String? = null,
        val pt: String? = null,
        val explicit: Boolean = false,
        val durationSec: Int? = null,
    )

    data class SearchResult(
        val best: Item? = null,
        val artists: List<Item> = emptyList(),
        val releases: List<Item> = emptyList(),
        val tracks: List<Item> = emptyList(),
        val playlists: List<Item> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = best == null && artists.isEmpty() && releases.isEmpty() && tracks.isEmpty() && playlists.isEmpty()

        val all: List<Item> get() = artists + releases + tracks + playlists
    }

    /** Метаданные трека по каталожному id (для очереди и треклистов). */
    data class ZvukTrack(
        val id: String,
        val title: String,
        val artist: String,
        val coverUrl: String?,
        val durationSec: Int?,
        val explicit: Boolean,
    )

    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            store.getOrPut(url.host) { mutableListOf() }.apply {
                for (c in cookies) {
                    removeAll { it.name == c.name }
                    add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/json")
                    .build(),
            )
        }
        .build()

    @Volatile
    private var token: String? = null

    // ───────────────────────────── auth ─────────────────────────────

    @Synchronized
    private fun ensureToken(force: Boolean = false): String {
        if (!force) token?.let { return it }
        val req = Request.Builder().url(PROFILE_URL).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Звук: профиль ${resp.code}")
            val body = JSONObject(resp.body?.string().orEmpty())
            val t = body.optJSONObject("result")?.optString("token").orEmpty()
            if (t.isEmpty()) throw IOException("Звук: токен не получен")
            token = t
            return t
        }
    }

    // ───────────────────────────── search ─────────────────────────────

    suspend fun search(query: String, limit: Int = 8): SearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchResult()
        val url = HttpUrl.Builder()
            .scheme("https").host("zvuk.com").addPathSegment("api").addPathSegment("tiny").addPathSegment("search")
            .addQueryParameter("query", query)
            .addQueryParameter("type", "artist,release,track,playlist")
            .addQueryParameter("limit", limit.toString())
            .build()

        fun exec(tok: String) = client.newCall(
            Request.Builder().url(url).header("X-Auth-Token", tok).get().build(),
        ).execute()

        var resp = exec(ensureToken())
        if (resp.code == 401) {
            resp.close()
            resp = exec(ensureToken(force = true))
        }
        val root = resp.use {
            if (!it.isSuccessful) throw IOException("Звук: поиск ${it.code}")
            JSONObject(it.body?.string().orEmpty())
        }
        val search = root.optJSONObject("result")?.optJSONObject("search") ?: return@withContext SearchResult()

        val bestItem = search.optJSONObject("best_item")?.let { b ->
            val id = b.optString("id")
            val docType = b.optString("doc_type")
            if (id.isEmpty() || docType.isEmpty()) null
            else Item(id = id, type = docType, title = b.optString("title"), subtitle = "", pt = ptFor(docType))
        }

        fun section(key: String, docType: String): List<Item> {
            val items = search.optJSONObject(key)?.optJSONArray("items") ?: return emptyList()
            return (0 until minOf(items.length(), limit)).mapNotNull { i ->
                items.optJSONObject(i)?.let { toItem(it, docType) }
            }
        }

        val result = SearchResult(
            best = bestItem,
            artists = section("artists", "artist"),
            releases = section("releases", "release"),
            tracks = section("tracks", "track"),
            playlists = section("playlists", "playlist"),
        )
        enrichCovers(result)
        result
    }

    private fun toItem(it: JSONObject, docType: String): Item? {
        val id = it.optString("id")
        if (id.isEmpty()) return null
        val aname = it.optString("aname").takeIf { it.isNotEmpty() }
        val subtitle = when (docType) {
            "artist" -> "Исполнитель"
            "playlist" -> {
                val n = it.optInt("tracks_number", 0)
                if (n > 0) "Плейлист · $n треков" else "Плейлист"
            }
            "release" -> if (aname != null) "$aname · Альбом" else "Альбом"
            else -> aname ?: ""
        }
        return Item(
            id = id,
            type = docType,
            title = it.optString("title"),
            subtitle = subtitle,
            pt = ptFor(docType),
            explicit = it.optBoolean("explicit", false),
            durationSec = it.optInt("duration", 0).takeIf { d -> d > 0 },
        )
    }

    private fun ptFor(docType: String): String = when (docType) {
        "release" -> "release"
        "artist" -> "artist"
        "playlist" -> "playlist"
        "podcast" -> "podcast"
        else -> "track"
    }

    /** Обложки для результатов поиска (best-effort, ошибки не критичны). */
    private fun enrichCovers(result: SearchResult) {
        runCatching {
            val artistIds = result.artists.map { it.id }
            if (artistIds.isNotEmpty()) {
                val covers = graphqlCovers(GQL_ARTISTS, "getArtists", artistIds)
                result.artists.forEach { it.coverUrl = covers[it.id] }
            }
        }
        runCatching {
            val releaseIds = result.releases.map { it.id }
            if (releaseIds.isNotEmpty()) {
                val covers = graphqlCovers(GQL_RELEASES, "getReleases", releaseIds)
                result.releases.forEach { it.coverUrl = covers[it.id] }
            }
        }
        runCatching {
            val trackIds = result.tracks.map { it.id }
            if (trackIds.isNotEmpty()) {
                val covers = graphqlCovers(GQL_TRACKS, "getTracks", trackIds)
                result.tracks.forEach { it.coverUrl = covers[it.id] }
            }
        }
    }

    // ───────────────────────────── graphql ─────────────────────────────

    private fun graphqlCovers(query: String, key: String, ids: List<String>): Map<String, String> {
        val variables = JSONObject().put("ids", JSONArray(ids))
        val payload = JSONObject().put("query", query).put("variables", variables).toString()
        fun exec(tok: String) = client.newCall(
            Request.Builder()
                .url(GRAPHQL_URL)
                .header("X-Auth-Token", tok)
                .post(payload.toRequestBody(JSON_MEDIA))
                .build(),
        ).execute()

        var resp = exec(ensureToken())
        if (resp.code == 401) {
            resp.close()
            resp = exec(ensureToken(force = true))
        }
        val body = resp.use { JSONObject(it.body?.string().orEmpty()) }
        val rows = body.optJSONObject("data")?.optJSONArray(key) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val src = row.optJSONObject("image")?.optString("src").orEmpty()
            if (src.isNotEmpty()) out[row.optString("id")] = sizeCover(src)
        }
        return out
    }

    private fun sizeCover(src: String): String = src.replace("{size}", COVER_SIZE)

    /** Похожие треки для «что дальше» (Bluetooth-источник очередь не отдаёт). */
    suspend fun similar(artist: String, limit: Int = 10): List<Item> =
        runCatching { search(artist, limit).tracks }.getOrDefault(emptyList())

    // ───────────────────────────── drill-down ─────────────────────────────

    data class ArtistDetail(
        val id: String,
        val title: String,
        val coverUrl: String?,
        val releases: List<Item>,
        val tracks: List<Item>,
    )

    data class ReleaseDetail(
        val id: String,
        val title: String,
        val year: Int?,
        val coverUrl: String?,
        val artist: String,
        val tracks: List<Item>,
    )

    /** Страница артиста: дискография + популярные треки (getArtist). */
    suspend fun artist(id: String): ArtistDetail? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val data = graphql(GQL_ARTIST, id) ?: return@withContext null
        val rows = data.optJSONArray("getArtists") ?: return@withContext null
        val a = rows.optJSONObject(0) ?: return@withContext null

        val releases = ArrayList<Item>()
        a.optJSONArray("releases")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val rid = r.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val year = year(r.optString("date"))
                val type = r.optString("type").ifEmpty { "album" }
                releases.add(
                    Item(
                        id = rid,
                        type = "release",
                        title = r.optString("title"),
                        subtitle = listOfNotNull(
                            if (type == "single") "Сингл" else "Альбом",
                            year?.toString(),
                        ).joinToString(" · "),
                        coverUrl = r.optJSONObject("image")?.optString("src")?.takeIf { it.isNotEmpty() }?.let { sizeCover(it) },
                        pt = "release",
                    ),
                )
            }
        }

        val tracks = ArrayList<Item>()
        a.optJSONArray("popularTracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val tid = t.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val artists = t.optJSONArray("artists")
                val artistLine = if (artists == null) "" else (0 until artists.length())
                    .mapNotNull { artists.optJSONObject(it)?.optString("title")?.takeIf { s -> s.isNotEmpty() } }
                    .joinToString(", ")
                tracks.add(
                    Item(
                        id = tid,
                        type = "track",
                        title = t.optString("title"),
                        subtitle = artistLine,
                        coverUrl = t.optJSONObject("release")?.optJSONObject("image")?.optString("src")
                            ?.takeIf { it.isNotEmpty() }?.let { sizeCover(it) },
                        pt = "track",
                        explicit = t.optBoolean("explicit", false),
                        durationSec = t.optInt("duration", 0).takeIf { d -> d > 0 },
                    ),
                )
            }
        }

        ArtistDetail(
            id = a.optString("id"),
            title = a.optString("title"),
            coverUrl = a.optJSONObject("image")?.optString("src")?.takeIf { it.isNotEmpty() }?.let { sizeCover(it) },
            releases = releases,
            tracks = tracks,
        )
    }

    /** Страница альбома: треклист (getRelease). */
    suspend fun release(id: String): ReleaseDetail? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val data = graphql(GQL_RELEASE, id) ?: return@withContext null
        val rows = data.optJSONArray("getReleases") ?: return@withContext null
        val r = rows.optJSONObject(0) ?: return@withContext null
        val cover = r.optJSONObject("image")?.optString("src")?.takeIf { it.isNotEmpty() }?.let { sizeCover(it) }

        val artists = ArrayList<String>()
        r.optJSONArray("artists")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("title")?.takeIf { t -> t.isNotEmpty() }?.let { artists.add(it) }
            }
        }

        val tracks = ArrayList<Item>()
        r.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val tid = t.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val line = t.optJSONArray("artists")?.let { a2 ->
                    (0 until a2.length())
                        .mapNotNull { a2.optJSONObject(it)?.optString("title")?.takeIf { s -> s.isNotEmpty() } }
                        .joinToString(", ")
                } ?: ""
                tracks.add(
                    Item(
                        id = tid,
                        type = "track",
                        title = t.optString("title"),
                        subtitle = line,
                        coverUrl = cover,
                        pt = "track",
                        explicit = t.optBoolean("explicit", false),
                        durationSec = t.optInt("duration", 0).takeIf { d -> d > 0 },
                    ),
                )
            }
        }

        ReleaseDetail(
            id = r.optString("id"),
            title = r.optString("title"),
            year = year(r.optString("date")),
            coverUrl = cover,
            artist = artists.joinToString(", "),
            tracks = tracks,
        )
    }

    private fun year(date: String): Int? =
        if (date.length >= 4 && date.take(4).all { it.isDigit() }) date.take(4).toInt() else null

    /** GraphQL-запрос с одним id — общий транспорт (404/401 → null). */
    private fun graphql(query: String, id: String): JSONObject? = runCatching {
        val payload = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("ids", JSONArray(listOf(id))))
            .toString()

        fun exec(tok: String) = client.newCall(
            Request.Builder()
                .url(GRAPHQL_URL)
                .header("X-Auth-Token", tok)
                .post(payload.toRequestBody(JSON_MEDIA))
                .build(),
        ).execute()

        var resp = exec(ensureToken())
        if (resp.code == 401) {
            resp.close()
            resp = exec(ensureToken(force = true))
        }
        resp.use {
            if (!it.isSuccessful) return@use null
            JSONObject(it.body?.string().orEmpty()).optJSONObject("data")
        }
    }.getOrNull()

    /** Метаданные треков по каталожным id: обложка, название, исполнитель, длительность. */
    suspend fun tracks(ids: List<String>): List<ZvukTrack> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val variables = JSONObject().put("ids", JSONArray(ids))
        val payload = JSONObject().put("query", GQL_TRACKS).put("variables", variables).toString()
        fun exec(tok: String) = client.newCall(
            Request.Builder()
                .url(GRAPHQL_URL)
                .header("X-Auth-Token", tok)
                .post(payload.toRequestBody(JSON_MEDIA))
                .build(),
        ).execute()

        var resp = exec(ensureToken())
        if (resp.code == 401) {
            resp.close()
            resp = exec(ensureToken(force = true))
        }
        val body = resp.use {
            if (!it.isSuccessful) throw IOException("Звук: метаданные ${it.code}")
            JSONObject(it.body?.string().orEmpty())
        }
        val rows = body.optJSONObject("data")?.optJSONArray("getTracks") ?: return@withContext emptyList()
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val artists = row.optJSONArray("artists")
            val artistLine = if (artists == null) "" else (0 until artists.length())
                .mapNotNull { artists.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotEmpty() } }
                .joinToString(", ")
            ZvukTrack(
                id = row.optString("id"),
                title = row.optString("title"),
                artist = artistLine,
                coverUrl = row.optJSONObject("release")?.optJSONObject("image")?.optString("src")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { sizeCover(it) },
                durationSec = row.optInt("duration", 0).takeIf { d -> d > 0 },
                explicit = row.optBoolean("explicit", false),
            )
        }
    }

    // ───────────────────────────── deeplinks ─────────────────────────────

    companion object {
        private const val PROFILE_URL = "https://zvuk.com/api/tiny/profile"
        private const val GRAPHQL_URL = "https://zvuk.com/api/v1/graphql"
        private const val COVER_SIZE = "400x400"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val JSON_MEDIA = "application/json".toMediaType()

        private const val GQL_TRACKS =
            "query getTracks(\$ids:[ID!]!){getTracks(ids:\$ids){id title duration explicit hasFlac lyrics artists{id title} release{id title image{src}}}}"
        private const val GQL_ARTISTS = "query getArtists(\$ids:[ID!]!){getArtists(ids:\$ids){id image{src}}}"
        private const val GQL_RELEASES = "query getReleases(\$ids:[ID!]!){getReleases(ids:\$ids){id image{src}}}"
        private const val GQL_ARTIST =
            "query getArtist(\$ids:[ID!]!){getArtists(ids:\$ids){id title image{src} " +
                "releases{id title date type image{src}} " +
                "popularTracks(limit:20){id title duration explicit artists{title} release{image{src}}}}}"
        private const val GQL_RELEASE =
            "query getRelease(\$ids:[ID!]!){getReleases(ids:\$ids){id title date type image{src} " +
                "artists{id title} tracks{id title duration explicit artists{title}}}}"

        private val URL_RE = Regex("zvuk\\.com/(track|artist|release|playlist|abook)/(\\d+)", RegexOption.IGNORE_CASE)

        private val ID_PARAM = mapOf(
            "track" to "tid", "podcast" to "tid", "abook" to "tid",
            "artist" to "pid", "release" to "pid", "playlist" to "pid",
        )
        private val VALID_PT = setOf("track", "podcast", "artist", "release", "playlist")

        /** Ссылка zvuk.com/<kind>/<id> → (pt, id) для staros://music deeplink. */
        fun parseZvukUrl(text: String): Pair<String, String>? {
            val m = URL_RE.find(text.trim()) ?: return null
            val kind = m.groupValues[1].lowercase()
            val pt = when (kind) {
                "abook" -> "podcast"
                else -> kind
            }
            return pt to m.groupValues[2]
        }

        /** Валидированная сборка deeplink: pt из whitelist, id — только цифры. */
        fun deeplink(pt: String?, id: String?): String? {
            val p = (pt ?: "").lowercase()
            val cid = id?.trim() ?: ""
            if (p !in VALID_PT || cid.isEmpty() || !cid.all { it.isDigit() }) return null
            val param = ID_PARAM[p] ?: return null
            return "staros://music?$param=$cid&pt=$p"
        }
    }

    // ───────────────────────────── обложки вне каталога ─────────────────────────────

    /** Обложка по исполнителю+треку (радио/Bluetooth): iTunes → Deezer. */
    suspend fun externalCover(title: String?, artist: String?): String? = withContext(Dispatchers.IO) {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return@withContext null
        runCatching { itunesCover(title, artist) }.getOrNull()
            ?: runCatching { deezerCover(title, artist) }.getOrNull()
    }

    private fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun itunesCover(title: String, artist: String): String? {
        val url = HttpUrl.Builder().scheme("https").host("itunes.apple.com").addPathSegment("search")
            .addQueryParameter("term", "$artist $title")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "1")
            .build()
        val body = plainClient().newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return null
            JSONObject(it.body?.string().orEmpty())
        }
        val results = body.optJSONArray("results") ?: return null
        val art = results.optJSONObject(0)?.optString("artworkUrl100").orEmpty()
        if (art.isEmpty()) return null
        return art.replace("100x100bb", "600x600bb")
    }

    private fun deezerCover(title: String, artist: String): String? {
        val url = HttpUrl.Builder().scheme("https").host("api.deezer.com").addPathSegment("search")
            .addQueryParameter("q", "artist:\"$artist\" track:\"$title\"")
            .addQueryParameter("limit", "1")
            .build()
        val body = plainClient().newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return null
            JSONObject(it.body?.string().orEmpty())
        }
        val item = body.optJSONArray("data")?.optJSONObject(0) ?: return null
        val album = item.optJSONObject("album") ?: return null
        return album.optString("cover_xl").ifEmpty { album.optString("cover_big") }.ifEmpty { null }
    }
}
