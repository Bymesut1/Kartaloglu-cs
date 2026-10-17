package com.Bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CizgiMax : MainAPI() {
    override var mainUrl        = "https://cizgimax.online"
    override var name           = "ÇizgiMax"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/diziler/" to "Tüm Diziler"
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun pageHeaders(ref: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent"      to ua,
        "Referer"         to ref,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // ---------------- Yardımcılar ----------------

    private val reSeriesPath = Regex("""(?:https?://[^/]+)?/diziler/([^/?#]+)/?$""", RegexOption.IGNORE_CASE)

    private fun cleanTitle(t: String): String = t
        .replace(Regex("\\s*[|\\-–]\\s*çizgimax.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+izle\\b.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun posterOf(img: Element?): String? {
        if (img == null) return null
        for (k in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val v: String = img.attr(k)
            if (v.isNotBlank() && !v.startsWith("data:")) return fixUrl(v)
        }
        return null
    }

    private fun absUrl(u: String?): String {
        val s = (u ?: "").trim()
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("/")  -> mainUrl + s
            else               -> s
        }
    }

    private fun originOf(u: String): String =
        Regex("^https?://[^/]+").find(u)?.value.orEmpty()

    private fun hostOf(u: String): String =
        Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)?.lowercase().orEmpty()

    private fun decodeHtml(s: String): String = s
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun fixMediaUrl(u: String): String {
        val x = decodeHtml(u).replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&").replace("\\/", "/")
        return if (x.startsWith("//")) "https:$x" else x
    }

    private fun b64(s: String): String {
        val clean = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/=]"), "")
        return String(android.util.Base64.decode(clean, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun qualOf(u: String): Int {
        val m = Regex("(2160|1440|1080|720|480|360|240)").find(u)?.groupValues?.get(1)?.toIntOrNull()
        return m ?: Qualities.Unknown.value
    }

    private fun isMediaUrl(u: String): Boolean =
        Regex("\\.(mp4|m3u8)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u) ||
            Regex("(okcdn\\.ru|mycdn\\.me|vkuser\\.net)/\\?", RegexOption.IGNORE_CASE).containsMatchIn(u)

    private fun refererFor(u: String, fallback: String): String {
        val h = hostOf(u)
        return when {
            Regex("okcdn|mycdn|vkuser|ok\\.ru").containsMatchIn(h) -> "https://ok.ru/"
            h.contains("sibnet") -> "https://video.sibnet.ru/"
            else -> fallback
        }
    }

    // ---------------- Kart ayrıştırma (ana sayfa + arama) ----------------

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()

        fun add(a: Element, container: Element?) {
            val href: String = fixUrl(a.attr("href")).substringBefore("#")
            val m = reSeriesPath.find(href) ?: return
            val img: Element? = (container ?: a).selectFirst("img")
            var title: String = container?.selectFirst(".film-name")?.text().orEmpty()
            if (title.isBlank()) title = a.attr("title")
            if (title.isBlank()) title = img?.attr("alt").orEmpty()
            if (title.isBlank()) title = a.text()
            if (title.isBlank()) title = m.groupValues[1].removeSuffix("-izle").replace('-', ' ')
            title = cleanTitle(title)
            if (title.isBlank() || out.containsKey(href)) return
            val poster: String? = posterOf(img)
            out[href] = newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }

        for (item in doc.select("div.film-item")) {
            val a: Element = item.selectFirst("a[href*=/diziler/]") ?: continue
            add(a, item)
        }
        if (out.isEmpty()) {
            for (a in doc.select("a[href*=/diziler/]")) add(a, a.parent())
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data
        val url = if (page <= 1) base else base.trimEnd('/') + "/page/$page/"
        val list = try {
            parseCards(app.get(url, headers = pageHeaders()).document)
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        return try {
            parseCards(app.get("$mainUrl/ara/?q=$enc", headers = pageHeaders()).document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------- TMDB (Türkçe) ----------------

    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"

    data class TmdbShow(
        val id: Int? = null,
        val name: String? = null,
        val original_name: String? = null
    )
    data class TmdbSearch(val results: List<TmdbShow>? = null)
    data class TmdbSeasonSum(val season_number: Int? = null, val episode_count: Int? = null)
    data class TmdbDetail(
        val overview: String? = null,
        val poster_path: String? = null,
        val backdrop_path: String? = null,
        val seasons: List<TmdbSeasonSum>? = null
    )
    data class TmdbEp(
        val episode_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val air_date: String? = null,
        val runtime: Int? = null
    )
    data class TmdbSeason(val episodes: List<TmdbEp>? = null)

    private fun norm(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private suspend fun tmdbFindId(title: String): Int? = try {
        val q = URLEncoder.encode(title, "UTF-8")
        val results = app.get("https://api.themoviedb.org/3/search/tv?api_key=$tmdbKey&language=tr-TR&query=$q")
            .parsedSafe<TmdbSearch>()?.results.orEmpty()
        val want = norm(title)
        val exact = results.firstOrNull { norm(it.name) == want || norm(it.original_name) == want }
        (exact ?: results.firstOrNull())?.id
    } catch (e: Exception) { null }

    private suspend fun tmdbDetailTr(id: Int): TmdbDetail? = try {
        app.get("https://api.themoviedb.org/3/tv/$id?api_key=$tmdbKey&language=tr-TR")
            .parsedSafe<TmdbDetail>()
    } catch (e: Exception) { null }

    // Bir sezonun bölüm bilgileri: ad, açıklama (yalnızca Türkçe), görsel, süre, tarih
    private suspend fun tmdbSeasonEps(id: Int, season: Int): Map<Int, TmdbEp> {
        suspend fun fetch(lang: String): List<TmdbEp> {
            for (attempt in 1..2) {
                try {
                    val r = app.get("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$tmdbKey&language=$lang")
                        .parsedSafe<TmdbSeason>()?.episodes
                    if (r != null) return r
                } catch (e: Exception) {
                }
            }
            return emptyList()
        }
        val tr = fetch("tr-TR")
        // Açıklama her zaman Türkçe; İngilizce yalnızca eksik görsel ve bölüm adı için
        val en = if (tr.any { it.still_path == null || it.name.isNullOrBlank() }) fetch("en-US") else emptyList()
        val enMap = en.associateBy { it.episode_number }
        return tr.mapNotNull { t ->
            val n = t.episode_number ?: return@mapNotNull null
            val f = enMap[n]
            n to t.copy(
                overview = t.overview?.takeIf { it.isNotBlank() },
                still_path = t.still_path ?: f?.still_path,
                name = t.name?.takeIf { it.isNotBlank() } ?: f?.name
            )
        }.toMap()
    }

    // ---------------- Dizi sayfası ----------------

    private data class EpRef(val href: String, val season: Int, val episode: Int)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = pageHeaders()).document

        fun meta(p: String): String? {
            val c: String = doc.selectFirst("meta[property=$p]")?.attr("content").orEmpty()
            return c.ifBlank { null }
        }

        val h1Text: String? = doc.selectFirst("h1")?.text()
        val title = cleanTitle(meta("og:title") ?: h1Text ?: doc.title()).ifBlank { "Dizi" }
        val poster = meta("og:image")?.let { fixUrl(it) }
        val sitePlot = meta("og:description")

        val slug = url.trimEnd('/').substringAfterLast('/').removeSuffix("-izle")

        // 1) Sayfadaki bölüm bağlantıları: "<dizi>-<sezon>-sezon-<bolum>-bolum-izle"
        val strict = Regex("^" + Regex.escape(slug) + "-(\\d+)-sezon-(\\d+)-bolum", RegexOption.IGNORE_CASE)
        val loose = Regex("-(\\d+)-sezon-(\\d+)-bolum", RegexOption.IGNORE_CASE)
        fun slugOf(h: String): String = h.trimEnd('/').substringAfterLast('/')

        val hrefs = ArrayList<String>()
        for (a in doc.select("a[href]")) {
            val href: String = fixUrl(a.attr("href")).substringBefore("#")
            if (!href.contains("cizgimax", true) || href.contains("/diziler/")) continue
            hrefs.add(href)
        }

        var refs: List<EpRef> = hrefs.mapNotNull { h ->
            val m = strict.find(slugOf(h)) ?: return@mapNotNull null
            val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            EpRef(h, s, e)
        }.distinctBy { it.href }

        if (refs.isEmpty()) {
            // Dizi adı farklı yazılmışsa: en çok görülen öneki seç (başka diziler karışmasın)
            val rows = hrefs.mapNotNull { h ->
                val sl = slugOf(h)
                val m = loose.find(sl) ?: return@mapNotNull null
                Triple(h, sl.substring(0, m.range.first), m)
            }
            val top = rows.groupBy { it.second }.maxByOrNull { it.value.size }?.key
            refs = rows.filter { it.second == top }.mapNotNull { (h, _, m) ->
                val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                EpRef(h, s, e)
            }.distinctBy { it.href }
        }

        val tmdbId = tmdbFindId(title)
        val detail = if (tmdbId != null) tmdbDetailTr(tmdbId) else null

        // 2) Sayfada bölüm bulunamazsa TMDB sezon/bölüm sayısından üret
        val seasonList: List<TmdbSeasonSum> = detail?.seasons.orEmpty()
        if (refs.isEmpty() && seasonList.isNotEmpty()) {
            val gen = ArrayList<EpRef>()
            for (sn in seasonList) {
                val s = sn.season_number ?: continue
                val c = sn.episode_count ?: continue
                if (s < 1) continue
                for (e in 1..c) gen.add(EpRef("$mainUrl/$slug-$s-sezon-$e-bolum-izle/", s, e))
            }
            refs = gen
        }
        if (refs.isEmpty()) return null

        val plot = detail?.overview?.trim()?.takeIf { it.isNotBlank() } ?: sitePlot
        val tmdbPoster = detail?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        val tmdbBackdrop = detail?.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" }

        val grouped = refs.groupBy { Pair(it.season, it.episode) }
            .toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))

        // TMDB bölüm bilgileri newEpisode bloğunun DIŞINDA önceden çekilir (suspend çağrı burada olmalı)
        val seasonInfo = HashMap<Int, Map<Int, TmdbEp>>()
        if (tmdbId != null) {
            for (sn in grouped.keys.map { it.first }.distinct()) {
                seasonInfo[sn] = tmdbSeasonEps(tmdbId, sn)
            }
        }

        val episodes = grouped.map { (key, list) ->
            val info = seasonInfo[key.first]?.get(key.second)
            val epDate: Long? = info?.air_date?.takeIf { it.isNotBlank() }?.let {
                try {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it)?.time
                } catch (_: Exception) { null }
            }
            newEpisode(list.first().href) {
                this.name        = info?.name?.takeIf { it.isNotBlank() }
                this.season      = key.first
                this.episode     = key.second
                this.description = info?.overview?.takeIf { it.isNotBlank() }
                this.posterUrl   = info?.still_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.runTime     = info?.runtime
                this.date        = epDate
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl           = tmdbPoster ?: poster
            this.backgroundPosterUrl = tmdbBackdrop
            this.plot                = plot
        }
    }

    // ---------------- Sunucu listesi (bölüm sayfası) ----------------

    private data class Srv(
        val src: String, val streamUrl: String, val resolveUrl: String, val metaUrl: String,
        val label: String, val type: String, val lang: String, val notice: String
    )

    private data class Media(val url: String, val quality: Int, val ref: String)

    private fun litJson(html: String, varName: String): String? {
        val m = Regex("""var $varName\s*=\s*JSON\.parse\(atob\(("(?:\\.|[^"\\])*")\)\)""").find(html) ?: return null
        val lit = m.groupValues[1].trim('"').replace("\\/", "/").replace("\\", "")
        return try { b64(lit) } catch (e: Exception) { null }
    }

    private fun srvOf(o: JSONObject, lang: String): Srv = Srv(
        o.optString("src"), o.optString("streamUrl"), o.optString("resolveUrl"), o.optString("metaUrl"),
        o.optString("label"), o.optString("type"),
        o.optString("lang").ifBlank { lang }, o.optString("notice")
    )

    private fun parseServers(html: String): List<Srv> {
        val out = ArrayList<Srv>()
        val seen = HashSet<String>()
        fun add(s: Srv) {
            val key = listOf(s.src, s.streamUrl, s.resolveUrl, s.metaUrl).firstOrNull { it.isNotBlank() } ?: return
            if (seen.add(key)) out.add(s)
        }
        try {
            val byLang = litJson(html, "serversByLang")
            if (byLang != null) {
                val o = JSONObject(byLang)
                for (l in listOf("dub", "any", "sub")) {
                    val arr: JSONArray = o.optJSONArray(l) ?: continue
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(srvOf(it, l)) }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val plain = litJson(html, "servers")
            if (plain != null) {
                val arr = JSONArray(plain)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(srvOf(it, "")) }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) {
            val im = Regex("""<iframe[^>]+id="cizgimax-iframe"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            if (im != null) add(Srv(decodeHtml(im.groupValues[1]), "", "", "", "Oynatıcı", "iframe", "any", ""))
        }
        return out
    }

    // ---------------- Video linki çözme ----------------

    private val okQ = mapOf(
        "ultra" to 2160, "quad" to 1440, "full" to 1080, "hd" to 720,
        "sd" to 480, "low" to 360, "lowest" to 240, "mobile" to 144
    )

    private fun okruMeta(html: String): List<Media> {
        val re = Regex("""data-options="([^"]+)"""")
        for (m in re.findAll(html)) {
            if (!m.groupValues[1].contains("flashvars")) continue
            try {
                val o = JSONObject(decodeHtml(m.groupValues[1]))
                val fv = o.optJSONObject("flashvars") ?: continue
                val raw = fv.opt("metadata")
                val md: JSONObject = when (raw) {
                    is JSONObject -> raw
                    is String -> JSONObject(raw)
                    else -> continue
                }
                val res = ArrayList<Media>()
                val vids = md.optJSONArray("videos")
                if (vids != null) {
                    for (i in 0 until vids.length()) {
                        val v = vids.optJSONObject(i) ?: continue
                        val u = v.optString("url")
                        if (u.isBlank()) continue
                        res.add(Media(fixMediaUrl(u), okQ[v.optString("name")] ?: Qualities.Unknown.value, ""))
                    }
                }
                res.sortByDescending { it.quality }
                val hls = listOf("hlsManifestUrl", "hlsMasterPlaylistUrl", "ondemandHls")
                    .map { md.optString(it) }.firstOrNull { it.isNotBlank() }
                if (hls != null) res.add(Media(fixMediaUrl(hls), Qualities.Unknown.value, ""))
                if (res.isNotEmpty()) return res
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun scanMedia(text: String): List<Media> {
        val t = fixMediaUrl(text)
        val out = ArrayList<Media>()
        val seen = HashSet<String>()
        val re1 = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        val re2 = Regex("""https?://[a-z0-9\-]+\.(?:okcdn\.ru|mycdn\.me|vkuser\.net)/\?[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        for (r in listOf(re1, re2)) {
            for (m in r.findAll(t)) {
                if (seen.add(m.value)) out.add(Media(m.value, qualOf(m.value), ""))
            }
        }
        return out
    }

    private suspend fun resolveServer(s: Srv, page: String): List<Media> {
        val direct = absUrl(s.streamUrl)
        if (direct.startsWith("http")) return listOf(Media(direct, qualOf(direct), "$mainUrl/"))

        val src = absUrl(s.src.ifBlank { s.resolveUrl.ifBlank { s.metaUrl } })
        if (src.isBlank()) return emptyList()

        val r = app.get(src, headers = pageHeaders(page) + ("Range" to "bytes=0-0"))
        val fin = r.url
        val ct = r.headers["content-type"].orEmpty()
        val isMediaCt = Regex("^(video|audio)/|mpegurl|dash\\+xml|octet-stream", RegexOption.IGNORE_CASE).containsMatchIn(ct)
        if (isMediaCt || (isMediaUrl(fin) && !ct.contains("html", true))) {
            return listOf(Media(fin, qualOf(fin), refererFor(fin, page)))
        }

        val t = r.text
        var res: List<Media> = okruMeta(t)
        if (res.isEmpty()) {
            val sm = Regex("""player\.src\(\s*\[\s*\{\s*src:\s*"([^"]+)"""").find(t)
            if (sm != null) {
                val u = sm.groupValues[1]
                res = listOf(Media(if (u.startsWith("http")) u else "https://video.sibnet.ru$u", Qualities.Unknown.value, ""))
            }
        }
        if (res.isEmpty()) res = scanMedia(t)
        val base = refererFor(fin, originOf(fin) + "/")
        val ref = if (hostOf(fin).contains("sibnet")) fin else base
        return res.map { it.copy(ref = ref) }
    }

    private fun typeOf(u: String): ExtractorLinkType = when {
        u.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        u.contains(".mpd", true) || u.contains("dzen_dash", true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private fun langLabel(l: String): String = when (l) {
        "dub" -> "TR Dublaj"
        "sub" -> "TR Altyazı"
        else  -> "TR"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = data.trim()
        val html = try {
            app.get(page, headers = pageHeaders()).text
        } catch (e: Exception) {
            return false
        }

        var any = false
        val seen = HashSet<String>()

        for (s in parseServers(html)) {
            if (s.notice.equals("copyright", true)) continue   // telif nedeniyle açılmayan sunucuları atla
            val label = listOf(langLabel(s.lang), s.label).filter { it.isNotBlank() }.distinct().joinToString(" ")

            val found: List<Media> = try {
                resolveServer(s, page)
            } catch (e: Exception) {
                emptyList()
            }

            if (found.isNotEmpty()) {
                for (v in found) {
                    if (!seen.add(v.url)) continue
                    val ref = v.ref.ifBlank { "$mainUrl/" }
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name   = "$name $label",
                            url    = v.url,
                            type   = typeOf(v.url)
                        ) {
                            this.referer = ref
                            this.quality = v.quality
                            this.headers = mapOf(
                                "User-Agent" to ua,
                                "Referer"    to ref,
                                "Origin"     to ref.trimEnd('/')
                            )
                        }
                    )
                    any = true
                }
            } else {
                // Yedek: bilinen oynatıcılar için hazır çıkarıcıları dene
                val src = absUrl(s.src.ifBlank { s.resolveUrl.ifBlank { s.metaUrl } })
                if (src.startsWith("http")) {
                    try {
                        if (loadExtractor(src, page, subtitleCallback, callback)) any = true
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return any
    }
}
