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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ───────────────────────── Yardımcılar ─────────────────────────

private val browserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
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

private data class SiteEpisode(
    val href: String,
    val name: String,
    val season: Int?,
    val episode: Int?
)

// ───────────────────────── Eklenti ─────────────────────────

class DiziFilmIzle : MainAPI() {

    // TMDB v3 API anahtarını buraya yaz. Boş/varsayılan kalırsa TMDB özelliği kapalı olur.
    private val tmdbKey = "BURAYA_TMDB_API_KEY"
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
        "https://dizifilmizle.to/seriler" to "Seri Filmler",
        "https://dizifilmizle.to/efsane-diziler" to "Efsane Diziler",
        "https://dizifilmizle.to/" to "Filmler"
    )

    companion object {
        private val BE_PLAYER = Regex("""bePlayer\('([^']+)'\s*,\s*'(\{.*?\})'""", RegexOption.DOT_MATCHES_ALL)
        private val MEDIA = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""")
        private val SUBS = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""")
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
            if (names.any { normalizeName(it) == q }) score += 10
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
                return pickBest(results, q, year, dateKey)
            }
        }
        return null
    }

    private suspend fun tmdbLookup(title: String, year: Int?, isSeries: Boolean): JSONObject? {
        if (!tmdbEnabled) return null
        val type = if (isSeries) "tv" else "movie"
        val found = tmdbSearch(type, title, year) ?: return null
        val id = found.optInt("id").takeIf { it > 0 } ?: return null
        val details = tmdbGet("/$type/$id", mapOf("append_to_response" to "credits")) ?: return null
        if (details.optInt("id") == 0) return null
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
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val siteScore = doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
            ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)

        val siteEpisodes = doc.select("a[href]").mapNotNull { anchor ->
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

        if (siteEpisodes.isNotEmpty()) {
            val tmdb = tmdbLookup(title, titleYear, true)
            val tmdbId = tmdb?.optInt("id") ?: 0
            val seasonCache = HashMap<Int, Map<Int, JSONObject>>()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            val episodes = siteEpisodes.mapNotNull { ep ->
                val info = if (tmdbId > 0 && ep.episode != null) {
                    val s = ep.season ?: 1
                    val map = seasonCache.getOrPut(s) { tmdbSeason(tmdbId, s) }
                    map[ep.episode]
                } else null
                val airDate = info?.str("air_date")
                if (airDate != null && airDate > today) return@mapNotNull null
                newEpisode(ep.href) {
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

        val tmdb = tmdbLookup(title, titleYear, false)
        val tPoster = tmdbImage(tmdb?.str("poster_path"), "w500") ?: poster
        val tBackdrop = tmdbImage(tmdb?.str("backdrop_path"), "w1280") ?: tPoster
        val tGenres = tmdbGenres(tmdb)
        val tScore = tmdb?.optDouble("vote_average")?.takeIf { it > 0 }?.toString() ?: siteScore
        val tYear = tmdb?.str("release_date")?.take(4)?.toIntOrNull() ?: year
        val tActors = tmdbActors(tmdb)
        val runtime = tmdb?.optInt("runtime")?.takeIf { it > 0 }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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

    // ───────────────────────── Linkler ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = app.get(data, headers = browserHeaders)
        val text = resp.text.replace("\\\\/", "/").replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        val seen = mutableSetOf<String>()

        suspend fun emit(stream: String, label: String, referer: String) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val type = if (stream.contains(".m3u8") || referer.contains("vidmixi") ||
                referer.contains("dosyaload")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = getQualityFromName(label)
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.headers = browserHeaders
                this.quality = quality
            })
            found = true
        }

        val allEmbeds = Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""")
            .findAll(text).map { it.value }.filter { it != data }.toList()
        val currentEpisode = Regex("""/bolum-(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val embeds = currentEpisode?.let { number -> allEmbeds.getOrNull(number - 1)?.let { setOf(it) } }
            ?: allEmbeds.toSet()
        for (embed in embeds) {
            val player = runCatching {
                app.get(embed, headers = browserHeaders, referer = data).text
            }.getOrNull() ?: continue
            decryptBePlayer(player)?.let { emit(it, "Vidmixi", embed) }
        }

        for (m in MEDIA.findAll(text)) emit(m.value, "Direct", data)
        val frames = resp.document.select("iframe").mapNotNull {
            val src = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
            fixUrlNull(src)
        }.distinct()
        for (frame in frames) {
            if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                emit(frame, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(frame, headers = browserHeaders, referer = data).text }.getOrNull()
            if (inner != null) {
                for (m in MEDIA.findAll(inner.replace("\\/", "/"))) emit(m.value, "Embed", frame)
            }
            if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
        }

        for (m in SUBS.findAll(text)) subtitleCallback.invoke(SubtitleFile("tr", m.value))
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok")
        return true
    }

    private fun decryptBePlayer(html: String): String? = runCatching {
        val match = BE_PLAYER.find(html) ?: return@runCatching null
        val params = JSONObject(match.groupValues[2])
        val cipherText = Base64.getDecoder().decode(params.optString("ct"))
        val clear = evpMd5AesCbcDecrypt(cipherText, match.groupValues[1], params.optString("s"), params.optString("iv"))
        JSONObject(clear).optString("video_location").trim().takeIf { it.startsWith("http") }
    }.getOrNull()
}
