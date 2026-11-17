package com.bymesut1

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.network.WebViewResolver
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

    private val json = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // textLarge: yanıt 5 MB'ı aşınca .text hata verir (logcat'teki sorun)
    private suspend inline fun <reified T : Any> get(path: String): T? = try {
        val body = app.get(base() + path, headers = headers).textLarge
        json.readValue(body, T::class.java)
    } catch (e: Exception) {
        Log.w("SeyirTURK", "get $path: ${e.message}")
        null
    }

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
        Log.d("SeyirTURK", "streams: $path -> ${links.size} kaynak")
        var any = false
        for ((i, s) in links.withIndex()) {
            val l = s.link ?: continue
            val label = listOfNotNull(s.mainProvider ?: s.provider, s.name).joinToString(" - ").ifBlank { "Kaynak ${i + 1}" }
            val ok = SeyirExtractor.resolve(l, label, mainUrl, headers, subtitleCallback, callback)
            Log.d("SeyirTURK", "kaynak[$i] $label -> ${if (ok) "OK" else "BULUNAMADI"} | $l")
            if (ok) any = true
        }
        return any
    }
}

// ---------- TMDB (sadece load() içinde kullanılır) ----------
/** TMDB sadece load() (diziye/filme tıklayınca) içinde kullanılır. */
object Tmdb {
    // >>> KENDİ TMDB API KEY'İNİ BURAYA YAZ (themoviedb.org > Ayarlar > API, "v3 auth") <<<
    const val API_KEY = "BURAYA_TMDB_API_KEY"

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

// ---------- Video kaynağı çözücüler (APK'nın new_parser_unob_safe.js dosyasından taşındı) ----------
object SeyirExtractor {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val TAG = "SeyirTURK"

    private val openPlayerHosts = listOf(
        "pichive", "selcukflix.com", "roketdizi", "hdfilmcehennemi.life",
        "dizipub3.life", "diziroll.club", "dizimia.live"
    )
    private val badFrames = listOf("googletag", "doubleclick", "recaptcha", "facebook.com", "disqus", "/ads/")

    // ---------- yardımcılar ----------
    fun decryptSecureData(data: String): JSONObject? = try {
        val sha = MessageDigest.getInstance("SHA-256").digest("!!22xx!!90!!".toByteArray())
        val key = Base64.encodeToString(sha, Base64.NO_WRAP).substring(0, 32).toByteArray()
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        JSONObject(String(c.doFinal(Base64.decode(data, Base64.DEFAULT))))
    } catch (e: Exception) {
        null
    }

    /** secureData bazen düz base64 JSON, bazen AES şifreli gelir. */
    private fun secureJson(data: String): JSONObject? {
        try {
            val t = String(Base64.decode(data, Base64.DEFAULT))
            if (t.contains("RelatedResults")) return JSONObject(t)
        } catch (e: Exception) {
        }
        return decryptSecureData(data)
    }

    private fun fix(s: String) = s.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
    private fun origin(u: String) = runCatching {
        val x = URI(u); "${x.scheme}://${x.host}"
    }.getOrDefault(u)

    private fun absUrl(href: String, base: String): String {
        val h = href.trim()
        return when {
            h.startsWith("http") -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> origin(base) + h
            else -> origin(base) + "/" + h
        }
    }

    private fun q(label: String) =
        Regex("(\\d{3,4})").find(label)?.value?.toIntOrNull() ?: Qualities.Unknown.value

    private fun unescapeJs(t: String): String =
        Regex("\\\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[ntrbf\"'\\\\/])").replace(t) { m ->
            val g = m.groupValues[1]
            when {
                g[0] == 'u' || g[0] == 'x' -> g.substring(1).toInt(16).toChar().toString()
                g == "n" -> "\n"
                g == "t" -> "\t"
                g == "r" -> "\r"
                else -> g
            }
        }

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

