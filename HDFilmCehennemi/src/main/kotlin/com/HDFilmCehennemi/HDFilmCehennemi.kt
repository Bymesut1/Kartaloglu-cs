package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Collections
import kotlin.math.abs

private data class SiteItem(val url: String, val title: String, val year: Int, val type: String)
private data class Alt(val id: String, val name: String, val lang: String)
private data class Player(val name: String, val lang: String, val id: String?, val embed: String?)
private data class EmbedResult(val urls: List<String>, val subs: List<SubItem>, val strict: Set<String>, val base: String)
private data class Verified(val ok: Boolean, val headers: Map<String, String>)

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ---------------- Ayarlar ----------------
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbSite = "https://www.themoviedb.org"
    private val imgBase = "https://image.tmdb.org/t/p"
    private val hosts = listOf("https://hdfilmcehennemi.nl", "https://www.hdfilmcehennemi.nl")
    private val showSubbed = false          // true: "Altyazılı" oynatıcılar da eklensin
    private val maxPlayers = 4              // en fazla kaç oynatıcı (Close, Rapid ...)
    private val subLangs = listOf("tr")     // İngilizce de istersen: listOf("tr", "en")
    private val uaMobile = "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private var good: Pair<String, Boolean>? = null   // çalışan adres + UA biçimi
    private val IC = RegexOption.IGNORE_CASE

    // ---------------- Ana sayfa (TMDB, Türkçe) ----------------
    override val mainPage = mainPageOf(
        "movie/popular?region=TR" to "Popüler Filmler",
        "movie/now_playing?region=TR" to "Vizyondaki Filmler",
        "movie/top_rated" to "En Çok Oy Alan Filmler",
        "discover/movie?with_original_language=tr&sort_by=popularity.desc" to "Türk Filmleri",
        "tv/popular" to "Popüler Diziler",
        "tv/on_the_air" to "Yayındaki Diziler",
        "discover/tv?with_original_language=tr&sort_by=popularity.desc" to "Türk Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val url = "$tmdbApi/${request.data}${sep}api_key=$tmdbKey&language=tr-TR&page=$page"
        val isTv = request.data.startsWith("tv") || request.data.startsWith("discover/tv")
        val items = app.get(url).parsedSafe<TmdbList>()?.results.orEmpty()
            .mapNotNull { it.toSearch(if (isTv) "tv" else "movie") }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private suspend fun TmdbItem.toSearch(forced: String? = null): SearchResponse? {
        val type = forced ?: this.media_type ?: return null
        if (type != "movie" && type != "tv") return null
        val tid = this.id ?: return null
        val ttl = this.title ?: this.name ?: return null
        val poster = this.poster_path?.let { "$imgBase/w500$it" }
        val yr = (this.release_date ?: this.first_air_date)?.take(4)?.toIntOrNull()
        val url = "$tmdbSite/$type/$tid"
        return if (type == "tv") {
            newTvSeriesSearchResponse(ttl, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = yr
            }
        } else {
            newMovieSearchResponse(ttl, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = yr
            }
        }
    }

    // ---------------- Arama ----------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$tmdbApi/search/multi?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=$q"
        return app.get(url).parsedSafe<TmdbList>()?.results.orEmpty().mapNotNull { it.toSearch() }
    }

    // ---------------- Detay ----------------
    override suspend fun load(url: String): LoadResponse? {
        val m = Regex("""/(movie|tv)/(\d+)""").find(url) ?: return null
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        return if (type == "tv") loadSeries(id) else loadMovie(id)
    }

    private suspend fun getDetail(type: String, id: String, language: String): TmdbDetail? {
        val url = "$tmdbApi/$type/$id?api_key=$tmdbKey&language=$language" +
            "&append_to_response=external_ids,credits,videos&include_video_language=tr,en,null"
        return app.get(url).parsedSafe<TmdbDetail>()
    }

    private fun castOf(d: TmdbDetail): List<ActorData> {
        return d.credits?.cast.orEmpty().take(15).mapNotNull { c ->
            val n = c.name ?: return@mapNotNull null
            ActorData(Actor(n, c.profile_path?.let { "$imgBase/w185$it" }), roleString = c.character)
        }
    }

    private fun trailerKey(d: TmdbDetail): String? {
        val list = d.videos?.results.orEmpty().filter { it.site == "YouTube" }
        return (list.firstOrNull { it.type == "Trailer" } ?: list.firstOrNull())?.key
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        val d = getDetail("movie", id, "tr-TR") ?: return null
        val en = getDetail("movie", id, "en-US")
        val title = d.title ?: d.original_title ?: en?.title ?: return null
        val plotText = d.overview?.takeIf { it.isNotBlank() } ?: en?.overview
        val yr = d.release_date?.take(4)?.toIntOrNull()
        val imdb = d.imdb_id ?: d.external_ids?.imdb_id
        val titles = listOfNotNull(d.title, en?.title, d.original_title).filter { it.isNotBlank() }.distinct()
        val data = LinkData(id.toInt(), false, null, null, imdb, yr, titles).toJson()
        val actorList = castOf(d)
        val tKey = trailerKey(d)

        return newMovieLoadResponse(title, "$tmdbSite/movie/$id", TvType.Movie, data) {
            this.posterUrl = d.poster_path?.let { "$imgBase/w500$it" }
            this.backgroundPosterUrl = d.backdrop_path?.let { "$imgBase/original$it" }
            this.year = yr
            this.plot = plotText
            this.tags = d.genres?.mapNotNull { it.name }
            this.duration = d.runtime
            this.actors = actorList
            if (tKey != null) addTrailer("https://www.youtube.com/watch?v=$tKey")
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        val d = getDetail("tv", id, "tr-TR") ?: return null
        val en = getDetail("tv", id, "en-US")
        val title = d.name ?: d.original_name ?: en?.name ?: return null
        val plotText = d.overview?.takeIf { it.isNotBlank() } ?: en?.overview
        val yr = d.first_air_date?.take(4)?.toIntOrNull()
        val imdb = d.external_ids?.imdb_id
        val titles = listOfNotNull(d.name, en?.name, d.original_name).filter { it.isNotBlank() }.distinct()

        val seasons = d.seasons.orEmpty().filter { (it.season_number ?: 0) > 0 }
        val seasonList = seasons.amap { s ->
            app.get("$tmdbApi/tv/$id/season/${s.season_number}?api_key=$tmdbKey&language=tr-TR")
                .parsedSafe<TmdbSeason>()
        }.filterNotNull()

        val episodes = ArrayList<Episode>()
        seasonList.forEach { sn ->
            sn.episodes.orEmpty().forEach { ep ->
                val epNo = ep.episode_number ?: return@forEach
                val sNo = ep.season_number ?: sn.season_number ?: return@forEach
                val data = LinkData(id.toInt(), true, sNo, epNo, imdb, yr, titles).toJson()
                val epName = ep.name?.takeIf { it.isNotBlank() } ?: "$epNo. Bölüm"
                val epPlot = ep.overview?.takeIf { it.isNotBlank() }
                val epImg = ep.still_path?.let { "$imgBase/w300$it" }
                episodes.add(
                    newEpisode(data) {
                        this.name = epName
                        this.season = sNo
                        this.episode = epNo
                        this.posterUrl = epImg
                        this.description = epPlot
                        addDate(ep.air_date)
                    }
                )
            }
        }

        val actorList = castOf(d)
        val tKey = trailerKey(d)
        val sNames = seasons.mapNotNull { s -> s.season_number?.let { SeasonData(it, "$it. Sezon") } }

        return newTvSeriesLoadResponse(title, "$tmdbSite/tv/$id", TvType.TvSeries, episodes) {
            this.posterUrl = d.poster_path?.let { "$imgBase/w500$it" }
            this.backgroundPosterUrl = d.backdrop_path?.let { "$imgBase/original$it" }
            this.year = yr
            this.plot = plotText
            this.tags = d.genres?.mapNotNull { it.name }
            this.actors = actorList
            this.seasonNames = sNames
            if (tKey != null) addTrailer("https://www.youtube.com/watch?v=$tKey")
        }
    }

    // ---------------- Site istekleri ----------------
    private fun siteHeaders(host: String, desktop: Boolean, extra: Map<String, String>): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to (if (desktop) uaDesktop else uaMobile),
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
            "Referer" to "$host/"
        )
        h.putAll(extra)
        return h
    }

    private fun denied(r: NiceResponse?): Boolean {
        if (r == null || !r.isSuccessful) return true
        val t = r.text
        return t.length < 700 &&
            Regex("access denied|forbidden|attention required|just a moment|you have been blocked", IC).containsMatchIn(t)
    }

    private suspend fun siteGet(path: String, extra: Map<String, String> = emptyMap()): NiceResponse? {
        val tries = ArrayList<Pair<String, Boolean>>()
        good?.let { tries.add(it) }
        hosts.forEach { h ->
            listOf(false, true).forEach { dsk ->
                val p = Pair(h, dsk)
                if (p != good) tries.add(p)
            }
        }
        for ((host, desk) in tries) {
            val r: NiceResponse? = try {
                app.get(host + path, headers = siteHeaders(host, desk, extra), timeout = 10L)
            } catch (e: Exception) {
                null
            }
            if (!denied(r)) {
                good = Pair(host, desk)
                return r
            }
        }
        return null
    }

    private fun siteBase(): String = good?.first ?: mainUrl

    private fun toSite(u0: String): String {
        val u = u0.replace("&amp;", "&").replace("\\/", "/")
        return when {
            Regex("^https?://", IC).containsMatchIn(u) ->
                u.replace(Regex("^https?://(?:www\\.)?hdfilmcehennemi\\.[a-z]+", IC), siteBase())
            u.startsWith("//") -> "https:$u"
            else -> siteBase().trimEnd('/') + (if (u.startsWith("/")) "" else "/") + u
        }
    }

    private fun pathOf(u: String): String = u.replace(Regex("^https?://[^/]+", IC), "").ifEmpty { "/" }

    private fun originOf(u: String): String =
        Regex("^(https?://[^/]+)", IC).find(u)?.groupValues?.get(1) ?: siteBase()

    // ---------------- Site araması ----------------
    private fun isSeriesItem(it: SiteItem): Boolean =
        it.type.contains("dizi", true) || it.url.contains("/dizi/")

    private fun parseResults(src: String): List<SiteItem> {
        val out = ArrayList<SiteItem>()
        val seen = HashSet<String>()
        Jsoup.parse(src).select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:")) return@forEach
            var title = a.selectFirst("[class*=poster-title], [class*=title]")?.text().orEmpty()
            if (title.isBlank()) title = a.attr("title")
            if (title.isBlank()) title = a.selectFirst("img[alt]")?.attr("alt").orEmpty()
            if (title.isBlank()) title = a.text()
            title = title.trim()
            val year = a.selectFirst("[class*=year]")?.text()
                ?.let { Regex("""\d{4}""").find(it)?.value }?.toIntOrNull() ?: 0
            val type = a.selectFirst("[class*=type]")?.text().orEmpty().trim()
            val url = toSite(href)
            if (title.isBlank() || !seen.add(url)) return@forEach
            out.add(SiteItem(url, title, year, type))
        }
        return out
    }

    private suspend fun searchSite(q: String): List<SiteItem> {
        val r = siteGet(
            "/search/?q=" + URLEncoder.encode(q, "UTF-8"),
            mapOf("Accept" to "application/json, text/plain, */*", "X-Requested-With" to "fetch")
        ) ?: return emptyList()
        val body = r.text
        val html = try {
            val j = JSONObject(body)
            val arr = j.optJSONArray("results") ?: j.optJSONArray("data")
            if (arr != null) (0 until arr.length()).joinToString("\n") { arr.optString(it) } else body
        } catch (e: Exception) {
            body
        }
        return parseResults(html)
    }

    private fun pickBest(list: List<SiteItem>, wants: List<String>, year: Int, isTv: Boolean): SiteItem? {
        var best: SiteItem? = null
        var bs = -1
        for (item in list) {
            if (isSeriesItem(item) != isTv) continue
            val n = Decoder.norm(item.title)
            val parts = (item.title.split(Regex("""\s+[-–—]\s+""")) + item.title).map { Decoder.norm(it) }
            var s = 0
            for (w in wants) {
                val wn = Decoder.norm(w)
                if (wn.isEmpty()) continue
                if (n == wn || parts.contains(wn)) s = maxOf(s, 100)
                else if (wn.length >= 6 && n.contains(wn)) s = maxOf(s, 60)
                else if (n.length >= 6 && wn.contains(n)) s = maxOf(s, 55)
            }
            if (s == 0) continue
            if (year != 0 && item.year != 0) {
                val d = abs(item.year - year)
                if (!isTv) {
                    if (d == 0) s += 30 else if (d == 1) s += 15 else s -= 60
                } else if (d == 0) s += 10
            }
            if (s > bs) {
                bs = s
                best = item
            }
        }
        return if (bs >= 50) best else null
    }

    private fun findEpisode(html: String, s: Int, e: Int, seriesPath: String): String {
        fun mk(prefix: String) = Regex(
            """href=\x22((?:https?://[^\x22/]+)?$prefix[^\x22]*?/?sezon-$s/bolum-$e(?:-[a-z0-9-]+)?/)\x22""", IC
        )
        mk(Regex.escape(seriesPath.trimEnd('/'))).find(html)?.let { return toSite(it.groupValues[1]) }
        mk("/dizi/").find(html)?.let { return toSite(it.groupValues[1]) }
        return ""
    }

    private suspend fun getEpisodeUrl(seriesUrl: String, season: Int, episode: Int): String {
        val r = siteGet(pathOf(seriesUrl))
        val u = findEpisode(r?.text.orEmpty(), season, episode, pathOf(seriesUrl))
        if (u.isNotEmpty()) return u
        return toSite(seriesUrl.trimEnd('/') + "/sezon-$season/bolum-$episode/")
    }

    private fun pageOk(r: NiceResponse?): Boolean =
        r != null && !denied(r) && Regex("alternative-link|<iframe|data-video=", IC).containsMatchIn(r.text)

    private fun slugGuess(titles: List<String>, isTv: Boolean): List<String> {
        val out = ArrayList<String>()
        titles.take(3).forEach { t ->
            val s = Decoder.slugify(t)
            if (s.isEmpty()) return@forEach
            if (isTv) out.add("/dizi/$s/") else {
                out.add("/$s-izle/")
                out.add("/$s/")
            }
        }
        return out.distinct()
    }

    private suspend fun findPage(link: LinkData): String? {
        val imdb = link.imdb.orEmpty()
        val queries = (listOf(imdb) + link.titles.take(3)).filter { it.isNotBlank() }.distinct()
        val lists = queries.amap { q -> q to (try { searchSite(q) } catch (e: Exception) { emptyList<SiteItem>() }) }

        val all = LinkedHashMap<String, SiteItem>()
        val byImdb = ArrayList<SiteItem>()
        lists.forEach { (q, l) ->
            l.forEach { item ->
                if (q == imdb && isSeriesItem(item) == link.isTv) byImdb.add(item)
                if (!all.containsKey(item.url)) all[item.url] = item
            }
        }

        var best: SiteItem? = null
        if (byImdb.isNotEmpty()) {
            val yr = link.year
            best = byImdb.firstOrNull { yr != null && it.year == yr } ?: byImdb[0]
        }
        if (best == null) best = pickBest(all.values.toList(), link.titles, link.year ?: 0, link.isTv)
        if (best != null) {
            return if (link.isTv) getEpisodeUrl(best.url, link.season ?: 1, link.episode ?: 1) else best.url
        }

        for (g in slugGuess(link.titles, link.isTv)) {
            if (pageOk(siteGet(g))) {
                return if (link.isTv) getEpisodeUrl(toSite(g), link.season ?: 1, link.episode ?: 1) else toSite(g)
            }
        }
        return null
    }

    // ---------------- Film / bölüm sayfası ----------------
    private fun parseAlts(html: String): List<Alt> {
        val out = ArrayList<Alt>()
        Jsoup.parse(html).select("div.alternative-links").forEach { div ->
            val lang = div.attr("data-lang")
            div.select("button[data-video]").forEach { b ->
                val id = b.attr("data-video")
                if (id.isBlank()) return@forEach
                out.add(Alt(id, b.text().trim().ifBlank { "Player" }, lang.ifBlank { b.attr("data-lang") }))
            }
        }
        return out
    }

    private fun pageIframe(html: String): String {
        val f = Jsoup.parse(html).selectFirst("iframe[data-src], iframe[src]") ?: return ""
        val u = f.attr("data-src").ifBlank { f.attr("src") }
        return if (u.startsWith("http") || u.startsWith("//")) toSite(u) else ""
    }

    private fun iframeFromAjax(text: String): String {
        var t = text
        try {
            val j = JSONObject(text)
            val d = j.opt("data")
            val h = when (d) {
                is JSONObject -> d.optString("html")
                is String -> d
                else -> j.optString("html")
            }
            if (h.isNotBlank()) t = h
        } catch (e: Exception) { }
        t = t.replace("\\/", "/").replace("\\\"", "\"").replace("\\u0026", "&")
        val m = Regex("""(?:data-src|src)=\x22((?:https?:)?//[^\x22]+)\x22""", IC).find(t)
        return if (m != null) toSite(m.groupValues[1]) else ""
    }

    // ---------------- Oynatıcı çözümleme ----------------
    private fun embedHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to uaMobile,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to ref
    )

    private fun embedVariants(u: String): List<String> {
        val out = ArrayList<String>()
        out.add(u)
        val id = Regex("""[?&]rapidrame_id=([^&#\x22\x27]+)""").find(u)?.groupValues?.get(1)
        if (id != null) {
            out.add(siteBase() + "/playerr/" + id)
            out.add(siteBase() + "/rplayer/" + id)
        }
        hosts.forEach { h ->
            if (u.startsWith(h, true)) return@forEach
            if (u.contains("hdfilmcehennemi.", true)) out.add(u.replace(Regex("^https?://[^/]+", IC), h))
        }
        return out.distinct().take(4)
    }

    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String, depth: Int = 0): EmbedResult? {
        val refs = listOf(siteBase() + "/", pageUrl).distinct()
        val variants = if (depth > 0) listOf(embedUrl) else embedVariants(embedUrl)

        for (v in variants) {
            for (rf in refs) {
                val r: NiceResponse? = try {
                    app.get(v, headers = embedHeaders(rf), timeout = 10L)
                } catch (e: Exception) {
                    null
                }
                if (r == null || r.code != 200) continue
                val html = r.text
                if (html.isEmpty()) continue

                val blobs = Decoder.collectBlobs(html)
                val c = Decoder.candFrom(blobs)
                val subs = ArrayList<SubItem>()
                val seenS = HashSet<String>()
                blobs.forEach { b ->
                    Decoder.extractSubs(b, originOf(v)).forEach { s -> if (seenS.add(s.url)) subs.add(s) }
                }
                if (c.urls.isNotEmpty()) return EmbedResult(c.urls, subs, c.strict, v)

                val inner = Jsoup.parse(html).selectFirst("iframe[data-src], iframe[src]")
                if (inner != null && depth < 1) {
                    val raw = inner.attr("data-src").ifBlank { inner.attr("src") }
                    if (raw.startsWith("http") || raw.startsWith("//")) {
                        val iu = if (raw.startsWith("//")) "https:$raw" else raw
                        val rr = resolveEmbed(iu, v, depth + 1)
                        if (rr != null && rr.urls.isNotEmpty()) return rr
                    }
                }
            }
        }

        // Son çare: sayfayı WebView'de açıp m3u8 isteğini yakala
        try {
            val wv = app.get(
                embedUrl,
                referer = siteBase() + "/",
                interceptor = WebViewResolver(Regex("""\.m3u8|master\.txt|/hls\d?/"""))
            )
            val u = wv.url
            if (Regex("""\.m3u8|master\.txt|/hls\d?/""").containsMatchIn(u)) {
                return EmbedResult(listOf(u), emptyList(), setOf(u), embedUrl)
            }
        } catch (e: Exception) { }
        return null
    }

    // ---------------- Doğrulama ----------------
    private fun streamHeaderSets(embedUrl: String): List<Map<String, String>> {
        val org = originOf(embedUrl)
        return listOf(
            mapOf("User-Agent" to uaMobile, "Referer" to "$org/", "Origin" to org),
            mapOf("User-Agent" to uaMobile, "Referer" to embedUrl),
            mapOf("User-Agent" to uaMobile, "Referer" to siteBase() + "/", "Origin" to siteBase()),
            mapOf("User-Agent" to uaMobile)
        )
    }

    private suspend fun verifyUrl(u: String, sets0: List<Map<String, String>>): Verified {
        val own = originOf(u)
        val sets = listOf(mapOf("User-Agent" to uaMobile, "Referer" to "$own/", "Origin" to own)) + sets0
        if (Regex("""\.mp4(\?|$)""", IC).containsMatchIn(u)) return Verified(true, sets[0])
        for (h in sets) {
            val r: NiceResponse? = try {
                app.get(u, headers = h, timeout = 6L)
            } catch (e: Exception) {
                null
            }
            if (r != null && (r.code == 200 || r.code == 206) && r.text.trimStart().startsWith("#EXTM3U")) {
                return Verified(true, h)
            }
        }
        return Verified(false, sets[0])
    }

    private fun rank(u: String): Int = when {
        u.contains("master", true) -> 0
        u.contains(".m3u8", true) -> 1
        else -> 2
    }

    // ---------------- Bağlantıları ver ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link: LinkData = try {
            mapper.readValue(data, LinkData::class.java)
        } catch (e: Exception) {
            null
        } ?: return false
        val pageUrl = findPage(link) ?: return false
        val html = siteGet(pathOf(pageUrl))?.text ?: return false
        val ifr = pageIframe(html)

        val players = ArrayList<Player>()
        parseAlts(html).forEach { a ->
            if (a.lang == "dual" || a.lang.isBlank() || (showSubbed && a.lang.isNotBlank())) {
                players.add(Player(a.name, a.lang.ifBlank { "dual" }, a.id, null))
            }
        }
        if (players.isEmpty() && ifr.isNotBlank()) players.add(Player("Player", "dual", null, ifr))
        val jobs = players.take(maxPlayers)
        if (jobs.isEmpty()) return false

        val provider = this.name
        val seenEmb = Collections.synchronizedSet(HashSet<String>())
        val seenUrl = Collections.synchronizedSet(HashSet<String>())
        val seenSub = Collections.synchronizedSet(HashSet<String>())

        jobs.amap { job ->
            try {
                val embedUrl = job.embed ?: run {
                    val rr = siteGet(
                        "/video/${job.id}/",
                        mapOf(
                            "Accept" to "application/json, text/plain, */*",
                            "X-Requested-With" to "fetch",
                            "Referer" to pageUrl
                        )
                    )
                    iframeFromAjax(rr?.text.orEmpty()).ifBlank { if (job === jobs[0]) ifr else "" }
                }
                if (embedUrl.isBlank() || !seenEmb.add(embedUrl)) return@amap

                val res = resolveEmbed(embedUrl, pageUrl) ?: return@amap
                val sets = streamHeaderSets(res.base)

                // Altyazılar
                res.subs
                    .filter { s ->
                        s.lang in subLangs && !Regex("forced|zorunlu", IC).containsMatchIn(s.label + " " + s.url)
                    }
                    .forEach { s ->
                        if (seenSub.add(s.url)) {
                            subtitleCallback.invoke(SubtitleFile(if (s.lang == "tr") "Türkçe" else "English", s.url))
                        }
                    }

                // Akışlar
                val cands = res.urls
                    .sortedWith(compareBy<String>({ if (res.strict.contains(it)) 0 else 1 }, { rank(it) }))
                    .take(6)
                val verified = cands.amap { u -> u to verifyUrl(u, sets) }
                var okList = verified.filter { it.second.ok }
                if (okList.isEmpty()) {
                    val st = verified.filter { res.strict.contains(it.first) }
                    if (st.isNotEmpty()) okList = st.take(1)
                }

                val nm = when {
                    job.name.contains("rapid", true) -> "Rapid"
                    job.name.contains("close", true) -> "Close"
                    else -> job.name
                }
                val lg = if (job.lang == "dual") "DUAL" else if (job.lang.isNotBlank()) "Altyazılı" else "TR"

                okList.forEachIndexed { i, pair ->
                    val u = pair.first
                    val v = pair.second
                    if (!seenUrl.add(u)) return@forEachIndexed
                    val label = "$lg $nm" + (if (i > 0) " #${i + 1}" else "")
                    val linkType = if (u.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    callback.invoke(
                        newExtractorLink(
                            source = provider,
                            name = "$provider - $label",
                            url = u,
                            type = linkType
                        ) {
                            this.referer = v.headers["Referer"] ?: (siteBase() + "/")
                            this.quality = Qualities.Unknown.value
                            this.headers = v.headers
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return seenUrl.isNotEmpty()
    }
}

// ================= Modeller =================
data class LinkData(
    val tmdbId: Int,
    val isTv: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    val imdb: String? = null,
    val year: Int? = null,
    val titles: List<String> = emptyList()
)

data class TmdbList(val results: List<TmdbItem>? = null)

data class TmdbItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val media_type: String? = null,
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null
)

data class TmdbGenre(val name: String? = null)
data class TmdbExternalIds(val imdb_id: String? = null)

data class TmdbCast(
    val name: String? = null,
    val character: String? = null,
    val profile_path: String? = null
)

data class TmdbCredits(val cast: List<TmdbCast>? = null)

data class TmdbVideo(
    val site: String? = null,
    val type: String? = null,
    val key: String? = null
)

data class TmdbVideos(val results: List<TmdbVideo>? = null)

data class TmdbSeasonInfo(val season_number: Int? = null)

data class TmdbDetail(
    val id: Int? = null,
    val title: String? = null,
    val original_title: String? = null,
    val name: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val imdb_id: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre>? = null,
    val external_ids: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val seasons: List<TmdbSeasonInfo>? = null
)

data class TmdbEpisode(
    val episode_number: Int? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val air_date: String? = null
)

data class TmdbSeason(
    val season_number: Int? = null,
    val episodes: List<TmdbEpisode>? = null
)

data class SubItem(val url: String, val label: String, val lang: String)
data class Cand(val urls: List<String>, val strict: Set<String>)

// ================= Decoder =================
object Decoder {
    private val IC = RegexOption.IGNORE_CASE

    // ---------- Metin yardımcıları ----------
    private fun fold(s: String): String {
        val t = s.lowercase()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    fun norm(s: String): String = fold(s).replace(Regex("[^a-z0-9]+"), "")

    fun slugify(s: String): String =
        fold(s).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun unesc(s: String): String {
        var t = s.replace("\\/", "/")
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return t.replace("&amp;", "&")
    }

    // ---------- p.a.c.k.e.r çözücü ----------
    private val packedRe = Regex(
        """\}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun unpack(src: String): List<String> {
        val out = ArrayList<String>()
        for (m in packedRe.findAll(src)) {
            try {
                val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                val radix = m.groupValues[2].toInt()
                val count = m.groupValues[3].toInt()
                val dict = m.groupValues[4].split("|")

                fun enc(c: Int): String {
                    val pre = if (c < radix) "" else enc(c / radix)
                    val r = c % radix
                    return pre + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
                }

                val map = HashMap<String, String>()
                for (i in count - 1 downTo 0) {
                    val k = enc(i)
                    map[k] = dict.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: k
                }
                out.add(Regex("""\b\w+\b""").replace(payload) { map[it.value] ?: it.value })
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ---------- Base64 ----------
    private val atobRe = Regex("""atob\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")
    private val longB64 = Regex("""["']([A-Za-z0-9+/]{80,}={0,2})["']""")

    private fun b64(src: String): List<String> {
        val out = ArrayList<String>()
        val raws = atobRe.findAll(src).map { it.groupValues[1] }.take(20).toList() +
            longB64.findAll(src).map { it.groupValues[1] }.take(20).toList()
        for (raw in raws) {
            val bytes = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (e: Exception) {
                try {
                    Base64.decode(raw, Base64.URL_SAFE)
                } catch (e2: Exception) {
                    null
                }
            } ?: continue
            val d = String(bytes, Charsets.UTF_8)
            if (d.contains("http", true) || d.contains("eval(") || d.contains("m3u8", true)) out.add(d)
        }
        return out
    }

    // ---------- Tüm çözülmüş metinleri topla ----------
    fun collectBlobs(html: String): List<String> {
        val out = LinkedHashSet<String>()
        var queue = ArrayList<String>()
        queue.add(html)
        var round = 0
        while (queue.isNotEmpty() && round < 3) {
            val next = ArrayList<String>()
            for (b in queue) {
                val u = unesc(b)
                if (!out.add(u)) continue
                next.addAll(unpack(u))
                next.addAll(b64(u))
            }
            queue = next
            round++
        }
        return out.toList()
    }

    // ---------- Akış adayları ----------
    private val urlRe = Regex("""https?://[^\s"'<>\\)]+""")
    private val streamRe = Regex("""\.m3u8|\.mp4|master\.txt|/hls\d?/""", IC)
    private val badRe = Regex("""\.(?:vtt|srt|jpg|jpeg|png|gif|webp|css|js|svg|ico|woff2?)(?:\?|#|\z)""", IC)
    private val fileRe = Regex("""(?:file|src|source|video_location)["']?\s*[:=]\s*["'](https?://[^"']+)["']""", IC)

    fun candFrom(blobs: List<String>): Cand {
        val urls = LinkedHashSet<String>()
        val strict = LinkedHashSet<String>()
        for (b in blobs) {
            for (m in fileRe.findAll(b)) {
                val u = m.groupValues[1]
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u)) {
                    urls.add(u)
                    strict.add(u)
                }
            }
            for (m in urlRe.findAll(b)) {
                val u = m.value.trimEnd(',', ';', '.')
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u)) urls.add(u)
            }
        }
        return Cand(urls.toList(), strict)
    }

    // ---------- Altyazılar ----------
    private val jsonTrackRe = Regex(
        """["']file["']\s*:\s*["']([^"']+?\.(?:vtt|srt)[^"']*)["'][^{}]*?["']label["']\s*:\s*["']([^"']*)["']""",
        IC
    )
    private val tagRe = Regex("""<track[^>]+>""", IC)
    private val srcAttr = Regex("""src\s*=\s*["']([^"']+)["']""", IC)
    private val labelAttr = Regex("""label\s*=\s*["']([^"']*)["']""", IC)
    private val langAttr = Regex("""srclang\s*=\s*["']([^"']*)["']""", IC)

    private fun absUrl(u: String, origin: String): String = when {
        u.startsWith("http", true) -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> origin.trimEnd('/') + u
        else -> origin.trimEnd('/') + "/" + u
    }

    private fun langOf(label: String, code: String, url: String): String {
        val c = code.lowercase()
        if (c.startsWith("tr")) return "tr"
        if (c.startsWith("en")) return "en"
        val f = fold(label + " " + url)
        return when {
            Regex("turk|turkce|\\btr\\b|/tr[._/-]").containsMatchIn(f) -> "tr"
            Regex("ingiliz|english|\\ben\\b|\\beng\\b|/en[._/-]").containsMatchIn(f) -> "en"
            else -> if (c.isNotBlank()) c.take(2) else "xx"
        }
    }

    fun extractSubs(blob: String, origin: String): List<SubItem> {
        val out = ArrayList<SubItem>()
        for (m in jsonTrackRe.findAll(blob)) {
            val url = absUrl(m.groupValues[1], origin)
            val label = m.groupValues[2]
            out.add(SubItem(url, label, langOf(label, "", url)))
        }
        for (m in tagRe.findAll(blob)) {
            val tag = m.value
            val src = srcAttr.find(tag)?.groupValues?.get(1) ?: continue
            if (!Regex("""\.(?:vtt|srt)""", IC).containsMatchIn(src)) continue
            val url = absUrl(src, origin)
            val label = labelAttr.find(tag)?.groupValues?.get(1).orEmpty()
            val code = langAttr.find(tag)?.groupValues?.get(1).orEmpty()
            out.add(SubItem(url, label, langOf(label, code, url)))
        }
        return out
    }
}
