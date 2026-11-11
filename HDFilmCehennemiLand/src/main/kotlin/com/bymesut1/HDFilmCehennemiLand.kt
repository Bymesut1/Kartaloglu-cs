package com.bymesut1

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HDFilmCehennemiLand : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.land"
    override var name = "HDFilmCehennemi.Land"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "X-Requested-With" to "fetch"
        )

    // ============================== Ana Sayfa ==============================
    override val mainPage = mainPageOf(
        "home" to "Son Eklenenler",
        "mostLiked" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(
            "$mainUrl/load/page/$page/${request.data}/",
            headers = apiHeaders
        ).parsedSafe<HdfcApiResponse>()?.html.orEmpty()

        val items = Jsoup.parse(html, mainUrl)
            .select("a.poster")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("strong.poster-title, h4.title")?.text()?.trim() ?: return null
        val poster = this.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        }
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // ================================ Arama ================================
    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.post(
            "$mainUrl/search",
            headers = apiHeaders,
            data = mapOf("query" to query)
        ).parsedSafe<HdfcSearchResponse>()

        return res?.results.orEmpty().mapNotNull {
            Jsoup.parse(it, mainUrl).selectFirst("a[href]")?.toSearchResult()
        }
    }

    // ========================= Detay (TMDB burada) =========================
    private data class SiteEp(
        val url: String,
        val name: String,
        val season: Int?,
        val episode: Int?,
        val date: String?
    )

    private val numberRegex = Regex("(\\d+)\\.")

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        val isSeries = url.contains("/dizi/")

        val siteTitle = doc.selectFirst(".section-title")?.ownText()
            ?.substringBefore(" Filminin Bilgileri")
            ?.substringBefore(" izle")
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val info = doc.selectFirst("div.section-content div.post-info")
        val sitePoster = info?.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        }
        val siteTags = info?.select("div.post-info-genres > a")?.eachText().orEmpty()
        val siteActors = info?.select("div.post-info-cast > a")?.eachText().orEmpty()
        val sitePlot = info?.selectFirst("div.post-info-content > p")?.text()
        val siteYear = info?.text()
            ?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }

        // ---- TMDB: sadece içine girince çalışır ----
        val cleanTitle = siteTitle.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
        val tmdb = findTmdb(if (isSeries) "tv" else "movie", cleanTitle, siteYear)

        val poster = tmdb?.posterPath?.let { "$TMDB_IMG/w500$it" } ?: sitePoster
        val backdrop = tmdb?.backdropPath?.let { "$TMDB_IMG/original$it" }
        val plot = tmdb?.overview?.takeIf { it.isNotBlank() } ?: sitePlot
        val tags = tmdb?.genres?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() } ?: siteTags
        val year = (tmdb?.releaseDate ?: tmdb?.firstAirDate)?.take(4)?.toIntOrNull() ?: siteYear
        val actors = tmdb?.credits?.cast.orEmpty().take(15).mapNotNull { c ->
            c.name?.let { Actor(it, c.profilePath?.let { p -> "$TMDB_IMG/w185$p" }) }
        }.takeIf { it.isNotEmpty() } ?: siteActors.map { Actor(it) }

        if (!isSeries) {
            return newMovieLoadResponse(siteTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = tmdb?.runtime
                addActors(actors)
            }
        }

        // ---- Bölümler (siteden) ----
        val siteEps = doc.select("div.seasons-tabs-wrapper > div.seasons-tab-content > a")
            .mapIndexedNotNull { index, a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapIndexedNotNull null
                val name = a.selectFirst("h3, h4")?.text()?.trim() ?: return@mapIndexedNotNull null
                val nums = numberRegex.findAll(name).map { it.groupValues[1].toIntOrNull() }.toList()
                SiteEp(
                    url = href,
                    name = name,
                    season = nums.getOrNull(0) ?: 1,
                    episode = nums.getOrNull(1) ?: (index + 1),
                    date = a.selectFirst("date")?.attr("datetime")?.trim()
                )
            }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        // ---- Bölümler (TMDB: başlık, açıklama, görsel, tarih) ----
        val seasonMap = HashMap<Int, Map<Int, HdfcTmdbEpisode>>()
        val tmdbId = tmdb?.id
        if (tmdbId != null) {
            siteEps.mapNotNull { it.season }.distinct().forEach { s ->
                val sd = tmdbGet<HdfcTmdbSeason>("tv/$tmdbId/season/$s")
                seasonMap[s] = sd?.episodes.orEmpty()
                    .filter { it.episodeNumber != null }
                    .associateBy { it.episodeNumber!! }
            }
        }

        val episodes = siteEps.map { e ->
            val t = if (e.season != null && e.episode != null) seasonMap[e.season]?.get(e.episode) else null
            newEpisode(e.url) {
                this.name = t?.name?.takeIf { it.isNotBlank() } ?: e.name
                this.season = e.season
                this.episode = e.episode
                this.posterUrl = t?.stillPath?.let { "$TMDB_IMG/w780$it" }
                this.description = t?.overview?.takeIf { it.isNotBlank() }
                addDate(t?.airDate ?: e.date)
            }
        }

        return newTvSeriesLoadResponse(siteTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = tmdb?.episodeRunTime?.firstOrNull()
            addActors(actors)
        }
    }

    // ================================ TMDB =================================
    private suspend inline fun <reified T : Any> tmdbGet(path: String, params: String = ""): T? {
        return try {
            app.get("$TMDB_API/$path?api_key=$TMDB_API_KEY&language=tr-TR$params").parsedSafe<T>()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun findTmdb(type: String, title: String, year: Int?): HdfcTmdbDetail? {
        if (TMDB_API_KEY.isBlank() || TMDB_API_KEY.startsWith("BURAYA")) return null
        val q = URLEncoder.encode(title, "UTF-8")
        val results = tmdbGet<HdfcTmdbSearch>("search/$type", "&query=$q")?.results.orEmpty()
        val hit = results.firstOrNull {
            year != null && (it.releaseDate ?: it.firstAirDate)?.startsWith(year.toString()) == true
        } ?: results.firstOrNull() ?: return null
        return tmdbGet<HdfcTmdbDetail>("$type/${hit.id}", "&append_to_response=credits")
    }

    // ============================= Video Linkleri ==========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val wrapped: (ExtractorLink) -> Unit = { found = true; callback(it) }

        val doc = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).document

        doc.select("div.alternative-tab div.alternative-links[data-lang]").forEach { langBox ->
            val lang = langBox.attr("data-lang").uppercase()
            langBox.select("button.alternative-link[data-video]").forEach { btn ->
                val sourceName = "${btn.text().trim()} [$lang]"
                val videoId = btn.attr("data-video")
                try {
                    val raw = app.get("$mainUrl/video/$videoId/", headers = apiHeaders)
                        .text.replace("\\/", "/")
                    val iframe = Regex("src=\\\\?[\"']([^\"'\\\\]+)").find(raw)
                        ?.groupValues?.get(1) ?: return@forEach
                    val link = fixUrl(iframe)

                    if (!resolvePlayer(link, sourceName, subtitleCallback, wrapped)) {
                        loadExtractor(link, "$mainUrl/", subtitleCallback, wrapped)
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        return found
    }

    // Close / Rapidrame / trstx (xbet) oynatıcıları için ortak çözücü
    private suspend fun resolvePlayer(
        url: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val own = listOf("/rplayer", "rapidrame", "close", "trstx.org", "hdfilmcehennemi")
        if (own.none { url.contains(it, ignoreCase = true) }) return false

        val origin = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/") + "/"

        val text = app.get(url, referer = "$mainUrl/").text.replace("\\/", "/")
        val unpacked = if (text.contains("eval(function(p,a,c,k,e")) {
            runCatching { getAndUnpack(text) }.getOrDefault("")
        } else ""
        val all = "$text\n$unpacked"

        // Altyazılar
        Regex("\\{[^{}]*\\.(?:vtt|srt)[^{}]*\\}").findAll(all).forEach { m ->
            val obj = m.value
            val file = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(obj)?.groupValues?.get(1)
                ?: return@forEach
            val label = Regex("\"label\"\\s*:\\s*\"([^\"]*)\"").find(obj)?.groupValues?.get(1)
                ?.ifBlank { null } ?: "Türkçe"
            subtitleCallback(SubtitleFile(label, fixUrl(file)))
        }

        // Video: önce sayfadan düz link, yoksa WebView ile yakala
        val direct = Regex("https?://[^\"'\\s<>\\\\]+?\\.(?:m3u8|mp4)[^\"'\\s<>\\\\]*")
            .find(all)?.value

        val finalUrl = direct ?: runCatching {
            app.get(
                url,
                referer = "$mainUrl/",
                interceptor = WebViewResolver(Regex("\\.(?:m3u8|mp4)"), timeout = 20_000L)
            ).url
        }.getOrNull()?.takeIf { it.contains(".m3u8") || it.contains(".mp4") } ?: return false

        callback(
            newExtractorLink(
                sourceName,
                sourceName,
                finalUrl,
                if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = origin
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        // DiziPod.kt'de kullandığın TMDB API key'in aynısını buraya yapıştır
        private const val TMDB_API_KEY = "000316508321ce461cf81e7c6815eec7"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
    }
}

// ============================== Veri sınıfları ==============================
data class HdfcApiResponse(
    @JsonProperty("html") val html: String? = null
)

data class HdfcSearchResponse(
    @JsonProperty("results") val results: List<String>? = null
)

data class HdfcTmdbSearch(
    @JsonProperty("results") val results: List<HdfcTmdbItem>? = null
)

data class HdfcTmdbItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)

data class HdfcTmdbGenre(
    @JsonProperty("name") val name: String? = null
)

data class HdfcTmdbCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null
)

data class HdfcTmdbCredits(
    @JsonProperty("cast") val cast: List<HdfcTmdbCast>? = null
)

data class HdfcTmdbDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("genres") val genres: List<HdfcTmdbGenre>? = null,
    @JsonProperty("credits") val credits: HdfcTmdbCredits? = null
)

data class HdfcTmdbEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("air_date") val airDate: String? = null
)

data class HdfcTmdbSeason(
    @JsonProperty("episodes") val episodes: List<HdfcTmdbEpisode>? = null
)