    private suspend fun direct(url: String, name: String, referer: String, cb: (ExtractorLink) -> Unit): Boolean {
        val u = fix(url)
        if (!u.startsWith("http")) return false
        if (u.contains(".m3u8")) m3u8("$name (Direkt)", u, origin(referer) + "/", Qualities.Unknown.value, cb)
        else cb(newExtractorLink("SeyirTURK", "$name (Direkt)", u) {
            this.referer = origin(referer) + "/"
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private suspend fun page(url: String, referer: String? = null, extra: Map<String, String> = emptyMap()): String? =
        try {
            app.get(url, headers = mapOf("User-Agent" to UA) + extra, referer = referer ?: (origin(url) + "/")).textLarge
        } catch (e: Exception) {
            Log.w(TAG, "page $url: ${e.message}")
            null
        }

    // ---------- giriş noktası ----------
    suspend fun resolve(
        rawLink: String, label: String, mainUrl: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = rawLink.trim().replace(Regex("[?&]l=\\d+.*"), "").let {
            if (it.startsWith("//")) "https:$it" else it
        }
        if (!link.startsWith("http")) return false

        // 1) siteye özel çözücüler
        try {
            if (site(link, label, mainUrl, headers, subtitleCallback, callback)) return true
        } catch (e: Exception) {
            Log.w(TAG, "site hata $label: ${e.message}")
        }

        // 2) doğrudan / bilinen oynatıcılar
        if (openPlayerHosts.any { link.contains(it) } && openPlayer(link, label, callback)) return true
        if (link.contains(".m3u8") || link.contains(".mp4")) return direct(link, label, link, callback)

        var ok = false
        try {
            ok = loadExtractor(link, mainUrl, subtitleCallback) { callback(it); ok = true }
        } catch (e: Exception) {
        }
        if (ok) return true
        if (webPage(link, label, mainUrl, headers, subtitleCallback, callback)) return true
        return serverParser(link, label, mainUrl, headers, callback)
    }

    private suspend fun site(
        link: String, label: String, mainUrl: String, headers: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val k = (link + " " + label).lowercase()
        return when {
            "dizilla" in k -> dizilla(link, label, sub, cb)
            "diziroll" in k -> diziroll(link, label, sub, cb)
            "dizimia" in k -> dizimia(link, label, sub, cb)
            "dizibox" in k -> dizibox(link, label, sub, cb)
            "ddizi" in k -> ddizi(link, label, sub, cb)
            "webteizle" in k -> webteizle(link, label, mainUrl, headers, sub, cb)
            "sinemabox" in k -> sinemabox(link, label, cb)
            "unutulmaz" in k -> unutulmaz(link, label, sub, cb)
            "hdfilmcehennemi" in k || "boncuk" in k || "syrtrk" in k -> hdfilmcehennemi(link, label, sub, cb)
            listOf("sinemaizle", "dizirella", "dizipal", "dizitime").any { it in k } -> webview(link, label, cb)
            listOf("sinefil", "selcukflix", "roketdizi", "yabancidizi", "yabanci-dizi").any { it in k } ->
                webPage(link, label, mainUrl, headers, sub, cb)
            else -> false
        }
    }

    // ---------- iframe / gömülü oynatıcı ----------
    private suspend fun embed(
        raw: String, referer: String, label: String,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit, depth: Int = 0
    ): Boolean {
        var u = fix(raw.trim()).replace("&amp;", "&")
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http") || badFrames.any { u.contains(it) }) return false
        if (openPlayerHosts.any { u.contains(it) } && openPlayer(u, label, cb)) return true
        if (u.contains(".m3u8") || u.contains(".mp4")) return direct(u, label, referer, cb)
        var ok = false
        try {
            ok = loadExtractor(u, referer, sub) { cb(it); ok = true }
        } catch (e: Exception) {
        }
        if (ok) return true
        if (depth >= 2) return false
        val html = page(u, referer) ?: return false
        if (harvest(html, u, label, cb)) return true
        return frames(html, u, label, sub, cb, depth + 1)
    }

    private suspend fun frames(
        html: String, base: String, label: String,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit, depth: Int = 0
    ): Boolean {
        var ok = false
        for (m in Regex("<iframe[^>]+(?:data-src|src)=[\"'](.*?)[\"']").findAll(html)) {
            if (embed(absUrl(m.groupValues[1], base), base, label, sub, cb, depth)) ok = true
        }
        return ok
    }

    /** Sayfadaki doğrudan m3u8 / mp4 linkleri. */
    private suspend fun harvest(html: String, base: String, label: String, cb: (ExtractorLink) -> Unit): Boolean {
        var ok = false
        val t = html.replace("\\/", "/")
        for (m in Regex("(https?://[^\\s\"'<>\\\\]+?\\.m3u8[^\\s\"'<>\\\\]*)").findAll(t).take(3)) {
            m3u8("$label (HLS)", m.groupValues[1], origin(base) + "/", Qualities.Unknown.value, cb); ok = true
        }
        if (!ok) for (m in Regex("(https?://[^\\s\"'<>\\\\]+?\\.mp4[^\\s\"'<>\\\\]*)").findAll(t).take(2)) {
            direct(m.groupValues[1], label, base, cb); ok = true
        }
        return ok
    }

    // ---------- siteye özel ----------
    private suspend fun dizilla(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link)?.replace("\\/", "/") ?: return false
        val u = Regex("""https://four\.pichive\.online/iframe\.php[^"\\\s]*""").find(html)?.value
            ?: return harvest(html, link, label, cb) || frames(html, link, label, sub, cb)
        return embed(u, origin(link) + "/", label, sub, cb)
    }

    private suspend fun diziroll(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link) ?: return false
        val ep = Regex("focus:outline-none\"\\s*href=\"(.*?)\"\\s*title=\"(.*?)\"\\s*data-navigo")
            .find(html)?.groupValues?.get(1)
        val epUrl = if (ep != null) absUrl(ep, link) else link
        val h2 = page(epUrl, link) ?: return false
        var ok = false
        for (m in Regex("class=\"player\".*?iframe.*?src=\"(.*?)\"").findAll(h2)) {
            if (embed(absUrl(m.groupValues[1], epUrl), epUrl, label, sub, cb)) ok = true
        }
        return ok || frames(h2, epUrl, label, sub, cb)
    }

    private suspend fun dizimia(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link) ?: return false
        val alts = Regex("<a class=\"focus:outline-none\"\\s*href=\"(.*?)\"\\s*title=\"(.*?)\"").findAll(html).toList()
        if (alts.isEmpty()) return frames(html, link, label, sub, cb)
        var ok = false
        for (m in alts.take(6)) {
            val u = absUrl(m.groupValues[1], link)
            val h = page(u, link) ?: continue
            val f = Regex("<iframe.*?src=\"(.*?)\"").find(h)?.groupValues?.get(1) ?: continue
            if (embed(absUrl(f, u), u, "$label ${m.groupValues[2]}", sub, cb)) ok = true
        }
        return ok
    }

