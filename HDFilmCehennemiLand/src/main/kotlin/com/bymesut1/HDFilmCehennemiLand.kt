package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.Interceptor
import okhttp3.Response
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
    override var sequentialMainPage = true

    // Sırayla denenir, çalışan domain mainUrl olarak hatırlanır
    private val domains = listOf(
        "https://www.hdfilmcehennemi.land",
        "https://www.hdfilmcehennemi.nl"
    )

    private val interceptor by lazy { HdfcCloudflareInterceptor(CloudflareKiller()) }

    private fun apiHeaders(base: String): Map<String, String> = mapOf(
        "Referer" to "$base/",
        "Origin" to base,
        "X-Requested-With" to "fetch",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    private fun baseOf(url: String): String =
        url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/")

    private fun extractHtml(body: String): String =
        if (body.trimStart().startsWith("{")) tryParseJson<HdfcApiResponse>(body)?.html.orEmpty() else body

    private fun debugItem(msg: String): SearchResponse =
        newMovieSearchResponse("DEBUG: ${msg.take(300)}", "$mainUrl/", TvType.Movie)

    // Domainleri sırayla dener; geçerli yanıt veren ilk domaini döndürür
    private suspend fun callApi(
        path: String,
        form: Map<String, String>?,
        dbg: StringBuilder,
        valid: (String) -> Boolean
    ): String? {
        val order = domains.sortedBy { if (it == mainUrl) 0 else 1 }
        for (base in order) {
            try {
                val res = if (form == null) {
                    app.get("$base$path", headers = apiHeaders(base), interceptor = interceptor)
                } else {
                    app.post("$base$path", headers = apiHeaders(base), data = form, interceptor = interceptor)
                }
                val body = res.text
                if (valid(body)) {
                    mainUrl = base
                    return body
                }
                dbg.append("${base.substringAfter("//")} → HTTP ${res.code}: ${body.take(80).replace(Regex("\\s+"), " ")}\n")
            } catch (e: Exception) {
                dbg.append("${base.substringAfter("//")} → ${e.javaClass.simpleName}: ${e.message?.take(60)}\n")
            }
        }
        return null
    }

    // ============================== Ana Sayfa ==============================
    override val mainPage = mainPageOf(
        "home" to "Son Eklenenler",
        "mostLiked" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val dbg = StringBuilder()
        val body = callApi("/load/page/$page/${request.data}/", null, dbg) {
            extractHtml(it).contains("poster")
        }

        if (body == null) {
            val list = if (DEBUG) listOf(debugItem(dbg.toString())) else emptyList()
            return newHomePageResponse(request.name, list, hasNext = false)
        }

        val items = Jsoup.parse(extractHtml(body), mainUrl)
            .select("a.poster")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("strong.poster-title, h4.title")?.text()?.trim() ?: return null
        val img: Element? = this.selectFirst("img")
        val poster: String? = if (img != null) {
            fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        } else null
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // ================================ Arama ================================
    override suspend fun search(query: String): List<SearchResponse> {
        val dbg = StringBuilder()
        val body = callApi("/search", mapOf("query" to query), dbg) { it.contains("results") }
            ?: return if (DEBUG) listOf(debugItem(dbg.toString())) else emptyList()

        val res = tryParseJson<HdfcSearchResponse>(body)
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
        val base = baseOf(url)
        val doc = app.get(url, headers = mapOf("Referer" to "$base/"), interceptor = interceptor).document
        val isSeries = url.contains("/dizi/")

        val siteTitle = doc.selectFirst(".section-title")?.ownText()
            ?.substringBefore(" Filminin Bilgileri")
            ?.substringBefore(" izle")
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val info: Element? = doc.selectFirst("div.section-content div.post-info")
        val siteImg: Element? = info?.selectFirst("img")
        val sitePoster: String? = if (siteImg != null) {
            fixUrlNull(siteImg.attr("data-src").ifBlank { siteImg.attr("src") })
        } else null
        val siteTags = info?.select("div.post-info-genres > a")?.eachText().orEmpty()
        val siteActors = info?.select("div.post-info-cast > a")?.eachText().orEmpty()
        val sitePlot = info?.selectFirst("div.post-info-content > p")?.text()
        val siteYear = info?.text()
            ?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }

        // ---- TMDB: sadece içine girince çalışır ----
        val cleanTitle = siteTitle.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
        val tmdb = findTmdb(if (isSeries) "tv" else "movie", cleanTitle, siteYear)

        val poster = tmdb?.poster_path?.let { "$TMDB_IMG/w500$it" } ?: sitePoster
        val backdrop = tmdb?.backdrop_path?.let { "$TMDB_IMG/original$it" }
        val plot = tmdb?.overview?.takeIf { it.isNotBlank() } ?: sitePlot
        val tags = tmdb?.genres?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() } ?: siteTags
        val year = (tmdb?.release_date ?: tmdb?.first_air_date)?.take(4)?.toIntOrNull() ?: siteYear
        val actors = tmdb?.credits?.cast.orEmpty().take(15).mapNotNull { c ->
            c.name?.let { Actor(it, c.profile_path?.let { p -> "$TMDB_IMG/w185$p" }) }
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
                    .filter { it.episode_number != null }
                    .associateBy { it.episode_number!! }
            }
        }

        val episodes = siteEps.map { e ->
            val t = if (e.season != null && e.episode != null) seasonMap[e.season]?.get(e.episode) else null
            newEpisode(e.url) {
                this.name = t?.name?.takeIf { it.isNotBlank() } ?: e.name
                this.season = e.season
                this.episode = e.episode
                this.posterUrl = t?.still_path?.let { "$TMDB_IMG/w780$it" }
                this.description = t?.overview?.takeIf { it.isNotBlank() }
                addDate(t?.air_date ?: e.date)
            }
        }

        return newTvSeriesLoadResponse(siteTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = tmdb?.episode_run_time?.firstOrNull()
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
            year != null && (it.release_date ?: it.first_air_date)?.startsWith(year.toString()) == true
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
        val base = baseOf(data)

        val doc = app.get(data, headers = mapOf("Referer" to "$base/"), interceptor = interceptor).document

        doc.select("div.alternative-tab div.alternative-links[data-lang]").forEach { langBox ->
            val lang = langBox.attr("data-lang").uppercase()
            langBox.select("button.alternative-link[data-video]").forEach { btn ->
                val sourceName = "${btn.text().trim()} [$lang]"
                val videoId = btn.attr("data-video")
                try {
                    val raw = app.get(
                        "$base/video/$videoId/",
                        headers = apiHeaders(base),
                        interceptor = interceptor
                    ).text.replace("\\/", "/")
                    val iframe = Regex("src=\\\\?[\"']([^\"'\\\\]+)").find(raw)
                        ?.groupValues?.get(1) ?: return@forEach
                    val link = fixUrl(iframe)

                    if (!resolvePlayer(link, base, sourceName, subtitleCallback, wrapped)) {
                        loadExtractor(link, "$base/", subtitleCallback, wrapped)
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
        base: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val own = listOf("/rplayer", "rapidrame", "close", "trstx.org", "hdfilmcehennemi")
        if (own.none { url.contains(it, ignoreCase = true) }) return false

        val origin = baseOf(url) + "/"

        val text = app.get(url, referer = "$base/").text.replace("\\/", "/")
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
                referer = "$base/",
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
        // Boş gelirse ekranda DEBUG satırı gösterir; çalışınca false yap
        private const val DEBUG = true

        // DiziPod.kt'de kullandığın TMDB API key'in aynısını buraya yapıştır
        private const val TMDB_API_KEY = "000316508321ce461cf81e7c6815eec7"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
    }
}

// Cloudflare "Just a moment" sayfası gelirse WebView ile aşar
class HdfcCloudflareInterceptor(private val killer: CloudflareKiller) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 403 || response.code == 503) {
            val body = response.peekBody(1024 * 1024).string()
            if (body.contains("Just a moment", ignoreCase = true) ||
                body.contains("cloudflare", ignoreCase = true)
            ) {
                response.close()
                return killer.intercept(chain)
            }
        }
        return response
    }
}

// ============================== Veri sınıfları ==============================
data class HdfcApiResponse(
    val html: String? = null
)

data class HdfcSearchResponse(
    val results: List<String>? = null
)

data class HdfcTmdbSearch(
    val results: List<HdfcTmdbItem>? = null
)

data class HdfcTmdbItem(
    val id: Int? = null,
    val release_date: String? = null,
    val first_air_date: String? = null
)

data class HdfcTmdbGenre(
    val name: String? = null
)

data class HdfcTmdbCast(
    val name: String? = null,
    val profile_path: String? = null
)

data class HdfcTmdbCredits(
    val cast: List<HdfcTmdbCast>? = null
)

data class HdfcTmdbDetail(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val runtime: Int? = null,
    val episode_run_time: List<Int>? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val genres: List<HdfcTmdbGenre>? = null,
    val credits: HdfcTmdbCredits? = null
)

data class HdfcTmdbEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val air_date: String? = null
)

data class HdfcTmdbSeason(
    val episodes: List<HdfcTmdbEpisode>? = null
)
