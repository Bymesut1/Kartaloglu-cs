package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmMakinesiCafe : MainAPI() {
    override var mainUrl = "https://filmakinesi.cafe"
    override var name = "FilmMakinesiCafe"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        // https://www.themoviedb.org/settings/api adresinden aldığın v3 API Key'i yaz
        private const val TMDB_API_KEY = "000316508321ce461cf81e7c6815eec7"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
        private const val TAG = "FMC"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmler?sirala=yeni" to "Son Eklenen Filmler",
        "$mainUrl/diziler?sirala=yeni" to "Son Eklenen Diziler",
        "$mainUrl/animes" to "Animeler",
        "$mainUrl/trend" to "Trend"
    )

    // ------------------------------------------------------------------
    // ANA SAYFA / ARAMA  (burada TMDB yok, sadece site verisi)
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val doc = app.get("${request.data}${sep}page=$page", headers = mapOf("User-Agent" to UA)).document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/ara?q=$q", headers = mapOf("User-Agent" to UA)).document
        return parseCards(doc)
    }

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("a[href*=/film/], a[href*=/dizi/], a[href*=/anime/]")
            .filter { !it.attr("href").contains("/bolum-") && !it.attr("href").contains("/oyuncu/") }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href"))?.substringBefore("?") ?: return null
        val img = this.selectFirst("img")
        val title = (this.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.cleanTitle()?.takeIf { it.isNotBlank() })
            ?: return null
        val poster = fixUrlNull(img?.attr("src"))
        return when {
            href.contains("/film/") -> newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            href.contains("/anime/") -> newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    // ------------------------------------------------------------------
    // DETAY SAYFASI  (TMDB: poster, açıklama, bölüm görselleri/açıklamaları)
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val isMovie = url.contains("/film/")
        val isAnime = url.contains("/anime/")
        val baseUrl = (if (isMovie) url else url.substringBefore("/sezon-")).substringBefore("?").trimEnd('/')

        val doc = app.get(baseUrl, headers = mapOf("User-Agent" to UA)).document

        val siteTitle = (doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null).cleanTitle()
        val siteYear = doc.select("a, span, time").asSequence()
            .map { it.text().trim() }
            .firstOrNull { Regex("""^(19|20)\d{2}$""").matches(it) }
            ?.toIntOrNull()
        val sitePoster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val sitePlot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val siteTags = doc.select("a[href*=/tur/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val tmdb = tmdbDetail(siteTitle, siteYear, isMovie)

        val title = tmdb?.title ?: siteTitle
        val poster = tmdb?.posterPath?.let { "$TMDB_IMG/w780$it" } ?: sitePoster
        val backdrop = tmdb?.backdropPath?.let { "$TMDB_IMG/w1280$it" }
        val plot = tmdb?.overview ?: sitePlot
        val year = tmdb?.date?.take(4)?.toIntOrNull() ?: siteYear
        val tags = tmdb?.genres?.takeIf { it.isNotEmpty() } ?: siteTags
        val actorList = tmdb?.cast.orEmpty().map { c ->
            ActorData(Actor(c.name, c.profile?.let { "$TMDB_IMG/w185$it" }), roleString = c.character)
        }
        val trailer = tmdb?.trailerKey

        if (isMovie) {
            return newMovieLoadResponse(title, baseUrl, TvType.Movie, baseUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                if (actorList.isNotEmpty()) this.actors = actorList
                if (trailer != null) addTrailer("https://www.youtube.com/watch?v=$trailer")
            }
        }

        // --- Bölümler: önce sitedeki linkler, yoksa TMDB sezon listesinden üret ---
        val epRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")
        var eps: List<Triple<Int, Int, String>> = doc.select("a[href*=/sezon-]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href"))?.substringBefore("?") ?: return@mapNotNull null
            if (href.substringBefore("/sezon-").trimEnd('/') != baseUrl) return@mapNotNull null
            val m = epRegex.find(href) ?: return@mapNotNull null
            Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), href)
        }.distinctBy { it.third }

        if (eps.isEmpty() && tmdb != null) {
            eps = tmdb.seasons
                .filter { it.first > 0 }
                .flatMap { (sn, count) ->
                    (1..count).map { e -> Triple(sn, e, "$baseUrl/sezon-$sn/bolum-$e") }
                }
        }

        // Her sezon için TMDB bölüm detaylarını bir kez çek
        val seasonMap = HashMap<Int, Map<Int, TmdbEpisode>>()
        if (tmdb != null) {
            eps.map { it.first }.distinct().forEach { s ->
                tmdbSeason(tmdb.id, s)?.let { seasonMap[s] = it }
            }
        }

        val episodes = eps.sortedWith(compareBy({ it.first }, { it.second })).map { (s, e, href) ->
            val t = seasonMap[s]?.get(e)
            newEpisode(href) {
                this.name = t?.name ?: "$e. Bölüm"
                this.season = s
                this.episode = e
                this.posterUrl = t?.still?.let { "$TMDB_IMG/w780$it" }
                this.description = t?.overview
                addDate(t?.airDate)
            }
        }

        return newTvSeriesLoadResponse(
            title, baseUrl, if (isAnime) TvType.Anime else TvType.TvSeries, episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
            if (actorList.isNotEmpty()) this.actors = actorList
            if (trailer != null) addTrailer("https://www.youtube.com/watch?v=$trailer")
        }
    }

    // ------------------------------------------------------------------
    // TMDB  (org.json ile, ek kütüphane gerektirmez)
    // ------------------------------------------------------------------
    private data class TmdbCast(val name: String, val character: String?, val profile: String?)

    private data class TmdbEpisode(
        val number: Int,
        val name: String?,
        val overview: String?,
        val still: String?,
        val airDate: String?
    )

    private data class TmdbDetail(
        val id: Int,
        val title: String?,
        val overview: String?,
        val posterPath: String?,
        val backdropPath: String?,
        val date: String?,
        val genres: List<String>,
        val cast: List<TmdbCast>,
        val trailerKey: String?,
        val seasons: List<Pair<Int, Int>> // (sezon no, bölüm sayısı)
    )

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }

    private suspend fun tmdbDetail(title: String, year: Int?, isMovie: Boolean): TmdbDetail? {
        if (TMDB_API_KEY.startsWith("BURAYA")) return null
        return try {
            val kind = if (isMovie) "movie" else "tv"
            val q = URLEncoder.encode(title, "UTF-8")

            suspend fun search(withYear: Boolean): JSONArray {
                val y = if (withYear && year != null)
                    (if (isMovie) "&year=$year" else "&first_air_date_year=$year") else ""
                val txt = app.get("$TMDB_API/search/$kind?api_key=$TMDB_API_KEY&language=tr-TR&query=$q$y").text
                return JSONObject(txt).optJSONArray("results") ?: JSONArray()
            }

            var results = search(true)
            if (results.length() == 0) results = search(false)
            if (results.length() == 0) return null

            var pick = results.getJSONObject(0)
            if (year != null) {
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val d = r.str("release_date") ?: r.str("first_air_date")
                    if (d?.take(4)?.toIntOrNull() == year) {
                        pick = r
                        break
                    }
                }
            }
            val id = pick.optInt("id", -1)
            if (id < 0) return null

            val d = JSONObject(
                app.get(
                    "$TMDB_API/$kind/$id?api_key=$TMDB_API_KEY&language=tr-TR" +
                            "&append_to_response=credits,videos&include_video_language=tr,en,null"
                ).text
            )

            var overview = d.str("overview")
            if (overview == null) {
                overview = JSONObject(
                    app.get("$TMDB_API/$kind/$id?api_key=$TMDB_API_KEY&language=en-US").text
                ).str("overview")
            }

            val genres = mutableListOf<String>()
            d.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) arr.getJSONObject(i).str("name")?.let { genres.add(it) }
            }

            val cast = mutableListOf<TmdbCast>()
            d.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 15)) {
                    val c = arr.getJSONObject(i)
                    val n = c.str("name") ?: continue
                    cast.add(TmdbCast(n, c.str("character"), c.str("profile_path")))
                }
            }

            var trailer: String? = null
            d.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    if (v.str("site") == "YouTube" && v.str("type") == "Trailer") {
                        trailer = v.str("key")
                        break
                    }
                }
            }

            val seasons = mutableListOf<Pair<Int, Int>>()
            d.optJSONArray("seasons")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    seasons.add(Pair(s.optInt("season_number", 0), s.optInt("episode_count", 0)))
                }
            }

            TmdbDetail(
                id = id,
                title = d.str("title") ?: d.str("name"),
                overview = overview,
                posterPath = d.str("poster_path"),
                backdropPath = d.str("backdrop_path"),
                date = d.str("release_date") ?: d.str("first_air_date"),
                genres = genres,
                cast = cast,
                trailerKey = trailer,
                seasons = seasons
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tmdbSeason(tvId: Int, season: Int): Map<Int, TmdbEpisode>? = try {
        val txt = app.get("$TMDB_API/tv/$tvId/season/$season?api_key=$TMDB_API_KEY&language=tr-TR").text
        val arr = JSONObject(txt).optJSONArray("episodes") ?: JSONArray()
        val map = HashMap<Int, TmdbEpisode>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val n = e.optInt("episode_number", -1)
            if (n < 0) continue
            map[n] = TmdbEpisode(
                number = n,
                name = e.str("name"),
                overview = e.str("overview"),
                still = e.str("still_path"),
                airDate = e.str("air_date")
            )
        }
        map
    } catch (e: Exception) {
        null
    }

    private fun String.cleanTitle(): String =
        this.substringBefore("|")
            .replace(Regex("""\s+(Dizi|Film|Anime)?\s*[İiIı]zle\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    // ------------------------------------------------------------------
    // VİDEO LİNKLERİ
    // ------------------------------------------------------------------
    private val streamRegex = Regex("""(?:https?:)?//[^\s"'<>\\]+/api/stream\.php\?[^\s"'<>\\]+""")
    private val m3u8Regex = Regex("""(?:https?:)?//[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
    private val subRegex = Regex("""(?:https?:)?//[^\s"'<>\\]+/api/sub\.php\?[^\s"'<>\\]+""")
    private val anyUrlRegex = Regex("""(?:https?:)?//[^\s"'<>\\()]+""")
    private val relApiRegex = Regex("""["'](/?api/[^"'\s]+)["']""")
    private val staticRegex = Regex("""\.(js|css|png|jpe?g|webp|svg|ico|woff2?|gif|mp4)(\?|$)""", RegexOption.IGNORE_CASE)

    private fun normalize(s: String) = s
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun absolute(u: String, base: String): String {
        val scheme = base.substringBefore("://", "https")
        val host = base.substringAfter("://").substringBefore("/")
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "$scheme:$u"
            u.startsWith("/") -> "$scheme://$host$u"
            else -> "$scheme://$host/$u"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seen = HashSet<String>()
        val visited = HashSet<String>()
        val seriesUrl = data.substringBefore("/sezon-")

        val page = app.get(data, referer = seriesUrl, headers = mapOf("User-Agent" to UA))
        val html = normalize(page.text)
        debugDump(data, page.code, html)

        var found = crawl(html, data, 0, visited, seen, subtitleCallback, callback)
        if (!found) found = tryPilavyer(html, visited, seen, subtitleCallback, callback)

        page.document.select("track[src]").forEach {
            val src = fixUrlNull(it.attr("src")) ?: return@forEach
            subtitleCallback(SubtitleFile(it.attr("label").ifBlank { "Türkçe" }, src))
        }

        if (!found) {
            // Bilinen bir extractor'a düşebilecek iframe'ler
            page.document.select("iframe[src], iframe[data-src]").mapNotNull {
                fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
            }.filter { it.startsWith("http") }.forEach { f ->
                runCatching { loadExtractor(f, "$mainUrl/", subtitleCallback, callback) }
            }
        }
        Log.d(TAG, "RESULT found=$found links=${seen.size} visited=${visited.size}")
        return found
    }

    /** Metinde stream/sub ara; bulamazsa içindeki embed/player/api linklerini takip et. */
    private suspend fun crawl(
        text: String,
        base: String,
        depth: Int,
        visited: MutableSet<String>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to UA)
        var found = scan(text, headers, seen, subtitleCallback, callback)
        if (found || depth >= 3) return found

        val cands = LinkedHashSet<String>()

        anyUrlRegex.findAll(text).forEach { m ->
            val u = absolute(m.value.trimEnd(',', ';', '.'), base)
            val l = u.lowercase()
            val interesting = l.contains("pilavyer") || l.contains("/embed") || l.contains("/player") ||
                    l.contains("/api/") || l.contains("iframe") || l.contains("/video")
            val bad = staticRegex.containsMatchIn(l) || l.contains("themoviedb") || l.contains("google") ||
                    l.contains("/api/stream.php") || l.contains("/api/sub.php") || l.contains("schema.org") ||
                    l.contains("w3.org")
            if (interesting && !bad) cands.add(u)
        }
        relApiRegex.findAll(text).forEach { m -> cands.add(absolute(m.groupValues[1], base)) }
        runCatching {
            Jsoup.parse(text).select("iframe[src], iframe[data-src], embed[src]").forEach {
                val s = it.attr("src").ifBlank { it.attr("data-src") }
                if (s.isNotBlank() && !s.startsWith("about:")) cands.add(absolute(s, base))
            }
        }

        for (u in cands.take(8)) {
            if (!visited.add(u)) continue
            val res = runCatching {
                app.get(u, referer = base, headers = mapOf("User-Agent" to UA, "Origin" to mainUrl))
            }.getOrNull()
            if (res == null) {
                Log.d(TAG, "FETCH d=$depth FAIL $u")
                continue
            }
            val body = normalize(res.text)
            Log.d(TAG, "FETCH d=$depth code=${res.code} len=${body.length} $u")
            if (crawl(body, u, depth + 1, visited, seen, subtitleCallback, callback)) {
                found = true
                break
            } else if (body.length < 3000) {
                Log.d(TAG, "BODY $u => ${body.replace(Regex("\\s+"), " ").take(1500)}")
            }
        }
        return found
    }

    /** Sayfada <div data-pv="ID"> + pilavyerplay core.js varsa player'ı elle çözmeyi dener. */
    private suspend fun tryPilavyer(
        html: String,
        visited: MutableSet<String>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pv = Regex("""data-pv=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return false
        val coreUrl = Regex("""https?://[^"'\s]*pilavyerplay[^"'\s]*core\.js[^"'\s]*""").find(html)?.value
            ?: "https://pilavyerplay.top/assets/js/core.js"
        val hdr = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Origin" to mainUrl)
        Log.d(TAG, "PV id=$pv core=$coreUrl")

        val core = runCatching { app.get(coreUrl, headers = hdr).text }.getOrNull()
        val cands = LinkedHashSet<String>()

        if (core != null) {
            Log.d(TAG, "CORE len=${core.length}")
            // core.js'in tamamını (en fazla ~18 KB) logcat'e yaz
            core.chunked(3000).take(6).forEachIndexed { i, c ->
                Log.d(TAG, "CORE[$i] ${c.replace(Regex("\\s+"), " ")}")
            }
            val keys = listOf("/embed", "/player", "/api", "/e/", "/v/", "stream", "source", ".php", "pilavyer", "iframe")
            val lits = Regex("""["'`]([^"'`\s]{3,200})["'`]""").findAll(core)
                .map { it.groupValues[1] }
                .filter { l -> keys.any { l.contains(it, ignoreCase = true) } && !staticRegex.containsMatchIn(l) }
                .distinct().take(40).toList()
            lits.forEach { Log.d(TAG, "CORE_LIT $it") }

            lits.forEach { l ->
                if (!(l.startsWith("/") || l.startsWith("http") || l.startsWith("//"))) return@forEach
                val b = absolute(l, coreUrl)
                when {
                    b.endsWith("/") || b.endsWith("=") -> cands.add(b + pv)
                    b.contains("?") -> cands.add("$b&v=$pv")
                    else -> {
                        cands.add("$b/$pv")
                        cands.add("$b?v=$pv")
                        cands.add("$b?id=$pv")
                    }
                }
            }
        }

        // Bilinen/muhtemel kalıplar
        for (h in listOf("https://pilavyerplay.top", "https://play2.pilavyerplay.top")) {
            listOf("/embed/$pv", "/e/$pv", "/player/$pv", "/v/$pv", "/embed.php?v=$pv", "/api/source.php?v=$pv")
                .forEach { cands.add(h + it) }
        }

        for (u in cands.take(16)) {
            if (!visited.add(u)) continue
            val res = runCatching { app.get(u, headers = hdr) }.getOrNull()
            if (res == null) {
                Log.d(TAG, "PVTRY FAIL $u")
                continue
            }
            val body = normalize(res.text)
            Log.d(TAG, "PVTRY code=${res.code} len=${body.length} $u")
            if (res.code in 200..299 && crawl(body, u, 1, visited, seen, subtitleCallback, callback)) return true
            if (res.code in 200..299 && body.length in 1..2500) {
                Log.d(TAG, "PVBODY $u => ${body.replace(Regex("\\s+"), " ").take(1200)}")
            }
        }
        return false
    }

    private suspend fun scan(
        text: String,
        headers: Map<String, String>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        fun fix(u: String) = if (u.startsWith("//")) "https:$u" else u

        subRegex.findAll(text).map { fix(it.value) }.distinct().forEach { sub ->
            if (seen.add(sub)) subtitleCallback(SubtitleFile("Türkçe", sub))
        }

        val urls = (streamRegex.findAll(text) + m3u8Regex.findAll(text)).map { fix(it.value) }.distinct()
        for (u in urls) {
            if (!seen.add(u)) continue
            val quality = Regex("""stream_v(\d{3,4})""").find(u)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            val isHls = u.contains("m3u8") || runCatching {
                app.get(u, headers = headers).text.trimStart().startsWith("#EXTM3U")
            }.getOrDefault(false)

            Log.d(TAG, "LINK hls=$isHls q=$quality ${u.take(160)}")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = u,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                    this.headers = headers
                }
            )
            any = true
        }
        return any
    }

    /** Link bulunamazsa logcat'te "FMC" etiketiyle sayfanın ilgili kısımlarını gösterir. */
    private fun debugDump(url: String, code: Int, html: String) {
        try {
            Log.d(TAG, "PAGE url=$url code=$code len=${html.length}")
            val scripts = Jsoup.parse(html).select("script[src]").map { it.attr("src") }
            Log.d(TAG, "SCRIPTS ${scripts.joinToString(" | ").take(1500)}")
            val keys = listOf(
                "pilavyer", "data-pv", "m3u8", "stream.php", "sub.php",
                "player", "embed", "fetch(", "/api/", "video"
            )
            for (k in keys) {
                var idx = html.indexOf(k, ignoreCase = true)
                var n = 0
                while (idx >= 0 && n < 3) {
                    val s = html.substring(maxOf(0, idx - 120), minOf(html.length, idx + 300))
                        .replace(Regex("\\s+"), " ")
                    Log.d(TAG, "[$k] $s")
                    n++
                    idx = html.indexOf(k, idx + 400, ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "debugDump error ${e.message}")
        }
    }
}