    private suspend fun innerFrame(f: String, ref: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val fu = absUrl(f, ref)
        if (!fu.contains("haydi.php") && !fu.contains("moly.php")) return embed(fu, ref, label, sub, cb)
        val h = page(fu, ref) ?: return false
        var body = h
        if (fu.contains("moly.php")) {
            val raw = Regex("unescape\\(\"(.*?)\"\\)").findAll(h).map { it.groupValues[1] }
                .firstOrNull { !it.contains("display:") }
            if (raw != null) {
                val dec = runCatching { String(Base64.decode(URLDecoder.decode(raw, "UTF-8"), Base64.DEFAULT)) }.getOrNull()
                if (dec != null) body = dec
            }
        }
        val src = Regex("<iframe[^>]*src=[\"'](.*?)[\"']").findAll(body).map { it.groupValues[1] }.firstOrNull()
            ?: return harvest(body, fu, label, cb)
        return embed(absUrl(src, fu), fu, label, sub, cb)
    }

    private suspend fun dizibox(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link) ?: return false
        val block = Regex("woca-linkpages-dd selectBox(.*?)/select", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        val urls: List<Pair<String, String>> = if (block != null)
            Regex("(?:value=[\"']|href=[\"'])(.*?)[\"'].*?>(.*?)<").findAll(block)
                .map { it.groupValues[1] to it.groupValues[2].trim() }
                .filter { it.first.startsWith("http") || it.first.startsWith("/") }.toList()
        else listOf(link to "")
        var ok = false
        for ((u0, n) in urls.take(6)) {
            val u = absUrl(u0, link)
            val h = page(u, link) ?: continue
            val f = Regex("frame.*?src=\"(.*?)\".*?webkitallowfullscreen").find(h)?.groupValues?.get(1) ?: continue
            if (innerFrame(f, u, "$label $n".trim(), sub, cb)) ok = true
        }
        return ok || frames(html, link, label, sub, cb)
    }

