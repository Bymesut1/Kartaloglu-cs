package com.bymesut1

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ───────────────────────── Yardımcılar ─────────────────────────

private const val UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val browserHeaders = mapOf(
    "User-Agent" to UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
)

private fun subHeaders(referer: String): Map<String, String> = mapOf(
    "User-Agent" to UA,
    "Referer" to referer
)

private fun asciiFold(value: String): String = value
    .replace('ı', 'i').replace('İ', 'I')
    .replace('ş', 's').replace('Ş', 'S')
    .replace('ğ', 'g').replace('Ğ', 'G')
    .replace('ü', 'u').replace('Ü', 'U')
    .replace('ö', 'o').replace('Ö', 'O')
    .replace('ç', 'c').replace('Ç', 'C')

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

private fun findDescription(node: Any?): String? = when (node) {
    is JSONObject -> node.optString("description").takeIf { it.isNotBlank() }
        ?: node.keys().asSequence().mapNotNull { findDescription(node.opt(it)) }.firstOrNull()
    is JSONArray -> (0 until node.length()).asSequence()
        .mapNotNull { findDescription(node.opt(it)) }.firstOrNull()
    else -> null
}

private fun Document.jsonLdDescription(): String? {
    for (script in select("script[type=application/ld+json]")) {
        val text = script.data().ifBlank { script.html() }.trim()
        if (text.isEmpty()) continue
        val found = runCatching {
            val node: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
            findDescription(node)
        }.getOrNull()
        if (!found.isNullOrBlank()) return found.trim()
    }
    return null
}

private fun Element.posterSrcLazy(): String? {
    for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-lazy", "src")) {
        val v = attr(attr).trim()
        if (v.isNotEmpty() && !v.startsWith("data:")) return v
    }
    val srcset = attr("data-srcset").ifBlank { attr("srcset") }
    return srcset.split(",").firstOrNull()?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
}

