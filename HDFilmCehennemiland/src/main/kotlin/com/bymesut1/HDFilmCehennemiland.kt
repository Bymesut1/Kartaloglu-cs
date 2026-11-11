package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

// Hata ayıklama: true iken kaynak bulunamazsa listede "🐞" satırları çıkar,
// logcat'e "HDFilmCehennemiland" etiketiyle kayıt düşer. Bitince false yap.
private const val HD_DEBUG = true
private const val HD_TAG = "HDFilmCehennemiland"

// TMDB: https://www.themoviedb.org/settings/api adresinden ücretsiz "API Key (v3)" al,
// tırnakların içine yapıştır. Boş bırakırsan sadece site bilgileri kullanılır.
private const val TMDB_API_KEY = "000316508321ce461cf81e7c6815eec7"

private const val HD_SITE = "https://www.hdfilmcehennemi.land"

// ------------------------------------------------------------------ ortak yardımcılar

private val hdMediaRegex = Regex(
    """https?://[^\s"'<>\\]+?(?:\.(?:m3u8|mp4|mpd)|/manifests/[^\s"'<>\\]+?\.txt|/(?:master|playlist|index)\.txt)[^\s"'<>\\]*"""
)
private val hdUrlRegex = Regex("""(?:https?:)?//[^\s"'<>\\)]+""")
private val hdAssetRegex =
    Regex("""(?i)\.(?:js|css|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|otf|map)(?:[?#].*)?$""")
private val hdHostRegex =
    Regex("""^https?://[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?::\d+)?(?:/|$)""")
private val hdBlocked = listOf(
    "jsdelivr", "bootstrapcdn", "cloudflare", "googleapis", "gstatic", "jquery", "w3.org",
    "schema.org", "fontawesome", "google", "facebook", "twitter", "instagram", "doubleclick",
    "disqus", "youtube", "imdb.com", "themoviedb", "tmdb.org", "gravatar", "hdfilmcehennemi",
    "devtools", "cdnjs", "unpkg", "://x.com", "twimg", "wp.com", "api.w.org", "wordpress",
    "googletagmanager", "gtag"
)

private fun hdLog(msg: String) = Log.d(HD_TAG, msg)

