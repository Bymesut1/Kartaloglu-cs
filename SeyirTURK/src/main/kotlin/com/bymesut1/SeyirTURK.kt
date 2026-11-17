package com.bymesut1

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ---------- JSON modelleri (cs3'ten çıkarıldı) ----------
data class SeyirMovie(
    @JsonProperty("ID") val id: String? = null,
    @JsonProperty("Name") val name: String? = null,
    @JsonProperty("Summary") val summary: String? = null,
    @JsonProperty("Image") val image: String? = null,
    @JsonProperty("Backdrop") val backdrop: String? = null,
    @JsonProperty("Type") val type: String? = null,
    @JsonProperty("Genres") val genres: String? = null,
    @JsonProperty("ReleaseDate") val releaseDate: String? = null,
    @JsonProperty("IMDBScore") val imdbScore: String? = null,
    @JsonProperty("Runtime") val runtime: String? = null,
)
data class SeyirEpisode(
    @JsonProperty("ID") val id: String? = null,
    @JsonProperty("E_ID") val eId: String? = null,
    @JsonProperty("Season") val season: String? = null,
    @JsonProperty("Episode") val episode: String? = null,
    @JsonProperty("Summary") val summary: String? = null,
    @JsonProperty("Image") val image: String? = null,
    @JsonProperty("IMDBScore") val imdbScore: String? = null,
    @JsonProperty("ReleaseDate") val releaseDate: String? = null,
)
data class SeyirStream(
    @JsonProperty("Name") val name: String? = null,
    @JsonProperty("Provider") val provider: String? = null,
    @JsonProperty("MainProvider") val mainProvider: String? = null,
    @JsonProperty("Link") val link: String? = null,
)
data class SeyirMainPage(
    @JsonProperty("first") val first: List<SeyirMovie>? = null,
    @JsonProperty("first_alt") val firstAlt: List<SeyirMovie>? = null,
    @JsonProperty("second") val second: List<SeyirMovie>? = null,
    @JsonProperty("third") val third: List<SeyirMovie>? = null,
    @JsonProperty("fourth") val fourth: List<SeyirMovie>? = null,
)
data class SeyirMovies(@JsonProperty("movies") val movies: List<SeyirMovie>? = null)
data class SeyirEpisodes(@JsonProperty("episodes") val episodes: List<SeyirEpisode>? = null)
data class SeyirStreams(@JsonProperty("links") val links: List<SeyirStream>? = null)
data class SeyirIptvItem(
    @JsonProperty("ID") val id: String? = null,
    @JsonProperty("Name") val name: String? = null,
    @JsonProperty("Link") val link: String? = null,
    @JsonProperty("Image") val image: String? = null,
)
data class SeyirIptv(
    @JsonProperty("movies") val movies: List<SeyirIptvItem>? = null,
    @JsonProperty("links") val links: List<SeyirIptvItem>? = null,
)

class SeyirTURKProvider : MainAPI() {
    override var mainUrl = "http://beytepemanav.tk"   // rootcheck ile güncellenir
    override var name = "SeyirTURK"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    override val mainPage = mainPageOf(
        "tv_featured" to "📺 Vizyondaki Diziler",
        "tv_latest" to "✨ Son Eklenen Diziler",
        "movie_featured" to "🎬 Vizyondakiler (Film)",
        "movie_latest" to "🆕 Son Eklenen Filmler",
        "movie_popular" to "🔥 Popüler Filmler",
        "genre_trdub" to "🗣️ Türkçe Dublaj Filmler",
        "genre_trsub" to "📝 Türkçe Altyazılı Filmler",
        "genre_yerli" to "🇹🇷 Yerli Filmler",
        "genre_4k" to "💎 4K Ultra HD Filmler",
        "live_iptv" to "📡 SeyirTURK IPTV Kanalları",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:19.0) Gecko/20100101 Firefox/19.0",
        "bety" to "jughead",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    private var baseResolved = false
    private val cache = HashMap<String, List<SearchResponse>>()

    // ---------- Sunucu adresi ----------
    private suspend fun base(): String {
        if (baseResolved) return mainUrl
        for (u in listOf("http://seyirturk.net/rootcheck/android.php", "https://seyirturk.net/rootcheck/android.php")) {
            try {
                val r = app.get(u, headers = headers)
                if (!r.isSuccessful || r.text.isBlank()) continue
                val dec = String(Base64.decode(r.text.trim().reversed(), Base64.DEFAULT)).trim().removeSuffix("/")
                if (dec.startsWith("http")) {
                    mainUrl = dec; baseResolved = true
                    Log.d("SeyirTURK", "Sunucu: $dec"); break
                }
            } catch (e: Exception) {
                Log.w("SeyirTURK", "rootcheck: ${e.message}")
            }
        }
        return mainUrl
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun img(u: String?): String? {
        if (u.isNullOrBlank()) return null
        var x = u.trim()
        if (x.startsWith("//")) x = "https:$x"
        x = x.replace("/original/", "/w500/")
        return if (x.startsWith("http")) x else null
    }

    private fun year(d: String?) = d?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    private suspend inline fun <reified T> get(path: String): T? =
        runCatching { app.get(base() + path, headers = headers).parsedSafe<T>() }.getOrNull()

    // ---------- Liste öğesi ----------
    private fun SeyirMovie.toSearch(): SearchResponse? {
        val i = id ?: return null
        val n = name ?: return null
        return if (type.equals("TVSeries", true))
            newTvSeriesSearchResponse(n, "$mainUrl/series/$i", TvType.TvSeries) { posterUrl = img(image) }
        else
            newMovieSearchResponse(n, "$mainUrl/movie/$i", TvType.Movie) { posterUrl = img(image) }
    }

    private suspend fun items(key: String): List<SearchResponse> {
        cache[key]?.let { return it }
        val out: List<SearchResponse> = when {
            key.startsWith("genre_") -> {
                val g = mapOf("genre_trdub" to "TRDUB", "genre_trsub" to "TRSUB", "genre_yerli" to "Yerli", "genre_4k" to "4KK")[key]
                get<SeyirMovies>("/sey/back/show.php?type=genre&genre=$g")?.movies.orEmpty().mapNotNull { it.toSearch() }
            }
            key == "live_iptv" -> {
                val r = get<SeyirIptv>("/sey/kodi/iptv/premium.php")
                (r?.links.orEmpty().ifEmpty { r?.movies.orEmpty() }).mapNotNull {
                    val n = it.name ?: return@mapNotNull null
                    val l = it.link ?: return@mapNotNull null
                    newLiveSearchResponse(n, "$mainUrl/live/?name=${enc(n)}&img=${enc(it.image ?: "")}&link=${enc(l)}", TvType.Live) {
                        posterUrl = img(it.image)
                    }
                }
            }
            else -> {
                val tv = key.startsWith("tv_")
                val r = get<SeyirMainPage>(if (tv) "/sey/back/mainpage.php?p_type=TV" else "/sey/back/mainpage.php")
                // NOT: first/second/third eşlemesi cs3'e göre tahmindir; boşsa sıradaki dolu listeye düşer.
                val order = when (key) {
                    "tv_latest", "movie_latest" -> listOf(r?.second, r?.first, r?.third, r?.fourth)
                    "movie_popular" -> listOf(r?.third, r?.second, r?.first, r?.fourth)
                    else -> listOf(r?.first, r?.firstAlt, r?.second, r?.third)
                }
                order.firstOrNull { !it.isNullOrEmpty() }.orEmpty().mapNotNull { it.toSearch() }
            }
        }
        if (out.isNotEmpty()) cache[key] = out
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        base()
        return newHomePageResponse(HomePageList(request.name, items(request.data), false), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = enc(query)
        val movies = get<SeyirMovies>("/sey/back/search.php?name=$q")?.movies.orEmpty()
        val tv = get<SeyirMovies>("/sey/back/search.php?p_type=TV&name=$q")?.movies.orEmpty()
        return (movies + tv).mapNotNull { it.toSearch() }.distinctBy { it.url }
    }

    // ---------- Detay sayfası (TMDB burada devreye girer) ----------
    override suspend fun load(url: String): LoadResponse? {
        base()
        if (url.contains("/live/")) {
            val p = url.substringAfter("?").split("&").associate {
                it.substringBefore("=") to dec(it.substringAfter("=", ""))
            }
            return newLiveStreamLoadResponse(p["name"] ?: "Canlı Yayın", url, url) {
                posterUrl = img(p["img"]); plot = "SeyirTURK Canlı Yayın"
            }
        }

        val m = Regex("/(movie|series)/(\\d+)").find(url) ?: return null
        val isTv = m.groupValues[1] == "series"
        val id = m.groupValues[2]
        val info = get<SeyirMovies>("/sey/back/show.php?type=single&id=$id" + if (isTv) "&p_type=TV" else "")
            ?.movies?.firstOrNull() ?: return null
        val title = info.name ?: "SeyirTURK"
        val y = year(info.releaseDate)
        val tmdb = Tmdb.details(title, y, isTv)
        val tmdbPoster = tmdb?.posterPath?.let { Tmdb.IMG + "w500" + it }
        val tmdbBack = tmdb?.backdropPath?.let { Tmdb.IMG + "original" + it }
        val plotText = tmdb?.overview?.takeIf { it.isNotBlank() } ?: info.summary
        val tags = tmdb?.genres?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() }
            ?: info.genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val scoreVal = tmdb?.voteAverage?.takeIf { it > 0 } ?: info.imdbScore?.toDoubleOrNull()
        val yr = year(tmdb?.firstAirDate ?: tmdb?.releaseDate) ?: y
        val actorList = tmdb?.credits?.cast.orEmpty().take(15).mapNotNull { c ->
            c.name?.let { Actor(it, c.profilePath?.let { p -> Tmdb.IMG + "w185" + p }) }
        }
        val trailer = tmdb?.videos?.results.orEmpty()
            .firstOrNull { it.site == "YouTube" && it.type == "Trailer" && !it.key.isNullOrBlank() }
            ?.let { "https://www.youtube.com/watch?v=${it.key}" }

        if (!isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, "movie|$id|1|1") {
                posterUrl = tmdbPoster ?: img(info.image)
                backgroundPosterUrl = tmdbBack ?: img(info.backdrop)
                plot = plotText
                this.score = Score.from10(scoreVal)
                this.year = yr
                this.tags = tags
                duration = tmdb?.runtime ?: info.runtime?.filter { it.isDigit() }?.toIntOrNull()
                addActors(actorList)
                addTrailer(trailer)
            }
        }

        val siteEps = get<SeyirEpisodes>("/sey/back/episodes.php?id=$id")?.episodes.orEmpty()
        val seasons = siteEps.mapNotNull { it.season?.toIntOrNull() }.distinct()
        val tmdbEps = HashMap<Int, Map<Int, Tmdb.Ep>>()
        if (tmdb?.id != null) seasons.forEach { tmdbEps[it] = Tmdb.season(tmdb.id, it) }

        val episodes = siteEps.map { e ->
            val s = e.season?.toIntOrNull() ?: 1
            val n = e.episode?.toIntOrNull() ?: 1
            val t = tmdbEps[s]?.get(n)
            newEpisode("series|$id|$s|$n") {
                this.name = t?.name?.takeIf { it.isNotBlank() } ?: "Bölüm $n"
                this.season = s
                this.episode = n
                this.posterUrl = t?.stillPath?.let { Tmdb.IMG + "w500" + it } ?: img(e.image)
                this.description = t?.overview?.takeIf { it.isNotBlank() } ?: e.summary
                (t?.voteAverage?.takeIf { it > 0 } ?: e.imdbScore?.toDoubleOrNull())?.let { this.score = Score.from10(it) }
                addDate(t?.airDate ?: e.releaseDate)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = tmdbPoster ?: img(info.image)
            backgroundPosterUrl = tmdbBack ?: img(info.backdrop)
            plot = plotText
            this.score = Score.from10(scoreVal)
            this.year = yr
            this.tags = tags
            addActors(actorList)
            addTrailer(trailer)
        }
    }

    // ---------- Video linkleri ----------
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        base()
        if (data.contains("/live/")) {
            val link = dec(data.substringAfter("link=").substringBefore("&"))
            val n = dec(data.substringAfter("name=", "Canlı").substringBefore("&"))
            return SeyirExtractor.resolve(link, "$name - $n", mainUrl, headers, subtitleCallback, callback)
        }

        val p = data.split("|")
        val kind = p.getOrNull(0) ?: return false
        val id = p.getOrNull(1) ?: return false
        val path = if (kind == "series")
            "/sey/back/streams.php?id=$id&s=${p.getOrNull(2) ?: "1"}&isTv=1&e=${p.getOrNull(3) ?: "1"}"
        else "/sey/back/streams.php?id=$id"

        val links = get<SeyirStreams>(path)?.links.orEmpty()
        var any = false
        for ((i, s) in links.withIndex()) {
            val l = s.link ?: continue
            val label = listOfNotNull(s.mainProvider ?: s.provider, s.name).joinToString(" - ").ifBlank { "Kaynak ${i + 1}" }
            if (SeyirExtractor.resolve(l, label, mainUrl, headers, subtitleCallback, callback)) any = true
        }
        return any
    }
}

// ---------- TMDB (sadece load() içinde kullanılır) ----------
/** TMDB sadece load() (diziye/filme tıklayınca) içinde kullanılır. */
object Tmdb {
    // >>> KENDİ TMDB API KEY'İNİ BURAYA YAZ (themoviedb.org > Ayarlar > API, "v3 auth") <<<
    const val API_KEY = "000316508321ce461cf81e7c6815eec7"

    private const val API = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/"
    private const val LANG = "tr-TR"

    val enabled get() = API_KEY.isNotBlank() && !API_KEY.startsWith("BURAYA")

    data class Search(@JsonProperty("results") val results: List<Item>? = null)
    data class Item(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
    )
    data class Genre(@JsonProperty("name") val name: String? = null)
    data class SeasonRef(@JsonProperty("season_number") val seasonNumber: Int? = null)
    data class Cast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )
    data class Credits(@JsonProperty("cast") val cast: List<Cast>? = null)
    data class Video(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
    data class Videos(@JsonProperty("results") val results: List<Video>? = null)
    data class Details(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("seasons") val seasons: List<SeasonRef>? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("videos") val videos: Videos? = null,
    )
    data class Ep(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
    )
    data class Season(@JsonProperty("episodes") val episodes: List<Ep>? = null)

    private fun clean(t: String) = t
        .replace(Regex("\\(\\d{4}\\)"), "")
        .replace(Regex("(?i)\\s+izle.*$"), "")
        .trim()

    private suspend fun searchId(title: String, year: Int?, tv: Boolean): Int? {
        val kind = if (tv) "tv" else "movie"
        val q = java.net.URLEncoder.encode(clean(title), "UTF-8")
        val yearParam = if (year == null) "" else if (tv) "&first_air_date_year=$year" else "&year=$year"
        for (u in listOf(
            "$API/search/$kind?api_key=$API_KEY&language=$LANG&query=$q$yearParam",
            "$API/search/$kind?api_key=$API_KEY&language=$LANG&query=$q",
        )) {
            val r = runCatching { app.get(u).parsedSafe<Search>() }.getOrNull()
            val id = r?.results?.firstOrNull()?.id
            if (id != null) return id
        }
        return null
    }

    suspend fun details(title: String, year: Int?, tv: Boolean): Details? {
        if (!enabled) return null
        val id = searchId(title, year, tv) ?: return null
        val kind = if (tv) "tv" else "movie"
        val u = "$API/$kind/$id?api_key=$API_KEY&language=$LANG" +
            "&append_to_response=credits,videos&include_video_language=tr,en,null"
        return runCatching { app.get(u).parsedSafe<Details>() }.getOrNull()
    }

    suspend fun season(tvId: Int, season: Int): Map<Int, Ep> {
        val u = "$API/tv/$tvId/season/$season?api_key=$API_KEY&language=$LANG"
        val s = runCatching { app.get(u).parsedSafe<Season>() }.getOrNull()
        return s?.episodes?.filter { it.episodeNumber != null }?.associateBy { it.episodeNumber!! } ?: emptyMap()
    }
}

// ---------- Video kaynağı çözücüler ----------
/** Video kaynağı çözücüler (cs3 / APK'daki mantığın portu). */
object SeyirExtractor {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val openPlayerHosts = listOf(
        "pichive", "selcukflix.com", "roketdizi.life", "hdfilmcehennemi.life",
        "dizipub3.life", "diziroll.club", "dizimia.live"
    )

    fun decryptSecureData(data: String): JSONObject? = try {
        val sha = MessageDigest.getInstance("SHA-256").digest("!!22xx!!90!!".toByteArray())
        val key = Base64.encodeToString(sha, Base64.NO_WRAP).substring(0, 32).toByteArray()
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        JSONObject(String(c.doFinal(Base64.decode(data, Base64.DEFAULT))))
    } catch (e: Exception) {
        null
    }

    private fun fix(s: String) = s.replace("\\/", "/").replace("\\", "")
    private fun origin(u: String) = runCatching {
        val x = URI(u); "${x.scheme}://${x.host}"
    }.getOrDefault(u)

    private fun q(label: String) =
        Regex("(\\d{3,4})").find(label)?.value?.toIntOrNull() ?: Qualities.Unknown.value

    private suspend fun m3u8(
        name: String, url: String, referer: String, quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(newExtractorLink("SeyirTURK", name, url, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = quality
            this.headers = mapOf("User-Agent" to UA, "Referer" to referer)
        })
    }

    /** Tek giriş noktası. true = en az bir link bulundu. */
    suspend fun resolve(
        rawLink: String, label: String, mainUrl: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = rawLink.trim().replace(Regex("[?&]l=\\d+.*"), "").let {
            if (it.startsWith("//")) "https:$it" else it
        }
        if (!link.startsWith("http")) return false

        if (openPlayerHosts.any { link.contains(it) } && openPlayer(link, label, callback)) return true

        if (link.contains(".m3u8")) {
            m3u8("$label (Direkt)", link, origin(link) + "/", Qualities.Unknown.value, callback); return true
        }
        if (link.contains(".mp4")) {
            callback(newExtractorLink("SeyirTURK", "$label (Direkt)", link) {
                this.referer = origin(link) + "/"
                this.quality = Qualities.Unknown.value
            }); return true
        }

        var ok = false
        runCatching {
            ok = loadExtractor(link, mainUrl, subtitleCallback) { callback(it); ok = true }
        }
        if (ok) return true
        if (webPage(link, label, subtitleCallback, callback)) return true
        return serverParser(link, label, mainUrl, headers, callback)
    }

    /** openPlayer('ID') -> /source2.php?v=ID */
    private suspend fun openPlayer(url: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val h = mapOf("User-Agent" to UA, "Referer" to origin(url) + "/")
        val page = runCatching { app.get(url, headers = h).text }.getOrNull() ?: return false
        val id = Regex("(?:window\\.)?openPlayer\\s*\\(\\s*['\"]([^'\"]+)['\"]").find(page)?.groupValues?.get(1)
            ?: return false
        val src = runCatching {
            app.get(
                origin(url) + "/source2.php?v=" + id,
                headers = h + mapOf("Origin" to origin(url), "Referer" to url)
            ).text
        }.getOrNull() ?: return false
        var found = false
        val withLabel = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"label\"\\s*:\\s*\"([^\"]+)\"").findAll(src).toList()
        for (m in withLabel) {
            m3u8("$label (${m.groupValues[2]})", fix(m.groupValues[1]), origin(url) + "/", q(m.groupValues[2]), callback)
            found = true
        }
        if (!found) Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").findAll(src).forEach {
            m3u8("$label (Full HD)", fix(it.groupValues[1]), origin(url) + "/", Qualities.Unknown.value, callback)
            found = true
        }
        return found
    }

    /** Next.js sayfası: __NEXT_DATA__ -> secureData -> AES -> kaynak iframe'leri */
    private suspend fun webPage(
        url: String, label: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val h = mapOf("User-Agent" to UA, "Referer" to origin(url) + "/")
        val html = runCatching { app.get(url, headers = h).text }.getOrNull() ?: return false
        var found = false

        val nd = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (nd != null) runCatching {
            val secure = JSONObject(nd).getJSONObject("props").getJSONObject("pageProps").optString("secureData")
            val rel = decryptSecureData(secure)?.optJSONObject("RelatedResults")
            for (k in listOf("getMovieSources", "getMoviePartSources", "getEpisodeSources")) {
                val arr = rel?.optJSONObject(k)?.optJSONArray("result") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val src = Regex("(?:src=[\"']|^)(https?://[^\"'\\s>]+|//[^\"'\\s>]+)")
                        .find(o.optString("source_content"))?.groupValues?.get(1) ?: continue
                    val full = if (src.startsWith("//")) "https:$src" else src
                    val name = "$label - ${o.optString("language_name")} ${o.optString("quality_name")}".trim()
                    if (full.contains("pichive") && openPlayer(full, name, callback)) { found = true; continue }
                    runCatching { loadExtractor(full, url, subtitleCallback) { callback(it); found = true } }
                }
            }
        }
        if (found) return true

        Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']").findAll(html).map { it.groupValues[1] }
            .filter { !it.contains("googletag") && !it.contains("youtube") }.forEach {
                val full = if (it.startsWith("//")) "https:$it" else it
                if (full.startsWith("http")) runCatching {
                    loadExtractor(full, url, subtitleCallback) { l -> callback(l); found = true }
                }
            }
        if (found) return true

        Regex("(https?://[^\\s\"']+\\.m3u8[^\\s\"']*)").findAll(html).forEach {
            m3u8("$label (HLS)", fix(it.groupValues[1]), origin(url) + "/", Qualities.Unknown.value, callback)
            found = true
        }
        return found
    }

    /** APK'nın kullandığı sunucu tarafı çözücü (yanıt biçimi doğrulanmadı; m3u8/mp4 arar). */
    private suspend fun serverParser(
        url: String, label: String, mainUrl: String, headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val enc = java.net.URLEncoder.encode(url, "UTF-8")
        val body = runCatching {
            app.get("$mainUrl/sey/back/v2/parser/parser_test.php?url=$enc", headers = headers).text
        }.getOrNull() ?: return false
        var found = false
        Regex("(https?:\\\\?/\\\\?/[^\\s\"'<>]+?\\.(?:m3u8|mp4)[^\\s\"'<>]*)").findAll(body).forEach {
            val u = fix(it.groupValues[1])
            if (u.contains(".m3u8")) m3u8("$label (Parser)", u, origin(url) + "/", Qualities.Unknown.value, callback)
            else callback(newExtractorLink("SeyirTURK", "$label (Parser)", u) { this.referer = origin(url) + "/" })
            found = true
        }
        return found
    }
}
