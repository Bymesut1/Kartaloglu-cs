package com.bymesut1

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class SinemaIzle : MainAPI() {
    override var mainUrl = "https://sinemaizle.org"
    override var name = "SinemaIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // DiziPod.kt icindeki TMDB anahtarini buraya yapistir
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val baseHeaders = mapOf("User-Agent" to ua)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Diziler",
        "$mainUrl/dil/turkce-dublaj-filmler-izle" to "Türkçe Dublaj Filmler",
        "$mainUrl/dil/turkce-altyazi-filmler-izle" to "Türkçe Altyazılı Filmler",
        "$mainUrl/dil/yerli-film-izle" to "Yerli Filmler",
        "$mainUrl/imdb-en-iyiler" to "IMDb En İyiler",
        "$mainUrl/tur/aksiyon-filmleri-izle" to "Aksiyon",
        "$mainUrl/tur/komedi-filmleri-izle-s1" to "Komedi",
        "$mainUrl/tur/korku-filmleri-izle" to "Korku",
        "$mainUrl/tur/gerilim-filmleri-izle" to "Gerilim",
        "$mainUrl/tur/dram-filmleri-izle" to "Dram",
        "$mainUrl/tur/fantastik-filmleri-izle" to "Fantastik",
        "$mainUrl/tur/macera-filmleri-izle" to "Macera",
        "$mainUrl/tur/romantik-filmleri-izle" to "Romantik",
        "$mainUrl/tur/suc-filmleri-izle" to "Suç"
    )

    // ------------------------------------------------------------------
    // ANA SAYFA
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, headers = baseHeaders, referer = "$mainUrl/").document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private val cardHref = Regex("""^https?://sinemaizle\.org/(film|dizi)/[^/?#]+/?$""")

    private fun cleanTitle(t: String): String {
        return t.replace(Regex("""\s*\(\d{4}\)"""), "")
            .replace(Regex("""\s*[-–|]\s*Sinemaizle.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(full\s*hd\s+)?izle\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun toSearch(title: String, href: String, poster: String?): SearchResponse {
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (a in doc.select("a[href]")) {
            val href = fixUrl(a.attr("href")).substringBefore("#").substringBefore("?")
            if (!cardHref.matches(href)) continue
            val img = a.selectFirst("img") ?: continue
            if (!seen.add(href)) continue
            val title = cleanTitle(
                a.selectFirst("p")?.text()?.takeIf { it.isNotBlank() }
                    ?: img.attr("alt").ifBlank { a.attr("title") }
            )
            if (title.isBlank()) continue
            val poster = img.attr("data-src").ifBlank { img.attr("src") }
                .replace("/bg/", "/poster/")
                .ifBlank { null }
            out.add(toSearch(title, href, poster))
        }
        return out
    }

    // ------------------------------------------------------------------
    // ARAMA  (site ici arama: /arama/oneri?q=...)
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val results = ArrayList<SearchResponse>()
        try {
            val txt = app.get(
                "$mainUrl/arama/oneri?q=$q",
                headers = baseHeaders + mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "$mainUrl/film"
            ).text
            results.addAll(parseSuggest(txt))
        } catch (_: Exception) {
        }
        if (results.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/arama?q=$q", headers = baseHeaders, referer = "$mainUrl/").document
                results.addAll(parseCards(doc))
            } catch (_: Exception) {
            }
        }
        return results.distinctBy { it.url }
    }

    private fun parseSuggest(txt: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val t = txt.trim()
        if (t.startsWith("{")) {
            collectItems(JSONObject(t), out)
        } else if (t.startsWith("[")) {
            collectItems(JSONArray(t), out)
        } else if (t.isNotEmpty()) {
            out.addAll(parseCards(Jsoup.parse(t, mainUrl)))
        }
        return out
    }

    private fun collectItems(node: Any?, out: MutableList<SearchResponse>) {
        when (node) {
            is JSONObject -> {
                val url = node.optString("url")
                val title = node.optString("title")
                if (url.isNotBlank() && title.isNotBlank()) {
                    val href = fixUrl(url).substringBefore("#").substringBefore("?")
                    if (cardHref.matches(href)) {
                        val poster = node.optString("poster").ifBlank { null }
                            ?.replace("/bg/", "/poster/")
                        out.add(toSearch(cleanTitle(title), href, poster))
                        return
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectItems(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectItems(node.opt(i), out)
            }
        }
    }

    // ------------------------------------------------------------------
    // TMDB (sadece icine girince)
    // ------------------------------------------------------------------
    data class TmdbHit(@JsonProperty("id") val id: Int? = null)
    data class TmdbSearch(@JsonProperty("results") val results: List<TmdbHit>? = null)
    data class TmdbGenre(@JsonProperty("name") val name: String? = null)
    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null
    )
    data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null)
    data class TmdbVideo(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null
    )
    data class TmdbVideos(@JsonProperty("results") val results: List<TmdbVideo>? = null)
    data class TmdbSeasonInfo(@JsonProperty("season_number") val seasonNumber: Int? = null)
    data class TmdbDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeasonInfo>? = null
    )
    data class TmdbEp(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null
    )
    data class TmdbSeason(@JsonProperty("episodes") val episodes: List<TmdbEp>? = null)

    private suspend fun tmdbDetail(title: String, year: Int?, tv: Boolean): TmdbDetail? {
        if (tmdbKey.startsWith("BURAYA")) return null
        return try {
            val q = URLEncoder.encode(title, "UTF-8")
            val kind = if (tv) "tv" else "movie"
            val yearParam = if (year != null) {
                if (tv) "&first_air_date_year=$year" else "&year=$year"
            } else ""
            var hit = app.get("$tmdbApi/search/$kind?api_key=$tmdbKey&language=tr-TR&query=$q$yearParam")
                .parsedSafe<TmdbSearch>()?.results?.firstOrNull()
            if (hit?.id == null && yearParam.isNotEmpty()) {
                hit = app.get("$tmdbApi/search/$kind?api_key=$tmdbKey&language=tr-TR&query=$q")
                    .parsedSafe<TmdbSearch>()?.results?.firstOrNull()
            }
            val id = hit?.id ?: return null
            app.get(
                "$tmdbApi/$kind/$id?api_key=$tmdbKey&language=tr-TR" +
                        "&append_to_response=credits,videos&include_video_language=tr,en,null"
            ).parsedSafe<TmdbDetail>()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tmdbSeason(tvId: Int, season: Int): List<TmdbEp> {
        return try {
            app.get("$tmdbApi/tv/$tvId/season/$season?api_key=$tmdbKey&language=tr-TR")
                .parsedSafe<TmdbSeason>()?.episodes ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun LoadResponse.applyTmdb(d: TmdbDetail?) {
        if (d == null) return
        d.posterPath?.let { this.posterUrl = "$tmdbImg/w500$it" }
        d.backdropPath?.let { this.backgroundPosterUrl = "$tmdbImg/w1280$it" }
        if (!d.overview.isNullOrBlank()) this.plot = d.overview
        val y = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull()
        if (y != null) this.year = y
        val g = d.genres?.mapNotNull { it.name }
        if (!g.isNullOrEmpty()) this.tags = g
        val cast = d.credits?.cast?.filter { !it.name.isNullOrBlank() }?.take(15)
        if (!cast.isNullOrEmpty()) {
            this.actors = cast.map {
                ActorData(
                    Actor(it.name ?: "", it.profilePath?.let { p -> "$tmdbImg/w185$p" }),
                    roleString = it.character
                )
            }
        }
        val trailer = d.videos?.results?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }
            ?: d.videos?.results?.firstOrNull { it.site == "YouTube" }
        trailer?.key?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
    }

    // ------------------------------------------------------------------
    // LOAD
    // ------------------------------------------------------------------
    private fun normalizeUrl(url: String): String {
        val u = url.substringBefore("#").substringBefore("?")
        val m = Regex("""^(https?://[^/]+/dizi/[^/]+)/sezon-.*$""").find(u)
        return (m?.groupValues?.get(1) ?: u).trimEnd('/')
    }

    private fun findYear(doc: Document, rawTitle: String): Int? {
        Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        Regex("""\((\d{4})\)""").find(og)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex(""""datePublished"\s*:\s*"(\d{4})""").find(doc.html())
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun collectEpisodes(text: String, slug: String, out: MutableSet<Pair<Int, Int>>) {
        val re = Regex("""/dizi/${Regex.escape(slug)}/sezon-(\d+)/bolum-(\d+)""")
        for (m in re.findAll(text.replace("\\/", "/"))) {
            val s = m.groupValues[1].toIntOrNull() ?: continue
            val e = m.groupValues[2].toIntOrNull() ?: continue
            out.add(s to e)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = normalizeUrl(url)
        val doc = app.get(base, headers = baseHeaders, referer = "$mainUrl/").document
        val isSeries = base.contains("/dizi/")

        val rawTitle = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val title = cleanTitle(rawTitle)
        val year = findYear(doc, rawTitle)
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val siteDesc = doc.selectFirst("meta[name=description]")?.attr("content")

        val tmdb = tmdbDetail(title, year, isSeries)

        if (!isSeries) {
            return newMovieLoadResponse(tmdb?.title ?: title, base, TvType.Movie, base) {
                this.posterUrl = sitePoster
                this.plot = siteDesc
                this.year = year
                this.duration = tmdb?.runtime
                applyTmdb(tmdb)
            }
        }

        val slug = base.substringAfter("/dizi/").trim('/')
        val found = linkedSetOf<Pair<Int, Int>>()
        collectEpisodes(doc.html(), slug, found)

        val tmdbSeasons = tmdb?.seasons?.mapNotNull { it.seasonNumber }?.filter { it > 0 } ?: emptyList()

        // sitede listelenmeyen sezonlari sezon sayfasindan dene
        val have = found.map { it.first }.toSet()
        for (sn in tmdbSeasons) {
            if (sn in have) continue
            try {
                val t = app.get("$mainUrl/dizi/$slug/sezon-$sn", headers = baseHeaders, referer = base).text
                collectEpisodes(t, slug, found)
            } catch (_: Exception) {
            }
        }

        val seasonNums = if (found.isNotEmpty()) found.map { it.first }.toSet() else tmdbSeasons.toSet()
        val epInfo = HashMap<Pair<Int, Int>, TmdbEp>()
        val tvId = tmdb?.id
        if (tvId != null) {
            for (sn in seasonNums) {
                for (ep in tmdbSeason(tvId, sn)) {
                    val en = ep.episodeNumber ?: continue
                    epInfo[sn to en] = ep
                }
            }
        }
        // sitede hic bolum bulunamazsa TMDB'den uret
        if (found.isEmpty()) epInfo.keys.forEach { found.add(it) }

        val episodes = found.sortedWith(compareBy({ it.first }, { it.second })).map { (s, e) ->
            val info = epInfo[s to e]
            newEpisode("$mainUrl/dizi/$slug/sezon-$s/bolum-$e") {
                this.name = info?.name?.takeIf { it.isNotBlank() } ?: "$e. Bölüm"
                this.season = s
                this.episode = e
                this.description = info?.overview?.takeIf { it.isNotBlank() }
                this.posterUrl = info?.stillPath?.let { "$tmdbImg/w500$it" }
                addDate(info?.airDate)
            }
        }

        return newTvSeriesLoadResponse(tmdb?.name ?: title, base, TvType.TvSeries, episodes) {
            this.posterUrl = sitePoster
            this.plot = siteDesc
            this.year = year
            applyTmdb(tmdb)
        }
    }

    // ------------------------------------------------------------------
    // LINKLER (vidmixi master m3u8 + Turkce altyazi)
    // ------------------------------------------------------------------
    private val processRegex = Regex("""https?://[^\s"'<>\\]+/process/[A-Za-z0-9+/=_\-%]+""")
    private val m3uRegex = Regex("""https?://[^\s"'<>\\]+(?:/m3u/[A-Za-z0-9+/=_\-]+|\.m3u8[^\s"'<>\\]*)""")
    private val vttRegex = Regex("""https?://[^\s"'<>\\]+?\.vtt""")

    private fun subLabel(url: String): String {
        val f = url.substringAfterLast('/').substringBefore('.').lowercase()
        return when {
            f.contains("forced") -> "Türkçe (Zorunlu)"
            f.startsWith("tur") || f.startsWith("tr") -> "Türkçe"
            f.startsWith("eng") || f.startsWith("en") -> "English"
            else -> f
        }
    }

    private fun collectSubs(text: String, seen: MutableSet<String>, cb: (SubtitleFile) -> Unit) {
        for (m in vttRegex.findAll(text)) {
            val u = m.value
            if (seen.add(u)) cb(newSubtitleFile(subLabel(u), u))
        }
    }

    private fun origin(u: String): String {
        return try {
            val p = URI(u)
            "${p.scheme}://${p.host}/"
        } catch (_: Exception) {
            "$mainUrl/"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, headers = baseHeaders, referer = "$mainUrl/")
        val html = page.text.replace("\\/", "/")
        val subSeen = HashSet<String>()
        collectSubs(html, subSeen, subtitleCallback)

        val players = linkedSetOf<String>()
        processRegex.findAll(html).forEach { players.add(it.value) }
        for (f in page.document.select("iframe[src], iframe[data-src]")) {
            val s = fixUrl(f.attr("src").ifBlank { f.attr("data-src") })
            if (s.startsWith("http") && !s.contains("sinemaizle.org")) players.add(s)
        }

        var found = false

        suspend fun emit(url: String, referer: String) {
            callback(
                newExtractorLink(this.name, this.name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to ua, "Referer" to referer)
                }
            )
            found = true
        }

        for (p in players) {
            try {
                val resp = app.get(p, headers = baseHeaders, referer = "$mainUrl/")
                val body = resp.text.replace("\\/", "/")
                collectSubs(body, subSeen, subtitleCallback)
                if (body.trimStart().startsWith("#EXTM3U")) {
                    // process linki dogrudan master m3u8 donuyor (ses + goruntu birlikte)
                    emit(p, "$mainUrl/")
                } else {
                    val m = m3uRegex.find(body)?.value
                    if (m != null) emit(m, origin(p))
                }
            } catch (_: Exception) {
            }
        }

        if (!found) {
            m3uRegex.find(html)?.value?.let { emit(it, "$mainUrl/") }
        }
        return found
    }
}