private fun hexToBytes(hex: String): ByteArray {
    val h = hex.trim()
    if (h.isEmpty() || h.length % 2 != 0) return ByteArray(0)
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun evpMd5AesCbcDecrypt(cipherText: ByteArray, passphrase: String, saltHex: String, ivHex: String): String {
    val salt = hexToBytes(saltHex)
    val pass = passphrase.toByteArray(Charsets.UTF_8)
    val md = MessageDigest.getInstance("MD5")
    var block = ByteArray(0)
    val derived = ByteArrayOutputStream()
    while (derived.size() < 48) {
        md.reset()
        md.update(block)
        md.update(pass)
        md.update(salt)
        block = md.digest()
        derived.write(block)
    }
    val d = derived.toByteArray()
    val key = d.copyOfRange(0, 32)
    val iv = if (ivHex.isNotBlank()) hexToBytes(ivHex) else d.copyOfRange(32, 48)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return String(cipher.doFinal(cipherText), Charsets.UTF_8)
}

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun tmdbImage(path: String?, size: String): String? =
    path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

private fun normalizeName(value: String): String =
    asciiFold(value).lowercase().replace(Regex("""[^a-z0-9]"""), "")

private fun absUrl(raw: String, base: String): String? {
    val u = raw.trim()
    if (u.isEmpty()) return null
    return when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        else -> runCatching { URI(base).resolve(u).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http") }
    }
}

private fun htmlAttr(tag: String, name: String): String? =
    Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(tag)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

private fun subLang(context: String): String {
    val c = asciiFold(context).lowercase()
    return when {
        Regex("""turk|\btur\b|\btr\b|tur_|tr[-_]|[-_/.]tr[-_/.]""").containsMatchIn(c) -> "tr"
        Regex("""engl|ingiliz|\beng\b|eng_|\ben\b""").containsMatchIn(c) -> "en"
        else -> "tr"
    }
}

private fun isTurkish(lang: String): Boolean {
    val l = asciiFold(lang).lowercase()
    return l == "tr" || l == "tur" || l.startsWith("turk")
}

private data class SiteEpisode(
    val href: String,
    val name: String,
    val season: Int?,
    val episode: Int?
)

private data class OversizedMaster(
    val proxyUrl: String?,
    val maxHeight: Int,
    val variants: List<Pair<String, String>>,
    val oversized: Boolean
)

private val BANDWIDTH_RE = Regex("""BANDWIDTH=(\d+)""")
private val URI_ATTR_RE = Regex("""URI="([^"]*)"""")

// Oynatma listesindeki göreli adresleri tam adrese çevirir.
private fun absolutizePlaylist(text: String, base: String): String =
    text.lines().joinToString("\n") { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> line
            line.startsWith("#") -> URI_ATTR_RE.replace(line) { m ->
                "URI=\"" + (absUrl(m.groupValues[1], base) ?: m.groupValues[1]) + "\""
            }
            else -> absUrl(line, base) ?: line
        }
    }

private data class ProxyEntry(val content: String?, val upstream: String?, val referer: String?)

// ExoPlayer'ın doğrudan açamadığı (BANDWIDTH > Int.MAX, ayrı ses listesi 404 vb.) ana m3u8'i
// 127.0.0.1 üzerinden düzeltilmiş halde sunan küçük yerel sunucu. Alt listeleri (video/ses)
// de bu sunucu kendi başlıklarıyla (Referer/Origin/User-Agent) çekip tam adreslerle verir.
private object LocalHlsServer {
    private const val TAG = "DiziFilmIzleProxy"
    private val store = LinkedHashMap<String, ProxyEntry>()
    private var server: java.net.ServerSocket? = null

    @Synchronized
    private fun ensureStarted(): Int {
        val current = server
        if (current != null && !current.isClosed) return current.localPort
        val created = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        server = created
        Thread {
            while (!created.isClosed) {
                val client = try { created.accept() } catch (e: Exception) { break }
                Thread { handle(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return created.localPort
    }

    private fun fetch(entry: ProxyEntry): String? {
        val upstream = entry.upstream ?: return null
        return try {
            val conn = java.net.URL(upstream).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
            val ref = entry.referer
            if (!ref.isNullOrBlank()) {
                conn.setRequestProperty("Referer", ref)
                val u = runCatching { URI(ref) }.getOrNull()
                if (u?.scheme != null && u.host != null) {
                    conn.setRequestProperty("Origin", u.scheme + "://" + u.host)
                }
            }
            val code = conn.responseCode
            if (code != 200) {
                android.util.Log.e(TAG, "upstream HTTP $code for $upstream")
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                absolutizePlaylist(text, upstream)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "upstream failed for $upstream: ${e.message}")
            null
        }
    }

    private fun handle(client: java.net.Socket) {
        client.use { socket ->
            try {
                val reader = socket.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val id = first.split(" ").getOrNull(1)
                    ?.substringBefore('?')?.substringAfterLast('/')?.substringBefore('.')
                val entry = synchronized(store) { store[id] }
                val body = if (entry == null) null else (entry.content ?: fetch(entry))
                val out = socket.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                            "Access-Control-Allow-Origin: *\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()
                    )
                    out.write(bytes)
                }
                out.flush()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "handle failed: ${e.message}")
            }
        }
    }

    private fun register(entry: ProxyEntry): String? = try {
        val port = ensureStarted()
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        synchronized(store) {
            store[id] = entry
            while (store.size > 60) store.remove(store.keys.first())
        }
        "http://127.0.0.1:$port/$id.m3u8"
    } catch (e: Exception) {
        android.util.Log.e(TAG, "register failed: ${e.message}")
        null
    }

    fun publish(content: String): String? = register(ProxyEntry(content, null, null))

    fun proxyPlaylist(upstream: String, referer: String): String? =
        register(ProxyEntry(null, upstream, referer))
}

// ───────────────────────── Eklenti ─────────────────────────

class DiziFilmIzle : MainAPI() {

    // TMDB v3 API anahtarını buraya yaz. Boş/varsayılan kalırsa TMDB özelliği kapalı olur.
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbEnabled get() = tmdbKey.isNotBlank() && tmdbKey != "BURAYA_TMDB_API_KEY"

    override var mainUrl = "https://dizifilmizle.to"
    override var name = "DiziFilmIzle"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "https://dizifilmizle.to/yabanci-dizi-izle" to "Diziler",
        "https://dizifilmizle.to/turkce-dublaj-filmler" to "Türkçe Dublaj",
        "https://dizifilmizle.to/turkce-altyazili-filmler" to "Türkçe Altyazı",
        "https://dizifilmizle.to/ulke/turkiye-hd" to "Yerli Filmler",
        "https://dizifilmizle.to/efsane-diziler" to "Efsane Diziler",
        "https://dizifilmizle.to/" to "Filmler"
    )

    companion object {
        private const val SUBS_MARK = "#imdb="
        private val BE_PLAYER = Regex("""bePlayer\('([^']+)'\s*,\s*'(\{.*?\})'""", RegexOption.DOT_MATCHES_ALL)
        private val MEDIA = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""")
        private val SUBS = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)?""")
        private val REL_SUBS = Regex("""["']([^"'\s<>]*/[^"'\s<>]*?\.(?:vtt|srt)(?:\?[^"'\s<>]*)?)["']""")
        private val TRACK = Regex("""<track\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val VIDMIXI_DIR = Regex("""/upload/videos/\d{4}/\d{2}/[0-9a-f]{32}/""")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, headers = browserHeaders).document
        return newHomePageResponse(request.name, cards(doc), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = runCatching {
            app.get("$mainUrl/api/search", params = mapOf("q" to query.trim()), headers = browserHeaders).text
        }.getOrNull() ?: return emptyList()
        val results = runCatching { JSONObject(response).optJSONArray("results") }.getOrNull()
            ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("slug").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kind = item.optString("content_type")
            if (kind == "actor" || kind == "director") return@mapNotNull null
            val isSeries = kind == "series"
            val href = if (isSeries) "$mainUrl/dizi/$slug" else "$mainUrl/film/$slug"
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = item.optString("poster_url").takeIf { it.startsWith("http") }
                    this.year = item.optInt("start_year").takeIf { it > 1900 }
                    this.score = Score.from10(item.optString("imdb_rating"))
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = item.optString("poster_url").takeIf { it.startsWith("http") }
                    this.year = item.optInt("start_year").takeIf { it > 1900 }
                    this.score = Score.from10(item.optString("imdb_rating"))
                }
            }
        }
    }

    private fun cards(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/dizi/", "/film/", "/serifilm/").none { path.startsWith(it) }) return@mapNotNull null
        if (path == "/" || path.contains("login") || path.contains("uye-ol") ||
            path.contains("/giris") || href.contains("?ara=") || href.contains("sirala=")) return@mapNotNull null
        val img = card.selectFirst("img") ?: card.parent()?.selectFirst("img") ?: return@mapNotNull null
        val title = card.attr("title").trim()
            .ifBlank { card.text().trim() }
            .ifBlank { img.attr("alt").trim() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val hrefLower = href.lowercase()
        val isSeries = hrefLower.contains("/dizi/") || hrefLower.contains("/seri/") ||
            hrefLower.contains("/anime/") || hrefLower.contains("/series/")
        if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        }
    }.distinctBy { it.url }

    // ───────────────────────── TMDB ─────────────────────────

    private suspend fun tmdbGet(
        path: String,
        extra: Map<String, String> = emptyMap(),
        language: String = "tr-TR"
    ): JSONObject? = runCatching {
        JSONObject(
            app.get(
                "$tmdbApi$path",
                params = mapOf("api_key" to tmdbKey, "language" to language) + extra
            ).text
        )
    }.getOrNull()

    private fun pickBest(results: JSONArray, query: String, year: Int?, dateKey: String): JSONObject? {
        val q = normalizeName(query)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            var score = 0
            val names = listOfNotNull(r.str("title"), r.str("name"), r.str("original_title"), r.str("original_name"))
            if (names.none { normalizeName(it) == q }) continue
            score += 10
            if (year != null && r.str(dateKey)?.startsWith(year.toString()) == true) score += 5
            if (score > bestScore) {
                best = r
                bestScore = score
            }
        }
        return best
    }

    private suspend fun tmdbSearch(type: String, title: String, year: Int?): JSONObject? {
        val yearKey = if (type == "tv") "first_air_date_year" else "year"
        val dateKey = if (type == "tv") "first_air_date" else "release_date"
        val variants: List<Int?> = if (year != null) listOf(year, null) else listOf(null)
        for (q in listOf(title, asciiFold(title)).distinct()) {
            for (y in variants) {
                val params = mutableMapOf("query" to q, "include_adult" to "false")
                if (y != null) params[yearKey] = y.toString()
                val results = tmdbGet("/search/$type", params)?.optJSONArray("results") ?: continue
                if (results.length() == 0) continue
                val best = pickBest(results, q, year, dateKey)
                if (best != null) return best
            }
        }
        return null
    }

    private suspend fun tmdbFindByImdb(imdb: String, type: String): Int? {
        val res = tmdbGet("/find/$imdb", mapOf("external_source" to "imdb_id")) ?: return null
        val arr = res.optJSONArray(if (type == "tv") "tv_results" else "movie_results") ?: return null
        return arr.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private suspend fun tmdbLookup(
        title: String,
        year: Int?,
        isSeries: Boolean,
        imdbId: String? = null,
        imdbTrusted: Boolean = false
    ): JSONObject? {
        if (!tmdbEnabled) return null
        val type = if (isSeries) "tv" else "movie"
        val dateKey = if (isSeries) "first_air_date" else "release_date"
        var id = 0
        if (imdbId != null) {
            id = tmdbFindByImdb(imdbId, type) ?: 0
        }
        if (id == 0) {
            val found = tmdbSearch(type, title, year) ?: return null
            id = found.optInt("id").takeIf { it > 0 } ?: return null
        }
        val details = tmdbGet("/$type/$id", mapOf("append_to_response" to "credits,external_ids")) ?: return null
        if (details.optInt("id") == 0) return null
        // Sayfadan zayıf yolla bulunan IMDb ID'si yanlış filme ait olabilir: ad veya yıl tutmalı.
        if (imdbId != null && !imdbTrusted) {
            val q = normalizeName(title)
            val names = listOfNotNull(details.str("title"), details.str("name"),
                details.str("original_title"), details.str("original_name"))
            val nameOk = names.any { normalizeName(it) == q }
            val yearOk = year != null && details.str(dateKey)?.startsWith(year.toString()) == true
            if (!nameOk && !yearOk) return null
        }
        if (details.str("overview") == null) {
            tmdbGet("/$type/$id", emptyMap(), "en-US")?.str("overview")?.let { details.put("overview", it) }
        }
        return details
    }

    private suspend fun tmdbSeason(id: Int, season: Int): Map<Int, JSONObject> {
        val arr = tmdbGet("/tv/$id/season/$season")?.optJSONArray("episodes") ?: return emptyMap()
        val map = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            map[e.optInt("episode_number")] = e
        }
        if (map.values.any { it.str("overview") == null }) {
            val en = tmdbGet("/tv/$id/season/$season", emptyMap(), "en-US")?.optJSONArray("episodes")
            if (en != null) {
                for (i in 0 until en.length()) {
                    val e = en.optJSONObject(i) ?: continue
                    val target = map[e.optInt("episode_number")] ?: continue
                    val overview = e.str("overview") ?: continue
                    if (target.str("overview") == null) target.put("overview", overview)
                }
            }
        }
        return map
    }

    private fun tmdbGenres(tmdb: JSONObject?): List<String> {
        val arr = tmdb?.optJSONArray("genres") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.str("name") }
    }

    private fun tmdbActors(tmdb: JSONObject?): List<ActorData>? {
        val cast = tmdb?.optJSONObject("credits")?.optJSONArray("cast") ?: return null
        return (0 until minOf(cast.length(), 20)).mapNotNull { i ->
            val c = cast.optJSONObject(i) ?: return@mapNotNull null
            val actorName = c.str("name") ?: return@mapNotNull null
            ActorData(
                Actor(actorName, tmdbImage(c.str("profile_path"), "w185")),
                roleString = c.str("character")
            )
        }.ifEmpty { null }
    }

    private fun tmdbImdbId(tmdb: JSONObject?): String? =
        (tmdb?.optJSONObject("external_ids")?.str("imdb_id") ?: tmdb?.str("imdb_id"))
            ?.takeIf { it.startsWith("tt") }

    // ───────────────────────── Detay sayfası ─────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = browserHeaders)
        if (resp.code == 404) throw ErrorLoadingException("$name: içerik bulunamadı.")
        val doc = resp.document
        val raw = resp.text
        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val title = cleanTitle(rawTitle).ifBlank { throw ErrorLoadingException("$name: başlık okunamadı") }
        val titleYear = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.jsonLdDescription()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val genres = doc.select("[itemprop=genre], a[href*='/tur/'], a[href*='/kategori/']")
            .mapNotNull { it.attr("content").ifBlank { it.text() }.trim().takeIf(String::isNotBlank) }
            .distinct().take(8)
        val year = titleYear
            ?: Regex(""""(?:datePublished|dateCreated|releaseDate|release_date|year)"\s*:\s*"?(\d{4})""")
                .find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val siteScore = doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
            ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        val strongImdb = Regex("""imdb\.com/title/(tt\d{7,9})""").find(raw)?.groupValues?.get(1)
            ?: Regex(""""imdb_?[iI][dD]"\s*:\s*"(tt\d{7,9})"""").find(raw)?.groupValues?.get(1)
        val rawImdb = strongImdb ?: Regex("""tt\d{7,9}""").find(raw)?.value
        val isFilmPage = url.substringBefore('?').contains("/film/")
        val pageSlug = url.substringBefore('?').trimEnd('/').substringAfterLast('/')

        val allSiteEpisodes = if (isFilmPage) emptyList() else doc.select("a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (!Regex("""(?:bolum|episode)[-_/]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
            val season = Regex("""(?:sezon|season)[-_/]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val number = Regex("""(?:sezon|season)[-_/]?(\d+).*?(?:bolum|episode)[-_/]?([0-9]*)""", RegexOption.IGNORE_CASE)
                .find(path)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_/]?(\d+)""", RegexOption.IGNORE_CASE).find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""", RegexOption.IGNORE_CASE).find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            SiteEpisode(href, anchor.text().trim().ifBlank { "Bölüm $number" }, season, number)
        }.distinctBy { it.href }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        // Yan listelerdeki ("son eklenen bölümler" vb.) başka dizilerin bölümlerini ele
        val siteEpisodes = allSiteEpisodes.filter { it.href.contains(pageSlug, ignoreCase = true) }
            .ifEmpty { allSiteEpisodes }

        val isDiziUrl = !isFilmPage && url.substringBefore('?').contains("/dizi/")
        if (!isFilmPage && (siteEpisodes.isNotEmpty() || isDiziUrl)) {
            val tmdb = tmdbLookup(title, year, true, rawImdb, strongImdb != null)
            val tmdbId = tmdb?.optInt("id") ?: 0
            val imdbId = tmdbImdbId(tmdb) ?: strongImdb
            val seasonCache = HashMap<Int, Map<Int, JSONObject>>()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            // Sayfada yalnızca ilk sezon görünüyorsa diğer sezon/bölümleri TMDB'den tamamla
            // (adres kalıbı: /dizi/<slug>/sezon-<s>/bolum-<b>).
            val merged = LinkedHashMap<String, SiteEpisode>()
            for (e in siteEpisodes) merged["${e.season ?: 1}-${e.episode ?: e.href}"] = e
            if (tmdbId > 0 && isDiziUrl) {
                val seasonArr = tmdb?.optJSONArray("seasons")
                val seasonNums = (0 until (seasonArr?.length() ?: 0)).mapNotNull { i ->
                    seasonArr?.optJSONObject(i)?.optInt("season_number")?.takeIf { it > 0 }
                }.ifEmpty { listOf(1) }
                for (sNo in seasonNums) {
                    val map = seasonCache.getOrPut(sNo) { tmdbSeason(tmdbId, sNo) }
                    for ((eNo, info) in map) {
                        val key = "$sNo-$eNo"
                        if (merged.containsKey(key)) continue
                        merged[key] = SiteEpisode(
                            "$mainUrl/dizi/$pageSlug/sezon-$sNo/bolum-$eNo",
                            info.str("name") ?: "Bölüm $eNo",
                            sNo,
                            eNo
                        )
                    }
                }
            }
            val allEpisodes = merged.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            val episodes = allEpisodes.mapNotNull { ep ->
                val info = if (tmdbId > 0 && ep.episode != null) {
                    val s = ep.season ?: 1
                    val map = seasonCache.getOrPut(s) { tmdbSeason(tmdbId, s) }
                    map[ep.episode]
                } else null
                val airDate = info?.str("air_date")
                if (airDate != null && airDate > today) return@mapNotNull null
                val subsTag = if (imdbId != null && ep.episode != null) {
                    "$SUBS_MARK$imdbId&s=${ep.season ?: 1}&e=${ep.episode}"
                } else ""
                newEpisode(ep.href + subsTag) {
                    this.name = info?.str("name") ?: ep.name
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = tmdbImage(info?.str("still_path"), "w500")
                    this.description = info?.str("overview")
                    this.runTime = info?.optInt("runtime")?.takeIf { it > 0 }
                    if (airDate != null) addDate(airDate)
                }
            }

            val tPoster = tmdbImage(tmdb?.str("poster_path"), "w500") ?: poster
            val tBackdrop = tmdbImage(tmdb?.str("backdrop_path"), "w1280") ?: tPoster
            val tGenres = tmdbGenres(tmdb)
            val tScore = tmdb?.optDouble("vote_average")?.takeIf { it > 0 }?.toString() ?: siteScore
            val tYear = tmdb?.str("first_air_date")?.take(4)?.toIntOrNull() ?: year
            val tActors = tmdbActors(tmdb)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = tPoster
                this.backgroundPosterUrl = tBackdrop
                this.plot = tmdb?.str("overview") ?: plot
                this.year = tYear
                this.score = Score.from10(tScore)
                this.tags = tGenres.ifEmpty { genres }.ifEmpty { null }
                if (tActors != null) this.actors = tActors
            }
        }

        val tmdb = tmdbLookup(title, year, false, rawImdb, strongImdb != null)
        val imdbId = tmdbImdbId(tmdb) ?: strongImdb
        val tPoster = tmdbImage(tmdb?.str("poster_path"), "w500") ?: poster
        val tBackdrop = tmdbImage(tmdb?.str("backdrop_path"), "w1280") ?: tPoster
        val tGenres = tmdbGenres(tmdb)
        val tScore = tmdb?.optDouble("vote_average")?.takeIf { it > 0 }?.toString() ?: siteScore
        val tYear = tmdb?.str("release_date")?.take(4)?.toIntOrNull() ?: year
        val tActors = tmdbActors(tmdb)
        val runtime = tmdb?.optInt("runtime")?.takeIf { it > 0 }
        val movieData = if (imdbId != null) "$url$SUBS_MARK$imdbId" else url

        return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
            this.posterUrl = tPoster
            this.backgroundPosterUrl = tBackdrop
            this.plot = tmdb?.str("overview") ?: plot
            this.year = tYear
            this.score = Score.from10(tScore)
            this.tags = tGenres.ifEmpty { genres }.ifEmpty { null }
            this.duration = runtime
            if (tActors != null) this.actors = tActors
        }
    }

    // ───────────────────────── Altyazı ─────────────────────────

    // Sayfa / oynatıcı metni içindeki <track>, mutlak ve göreli .vtt/.srt adreslerini toplar.
    private suspend fun scanSubs(
        rawText: String,
        base: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        val text = rawText.replace("\\/", "/")
        for (tagMatch in TRACK.findAll(text)) {
            val tag = tagMatch.value
            val src = htmlAttr(tag, "src") ?: continue
            val kind = htmlAttr(tag, "kind")
            if (kind != null && !kind.equals("subtitles", true) && !kind.equals("captions", true)) continue
            val url = absUrl(src, base) ?: continue
            if (!seen.add(url)) continue
            val hint = (htmlAttr(tag, "srclang") ?: "") + " " + (htmlAttr(tag, "label") ?: "") + " " + url
            callback.invoke(newSubtitleFile(subLang(hint), url) { this.headers = subHeaders(base) })
        }
        for (m in SUBS.findAll(text)) {
            val url = m.value
            if (!seen.add(url)) continue
            val from = maxOf(0, m.range.first - 80)
            val to = minOf(text.length, m.range.last + 80)
            callback.invoke(newSubtitleFile(subLang(text.substring(from, to) + " " + url), url) { this.headers = subHeaders(base) })
        }
        for (m in REL_SUBS.findAll(text)) {
            val rel = m.groupValues[1]
            if (rel.startsWith("http")) continue
            val url = absUrl(rel, base) ?: continue
            if (!seen.add(url)) continue
            val from = maxOf(0, m.range.first - 80)
            val to = minOf(text.length, m.range.last + 80)
            callback.invoke(newSubtitleFile(subLang(text.substring(from, to) + " " + url), url) { this.headers = subHeaders(base) })
        }
    }

    // Vidmixi: /upload/videos/YYYY/MM/<hash>/altyazi/tur_sub.vtt (+ eng_sub.vtt)
    private suspend fun vidmixiSubs(
        embed: String,
        sources: List<String>,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        val dir = sources.firstNotNullOfOrNull { VIDMIXI_DIR.find(it.replace("\\/", "/"))?.value } ?: return
        val origin = runCatching {
            val uri = URI(embed)
            "${uri.scheme}://${uri.host}"
        }.getOrNull() ?: return
        for ((file, lang) in listOf("tur_sub.vtt" to "tr", "eng_sub.vtt" to "en")) {
            val url = "$origin${dir}altyazi/$file"
            if (url in seen) continue
            val ok = runCatching {
                val r = app.get(url, headers = browserHeaders, referer = embed)
                r.code == 200 && r.text.take(20).contains("WEBVTT")
            }.getOrDefault(false)
            if (ok && seen.add(url)) {
                callback.invoke(newSubtitleFile(lang, url) { this.headers = subHeaders(embed) })
            }
        }
    }

    // Sitede Türkçe altyazı bulunamazsa devreye giren dış kaynaklar (IMDb ID ile).
    private suspend fun externalSubs(
        imdb: String,
        season: Int?,
        episode: Int?,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        val isSeries = season != null && episode != null

        // OpenSubtitles (Stremio eklentisi)
        runCatching {
            val path = if (isSeries) "series/$imdb:$season:$episode" else "movie/$imdb"
            val body = app.get("https://opensubtitles-v3.strem.io/subtitles/$path.json", headers = browserHeaders).text
            val arr = JSONObject(body).optJSONArray("subtitles")
            var count = 0
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    if (count >= 5) break
                    val o = arr.optJSONObject(i) ?: continue
                    val lang = o.optString("lang").lowercase()
                    if (lang != "tur" && lang != "tr" && lang != "tr-tr") continue
                    val url = o.str("url") ?: continue
                    if (!seen.add(url)) continue
                    count++
                    callback.invoke(newSubtitleFile("tr", url))
                }
            }
        }

        // Wyzie Subs
        runCatching {
            val params = mutableMapOf("id" to imdb, "language" to "tr")
            if (isSeries) {
                params["season"] = season.toString()
                params["episode"] = episode.toString()
            }
            val body = app.get("https://sub.wyzie.ru/search", params = params, headers = browserHeaders).text
            val arr = JSONArray(body)
            var count = 0
            for (i in 0 until arr.length()) {
                if (count >= 5) break
                val o = arr.optJSONObject(i) ?: continue
                val url = o.str("url") ?: continue
                if (!seen.add(url)) continue
                count++
                callback.invoke(newSubtitleFile("tr", url))
            }
        }
    }

    // ───────────────────────── Linkler ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = data.substringBefore(SUBS_MARK)
        val meta = data.substringAfter(SUBS_MARK, "")
        val imdbId = meta.substringBefore('&').takeIf { it.startsWith("tt") }
        val subSeason = Regex("""(?:^|&)s=(\d+)""").find(meta)?.groupValues?.get(1)?.toIntOrNull()
        val subEpisode = Regex("""(?:^|&)e=(\d+)""").find(meta)?.groupValues?.get(1)?.toIntOrNull()

        val resp = app.get(pageUrl, headers = browserHeaders)
        val text = resp.text.replace("\\\\/", "/").replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        var siteTurkish = false
        val seen = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()

        val subCb: (SubtitleFile) -> Unit = { sub ->
            if (isTurkish(sub.lang)) siteTurkish = true
            subtitleCallback.invoke(sub)
        }

        suspend fun emit(stream: String, label: String, referer: String) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val type = if (stream.contains(".m3u8") || referer.contains("vidmixi") ||
                referer.contains("dosyaload")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            // Ana m3u8 doğrudan açılamıyorsa (BANDWIDTH > Int.MAX veya ayrı ses listesi) düzeltilmiş
            // ana listeyi (ses parçalarıyla birlikte) yerel sunucudan veriyoruz.
            var directQuality = getQualityFromName(label)
            var directName = label
            if (type == ExtractorLinkType.M3U8) {
                val fixed = specialMaster(stream, referer)
                if (fixed != null) {
                    if (fixed.proxyUrl != null) {
                        val pName = if (fixed.maxHeight > 0) "$label ${fixed.maxHeight}p" else label
                        val pQuality = if (fixed.maxHeight > 0) fixed.maxHeight else directQuality
                        callback.invoke(newExtractorLink(name, pName, fixed.proxyUrl, ExtractorLinkType.M3U8) {
                            this.referer = referer
                            this.headers = browserHeaders
                            this.quality = pQuality
                        })
                        found = true
                    }
                    if (fixed.oversized) {
                        // Doğrudan ana liste çöker: yedek olarak alt listeleri ver
                        for ((vUrl, vLabel) in fixed.variants) {
                            if (!seen.add(vUrl)) continue
                            callback.invoke(newExtractorLink(name, "$label $vLabel (yedek)".trim(), vUrl, ExtractorLinkType.M3U8) {
                                this.referer = referer
                                this.headers = browserHeaders
                                this.quality = 0
                            })
                            found = true
                        }
                        return
                    }
                    // Doğrudan ana liste çalışabilir: yedek olarak düşük öncelikle ekle
                    if (fixed.proxyUrl != null) {
                        directQuality = 0
                        directName = "$label (yedek)"
                    }
                }
            }

            val quality = directQuality
            callback.invoke(newExtractorLink(name, directName, stream, type) {
                this.referer = referer
                this.headers = browserHeaders
                this.quality = quality
            })
            found = true
        }

        // Sayfadaki altyazılar
        scanSubs(text, pageUrl, seenSubs, subCb)

        val allEmbeds = Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""")
            .findAll(text).map { it.value }.filter { it != pageUrl }.toList()
        val currentEpisode = Regex("""/bolum-(\d+)""").find(pageUrl)?.groupValues?.get(1)?.toIntOrNull()
        val embeds = currentEpisode?.let { number -> allEmbeds.getOrNull(number - 1)?.let { setOf(it) } }
            ?: allEmbeds.toSet()
        for (embed in embeds) {
            val player = runCatching {
                app.get(embed, headers = browserHeaders, referer = pageUrl).text
            }.getOrNull() ?: continue
            scanSubs(player, embed, seenSubs, subCb)
            val clear = decryptBePlayer(player)
            val video = clear?.let {
                runCatching { JSONObject(it).optString("video_location").trim() }.getOrNull()
            }
            if (clear != null) scanSubs(clear, embed, seenSubs, subCb)
            vidmixiSubs(embed, listOfNotNull(video, clear, player, text), seenSubs, subCb)
            if (video != null && video.startsWith("http")) emit(video, "Vidmixi", embed)
        }

        for (m in MEDIA.findAll(text)) emit(m.value, "Direct", pageUrl)
        val frames = resp.document.select("iframe").mapNotNull {
            val src = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
            fixUrlNull(src)
        }.distinct()
        for (frame in frames) {
            if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                emit(frame, "Embed", pageUrl)
                continue
            }
            val inner = runCatching { app.get(frame, headers = browserHeaders, referer = pageUrl).text }.getOrNull()
            if (inner != null) {
                val innerFixed = inner.replace("\\/", "/")
                for (m in MEDIA.findAll(innerFixed)) emit(m.value, "Embed", frame)
                scanSubs(innerFixed, frame, seenSubs, subCb)
            }
            if (loadExtractor(frame, "$mainUrl/", subCb, callback)) found = true
        }

        // Sitede Türkçe altyazı yoksa dış kaynaklardan ara (IMDb ID ile)
        if (!siteTurkish) {
            val imdb = imdbId ?: Regex("""tt\d{7,9}""").find(text)?.value
            if (imdb != null) {
                externalSubs(imdb, subSeason, subEpisode, seenSubs, subtitleCallback)
            }
        }

        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok")
        return true
    }

    // Ana m3u8'i indirip inceler. BANDWIDTH değeri Int.MAX_VALUE'dan büyükse (ExoPlayer çöker) ya da
    // ayrı ses listeleri (EXT-X-MEDIA TYPE=AUDIO URI=...) varsa düzeltilmiş kopyasını yerel sunucudan verir.
    private suspend fun specialMaster(master: String, referer: String): OversizedMaster? {
        val body = runCatching {
            app.get(master, headers = browserHeaders, referer = referer).text
        }.getOrNull() ?: return null
        if (!body.contains("#EXT-X-STREAM-INF")) return null
        val tooBig = BANDWIDTH_RE.findAll(body)
            .any { (it.groupValues[1].toLongOrNull() ?: 0L) > Int.MAX_VALUE.toLong() }
        val lines = body.lines().map { it.trim() }
        val hasAudioList = lines.any {
            it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") && it.contains("URI=\"")
        }
        if (!tooBig && !hasAudioList) return null

        val fixedLines = ArrayList<String>()
        val variants = mutableListOf<Pair<String, String>>()
        var maxHeight = 0
        var pendingVariantHeight: Int? = null
        for (line in lines) {
            when {
                line.isEmpty() -> fixedLines.add(line)
                line.startsWith("#") -> {
                    var l = BANDWIDTH_RE.replace(line) { m ->
                        val v = m.groupValues[1].toLongOrNull() ?: 0L
                        m.value.substringBefore('=') + "=" + (if (v > Int.MAX_VALUE.toLong()) 2000000000L else v)
                    }
                    val isAudio = l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")
                    l = URI_ATTR_RE.replace(l) { m ->
                        val abs = absUrl(m.groupValues[1], master) ?: m.groupValues[1]
                        val target = if (isAudio) LocalHlsServer.proxyPlaylist(abs, referer) ?: abs else abs
                        "URI=\"" + target + "\""
                    }
                    if (l.startsWith("#EXT-X-STREAM-INF")) {
                        pendingVariantHeight = Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull()
                        if (pendingVariantHeight != null && pendingVariantHeight > maxHeight) maxHeight = pendingVariantHeight
                    }
                    fixedLines.add(l)
                }
                else -> {
                    val abs = absUrl(line, master) ?: line
                    variants.add(abs to (pendingVariantHeight?.let { "${it}p" } ?: ""))
                    pendingVariantHeight = null
                    fixedLines.add(LocalHlsServer.proxyPlaylist(abs, referer) ?: abs)
                }
            }
        }
        val proxy = LocalHlsServer.publish(fixedLines.joinToString("\n"))
        return OversizedMaster(proxy, maxHeight, variants, tooBig)
    }

    private fun decryptBePlayer(html: String): String? = runCatching {
        val match = BE_PLAYER.find(html) ?: return@runCatching null
        val params = JSONObject(match.groupValues[2])
        val cipherText = Base64.getDecoder().decode(params.optString("ct"))
        evpMd5AesCbcDecrypt(cipherText, match.groupValues[1], params.optString("s"), params.optString("iv"))
    }.getOrNull()
}