    private suspend fun ddizi(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link) ?: return false
        val v = Regex("\"og:video\"\\s*content=\"(.*?)\"").find(html)?.groupValues?.get(1)
            ?: return harvest(html, link, label, cb) || frames(html, link, label, sub, cb)
        if (v.contains("youtube")) return embed(v, link, label, sub, cb)
        val h = page(v, link) ?: return false
        val f = Regex("file\\s*:\\s*\"(.*?)\"").find(h)?.groupValues?.get(1) ?: return harvest(h, v, label, cb)
        return direct(f, label, link, cb)
    }

    private suspend fun webteizle(
        link: String, label: String, mainUrl: String, headers: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val cands = listOf(link, link.substringAfter(origin(link)))
        for (c in cands) {
            val r = try {
                app.get("$mainUrl/sey/kodi/proxies/webteizle.php?path=izle&url=" + URLEncoder.encode(c, "UTF-8"), headers = headers).text
            } catch (e: Exception) {
                continue
            }
            val u = Regex("https?://[^\\s\"'<>]+").find(r)?.value ?: continue
            if (embed(u, link, label, sub, cb)) return true
        }
        return false
    }

    private suspend fun sinemabox(link: String, label: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link) ?: return false
        val names = Regex("<button>(.*?)</button>").findAll(html).map { it.groupValues[1] }.toList()
        val fr = Regex("iframe data-src=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        var ok = false
        for ((i, f) in fr.withIndex()) {
            val fu = absUrl(f, link)
            val h = page(fu, origin(fu)) ?: continue
            val txt = Regex("var\\s*video\\s*=\\s*(.*?\\});").find(h)?.groupValues?.get(1) ?: continue
            val v = runCatching { JSONObject(txt) }.getOrNull() ?: continue
            val u = origin(fu) + "/m3u8/" + v.optString("uid") + "/" + v.optString("md5") +
                "/master.txt?s=1&id=" + v.optString("id") + "&cache=" + v.optString("status")
            m3u8("$label ${names.getOrNull(i) ?: ""}".trim(), u, origin(link) + "/", Qualities.Unknown.value, cb)
            ok = true
        }
        return ok
    }