// Anlık DNS ("Unable to resolve host") / timeout hatalarına karşı 3 deneme yapar.
// Hepsi başarısız olursa null döner, uygulama hata ekranı göstermez.
private suspend fun hdGet(
    url: String,
    referer: String? = null,
    params: Map<String, String> = emptyMap(),
    tries: Int = 3
): NiceResponse? {
    repeat(tries) { i ->
        try {
            return app.get(url, referer = referer, params = params, timeout = 25L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hdLog("hdGet hata (${i + 1}/$tries) $url: ${e.javaClass.simpleName} ${e.message}")
            if (i < tries - 1) delay(800L * (i + 1))
        }
    }
    return null
}

// Uzun metni logcat'e parça parça yazar (satır ~4000 karakterde kesiliyor)
private fun hdLogBody(tag: String, body: String, max: Int = 15000) {
    hdLog("$tag uzunluk=${body.length}")
    body.take(max).chunked(3000).forEachIndexed { i, c -> hdLog("$tag[$i] $c") }
}

private fun hdOrigin(url: String): String =
    runCatching { URI(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(HD_SITE)


// WebView'in gerçek isteğinden alınan başlıklar (User-Agent, Cookie, Referer) ile doğrudan M3U8 linki üretir.
// generateM3u8 kullanılmaz: ek bir istek atıp tek kullanımlık/başlığa bağlı manifesti bozabiliyor.
private val hdSkipHeaders = setOf(
    "host", "accept-encoding", "connection", "range", "content-length",
    "if-none-match", "if-modified-since", "x-requested-with"
)

private suspend fun hdEmitCaptured(
    name: String, url: String, reqHeaders: Map<String, String>, callback: (ExtractorLink) -> Unit
) {
    val hdrs = LinkedHashMap<String, String>()
    reqHeaders.forEach { (k, v) -> if (k.lowercase() !in hdSkipHeaders) hdrs[k] = v }
    if (hdrs.keys.none { it.equals("cookie", true) }) {
        val ck = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!ck.isNullOrBlank()) hdrs["Cookie"] = ck
    }
    if (hdrs.keys.none { it.equals("origin", true) }) hdrs["Origin"] = hdOrigin(url)
    val ref = hdrs.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: "${hdOrigin(url)}/"
    hdLog("emit başlıklar: ${hdrs.keys.joinToString(",")} referer=$ref")
    val type = if (url.lowercase().contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
    callback(
        newExtractorLink(name, name, url, type) {
            this.referer = ref
            this.headers = hdrs
            this.quality = Qualities.Unknown.value
        }
    )
}

// \/ \uXXXX \xXX açar, packed JS çözer, base64 içindeki adresleri ortaya çıkarır
private fun hdExpand(raw: String): String {
    var t = raw.replace("\\/", "/").replace("&amp;", "&")
    t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
    t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
    val extra = StringBuilder()
    if (raw.contains("eval(function(p,a,c,k,e,d)")) {
        try {
            extra.append("\n").append(getAndUnpack(raw).replace("\\/", "/"))
        } catch (e: Exception) {
            hdLog("unpack hata: ${e.message}")
        }
    }
    Regex("""["']([A-Za-z0-9+/_-]{24,}={0,2})["']""").findAll(t).take(40).forEach { m ->
        try {
            val s = m.groupValues[1].replace('-', '+').replace('_', '/')
            val dec = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
            if (dec.contains("http") || dec.contains(".m3u8") || dec.contains(".mp4")) {
                extra.append("\n").append(dec.replace("\\/", "/"))
            }
        } catch (ignored: Exception) {
        }
    }
    return t + extra.toString()
}

private fun hdPickMedia(text: String): String? =
    hdMediaRegex.findAll(text).map { it.value }.distinct()
        .sortedByDescending { if (it.contains("master", true)) 1 else 0 }
        .firstOrNull()

private fun hdCandidates(text: String, selfUrl: String): List<String> =
    hdUrlRegex.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', '\\') }
        .map { if (it.startsWith("//")) "https:$it" else it }
        .filter { u ->
            u != selfUrl && hdHostRegex.containsMatchIn(u) &&
                hdBlocked.none { u.contains(it, true) } &&
                !hdAssetRegex.containsMatchIn(u) && !u.contains("/assets/")
        }
        .distinct()
        .toList()

private suspend fun hdEmit(name: String, url: String, referer: String, callback: (ExtractorLink) -> Unit) {
    val hdrs = mapOf("Origin" to hdOrigin(referer), "Referer" to referer)
    if (url.lowercase().contains(".mp4")) {
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.headers = hdrs
                this.quality = Qualities.Unknown.value
            }
        )
        return
    }
    val links = try {
        M3u8Helper.generateM3u8(source = name, streamUrl = url, referer = referer, headers = hdrs)
    } catch (e: Exception) {
        hdLog("generateM3u8 hata: ${e.message}")
        null
    }
    if (!links.isNullOrEmpty()) {
        links.forEach(callback)
    } else {
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.headers = hdrs
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// "file":"...vtt","label":"Türkçe" biçimindeki altyazıları yakalar
private suspend fun hdSubs(text: String, subtitleCallback: (SubtitleFile) -> Unit) {
    Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"[^}]*?"label"\s*:\s*"([^"]+)"""")
        .findAll(text).forEach { m ->
            subtitleCallback(newSubtitleFile(m.groupValues[2], m.groupValues[1]))
        }
}

// ------------------------------------------------------------------ FastPlay (SetPlay) çıkarıcı

class FastPlay : ExtractorApi() {
    override var name = "SetPlay"
    override var mainUrl = "https://fastplay.mom"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: "$HD_SITE/"
        val text = hdExpand(app.get(url, referer = ref).text)
        hdSubs(text, subtitleCallback)
        val media = hdPickMedia(text) ?: return
        hdEmit(name, media, "${hdOrigin(url)}/", callback)
    }
}

// ------------------------------------------------------------------ Ana sınıf

class HDFilmCehennemiland : MainAPI() {
    override var mainUrl = HD_SITE
    override var name = "HDFilmCehennemiland"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/turkce-dublaj-filmler-izle-1/" to "Türkçe Dublaj Filmler",
        "$mainUrl/film-izle-1/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/yerli-film-izle-3/" to "Yerli Filmler",
        "$mainUrl/dizi/" to "Diziler",
        "$mainUrl/turkce-dublaj-diziler-izle-1/" to "Türkçe Dublaj Diziler",
        "$mainUrl/turkce-altyazili-diziler-izle/" to "Türkçe Altyazılı Diziler",
        "$mainUrl/yerli-dizi-izle-3/" to "Yerli Diziler",
        "$mainUrl/imdb-en-iyiler-izle/" to "IMDb En İyiler",
        "$mainUrl/populer-izle/" to "Popüler",
        "$mainUrl/boxset-izle/" to "Seriler",
        "$mainUrl/tur/aksiyon-izle-1/" to "Aksiyon",
        "$mainUrl/tur/komedi-izle-2/" to "Komedi",
        "$mainUrl/tur/korku-izle-2/" to "Korku",
        "$mainUrl/tur/bilim-kurgu-izle/" to "Bilim-Kurgu",
        "$mainUrl/tur/animasyon-izle/" to "Animasyon",
        "$mainUrl/tur/dram-izle-2/" to "Dram"
    )

    private val dbgBuf = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun dbg(msg: String) {
        hdLog(msg)
        if (HD_DEBUG) dbgBuf.add(msg)
    }

    private val cardRegex = Regex("""^https?://[^/]+/(film|dizi)/[^/?#]+/?$""")

    private fun cleanTitle(s: String): String =
        s.replace(Regex("""\s+(Small\s+)?Poster\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+izle(\s*\|.*)?$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*HDFilmcehennemi\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    // ---------------------------------------------------------------- liste / arama

    private fun parseCards(doc: Document): List<SearchResponse> {
        val groups = doc.select("a[href]").groupBy { fixUrl(it.attr("href")) }
        val out = ArrayList<SearchResponse>()
        for ((href, anchors) in groups) {
            if (!cardRegex.matches(href)) continue
            val img = anchors.firstNotNullOfOrNull { it.selectFirst("img") }
            val poster = img?.let { i ->
                listOf("data-src", "data-lazy-src", "src").map { i.attr(it) }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            }
            val candidates = listOf(
                img?.attr("alt"),
                anchors.map { it.attr("title") }.firstOrNull { it.isNotBlank() },
                anchors.map { it.text() }.firstOrNull { it.isNotBlank() }
            )
            val title = candidates.mapNotNull { c -> c?.let { cleanTitle(it) }?.takeIf { it.isNotBlank() } }
                .firstOrNull() ?: continue

            if (href.contains("/dizi/")) {
                out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster)
                })
            } else {
                out.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = fixUrlNull(poster)
                })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = hdGet(url, referer = "$mainUrl/")?.document
        val items = if (doc != null) parseCards(doc) else emptyList()
        hdLog("getMainPage url=$url kart=${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = hdGet("$mainUrl/", referer = "$mainUrl/", params = mapOf("s" to query))?.document
            ?: return emptyList()
        return parseCards(doc)
    }

    // ---------------------------------------------------------------- TMDB

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private suspend fun tmdb(path: String, extra: String = ""): JSONObject? {
        if (TMDB_API_KEY.isBlank()) return null
        return try {
            val sep = if (path.contains("?")) "&" else "?"
            JSONObject(app.get("https://api.themoviedb.org/3$path${sep}api_key=$TMDB_API_KEY$extra").text)
        } catch (e: Exception) {
            hdLog("tmdb hata $path: ${e.message}")
            null
        }
    }

    private suspend fun findTmdbId(isSeries: Boolean, imdb: String?, queries: List<String>, year: Int?): Int? {
        var id: Int? = null
        if (imdb != null) {
            val r = tmdb("/find/$imdb", "&external_source=imdb_id")
            id = r?.optJSONArray(if (isSeries) "tv_results" else "movie_results")
                ?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        }
        if (id == null) {
            for (q in queries) {
                val enc = URLEncoder.encode(q, "UTF-8")
                val y = if (year != null) (if (isSeries) "&first_air_date_year=$year" else "&year=$year") else ""
                val r = tmdb(if (isSeries) "/search/tv" else "/search/movie", "&query=$enc$y&language=tr-TR")
                id = r?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
                if (id != null) break
            }
        }
        return id
    }

    private fun img(path: String?, size: String): String? =
        path?.let { "https://image.tmdb.org/t/p/$size$it" }

    private data class EpMeta(
        val name: String?, val overview: String?, val still: String?,
        val date: String?, val vote: Double?, val runtime: Int?
    )

    private suspend fun tmdbSeason(tvId: Int, season: Int): Map<Int, EpMeta> {
        val tr = tmdb("/tv/$tvId/season/$season", "&language=tr-TR")?.optJSONArray("episodes")
        var enArr: org.json.JSONArray? = null
        val needEn = (0 until (tr?.length() ?: 0)).any { tr?.optJSONObject(it)?.str("overview") == null }
        if (needEn) enArr = tmdb("/tv/$tvId/season/$season", "&language=en-US")?.optJSONArray("episodes")
        val enMap = HashMap<Int, JSONObject>()
        for (i in 0 until (enArr?.length() ?: 0)) {
            enArr?.optJSONObject(i)?.let { enMap[it.optInt("episode_number")] = it }
        }
        val out = HashMap<Int, EpMeta>()
        for (i in 0 until (tr?.length() ?: 0)) {
            val e = tr?.optJSONObject(i) ?: continue
            val n = e.optInt("episode_number")
            val fb = enMap[n]
            out[n] = EpMeta(
                name = e.str("name") ?: fb?.str("name"),
                overview = e.str("overview") ?: fb?.str("overview"),
                still = img(e.str("still_path"), "w780"),
                date = e.str("air_date"),
                vote = if (e.has("vote_average")) e.optDouble("vote_average").takeIf { it > 0 } else null,
                runtime = e.optInt("runtime").takeIf { it > 0 }
            )
        }
        return out
    }

    private data class Meta(
        val title: String, val plot: String?, val poster: String?, val backdrop: String?,
        val year: Int?, val tags: List<String>, val score: Double?, val duration: Int?,
        val actors: List<ActorData>, val trailer: String?, val imdbId: String?, val tmdbId: Int?
    )

    private suspend fun LoadResponse.applyMeta(m: Meta) {
        this.posterUrl = m.poster
        this.backgroundPosterUrl = m.backdrop
        this.plot = m.plot
        this.year = m.year
        this.tags = m.tags
        this.duration = m.duration
        m.score?.let { this.score = Score.from10(it) }
        if (m.actors.isNotEmpty()) this.actors = m.actors
        addTrailer(m.trailer)
        addImdbId(m.imdbId)
    }

    // ---------------------------------------------------------------- Detay

    override suspend fun load(url: String): LoadResponse? {
        val doc: Document = hdGet(url, referer = "$mainUrl/")?.document ?: return null
        val isSeries = url.contains("/dizi/")

        val h1: org.jsoup.nodes.Element? = doc.selectFirst("h1")
        val siteTitle = cleanTitle(
            h1?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
        )
        val origTitle = h1?.nextElementSibling()?.text()?.trim()
            ?.takeIf { it.isNotBlank() && it.length < 120 && !it.equals(siteTitle, true) }
        val year = doc.selectFirst("meta[name=twitter:data1]")?.attr("content")?.trim()?.toIntOrNull()
            ?: doc.selectFirst("a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
        val imdbHref: org.jsoup.nodes.Element? = doc.selectFirst("a[href*=imdb.com/title/]")
        val imdbId = Regex("""tt\d{6,10}""").find(imdbHref?.attr("href") ?: "")?.value
        val imdbScore = Regex("""\d+(?:[.,]\d+)?""").find(imdbHref?.text() ?: "")
            ?.value?.replace(',', '.')?.toDoubleOrNull()

        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val sitePlot = doc.select("p").map { it.text().trim() }
            .filter { it.length > 80 }.maxByOrNull { it.length }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val siteTags = doc.select("a[href*=/tur/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val siteActors = doc.select("a[href*=/oyuncu/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            .map { ActorData(Actor(it, null)) }

        // --- TMDB
        val tmId = findTmdbId(isSeries, imdbId, listOfNotNull(origTitle, siteTitle).distinct(), year)
        var d: JSONObject? = null
        if (tmId != null) {
            d = tmdb(
                "/${if (isSeries) "tv" else "movie"}/$tmId",
                "&language=tr-TR&append_to_response=credits,videos,external_ids&include_video_language=tr,en,null"
            )
        }
        var overview = d?.str("overview")
        if (overview == null && tmId != null) {
            overview = tmdb("/${if (isSeries) "tv" else "movie"}/$tmId", "&language=en-US")?.str("overview")
        }
        val cast = d?.optJSONObject("credits")?.optJSONArray("cast")
        val tmActors = ArrayList<ActorData>()
        for (i in 0 until minOf(cast?.length() ?: 0, 15)) {
            val c = cast?.optJSONObject(i) ?: continue
            val n = c.str("name") ?: continue
            tmActors.add(ActorData(Actor(n, img(c.str("profile_path"), "w185")), roleString = c.str("character")))
        }
        var trailer: String? = null
        val vids = d?.optJSONObject("videos")?.optJSONArray("results")
        for (i in 0 until (vids?.length() ?: 0)) {
            val v = vids?.optJSONObject(i) ?: continue
            if (v.str("site") == "YouTube" && v.str("type") == "Trailer") {
                trailer = "https://www.youtube.com/watch?v=${v.str("key")}"
                break
            }
        }
        val genres = d?.optJSONArray("genres")
        val tmTags = (0 until (genres?.length() ?: 0)).mapNotNull { genres?.optJSONObject(it)?.str("name") }
        val dateStr = d?.str(if (isSeries) "first_air_date" else "release_date")
        val tmYear = dateStr?.take(4)?.toIntOrNull()
        val runtime = if (isSeries) d?.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        else d?.optInt("runtime")?.takeIf { it > 0 }
        val vote = d?.takeIf { it.has("vote_average") }?.optDouble("vote_average")?.takeIf { it > 0 }

        val meta = Meta(
            title = d?.str(if (isSeries) "name" else "title") ?: siteTitle,
            plot = overview ?: sitePlot,
            poster = img(d?.str("poster_path"), "w500") ?: fixUrlNull(sitePoster),
            backdrop = img(d?.str("backdrop_path"), "original"),
            year = tmYear ?: year,
            tags = tmTags.ifEmpty { siteTags },
            score = vote ?: imdbScore,
            duration = runtime,
            actors = tmActors.ifEmpty { siteActors },
            trailer = trailer,
            imdbId = imdbId ?: d?.optJSONObject("external_ids")?.str("imdb_id") ?: d?.str("imdb_id"),
            tmdbId = tmId
        )
        hdLog("load: $siteTitle tmdb=$tmId imdb=$imdbId series=$isSeries")

        if (!isSeries) {
            return newMovieLoadResponse(meta.title, url, TvType.Movie, url) { applyMeta(meta) }
        }

        // --- Bölümler (site) + TMDB bölüm bilgisi
        val slugRegex = Regex("""-(\d+)-sezon-(\d+)-bolum""")
        val eps = doc.select("a[href*=/bolum/]").map { fixUrl(it.attr("href")) }.distinct().mapNotNull { href ->
            val m = slugRegex.find(href) ?: return@mapNotNull null
            Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), href)
        }.sortedWith(compareBy({ it.first }, { it.second }))

        val seasonMeta = HashMap<Int, Map<Int, EpMeta>>()
        if (tmId != null) {
            coroutineScope {
                eps.map { it.first }.distinct().map { s -> async { s to tmdbSeason(tmId, s) } }
                    .awaitAll().forEach { (s, mp) -> seasonMeta[s] = mp }
            }
        }

        val episodes = eps.map { (s, e, href) ->
            val em = seasonMeta[s]?.get(e)
            newEpisode(href) {
                this.season = s
                this.episode = e
                this.name = em?.name ?: "$e. Bölüm"
                this.posterUrl = em?.still ?: meta.poster
                this.description = em?.overview
                em?.vote?.let { this.score = Score.from10(it) }
                this.runTime = em?.runtime
                addDate(em?.date)
            }
        }
        hdLog("load: bölüm=${episodes.size}")
        return newTvSeriesLoadResponse(meta.title, url, TvType.TvSeries, episodes) { applyMeta(meta) }
    }

    // ---------------------------------------------------------------- Kaynaklar

    private fun sourceLabel(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: return "Player"
        return if (host.contains("fastplay")) "SetPlay" else host.substringBefore(".").replaceFirstChar { it.uppercase() }
    }

    private fun pageEmbeds(doc: Document, pageUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        val attrs = listOf(
            "src", "data-src", "data-lazy-src", "data-url", "data-link",
            "data-video", "data-iframe", "data-embed", "data-player", "data-frame"
        )
        doc.select("iframe, [data-src], [data-url], [data-link], [data-video], [data-iframe], [data-embed], [data-player], [data-frame]")
            .forEach { e ->
                attrs.forEach { a ->
                    val v = e.attr(a).trim()
                    if (v.startsWith("http") || v.startsWith("//")) {
                        val full = if (v.startsWith("//")) "https:$v" else v
                        if (!hdAssetRegex.containsMatchIn(full) && hdBlocked.none { full.contains(it, true) }) out.add(full)
                    }
                }
            }
        out.addAll(hdCandidates(hdExpand(doc.html()), pageUrl))
        return out.sortedByDescending {
            if (it.contains("fastplay", true) || it.contains("player", true) || it.contains("embed", true)) 1 else 0
        }
    }

    // Sitenin kendi yöntemi: admin-ajax.php -> action=get_video_url -> data.url (iframe adresi)
    private suspend fun resolveViaAjax(
        doc: Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val scripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val nonce = Regex("""videoAjax\s*=\s*\{[^}]*?nonce\s*:\s*['"]([^'"]+)['"]""")
            .find(scripts)?.groupValues?.get(1)
        val ajaxUrl = Regex("""videoAjax\s*=\s*\{[^}]*?ajaxurl\s*:\s*['"]([^'"]+)['"]""")
            .find(scripts)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: "$mainUrl/wp-admin/admin-ajax.php"
        hdLog("ajax nonce=$nonce url=$ajaxUrl")
        if (nonce == null) {
            dbg("videoAjax nonce bulunamadı")
            return false
        }

        val opts = doc.select("a[data-post-id][data-player-name]").mapNotNull { a ->
            val pid = a.attr("data-post-id").trim()
            val pn = a.attr("data-player-name").trim()
            if (pid.isBlank() || pn.isBlank()) null else Triple(pid, pn, a.attr("data-part-key").trim())
        }.distinct()
        hdLog("ajax kaynak seçenekleri=${opts.size}: $opts")
        if (opts.isEmpty()) {
            dbg("kaynak butonu (data-player-name) bulunamadı")
            return false
        }

        val results = coroutineScope {
            opts.take(6).map { (pid, pn, pk) ->
                async {
                    withTimeoutOrNull(40_000L) {
                        try {
                            val res = app.post(
                                ajaxUrl,
                                data = mapOf(
                                    "action" to "get_video_url",
                                    "nonce" to nonce,
                                    "post_id" to pid,
                                    "player_name" to pn,
                                    "part_key" to pk
                                ),
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                referer = pageUrl
                            )
                            hdLog("ajax[$pn] kod=${res.code} cevap=${res.text.take(400)}")
                            val u = runCatching {
                                JSONObject(res.text).optJSONObject("data")?.str("url")
                            }.getOrNull()
                            if (u == null) {
                                dbg("ajax cevabında url yok ($pn): ${res.text.take(150)}")
                                false
                            } else {
                                val full = if (u.startsWith("//")) "https:$u" else u
                                resolveEmbed(full, "$mainUrl/", subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            dbg("ajax hata ($pn): ${e.message}")
                            false
                        }
                    } ?: false
                }
            }.awaitAll()
        }
        return results.any { it }
    }

    // Teşhis: oynatıcıyı yükleyen script'i bulmak için sayfadaki script'leri tarar, logcat'e yazar
    private suspend fun dumpScripts(doc: Document, pageUrl: String) {
        val kw = Regex("fimcnt|data-player-name|playerName|player_name|admin-ajax|wp-json|loadingIframe|play-button|get_video|sourceslist")
        fun scan(label: String, text: String) {
            val hits = kw.findAll(text).map { it.range.first }.toList()
            if (hits.isEmpty()) {
                hdLog("JS[$label] anahtar kelime yok (${text.length} karakter)")
                return
            }
            var last = -100000
            var n = 0
            for (h in hits) {
                if (h - last < 1500) continue
                last = h
                if (++n > 6) break
                val s = text.substring(maxOf(0, h - 600), minOf(text.length, h + 1400))
                s.chunked(3000).forEachIndexed { i, c -> hdLog("JS[$label@$h/$i] $c") }
            }
        }

        val srcs = doc.select("script[src]").map { fixUrl(it.attr("src")) }.distinct()
        hdLog("SCRIPT SRC (${srcs.size}): ${srcs.joinToString(" | ")}")

        doc.select("script:not([src])").forEachIndexed { i, s -> scan("inline$i", s.data()) }

        srcs.filter { u ->
            !u.contains("wp-includes", true) && !u.contains("google", true) &&
                !u.contains("facebook", true) && !u.contains("jquery", true)
        }.take(12).forEach { u ->
            try {
                val t = app.get(u, referer = pageUrl).text
                scan(u.substringAfterLast('/').take(50), t)
            } catch (e: Exception) {
                hdLog("JS[$u] indirilemedi: ${e.message}")
            }
        }
    }

    private suspend fun viaWebView(
        name: String, url: String, referer: String, callback: (ExtractorLink) -> Unit,
        timeoutMs: Long = 20_000L
    ): Boolean = try {
        hdLog("WebView deneniyor: $url")
        val resolver = WebViewResolver(
            Regex("""(?i)\.(?:m3u8|mp4|mpd)(?:[?#].*)?$|/manifests/.+\.txt|/(?:master|playlist|index)\.txt"""),
            useOkhttp = false,
            // sayfa açılınca oynat tuşuna basar / video.play() dener (otomatik başlamayan oynatıcılar için)
            script = """
                (function(){
                  var t=0, i=setInterval(function(){
                    try{
                      var v=document.querySelector('video'); if(v){v.muted=true; v.play();}
                      ['.jw-icon-display','.vjs-big-play-button','.play-button','.play','#play','.plyr__control--overlay','[class*=play]','button']
                        .forEach(function(q){var e=document.querySelector(q); if(e) e.click();});
                    }catch(e){}
                    if(++t>12) clearInterval(i);
                  },1000);
                })();
            """.trimIndent(),
            timeout = timeoutMs
        )
        val req = resolver.resolveUsingWebView(url, referer).first
        if (req == null) {
            dbg("WebView medya yakalayamadı ($name)")
            false
        } else {
            val captured = req.url.toString()
            hdLog("WebView yakalanan: $captured")
            if (hdMediaRegex.containsMatchIn(captured)) {
                hdEmitCaptured(name, captured, req.headers.toMap(), callback)
                true
            } else {
                dbg("WebView medya yakalayamadı ($name)")
                false
            }
        }
    } catch (e: Exception) {
        dbg("WebView hata ($name): ${e.message}")
        false
    }

    private suspend fun resolveEmbed(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        val name = sourceLabel(url)
        hdLog("resolveEmbed[$depth] $name -> $url")

        // Gerçekten link üretildi mi sayılır (loadExtractor link üretmese de true dönebiliyor)
        val emitted = AtomicInteger(0)
        val cb: (ExtractorLink) -> Unit = { emitted.incrementAndGet(); callback(it) }

        if (hdMediaRegex.containsMatchIn(url.lowercase())) {
            hdEmit(name, url, referer, cb)
            return true
        }

        runCatching { loadExtractor(url, referer, subtitleCallback, cb) }
        if (emitted.get() > 0) return true
        if (depth > 2) return false
        val isSetPlay = url.contains("setplay.", true)

        val res = try {
            app.get(url, referer = referer, headers = mapOf("Sec-Fetch-Dest" to "iframe"), timeout = 20L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dbg("istek hatası ($name): ${e.message}")
            // ağ anlık hata verdiyse yine de WebView ile dene
            return if (depth == 0) viaWebView(name, url, referer, callback) else false
        }
        val text = hdExpand(res.text)
        hdSubs(text, subtitleCallback)
        val media = hdPickMedia(text)
        hdLog("fetch kod=${res.code} medya=$media")
        if (media != null) {
            hdEmit(name, media, "${hdOrigin(url)}/", cb)
            return true
        }

        // Oynatıcı sayfasının içeriğini logcat'e yaz (medya statik bulunamadıysa teşhis için)
        if (depth == 0 && HD_DEBUG) {
            hdLogBody("PLAYERSAYFA[$name]", res.text)
            val ss = res.document.select("script[src]").map { it.attr("src") }
            hdLog("PLAYERSAYFA[$name] script src: ${ss.joinToString(" | ")}")
        }

        // SetPlay oynatıcısı: link JS ile yükleniyor, boşuna aday kovalamadan doğrudan WebView ile yakala
        if (isSetPlay && depth == 0) {
            return viaWebView(name, url, referer, callback, 25_000L)
        }

        val next = LinkedHashSet<String>()
        res.document.select("iframe").forEach { f ->
            val src = f.attr("src").ifBlank { f.attr("data-src") }.trim()
            if (src.isNotBlank() && !src.startsWith("data:") && !src.startsWith("about:")) {
                runCatching { URI(url).resolve(src).toString() }.getOrNull()?.let { next.add(it) }
            }
        }
        if (depth == 0) next.addAll(hdCandidates(text, url).take(3))
        for (n in next.take(4)) {
            if (resolveEmbed(n, url, subtitleCallback, callback, depth + 1)) return true
        }

        if (depth == 0) {
            dbg("statik çıkarım başarısız ($name) kod=${res.code}")
            return viaWebView(name, url, referer, callback)
        }
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        hdLog("loadLinks data=$data")
        dbgBuf.clear()

        val doc = hdGet(data, referer = "$mainUrl/")?.document ?: return false

        // 1) Sitenin kendi yöntemi (admin-ajax get_video_url)
        val viaAjax = try {
            resolveViaAjax(doc, data, subtitleCallback, callback)
        } catch (e: Exception) {
            dbg("resolveViaAjax hata: ${e.message}")
            false
        }
        if (viaAjax) {
            hdLog("loadLinks bitti (ajax) found=true")
            return true
        }

        // Oynatıcı bölümünün HTML'i logcat'e yazılır (kaynak çıkmazsa buna bakılır)
        val playerHtml = doc.select(".player_sist, .playex, #playex, .controlx, .video-wrapper, .playerz").outerHtml()
        playerHtml.take(9000).chunked(3000).forEachIndexed { i, c -> hdLog("PLAYER[$i] $c") }

        // 2) Sayfadaki iframe / adaylar
        val frames = pageEmbeds(doc, data)
        hdLog("embed adayları=${frames.size}: ${frames.take(8).joinToString(" | ")}")

        val results = coroutineScope {
            frames.take(6).map { f ->
                async {
                    withTimeoutOrNull(30_000L) {
                        resolveEmbed(f, data, subtitleCallback, callback)
                    } ?: false
                }
            }.awaitAll()
        }
        var found = results.any { it }

        // Teşhis: kaynak yoksa oynatıcıyı yükleyen script'leri logcat'e dök
        if (!found && HD_DEBUG) {
            try {
                dumpScripts(doc, data)
            } catch (e: Exception) {
                hdLog("dumpScripts hata: ${e.message}")
            }
        }

        // 3) Son çare: sayfanın kendisini WebView ile aç, ağdan master.txt / m3u8 yakala
        if (!found) found = viaWebView("Player", data, "$mainUrl/", callback)

        if (!found && HD_DEBUG) {
            dbgBuf.toList().takeLast(8).forEach { msg ->
                callback(
                    newExtractorLink("DEBUG", "🐞 ${msg.take(300)}", "https://debug.local/", ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        hdLog("loadLinks bitti found=$found")
        return found
    }
}
