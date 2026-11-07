@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.bymesut1

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean

class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/film/" to "Son Eklenen Filmler",
        "${mainUrl}/dizi/" to "Son Eklenen Diziler",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon Filmleri",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}page/${page}/"
        }

        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newHomePageResponse(request.name, items)
    }

    private val reTitleJunk = Regex("""(?i)\b(izle|türkçe dublaj|dual)\b""")

    private fun cleanTitle(t: String): String =
        t.replace(reTitleJunk, "").replace(Regex("""\s{2,}"""), " ").trim()

    fun toSearchResult(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a[href*='/film/'], a[href*='/dizi/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.endsWith("/film/") || href.endsWith("/dizi/") || href.contains("/tur/")) return null

        val img = element.selectFirst("img")
        val rawTitle = img?.attr("alt")?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst(".title, h3, h2, h4, span")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val title = cleanTitle(rawTitle)

        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
        )

        val isTvSeries = href.contains("/dizi/")
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = if (page <= 1) {
            "${mainUrl}/?s=${query}"
        } else {
            "${mainUrl}/page/${page}/?s=${query}"
        }

        val document = app.get(targetUrl).document
        val items = parseSearchResults(document)
        return newSearchResponseList(items, hasNext = false)
    }

    fun parseSearchResults(document: Document): List<SearchResponse> {
        val elements = document.select("a[href*='/film/'], a[href*='/dizi/'], div.movie-card, div.col-movie")
        val results = elements.mapNotNull { toSearchResult(it) }
        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return parseLoadMetadata(document, url)
    }

    // Ana sayfa ve arama sonuclari sitenin kendi verisiyle gelir; TMDB yalnizca filme/diziye tiklaninca (load) devreye girer.
    suspend fun parseLoadMetadata(document: Document, url: String): LoadResponse? {
        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()

        val title = cleanTitle(rawTitle)

        val sitePoster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.poster img, img.img-fluid, img[src*='uploads']")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )

        val siteDesc = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("div.content-desc, div.story, p.desc")?.text()?.trim()

        val isTvSeries = url.contains("/dizi/")

        // ---------------- FILM ----------------
        if (!isTvSeries) {
            val tm = try { tmdbMatchMovie(title) } catch (e: Exception) { null }
            val poster = tm?.poster_path?.let { "$tmdbImg/w500$it" } ?: sitePoster
            val backdrop = tm?.backdrop_path?.let { "$tmdbImg/w1280$it" }
            val plot = tm?.overview?.takeIf { it.isNotBlank() } ?: siteDesc
            val year = tm?.release_date?.take(4)?.toIntOrNull()
            val tags = tm?.genres.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            val rating = tm?.vote_average?.takeIf { it > 0.0 }?.let { (it * 1000).toInt() }
            val actors = tmdbActors(tm?.credits)
            val runtime = tm?.runtime?.takeIf { it > 0 }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                if (year != null) this.year = year
                if (tags.isNotEmpty()) this.tags = tags
                if (rating != null) this.rating = rating
                if (runtime != null) this.duration = runtime
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        // ---------------- DIZI ----------------
        val episodeElements = document.select("a[href*='-sezon-'], a[href*='-bolum-'], a[href*='/bolum/']")
        val siteEpisodes = mutableListOf<Episode>()

        val sRegex = Regex("""(?:(\d+)-sezon|sezon-(\d+))""")
        val eRegex = Regex("""(?:(\d+)-bolum|bolum-(\d+))""")
        val seenUrls = mutableSetOf<String>()

        for (el in episodeElements) {
            val href = fixUrlNull(el.attr("href")) ?: continue
            if (!seenUrls.add(href)) continue

            val sMatch = sRegex.find(href)
            val seasonNum = sMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: sMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

            val eMatch = eRegex.find(href)
            val epNum = eMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: eMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1

            val epName = el.text().trim().takeIf { it.isNotBlank() } ?: "$seasonNum. Sezon $epNum. Bölüm"

            siteEpisodes.add(
                newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        val counts = siteEpisodes.groupBy { it.season ?: 0 }.mapValues { it.value.size }
        val tm = try { tmdbMatchTv(title, counts) } catch (e: Exception) { null }
        val episodes = try { tmdbBolumleri(tm, siteEpisodes) } catch (e: Exception) { siteEpisodes }

        val poster = tm?.poster_path?.let { "$tmdbImg/w500$it" } ?: sitePoster
        val backdrop = tm?.backdrop_path?.let { "$tmdbImg/w1280$it" }
        val plot = tm?.overview?.takeIf { it.isNotBlank() } ?: siteDesc
        val year = tm?.first_air_date?.take(4)?.toIntOrNull()
        val tags = tm?.genres.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
        val rating = tm?.vote_average?.takeIf { it > 0.0 }?.let { (it * 1000).toInt() }
        val actors = tmdbActors(tm?.credits)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            if (year != null) this.year = year
            if (tags.isNotEmpty()) this.tags = tags
            if (rating != null) this.rating = rating
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    // ---------------- TMDB: yalnizca filme/diziye tiklaninca (load) kullanilir ----------------
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val reGenericEp = Regex("""(?iu)^\s*(bölüm|episode|ep\.?)\s*\d+\s*$""")

    private fun norm(t: String): String = t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private fun dateMs(s: String?): Long? = try {
        if (s.isNullOrBlank()) null else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    private fun tmdbActors(c: SfTmdbCredits?): List<Actor> =
        c?.cast.orEmpty().take(20).mapNotNull { a ->
            val n = a.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Actor(n, a.profile_path?.let { "$tmdbImg/w185$it" })
        }

    // ----- film -----
    private suspend fun tmdbMatchMovie(title: String): SfTmdbMovieDetails? {
        val key = norm(title)
        if (key.isEmpty()) return null
        val results = try {
            app.get(
                "$tmdbApi/search/movie?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${java.net.URLEncoder.encode(title, "UTF-8")}",
                timeout = 15L
            ).parsedSafe<SfTmdbMovieSearch>()?.results.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        if (results.isEmpty()) return null
        val exact = results.filter {
            norm(it.title.orEmpty()) == key || norm(it.original_title.orEmpty()) == key
        }
        val pick = exact.maxByOrNull { it.popularity ?: 0.0 }
            ?: results.firstOrNull {
                val n = norm(it.title.orEmpty())
                n.isNotEmpty() && (n.contains(key) || key.contains(n))
            }
            ?: return null
        val id = pick.id ?: return null
        return try {
            app.get("$tmdbApi/movie/$id?api_key=$tmdbKey&language=tr-TR&append_to_response=credits", timeout = 15L)
                .parsedSafe<SfTmdbMovieDetails>()
        } catch (e: Exception) {
            null
        }
    }

    // ----- dizi -----
    private suspend fun tmdbSearchTv(q: String): List<SfTmdbTvResult> = try {
        app.get(
            "$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${java.net.URLEncoder.encode(q, "UTF-8")}",
            timeout = 15L
        ).parsedSafe<SfTmdbTvSearch>()?.results.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun tmdbTvDetails(id: Int): SfTmdbTvDetails? = try {
        app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=tr-TR&append_to_response=credits", timeout = 15L)
            .parsedSafe<SfTmdbTvDetails>()
    } catch (e: Exception) {
        null
    }

    // Sitedeki diziyi TMDB'deki dogru diziyle esler; ayni adli birden fazla dizi varsa sezon basina bolum sayisi en yakin olan secilir
    private suspend fun tmdbMatchTv(title: String, siteCounts: Map<Int, Int>): SfTmdbTvDetails? {
        val key = norm(title)
        if (key.isEmpty()) return null
        val results = tmdbSearchTv(title)
        if (results.isEmpty()) return null
        val exact = results.filter {
            norm(it.name.orEmpty()) == key || norm(it.original_name.orEmpty()) == key
        }
        val cands = if (exact.isNotEmpty()) exact.take(3) else listOfNotNull(
            results.firstOrNull {
                val n = norm(it.name.orEmpty())
                n.isNotEmpty() && (n.contains(key) || key.contains(n))
            }
        )
        if (cands.isEmpty()) return null
        if (cands.size == 1) return cands[0].id?.let { tmdbTvDetails(it) }
        val details = cands.amap { c -> c.id?.let { tmdbTvDetails(it) } }.filterNotNull()
        if (details.isEmpty()) return null
        return details.minByOrNull { d ->
            val cnt = d.seasons.orEmpty().associate { (it.season_number ?: -1) to (it.episode_count ?: 0) }
            var score = 0
            for ((sn, c) in siteCounts) score += kotlin.math.abs(c - (cnt[sn] ?: 0))
            score * 1000 - (d.popularity ?: 0.0).toInt().coerceAtMost(999)
        }
    }

    private suspend fun tmdbSeason(id: Int, sn: Int, lang: String): Map<Int, SfTmdbEpisode> = try {
        app.get("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=$lang", timeout = 15L)
            .parsedSafe<SfTmdbSeasonDetails>()?.episodes.orEmpty()
            .mapNotNull { e -> e.episode_number?.let { it to e } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    // Sitedeki her bolum satirina TMDB'den Turkce ad, aciklama, gorsel, sure ve tarih ekler. Sitede olmayan bolum eklenmez.
    private suspend fun tmdbBolumleri(tm: SfTmdbTvDetails?, siteEps: List<Episode>): List<Episode> {
        if (tm == null || siteEps.isEmpty()) return siteEps
        val tid = tm.id ?: return siteEps
        val seasons = siteEps.map { it.season ?: 0 }.distinct().filter { it > 0 }
        val rows = seasons.amap { sn ->
            Triple(sn, tmdbSeason(tid, sn, "tr-TR"), tmdbSeason(tid, sn, "en-US"))
        }
        val trMap = rows.associate { it.first to it.second }
        val enMap = rows.associate { it.first to it.third }

        return siteEps.map { old ->
            val sn = old.season ?: 0
            val en = old.episode ?: 0
            val tr = trMap[sn]?.get(en)
            val us = enMap[sn]?.get(en)
            if (tr == null && us == null) return@map old
            // Turkce ad yoksa ("Bolum 3" gibi) Ingilizce ad, o da yoksa sitenin adi
            val epName = tr?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: us?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: old.name
            val overview = tr?.overview?.takeIf { it.isNotBlank() } ?: us?.overview?.takeIf { it.isNotBlank() }
            val still = (tr?.still_path ?: us?.still_path)?.let { "$tmdbImg/w500$it" }
            val runtime = tr?.runtime ?: us?.runtime
            val airMs = dateMs(tr?.air_date ?: us?.air_date)
            newEpisode(old.data) {
                this.season = old.season
                this.episode = old.episode
                this.name = epName
                this.description = overview
                this.posterUrl = still
                if (runtime != null && runtime > 0) this.runTime = runtime
                if (airMs != null) this.date = airMs
            }
        }
    }

    // ---------------- Kaynaklar ----------------
    private data class StreamData(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("provider") val provider: String? = null
    )

    private data class VideoData(
        @JsonProperty("stream") val stream: StreamData? = null
    )

    private data class VideoResponse(
        @JsonProperty("success") val success: Boolean = false,
        @JsonProperty("data") val data: VideoData? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = AtomicBoolean(false)
        val document = app.get(data).document

        val postId = document.selectFirst("[data-post-id]")?.attr("data-post-id")
            ?: document.selectFirst("[data-id]")?.attr("data-id")

        val html = document.html()
        val nonceRegex = Regex("""video\s*:\s*["']([^"']+)["']""")
        val nonce = nonceRegex.find(html)?.groupValues?.getOrNull(1)

        val playerNames = document.select("button[data-player-name]").map { it.attr("data-player-name") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("SetPlay") }

        if (!postId.isNullOrBlank() && !nonce.isNullOrBlank()) {
            playerNames.amap { playerName ->
                try {
                    val resp = app.post(
                        "${mainUrl}/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "get_video_url",
                            "nonce" to nonce,
                            "post_id" to postId,
                            "player_name" to playerName,
                            "part_key" to ""
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                    ).parsedSafe<VideoResponse>()

                    val streamUrl = resp?.data?.stream?.url
                    if (!streamUrl.isNullOrBlank()) {
                        loadExtractor(fixUrl(streamUrl), data, subtitleCallback) { link ->
                            callback(link)
                            found.set(true)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (found.get()) return true
        }

        // Dogrudan iframe yedegi
        val iframes = document.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }
        iframes.amap { iframeUrl ->
            try {
                loadExtractor(iframeUrl, data, subtitleCallback) { link ->
                    callback(link)
                    found.set(true)
                }
            } catch (_: Exception) {
            }
        }

        return found.get()
    }
}

// ---------------- TMDB JSON veri siniflari (alan adlari TMDB JSON'u ile birebir ayni) ----------------
data class SfTmdbGenre(val name: String? = null)

data class SfTmdbCast(val name: String? = null, val profile_path: String? = null)

data class SfTmdbCredits(val cast: List<SfTmdbCast>? = null)

data class SfTmdbMovieResult(
    val id: Int? = null,
    val title: String? = null,
    val original_title: String? = null,
    val popularity: Double? = null
)

data class SfTmdbMovieSearch(val results: List<SfTmdbMovieResult>? = null)

data class SfTmdbMovieDetails(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Double? = null,
    val release_date: String? = null,
    val runtime: Int? = null,
    val genres: List<SfTmdbGenre>? = null,
    val credits: SfTmdbCredits? = null
)

data class SfTmdbTvResult(
    val id: Int? = null,
    val name: String? = null,
    val original_name: String? = null,
    val popularity: Double? = null
)

data class SfTmdbTvSearch(val results: List<SfTmdbTvResult>? = null)

data class SfTmdbSeasonInfo(
    val season_number: Int? = null,
    val episode_count: Int? = null
)

data class SfTmdbTvDetails(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Double? = null,
    val first_air_date: String? = null,
    val popularity: Double? = null,
    val genres: List<SfTmdbGenre>? = null,
    val seasons: List<SfTmdbSeasonInfo>? = null,
    val credits: SfTmdbCredits? = null
)

data class SfTmdbEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val runtime: Int? = null,
    val air_date: String? = null
)

data class SfTmdbSeasonDetails(val episodes: List<SfTmdbEpisode>? = null)