    private suspend fun unutulmaz(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link) ?: return false
        var ok = false
        Regex("<iframe.*?src=(.*?)\\s").find(html)?.groupValues?.get(1)?.replace("\"", "")?.let {
            if (embed(absUrl(it, link), link, label, sub, cb)) ok = true
        }
        for (m in Regex("<li class=\"part\">\\s*<a href=\"(.*?)\"").findAll(html).take(6)) {
            val h = page(absUrl(m.groupValues[1], link), link) ?: continue
            val f = Regex("<iframe.*?src=(.*?)\\s").find(h)?.groupValues?.get(1)?.replace("\"", "") ?: continue
            if (embed(absUrl(f, link), link, label, sub, cb)) ok = true
        }
        return ok || frames(html, link, label, sub, cb)
    }

    private suspend fun hdfilmcehennemi(link: String, label: String, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit): Boolean {
        val html = page(link) ?: return false
        val js = Regex("JSON\\.parse\\('(.+?)'\\)").find(html)?.groupValues?.get(1)
        var ok = false
        if (js != null) {
            val root = runCatching { JSONObject(unescapeJs(js)) }.getOrNull()
            if (root != null) for (k in root.keys()) {
                val arr = root.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    for (vk in o.keys()) {
                        val v = o.optString(vk)
                        if (v.length < 40) continue
                        val dec = runCatching { String(Base64.decode(v, Base64.DEFAULT)) }.getOrNull() ?: continue
                        val src = Regex("data-src=\"([^\"]+)\"").find(dec)?.groupValues?.get(1) ?: continue
                        val u = src.replace("{url}", o.optString("link")).replace("{slug}", o.optString("slug"))
                        val n = o.optString("name").ifBlank { o.optString("title") }
                        if (embed(absUrl(u, link), link, "$label $n".trim(), sub, cb)) ok = true
                    }
                }
            }
        }
        return ok || harvest(html, link, label, cb) || frames(html, link, label, sub, cb)
    }

    /** WebView gerektiren siteler: istekleri izleyip m3u8'i yakalar. */
    private suspend fun webview(link: String, label: String, cb: (ExtractorLink) -> Unit): Boolean {
        val r = try {
            app.get(link, referer = origin(link) + "/", interceptor = WebViewResolver(Regex("""\.m3u8|master\.txt|\.mp4""")))
        } catch (e: Exception) {
            Log.w(TAG, "webview ${e.message}"); return false
        }
        val u = r.url
        return if (u.contains(".m3u8") || u.contains(".txt") || u.contains(".mp4")) direct(u, label, link, cb) else false
    }

    // ---------- openPlayer: /source2.php?v=ID ----------
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

    // ---------- Next.js: sinefil / selcukflix / roketdizi / yabancidizi ----------
    private fun relatedSources(rel: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun add(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) out.add(o)
            }
        }
        for (k in rel.keys()) {
            if (k.startsWith("get") && k.contains("Source")) add(rel.optJSONObject(k)?.optJSONArray("result"))
        }
        val parts = rel.optJSONObject("getMoviePartsById")?.optJSONArray("result")
        if (parts != null) for (i in 0 until parts.length()) {
            val id = parts.optJSONObject(i)?.optString("id") ?: continue
            add(rel.optJSONObject("getMoviePartSourcesById_$id")?.optJSONArray("result"))
        }
        return out.distinctBy { it.optString("source_content") }
    }

    private fun nextFlight(html: String): String {
        val sb = StringBuilder()
        for (m in Regex("<script\\b[^>]*>\\s*self\\.__next_f\\.push\\(\\s*(\\[[\\s\\S]*?)\\s*\\)\\s*;?\\s*</script\\s*>").findAll(html)) {
            try {
                val a = JSONArray(m.groupValues[1])
                if (a.optInt(0) == 1 && a.opt(1) is String) sb.append(a.getString(1))
            } catch (e: Exception) {
            }
        }
        return sb.toString()
    }

    private suspend fun webPage(
        url: String, label: String, mainUrl: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = page(url) ?: return false
        var found = false

        // secureData -> RelatedResults
        val secure = Regex("\"secureData\":\"(.*?)\"").find(html)?.groupValues?.get(1)
            ?: Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
                ?.groupValues?.get(1)?.let { runCatching { JSONObject(it).getJSONObject("props").getJSONObject("pageProps").optString("secureData") }.getOrNull() }
        if (!secure.isNullOrBlank()) {
            val rel = secureJson(secure)?.optJSONObject("RelatedResults")
            if (rel != null) for (o in relatedSources(rel)) {
                val src = Regex("src=\\\\?[\"'](.*?)\\\\?[\"']").find(o.optString("source_content"))?.groupValues?.get(1) ?: continue
                val name = "$label - ${o.optString("language_name")} ${o.optString("quality_name")}".trim()
                if (embed(absUrl(fix(src), url), url, name, subtitleCallback, callback)) found = true
            }
            if (found) return true
        } else {
            // secureData yoksa sunucu çözücüsü (yabanci.php)
            val flight = nextFlight(html)
            val m = Regex("\"enc\"\\s*:\\s*(\\{[^{}]*\\})\\s*,\\s*\"k\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")").find(flight)
            if (m != null) {
                val v1 = runCatching { JSONArray("[" + m.groupValues[2] + "]").getString(0) }.getOrNull()
                if (v1 != null) {
                    val r = runCatching {
                        app.post(
                            "$mainUrl/sey/back/v2/parser/yabanci.php", headers = headers,
                            data = mapOf("v1" to v1, "v2" to m.groupValues[1])
                        ).text.trim()
                    }.getOrNull()
                    Log.d(TAG, "yabanci.php -> $r")
                    if (r != null && r.startsWith("http") && embed(r, url, label, subtitleCallback, callback)) return true
                }
            }
        }
        if (frames(html, url, label, subtitleCallback, callback)) return true
        return harvest(html, url, label, callback)
    }

    /** APK'nın kullandığı sunucu tarafı çözücü (yanıt biçimi doğrulanmadı; m3u8/mp4 arar). */
    private suspend fun serverParser(
        url: String, label: String, mainUrl: String, headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val enc = URLEncoder.encode(url, "UTF-8")
        val body = runCatching {
            app.get("$mainUrl/sey/back/v2/parser/parser_test.php?url=$enc&lang=TR", headers = headers).text
        }.getOrNull() ?: return false
        return harvest(body, url, "$label (Parser)", callback)
    }
}
