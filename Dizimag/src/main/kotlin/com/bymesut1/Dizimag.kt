package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dizimag : MainAPI() {
    override var mainUrl = "https://ddizimag.icu"
    override var name = "Dizimag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Yabancı Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes" to "Animeler",
        "$mainUrl/platform/netflix" to "Netflix Yapımları",
        "$mainUrl/platform/disney" to "Disney+ Yapımları",
        "$mainUrl/platform/amazon-prime" to "Amazon Prime Yapımları",
        "$mainUrl/platform/apple-tv" to "Apple TV+ Yapımları",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
        "$mainUrl/tur/dram" to "Dram"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val targetUrl = if (page <= 1) request.data else "${request.data}${separator}page=$page"
        val document = app.get(targetUrl).document
        val items = parseCatalogCards(document)
        val hasNext = document.selectFirst("a[href*='page=${page + 1}'], a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseCatalogCards(document: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<SearchResponse>()

        document.select("a.group.block[href]").forEach { card ->
            val item = card.toSearchResult() ?: return@forEach
            if (seen.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = attr("href").trim()
        if (rawHref.isBlank()) return null

        val cleanHref = rawHref
            .replace(Regex("""/(dizi|anime)/([^/?#]+)/sezon-\d+/bolum-\d+.*"""), "/$1/$2")
            .replace(Regex("""/film/([^/?#]+)/izle.*"""), "/film/$1")

        val url = fixUrlNull(cleanHref) ?: return null
        if (!url.contains("/dizi/") && !url.contains("/film/") && !url.contains("/anime/")) return null

        val title = (selectFirst("h3")?.text()
            ?: selectFirst("img")?.attr("alt")?.replace(Regex("""\s*(Dizi|Film|Anime)\s*izle.*""", RegexOption.IGNORE_CASE), "")
            )?.trim().orEmpty()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst("p")?.text()?.trim()?.toIntOrNull()

        return when {
            url.contains("/film/") -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
            url.contains("/anime/") -> newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterUrl
                this.year = year
            }
            else -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/ara?q=$encoded").document
        return parseCatalogCards(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url
            .replace(Regex("""/(dizi|anime)/([^/?#]+)/sezon-\d+/bolum-\d+.*"""), "/$1/$2")
            .replace(Regex("""/film/([^/?#]+)/izle.*"""), "/film/$1")
            .substringBefore("?")
            .substringBefore("#")

        val document = app.get(cleanUrl).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img[src*='/posters/']")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val backgroundPoster = fixUrlNull(
            document.selectFirst("img[src*='/backdrops/']")?.attr("src")
                ?: poster
        )

        val ldJsonScripts = document.select("script[type=application/ld+json]").map { it.data() }
        val fullLdJson = ldJsonScripts.joinToString("\n")

        val description = Regex(""""description"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(fullLdJson)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?.replace("\\r", "")
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex(""""(?:startDate|datePublished)"\s*:\s*"(\d{4})""")
            .find(fullLdJson)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = document.select("a[href*='/tur/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val actorNames = Regex(""""@type"\s*:\s*"Person"\s*,\s*"name"\s*:\s*"([^"]+)"""")
            .findAll(fullLdJson)
            .map { Actor(it.groupValues[1]) }
            .distinctBy { it.name }
            .toList()

        val trailerUrl = Regex(""""embedUrl"\s*:\s*"(https://www\.youtube\.com/embed/[^"]+)"""")
            .find(fullLdJson)?.groupValues?.getOrNull(1)

        val recommendations = parseCatalogCards(document).filter { it.url != cleanUrl }

        val isMovie = cleanUrl.contains("/film/")

        // TMDB: yalnızca içeriğin detay sayfasında (tıklayınca) çalışır; liste/arama aynen kalır
        val searchTitle = title
            .replace(Regex("""\s*(Dizi|Film|Anime)?\s*izle.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()
        val tmdbId = tmdbFind(!isMovie, searchTitle, year)
        val tmdb = tmdbId?.let { tmdbDetails(!isMovie, it) }

        val finalPoster = tmdb?.poster ?: poster
        val finalBackdrop = tmdb?.backdrop ?: backgroundPoster
        val finalPlot = tmdb?.plot ?: description
        val finalYear = year ?: tmdb?.year
        val finalTags = tmdb?.genres?.takeIf { it.isNotEmpty() } ?: tags
        val finalActors = tmdb?.actors?.takeIf { it.isNotEmpty() } ?: actorNames
        val finalTrailer = tmdb?.trailer ?: trailerUrl

        if (isMovie) {
            val watchUrl = "$cleanUrl/izle"
            return newMovieLoadResponse(title, cleanUrl, TvType.Movie, watchUrl) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags
                this.recommendations = recommendations
                tmdb?.rating?.let { this.score = Score.from10(it) }
                tmdb?.runtime?.let { this.duration = it }
                if (finalActors.isNotEmpty()) addActors(finalActors)
                addTrailer(finalTrailer)
            }
        }

        // Collect all seasons for TV Series / Anime
        val seasonNums = document.select("a[href*='?sezon=']")
            .mapNotNull { link ->
                Regex("""[?&]sezon=(\d+)""").find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .distinct()
            .sorted()
            .ifEmpty { listOf(1) }

        val siteEps = mutableListOf<SiteEp>()
        val seenEpUrls = LinkedHashSet<String>()

        for (sNum in seasonNums) {
            val seasonDoc = if (sNum == 1) document else app.get("$cleanUrl?sezon=$sNum").document
            parseSeasonEpisodes(seasonDoc, sNum, seenEpUrls, siteEps)
        }

        // Sitedeki sezonlar için TMDB bölüm bilgilerini çek
        val tmdbSeasons = HashMap<Int, Map<Int, JSONObject>>()
        if (tmdbId != null) {
            for (s in siteEps.map { it.season }.distinct()) {
                tmdbSeasons[s] = tmdbSeason(tmdbId, s)
            }
        }

        val episodes = siteEps.map { se ->
            val te = tmdbSeasons[se.season]?.get(se.episode)
            newEpisode(se.url) {
                this.name = te?.str("name") ?: se.name
                this.season = se.season
                this.episode = se.episode
                this.posterUrl = te?.str("still_path")?.let { tmdbStill + it } ?: se.thumb
                if (te != null) {
                    this.description = te.str("overview")
                    te.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let {
                        this.score = Score.from10(it)
                    }
                    te.optInt("runtime", 0).takeIf { it > 0 }?.let { this.runTime = it }
                    addDate(te.str("air_date"))
                }
            }
        }

        val tvType = if (cleanUrl.contains("/anime/")) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, cleanUrl, tvType, episodes) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBackdrop
            this.plot = finalPlot
            this.year = finalYear
            this.tags = finalTags
            this.recommendations = recommendations
            tmdb?.rating?.let { this.score = Score.from10(it) }
            tmdb?.runtime?.let { this.duration = it }
            if (finalActors.isNotEmpty()) addActors(finalActors)
            addTrailer(finalTrailer)
        }
    }

    private fun parseSeasonEpisodes(
        doc: Document,
        expectedSeason: Int,
        seenEpUrls: MutableSet<String>,
        episodes: MutableList<SiteEp>
    ) {
        val epRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")
        doc.select("a.group[href*='/sezon-'][href*='/bolum-']").forEach { epEl ->
            val epUrl = fixUrlNull(epEl.attr("href")) ?: return@forEach
            val match = epRegex.find(epUrl) ?: return@forEach
            val sNum = match.groupValues[1].toIntOrNull() ?: expectedSeason
            val eNum = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (!seenEpUrls.add(epUrl)) return@forEach

            val epTitle = epEl.selectFirst("p.font-semibold")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: "$sNum. Sezon $eNum. Bölüm"
            val thumb = fixUrlNull(epEl.selectFirst("img")?.attr("src"))

            episodes.add(SiteEp(epUrl, sNum, eNum, epTitle, thumb))
        }
    }

    // ---------------- TMDB ----------------

    // TMDB API anahtarını buraya yaz (themoviedb.org > Ayarlar > API). Boş/yer tutucu kalırsa TMDB atlanır, site bilgileri kullanılır.
    private val tmdbApiKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbPoster = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdrop = "https://image.tmdb.org/t/p/original"
    private val tmdbStill = "https://image.tmdb.org/t/p/w300"
    private val tmdbProfile = "https://image.tmdb.org/t/p/w185"

    private data class SiteEp(
        val url: String,
        val season: Int,
        val episode: Int,
        val name: String,
        val thumb: String?
    )

    private data class TmdbInfo(
        val plot: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val genres: List<String>,
        val rating: Double?,
        val runtime: Int?,
        val actors: List<Actor>,
        val trailer: String?
    )

    // JSON null / boş değerleri null döndürür ("null" yazısı çıkmasın)
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }

    private suspend fun tmdbJson(path: String, vararg params: Pair<String, String>): JSONObject? {
        if (tmdbApiKey.isBlank() || tmdbApiKey.startsWith("BURAYA")) return null
        return try {
            val q = (listOf("api_key" to tmdbApiKey) + params)
                .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
            JSONObject(app.get("$tmdbApi/$path?$q", timeout = 20L).text)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tmdbFind(isTv: Boolean, title: String, year: Int?): Int? {
        if (title.isBlank()) return null
        val kind = if (isTv) "tv" else "movie"
        val yearKey = if (isTv) "first_air_date_year" else "year"
        val attempts: List<Int?> = if (year != null) listOf(year, null) else listOf(null)
        for (y in attempts) {
            val params = mutableListOf(
                "query" to title,
                "language" to "tr-TR",
                "include_adult" to "false"
            )
            if (y != null) params.add(yearKey to y.toString())
            val results = tmdbJson("search/$kind", *params.toTypedArray())?.optJSONArray("results") ?: continue
            if (results.length() > 0) {
                val id = results.optJSONObject(0)?.optInt("id", 0) ?: 0
                if (id > 0) return id
            }
        }
        return null
    }

    private suspend fun tmdbDetails(isTv: Boolean, id: Int): TmdbInfo? {
        val kind = if (isTv) "tv" else "movie"
        val d = tmdbJson(
            "$kind/$id",
            "language" to "tr-TR",
            "append_to_response" to "credits,videos",
            "include_video_language" to "tr,en,null"
        ) ?: return null

        // Türkçe özet yoksa İngilizce'ye düş
        val plot = d.str("overview")
            ?: tmdbJson("$kind/$id", "language" to "en-US")?.str("overview")

        val genres = mutableListOf<String>()
        d.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.str("name")?.let { genres.add(it) }
        }

        val actors = mutableListOf<Actor>()
        d.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 20)) {
                val c = arr.optJSONObject(i) ?: continue
                val n = c.str("name") ?: continue
                actors.add(Actor(n, c.str("profile_path")?.let { tmdbProfile + it }))
            }
        }

        var trailer: String? = null
        d.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                if (v.str("site") == "YouTube" && v.str("type") == "Trailer") {
                    trailer = v.str("key")?.let { "https://www.youtube.com/watch?v=$it" }
                    if (trailer != null) break
                }
            }
        }

        val dateStr = d.str(if (isTv) "first_air_date" else "release_date")
        val runtime = if (isTv) d.optJSONArray("episode_run_time")?.optInt(0, 0) else d.optInt("runtime", 0)

        return TmdbInfo(
            plot = plot,
            poster = d.str("poster_path")?.let { tmdbPoster + it },
            backdrop = d.str("backdrop_path")?.let { tmdbBackdrop + it },
            year = dateStr?.take(4)?.toIntOrNull(),
            genres = genres,
            rating = d.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            runtime = runtime?.takeIf { it > 0 },
            actors = actors,
            trailer = trailer
        )
    }

    // Bir sezonun bölümleri: bölüm numarası -> TMDB bölüm JSON'u (Türkçe, eksikse İngilizce ile tamamlanır)
    private suspend fun tmdbSeason(id: Int, season: Int): Map<Int, JSONObject> {
        val arr = tmdbJson("tv/$id/season/$season", "language" to "tr-TR")?.optJSONArray("episodes")
            ?: return emptyMap()
        val map = LinkedHashMap<Int, JSONObject>()
        var needFallback = false
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val n = e.optInt("episode_number", -1)
            if (n < 0) continue
            map[n] = e
            if (e.str("overview") == null || e.str("name") == null) needFallback = true
        }
        if (needFallback) {
            val en = tmdbJson("tv/$id/season/$season", "language" to "en-US")?.optJSONArray("episodes")
            if (en != null) {
                for (i in 0 until en.length()) {
                    val ee = en.optJSONObject(i) ?: continue
                    val target = map[ee.optInt("episode_number", -1)] ?: continue
                    if (target.str("overview") == null) ee.str("overview")?.let { target.put("overview", it) }
                    if (target.str("name") == null) ee.str("name")?.let { target.put("name", it) }
                }
            }
        }
        return map
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml = app.get(data).text
        var foundAny = false

        // Extract Pilavyer data-pv slugs and script host from <template data-tbx-defer> or page HTML
        val pvSlugs = Regex("""data-pv=["']([^"']+)["']""")
            .findAll(pageHtml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val coreHost = Regex("""https?://[^/"']+/assets/js/core\.js""")
            .find(pageHtml)?.value?.substringBefore("/assets/js/core.js")
            ?: "https://play2.pilavyerplay.top"

        for (slug in pvSlugs) {
            try {
                val playerUrl = "$coreHost/assets/js/s.php?s=${URLEncoder.encode(slug, "UTF-8")}"
                val playerHtml = app.get(
                    playerUrl,
                    referer = "$mainUrl/",
                    headers = mapOf("Origin" to mainUrl)
                ).text

                val playerJsonStr = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                    .find(playerHtml)?.groupValues?.getOrNull(1)

                if (!playerJsonStr.isNullOrBlank()) {
                    val config = runCatching { JSONObject(playerJsonStr) }.getOrNull()
                    val streamUrl = config?.optString("stream")?.takeIf { it.isNotBlank() }
                    if (!streamUrl.isNullOrBlank()) {
                        val subsArr = config.optJSONArray("subs")
                        if (subsArr != null) {
                            for (i in 0 until subsArr.length()) {
                                val sub = subsArr.optJSONObject(i) ?: continue
                                val subUrl = sub.optString("src").takeIf { it.isNotBlank() } ?: continue
                                val label = sub.optString("label").takeIf { it.isNotBlank() }
                                    ?: sub.optString("lang").takeIf { it.isNotBlank() }
                                    ?: "Türkçe"
                                subtitleCallback.invoke(SubtitleFile(label, subUrl))
                            }
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = "$name (Pilavyer)",
                                name = "$name (Pilavyer)",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$coreHost/"
                                this.headers = mapOf(
                                    "Referer" to "$coreHost/",
                                    "Origin" to coreHost
                                )
                                this.quality = Qualities.P720.value
                            }
                        )
                        foundAny = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Also check any standard iframes if present
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml)
            .map { it.groupValues[1] }
            .filter { !it.contains("youtube.com") && !it.contains("googletagmanager") }
            .forEach { rawIframe ->
                val iframeUrl = fixUrlNull(rawIframe) ?: return@forEach
                try {
                    if (loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)) {
                        foundAny = true
                    }
                } catch (_: Exception) {
                }
            }

        return foundAny
    }
}
