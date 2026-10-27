package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Zdizi : MainAPI() {
    override var mainUrl = "https://zdizi.com"
    override var name = "Zdizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // Bağlantı bulunamazsa kaynak listesine "DEBUG ..." satırı ekler (sorun bitince false yap)
    private val debug = true

    @Volatile
    private var dbg = ""

    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"

    @Volatile
    private var cfKiller: CloudflareKiller? = null

    override val mainPage = mainPageOf(
        "/" to "Ana Sayfa",
        "/trendler" to "Trendler"
    )

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------
    private class Page(val code: Int, val text: String, val cookies: Map<String, String>)

    private class Found {
        val hls = LinkedHashSet<String>()
        val mp4 = LinkedHashSet<String>()
        val subs = LinkedHashMap<String, String>() // adres -> etiket
    }

    private val epRe = Regex("^/(.+)-(\\d+)-sezon-(\\d+)-bolum-izle/?$")

    private val skipNames = setOf(
        "dizi", "yabanci-film", "film", "kesfet", "trendler", "takvim", "forumlar",
        "istek-listesi", "iletisim", "forum", "arsiv"
    )

    private fun hdr(ref: String?, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        if (ref != null) h["Referer"] = ref
        h.putAll(extra)
        return h
    }

    // cf = true: site adresi için Cloudflare 403/503 gelirse CloudflareKiller ile yeniden dener
    private suspend fun fetch(
        url: String,
        ref: String?,
        cf: Boolean,
        extra: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap()
    ): Page? {
        return try {
            var r = app.get(url, headers = hdr(ref, extra), cookies = cookies)
            if (cf && (r.code == 403 || r.code == 503)) {
                val k = cfKiller ?: CloudflareKiller().also { cfKiller = it }
                r = app.get(url, headers = hdr(ref, extra), cookies = cookies, interceptor = k)
            }
            Page(r.code, r.text, r.cookies)
        } catch (e: Exception) {
            dbg = "hata: ${e.message}"
            null
        }
    }

    private fun abs(u: String): String = when {
        u.startsWith("http", true) -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> mainUrl + u
        else -> "$mainUrl/$u"
    }

    private fun originOf(u: String): String =
        Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1) ?: mainUrl

    private fun pathOf(u: String): String {
        var p = u.trim()
        p = p.replace(Regex("^https?://(?:www\\.)?zdizi\\.com", RegexOption.IGNORE_CASE), "")
        p = p.substringBefore("#").substringBefore("?")
        if (!p.startsWith("/")) p = "/$p"
        return if (p.length > 1) p.trimEnd('/') else p
    }

    // 1 = dizi, 2 = film, 3 = bölüm, 0 = diğer
    private fun kindOf(path: String): Int {
        if (Regex("^/diziler/[^/]+$").matches(path)) return 1
        if (epRe.containsMatchIn(path)) return 3
        if (Regex("^/(?:film|filmler|filmi)/[^/]+$").matches(path)) return 2
        val m = Regex("^/([^/]+)-izle$").find(path)
        if (m != null && m.groupValues[1] !in skipNames) return 2
        return 0
    }

    private fun epToSeries(path: String): String {
        val m = epRe.find(path) ?: return path
        return "/diziler/${m.groupValues[1]}-izle"
    }

    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\s*[-|–]\\s*Zdizi.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*:?\\s*\\d+\\.\\s*Sezon.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+izle(\\s*\\(\\d{4}\\))?(\\s*izle)?$", RegexOption.IGNORE_CASE), "")
            .trim()

    // WordPress küçük resim ekini (-300x450) kaldırıp orijinal (net) boyutu kullanır
    private fun upsize(u: String): String =
        if (u.contains("/wp-content/uploads/", true))
            u.replace(Regex("-\\d{2,4}x\\d{2,4}(\\.(?:jpe?g|png|webp))", RegexOption.IGNORE_CASE), "$1")
        else u

    private fun fullUrl(s: String): String? = when {
        s.isBlank() || s.startsWith("data:", true) -> null
        s.startsWith("http", true) -> s
        s.startsWith("//") -> "https:$s"
        s.startsWith("/") -> mainUrl + s
        else -> null
    }

    private fun imgUrl(img: Element?): String? {
        if (img == null) return null
        // srcset varsa en geniş olanı
        val srcset = img.attr("data-srcset").ifBlank { img.attr("srcset") }.trim()
        var s = ""
        if (srcset.isNotBlank()) {
            s = srcset.split(",")
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.isNotEmpty() && it[0].isNotBlank() }
                .maxByOrNull { it.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0 }
                ?.firstOrNull().orEmpty()
        }
        var u = fullUrl(s)
        if (u == null) {
            for (k in listOf("data-src", "data-lazy-src", "data-original", "data-lazy", "src")) {
                u = fullUrl(img.attr(k).trim())
                if (u != null) break
            }
        }
        return u?.let { upsize(it) }
    }

    // <div style="background-image:url(...)"> biçimindeki kapaklar
    private fun bgImage(el: Element?): String? {
        if (el == null) return null
        val st = (listOf(el) + el.select("[style*=url]")).map { it.attr("style") }.firstOrNull { it.contains("url(") }
            ?: return null
        val m = Regex("url\\(['\"]?([^'\")]+)['\"]?\\)").find(st)?.groupValues?.get(1) ?: return null
        return fullUrl(m.trim())?.let { upsize(it) }
    }

    private fun fixText(s: String): String =
        s.replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true)
            .replace("\\/", "/")
            .replace("&amp;", "&")

    // Sayfadaki dizi / film kartları (yan menü, üst menü ve alt bilgi alanı hariç)
    private fun cards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        val scope = doc.selectFirst("#content") ?: doc
        for (a in scope.select("a[href]")) {
            if (a.parents().any { it.tagName() == "aside" || it.tagName() == "footer" || it.tagName() == "header" }) continue
            val raw = a.attr("href").trim()
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript", true)) continue
            if (raw.startsWith("http", true) && !raw.contains("zdizi.com", true)) continue
            var path = pathOf(raw)
            var kind = kindOf(path)
            if (kind == 3) {
                path = epToSeries(path)
                kind = 1
            }
            if (kind == 0) continue
            val url = mainUrl + path
            if (!seen.add(url)) continue
            val title = cleanTitle(
                a.attr("title").ifBlank {
                    a.selectFirst("h1,h2,h3,h4,h5,.title,.name")?.text().orEmpty()
                }.ifBlank {
                    a.selectFirst("img")?.attr("alt").orEmpty()
                }.ifBlank { a.text() }
            )
            if (title.isBlank()) continue
            val poster = imgUrl(a.selectFirst("img") ?: a.parent()?.selectFirst("img"))
                ?: bgImage(a) ?: bgImage(a.parent())
            if (kind == 2) {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
            } else {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Ana sayfa / arama
    // ------------------------------------------------------------------
    // Sitede resmi çıkmayan kartlara TMDB'den poster bulur
    private suspend fun fillPosters(list: List<SearchResponse>) {
        list.filter { it.posterUrl.isNullOrBlank() }.take(30).amap { r ->
            try {
                val isMovie = r.type == TvType.Movie
                val nm = r.name.replace(Regex("(?i)\\bizle\\b"), "").replace(Regex("\\(\\d{4}\\)"), "")
                    .replace(Regex("\\s+"), " ").trim()
                val q = URLEncoder.encode(nm, "UTF-8")
                val j = tmdbJson("$tmdbApi/search/${if (isMovie) "movie" else "tv"}?api_key=$tmdbKey&language=tr-TR&query=$q")
                val pp = j?.optJSONArray("results")?.optJSONObject(0)?.optString("poster_path").orEmpty()
                if (pp.isNotBlank() && pp != "null") r.posterUrl = "$tmdbImg/w500$pp"
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val p = fetch(mainUrl + request.data, "$mainUrl/", true)
        val items = if (p != null && p.code == 200) cards(Jsoup.parse(p.text, mainUrl)) else emptyList()
        fillPosters(items)
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        // 1) WordPress arama sayfası
        val p = fetch("$mainUrl/?s=$q", "$mainUrl/", true)
        if (p != null && p.code == 200) {
            val r = cards(Jsoup.parse(p.text, mainUrl))
            if (r.isNotEmpty()) return r
        }
        // 2) Yedek: WordPress REST araması
        val j = fetch(
            "$mainUrl/wp-json/wp/v2/search?search=$q&per_page=20&_fields=title,url",
            "$mainUrl/",
            true,
            extra = mapOf("Accept" to "application/json")
        )
        val out = ArrayList<SearchResponse>()
        if (j != null && j.code == 200) {
            try {
                val arr = JSONArray(j.text)
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    var path = pathOf(o.optString("url", ""))
                    var kind = kindOf(path)
                    if (kind == 3) {
                        path = epToSeries(path)
                        kind = 1
                    }
                    if (kind == 0) continue
                    val url = mainUrl + path
                    if (!seen.add(url)) continue
                    val title = cleanTitle(Jsoup.parse(o.optString("title", "")).text())
                    if (title.isBlank()) continue
                    if (kind == 2) out.add(newMovieSearchResponse(title, url, TvType.Movie) { })
                    else out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { })
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // TMDB: Türkçe bölüm adı / açıklama / bölüm görseli
    // ------------------------------------------------------------------
    private class TEp(val name: String, val overview: String, val still: String?, val runtime: Int?, val air: String?)

    private class TShow(
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val eps: Map<String, TEp>
    )

    private suspend fun tmdbJson(url: String): JSONObject? = try {
        JSONObject(app.get(url, timeout = 15L).text)
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        null
    }

    private suspend fun tmdbShow(title: String, year: Int?, seasons: List<Int>): TShow? {
        val q = URLEncoder.encode(title, "UTF-8")
        val urls = ArrayList<String>()
        if (year != null) urls.add("$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&query=$q&first_air_date_year=$year")
        urls.add("$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&query=$q")
        var show: JSONObject? = null
        for (u in urls) {
            val arr = tmdbJson(u)?.optJSONArray("results")
            if (arr != null && arr.length() > 0) {
                show = arr.optJSONObject(0)
                break
            }
        }
        val sh = show ?: return null
        val id = sh.optInt("id")
        if (id == 0) return null

        val map = java.util.concurrent.ConcurrentHashMap<String, TEp>()
        seasons.filter { it > 0 }.amap { sn ->
            val tr = tmdbJson("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=tr-TR")?.optJSONArray("episodes")
            val en = tmdbJson("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=en-US")?.optJSONArray("episodes")
            val enBy = HashMap<Int, JSONObject>()
            if (en != null) for (i in 0 until en.length()) {
                val o = en.optJSONObject(i) ?: continue
                enBy[o.optInt("episode_number")] = o
            }
            val src = tr ?: en
            if (src != null) for (i in 0 until src.length()) {
                val o = src.optJSONObject(i) ?: continue
                val n = o.optInt("episode_number")
                val e = enBy[n]
                val name = o.optString("name").ifBlank { e?.optString("name").orEmpty() }
                val ov = o.optString("overview").ifBlank { e?.optString("overview").orEmpty() }
                val still = o.optString("still_path").ifBlank { e?.optString("still_path").orEmpty() }
                    .takeIf { it.isNotBlank() && it != "null" }
                val rt = o.optInt("runtime", 0).takeIf { it > 0 }
                val air = o.optString("air_date").takeIf { it.isNotBlank() && it != "null" }
                map["${sn}_$n"] = TEp(name, ov, still?.let { "$tmdbImg/w780$it" }, rt, air)
            }
        }
        val pp = sh.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val bp = sh.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
        return TShow(
            pp?.let { "$tmdbImg/w780$it" },
            bp?.let { "$tmdbImg/w1280$it" },
            sh.optString("overview").takeIf { it.isNotBlank() },
            map
        )
    }

    // ------------------------------------------------------------------
    // Detay sayfası
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        var path = pathOf(url)
        if (kindOf(path) == 3) path = epToSeries(path)
        val kind = kindOf(path)
        if (kind == 0) return null
        val pageUrl = mainUrl + path
        val p = fetch(pageUrl, "$mainUrl/", true) ?: return null
        if (p.code != 200) return null
        val doc = Jsoup.parse(p.text, mainUrl)

        val slug = path.substringAfterLast("/").removeSuffix("-izle")
        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val year = Regex("\\((\\d{4})\\)").find(h1)?.groupValues?.get(1)?.toIntOrNull()
            ?: doc.selectFirst("a[href*=/tarih/]")?.text()?.trim()?.toIntOrNull()
        var title = cleanTitle(h1.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), ""))
        if (title.isBlank()) {
            title = slug.split("-").filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        }

        val poster = imgUrl(
            doc.select("img").firstOrNull {
                val s = it.attr("data-src").ifBlank { it.attr("src") }
                s.contains("/series/") && !s.contains("/cover/")
            }
        ) ?: imgUrl(
            doc.select("img").firstOrNull {
                val s = it.attr("data-src").ifBlank { it.attr("src") }
                s.contains("/wp-content/uploads/") && !s.contains("/cover/") && !s.contains("-48x48")
            }
        )
        val cover = imgUrl(doc.select("img").firstOrNull {
            it.attr("data-src").ifBlank { it.attr("src") }.contains("/cover/")
        })

        // Özet: "... KONUSU" başlığından sonraki metin
        var plot: String? = null
        val head = doc.select("h1,h2,h3,h4,h5,h6,div,span,strong").firstOrNull {
            Regex("(?i)(dizisi|filmi)?\\s*konusu").containsMatchIn(it.ownText()) && it.ownText().length < 60
        }
        if (head != null) {
            plot = head.nextElementSibling()?.text()?.takeIf { it.length > 20 }
                ?: head.parent()?.nextElementSibling()?.text()?.takeIf { it.length > 20 }
        }
        if (plot == null) {
            plot = doc.selectFirst("#tv-series-desc, .tv-series-desc")?.text()?.takeIf { it.length > 20 }
        }
        if (plot == null) {
            plot = doc.select("#content p").map { it.text().trim() }.firstOrNull { it.length > 60 }
        }

        val tags = doc.select("a[href*=/tur/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        if (kind == 2) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = cover
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        val today = Date()
        val df = SimpleDateFormat("d MMMM yyyy", Locale("tr", "TR"))
        val seen = HashSet<String>()
        val eps = ArrayList<Episode>()
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href").trim()
            if (raw.startsWith("http", true) && !raw.contains("zdizi.com", true)) continue
            val ep = pathOf(raw)
            val m = epRe.find(ep) ?: continue
            if (m.groupValues[1] != slug) continue
            val epUrl = mainUrl + ep
            if (!seen.add(epUrl)) continue
            val s = m.groupValues[2].toIntOrNull() ?: continue
            val e = m.groupValues[3].toIntOrNull() ?: continue

            var epName = a.selectFirst("small, .truncate")?.text()?.trim().orEmpty()
            if (epName.isBlank()) {
                epName = Regex("\\d+\\.\\s*Bölüm\\s*(.*)$", RegexOption.IGNORE_CASE)
                    .find(a.text().trim())?.groupValues?.get(1)?.trim().orEmpty()
                epName = epName.replace(Regex("\\s*\\d{4,}\\s*$"), "").trim()
            }
            val dateText = a.selectFirst("date")?.text()?.trim().orEmpty()
            var millis: Long? = null
            if (dateText.isNotBlank()) {
                try {
                    millis = df.parse(dateText)?.time
                } catch (ex: Exception) {
                }
            }
            // Gelecek tarihli bölümler listeye eklenmez
            if (millis != null && millis > today.time) continue

            eps.add(newEpisode(epUrl) {
                this.season = s
                this.episode = e
                if (epName.isNotBlank()) this.name = epName
                if (millis != null) this.date = millis
            })
        }
        eps.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        // TMDB: Türkçe bölüm adı, açıklama, bölüm görseli, süre
        val tm = try {
            tmdbShow(title, year, eps.mapNotNull { it.season }.distinct())
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }
        if (tm != null) {
            val df2 = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            for (ep in eps) {
                val t = tm.eps["${ep.season}_${ep.episode}"] ?: continue
                if (t.name.isNotBlank()) ep.name = t.name
                if (t.overview.isNotBlank()) ep.description = t.overview
                if (t.still != null) ep.posterUrl = t.still
                if (t.runtime != null) ep.runTime = t.runtime
                if (ep.date == null && t.air != null) {
                    try {
                        ep.date = df2.parse(t.air)?.time
                    } catch (ex: Exception) {
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, eps) {
            this.posterUrl = tm?.poster ?: poster
            this.backgroundPosterUrl = tm?.backdrop ?: cover
            this.plot = plot ?: tm?.overview
            this.tags = tags
            this.year = year
        }
    }

    // ------------------------------------------------------------------
    // Linkler
    // ------------------------------------------------------------------
    private suspend fun debugLink(callback: (ExtractorLink) -> Unit, info: String) {
        callback(
            newExtractorLink(name, "DEBUG $info".take(600), "https://debug.invalid/x.m3u8", ExtractorLinkType.M3U8) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = abs(data)
        val p = fetch(pageUrl, "$mainUrl/", true)
        if (p == null || p.code != 200) {
            if (debug) {
                debugLink(callback, "sayfa alinamadi: code=${p?.code} $dbg")
                return true
            }
            return false
        }
        val doc = Jsoup.parse(p.text, mainUrl)

        // Oynatıcı seçenekleri: <ul class="linkler"><li><a data-frame="https://dosyaload.com/embed/xxxx">BEPLAYER+</a>
        val embeds = LinkedHashMap<String, String>()
        for (a in doc.select("a[data-frame]")) {
            val u = a.attr("data-frame").trim()
            if (u.isBlank() || u.startsWith("about:")) continue
            val full = abs(u)
            if (!embeds.containsKey(full)) embeds[full] = a.text().trim().ifBlank { "Player" }
        }
        for (f in doc.select("#srcframe[src], .playercont iframe[src], #singlePlay iframe[src]")) {
            val u = f.attr("src").trim()
            if (!u.startsWith("http", true)) continue
            if (!embeds.containsKey(u)) embeds[u] = "Player"
        }

        // Altyazı klasörü için yıl/ay ipuçları (bölüm görselinin yüklendiği ay + yakın aylar)
        val hints = ArrayList<Pair<String, String>>()
        val rx = Regex("/wp-content/uploads/(\\d{4})/(\\d{2})/episodes/")
        rx.findAll(p.text).forEach { m ->
            val pr = m.groupValues[1] to m.groupValues[2]
            if (!hints.contains(pr)) hints.add(pr)
        }
        val cal = Calendar.getInstance()
        for (i in 0 until 3) {
            val pr = cal.get(Calendar.YEAR).toString() to String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
            if (!hints.contains(pr)) hints.add(pr)
            cal.add(Calendar.MONTH, -1)
        }

        var any = false
        for (entry in embeds.entries.take(5)) {
            val embedUrl = entry.key
            val label = entry.value
            if (resolveEmbed(embedUrl, label, hints.take(4), subtitleCallback, callback)) {
                any = true
            } else {
                // son çare: CloudStream'in hazır çıkarıcıları
                try {
                    if (loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)) any = true
                } catch (e: Exception) {
                }
            }
        }

        // Sayfadan adres çıkmadıysa: oynatıcıyı gerçek WebView'da açıp m3u / m3u8 / mp4 isteğini yakala
        if (!any) {
            for (entry in embeds.entries.take(2)) {
                if (resolveEmbed(entry.key, entry.value, hints.take(4), subtitleCallback, callback, true)) {
                    any = true
                    break
                }
            }
        }

        if (!any && debug) {
            debugLink(callback, "embed=${embeds.size} ${embeds.keys.firstOrNull()?.take(60) ?: ""} | $dbg")
            return true
        }
        return any
    }

    // BEPLAYER+ (dosyaload.com/embed/<id>) ve benzeri sayfalar: m3u / m3u8 / mp4 adreslerini bulur
    private suspend fun resolveEmbed(
        embedUrl: String,
        label: String,
        hints: List<Pair<String, String>>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        allowWeb: Boolean = false
    ): Boolean {
        val origin = originOf(embedUrl)
        val er = fetch(
            embedUrl,
            "$mainUrl/",
            false,
            extra = mapOf(
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1"
            )
        )
        if (er == null) {
            dbg = "embed alinamadi ($dbg)"
            return false
        }
        val html = er.text
        // Oturum çerezleri (PHPSESSID, _uads, ...): m3u adresi oturuma bağlı olabilir
        val cookies = LinkedHashMap<String, String>()
        cookies.putAll(er.cookies)

        val texts = ArrayList<String>()
        texts.add(html)
        texts.addAll(unpackAll(html))
        texts.addAll(deepTexts(texts))
        val found = Found()
        texts.forEach { scan(it, origin, found) }

        // Adres sayfada yoksa: sayfadaki player / api / ajax uç noktalarını dene
        if (found.hls.isEmpty() && found.mp4.isEmpty()) {
            val ends = LinkedHashSet<String>()
            Regex("[\"'](/[A-Za-z0-9_\\-/]*(?:player|source|video|api|ajax|m3u)[A-Za-z0-9_\\-/.?=&]*)[\"']", RegexOption.IGNORE_CASE)
                .findAll(fixText(html)).forEach { ends.add(it.groupValues[1]) }
            for (ep in ends.take(4)) {
                val rr = fetch(
                    origin + ep,
                    embedUrl,
                    false,
                    extra = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*"),
                    cookies = cookies
                ) ?: continue
                cookies.putAll(rr.cookies)
                scan(rr.text, origin, found)
                if (found.hls.isNotEmpty() || found.mp4.isNotEmpty()) break
            }
        }

        if (allowWeb && found.hls.isEmpty() && found.mp4.isEmpty()) {
            val wv = webCapture(embedUrl)
            if (wv != null) {
                if (Regex("\\.mp4", RegexOption.IGNORE_CASE).containsMatchIn(wv)) found.mp4.add(wv) else found.hls.add(wv)
                // WebView oturum çerezlerini de oynatıcıya ver
                try {
                    val cm = android.webkit.CookieManager.getInstance()
                    for (cu in listOf(wv, embedUrl)) {
                        cm.getCookie(cu)?.split(";")?.forEach { kv ->
                            val k = kv.substringBefore("=").trim()
                            val v = kv.substringAfter("=", "").trim()
                            if (k.isNotBlank() && v.isNotBlank()) cookies[k] = v
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }

        if (found.hls.isEmpty() && found.mp4.isEmpty()) {
            val kw = Regex("(?i)(m3u|atob|eval\\(|fetch\\(|XMLHttpRequest|jwplayer|player|iframe|\\.mp4)")
                .findAll(html).groupingBy { it.value.lowercase() }.eachCount()
            val si = html.indexOf("setup(")
            val setup = if (si >= 0) html.substring(si, minOf(html.length, si + 400)).replace(Regex("\\s+"), " ") else "yok"
            dbg = "kod=${er.code} len=${html.length} ipucu=$kw setup=$setup | $dbg"
            return false
        }

        emitSubs(found, embedUrl, origin, hints, cookies, subtitleCallback)

        val ck = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val hd = LinkedHashMap<String, String>()
        hd["User-Agent"] = ua
        hd["Referer"] = embedUrl
        hd["Origin"] = origin
        if (ck.isNotBlank()) hd["Cookie"] = ck

        // Not: m3u adresi tek kullanımlık olabilir; bu yüzden oynatmadan önce kendimiz istemiyoruz
        for (u in found.hls.take(3)) {
            callback(
                newExtractorLink(name, label, u, ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = hd
                }
            )
        }
        for (u in found.mp4.take(2)) {
            callback(
                newExtractorLink(name, label, u, ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = hd
                }
            )
        }
        return true
    }

    private val mediaRe = Regex("""(?i)/m3u/[A-Za-z0-9+/=_%.\-]{24,}|\.m3u8|\.mp4(\?|$)""")

    // Sayfa yüklenince çalışır: jwplayer oynatma listesi hazırsa JSON olarak döndürür, değilse oynatmayı başlatır
    private val playScript = """
        (function(){
          try{
            var p=jwplayer();
            var pl=p.getPlaylist();
            if(pl&&pl.length){return JSON.stringify(pl);}
          }catch(e){}
          var n=0;
          var t=setInterval(function(){
            n++;
            try{var q=jwplayer();if(q&&q.play){q.play(true);}}catch(e){}
            try{var el=document.querySelector('.jw-icon-display,.jw-display-icon-container');if(el){el.click();}}catch(e){}
            if(n>20){clearInterval(t);}
          },700);
          return '';
        })()
    """.trimIndent()

    // Oynatıcı sayfasını WebView'da açar: ya oynatıcının istediği medya adresini yakalar ya da jwplayer listesinden okur
    private suspend fun webCapture(embedUrl: String): String? {
        var plist = ""
        var hit: String? = null
        try {
            val r = app.get(
                embedUrl,
                headers = hdr("$mainUrl/"),
                referer = "$mainUrl/",
                interceptor = WebViewResolver(
                    mediaRe,
                    // m3u adresi (token) sayfayı açan tarayıcının UA'sına bağlı olabilir: oynatıcıya verilen UA ile AYNI olsun
                    userAgent = ua,
                    useOkhttp = false,
                    script = playScript,
                    scriptCallback = { res -> plist = res },
                    timeout = 15_000L
                ),
                timeout = 30L
            )
            val u = r.url
            if (u != embedUrl && mediaRe.containsMatchIn(u)) hit = u
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg = "webview: ${e.message?.take(60)} $dbg"
        }
        if (hit != null) return hit
        // evaluateJavascript sonucu JSON metni olarak gelir: önce dışındaki tırnağı aç
        val txt = try {
            (org.json.JSONTokener(plist).nextValue() as? String).orEmpty()
        } catch (e: Exception) {
            plist
        }
        val origin = originOf(embedUrl)
        for (m in Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").findAll(fixText(txt))) {
            val v = m.groupValues[1]
            if (Regex("\\.(vtt|srt|jpe?g|png|webp|gif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(v)) continue
            return when {
                v.startsWith("http", true) -> v
                v.startsWith("//") -> "https:$v"
                v.startsWith("/") -> origin + v
                else -> continue
            }
        }
        dbg = "playlist=${plist.take(160)} $dbg"
        return null
    }

    // Metin içinden m3u / m3u8 / mp4 / altyazı adreslerini topla
    private fun scan(raw: String, origin: String, f: Found) {
        val t = fixText(raw)
        // BEPLAYER+: https://host/m3u/<şifreli-token> (uzantısız HLS)
        Regex("https?://[^\\s\"'<>\\\\)]+?/m3u/[A-Za-z0-9+/=_%\\-.]{24,}")
            .findAll(t).forEach { f.hls.add(it.value) }
        Regex("[\"'](/m3u/[A-Za-z0-9+/=_%\\-.]{24,})[\"']")
            .findAll(t).forEach { f.hls.add(origin + it.groupValues[1]) }
        Regex("https?://[^\\s\"'<>\\\\)]+?\\.m3u8(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.hls.add(it.value) }
        Regex("[\"'](/[^\"'\\s]+?\\.m3u8[^\"'\\s]*)[\"']", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.hls.add(origin + it.groupValues[1]) }
        Regex("https?://[^\\s\"'<>\\\\)]+?\\.mp4(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.mp4.add(it.value) }
        // jwplayer: file: "..." / "file":"..." (altyazı ve görseller hariç)
        Regex("[\"']?file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(t).forEach { m ->
            val v = m.groupValues[1]
            if (Regex("\\.(vtt|srt|jpe?g|png|webp|gif|css|js)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(v)) return@forEach
            val u = when {
                v.startsWith("http", true) -> v
                v.startsWith("//") -> "https:$v"
                v.startsWith("/") -> origin + v
                else -> return@forEach
            }
            if (u.contains(".mp4", true)) f.mp4.add(u) else f.hls.add(u)
        }
        Regex("https?://[^\\s\"'<>\\\\)]+?\\.(?:vtt|srt)(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { m ->
                if (Regex("thumb|sprite|chapter", RegexOption.IGNORE_CASE).containsMatchIn(m.value)) return@forEach
                val ctx = t.substring(maxOf(0, m.range.first - 150), minOf(t.length, m.range.last + 150))
                val lb = Regex("label[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(ctx)?.groupValues?.get(1)
                if (!f.subs.containsKey(m.value)) f.subs[m.value] = lb ?: ""
            }
        Regex("[\"'](/upload/[^\"'\\s]+?\\.(?:vtt|srt))[\"']", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { m ->
                val u = origin + m.groupValues[1]
                if (!f.subs.containsKey(u)) f.subs[u] = ""
            }
    }

    private fun subName(label: String?, url: String): String {
        val l = (label ?: "").lowercase()
        return when {
            l.contains("turk") || l.contains("türk") || l == "tr" || l == "tur" -> "Türkçe"
            l.contains("engl") || l.contains("ingiliz") || l == "en" || l == "eng" -> "İngilizce"
            !label.isNullOrBlank() -> label
            url.contains("tur", true) -> "Türkçe"
            url.contains("eng", true) -> "İngilizce"
            else -> "Türkçe"
        }
    }

    // Sayfada altyazı adresi yoksa: /upload/videos/<yıl>/<ay>/<id>/altyazi/tur_sub.vtt adreslerini yoklar
    private suspend fun emitSubs(
        found: Found,
        embedUrl: String,
        origin: String,
        hints: List<Pair<String, String>>,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val done = HashSet<String>()
        for ((u, lb) in found.subs) {
            if (done.add(u)) subtitleCallback(SubtitleFile(subName(lb, u), u))
        }
        if (found.subs.isNotEmpty()) return
        val id = Regex("/embed/([A-Za-z0-9_-]+)").find(embedUrl)?.groupValues?.get(1) ?: return
        val cands = ArrayList<String>()
        for (h in hints) {
            for (nm in listOf("tur_sub", "eng_sub")) {
                cands.add("$origin/upload/videos/${h.first}/${h.second}/$id/altyazi/$nm.vtt")
            }
        }
        val ok = cands.take(8).amap { c ->
            val r = fetch(c, embedUrl, false, cookies = cookies)
            if (r != null && r.code == 200 && r.text.trimStart().startsWith("WEBVTT")) c else null
        }.filterNotNull()
        ok.forEach { if (done.add(it)) subtitleCallback(SubtitleFile(subName(null, it), it)) }
    }

    private fun b64bin(s: String): ByteArray {
        var c = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        while (c.length % 4 != 0) c += "="
        return try {
            Base64.decode(c, Base64.DEFAULT)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun deepTexts(texts: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in texts) {
            if (Regex("\\\\x[0-9a-fA-F]{2}|\\\\u[0-9a-fA-F]{4}").containsMatchIn(t)) {
                var d = Regex("\\\\x([0-9a-fA-F]{2})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
                d = Regex("\\\\u([0-9a-fA-F]{4})").replace(d) { it.groupValues[1].toInt(16).toChar().toString() }
                out.add(d)
            }
            if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(t)) {
                try {
                    out.add(URLDecoder.decode(t.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"), "UTF-8"))
                } catch (e: Exception) {
                }
            }
            Regex("[A-Za-z0-9+/_\\-]{28,}={0,2}").findAll(t).take(400).forEach { m ->
                val bytes = b64bin(m.value)
                if (bytes.isNotEmpty()) {
                    val d = String(bytes, Charsets.ISO_8859_1)
                    if (d.length > 12 && Regex("https?://|\\.m3u8|\\.mp4|\"file\"|sources", RegexOption.IGNORE_CASE).containsMatchIn(d)) {
                        out.add(d)
                    }
                }
            }
        }
        return out.toList()
    }

    // Dean Edwards packer: eval(function(p,a,c,k,e,d){...}('...',62,300,'a|b'.split('|'),0,{}))
    private fun unpackAll(text: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("""\}\('((?:\\.|[^'\\])*)',\s*(\d+),\s*(\d+),\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)""")
        re.findAll(text).forEach { m ->
            try {
                var p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                val a = m.groupValues[2].toInt()
                var c = m.groupValues[3].toInt()
                val k = m.groupValues[4].split("|")
                fun enc(n: Int): String {
                    val hi = if (n < a) "" else enc(n / a)
                    val r = n % a
                    return hi + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
                }
                while (c-- > 0) {
                    if (c < k.size && k[c].isNotEmpty()) {
                        val word = k[c]
                        p = Regex("\\b" + Regex.escape(enc(c)) + "\\b").replace(p) { word }
                    }
                }
                out.add(p)
            } catch (e: Exception) {
            }
        }
        return out
    }
}
