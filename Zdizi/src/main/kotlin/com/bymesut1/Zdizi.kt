package com.bymesut1

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

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

    private fun d(m: String) {
        try {
            android.util.Log.d("ZdiziDbg", m.replace("\n", " ").take(900))
        } catch (e: Throwable) {
        }
    }

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

        // Dizinin TÜM sezonlarını TMDB'den öğren (sitede eksik sezonları tamamlamak için)
        val allSeasons = ArrayList<Int>()
        allSeasons.addAll(seasons)
        val detail = tmdbJson("$tmdbApi/tv/$id?api_key=$tmdbKey&language=tr-TR")
        val sArr = detail?.optJSONArray("seasons")
        if (sArr != null) for (i in 0 until sArr.length()) {
            val sn = sArr.optJSONObject(i)?.optInt("season_number", 0) ?: 0
            if (sn > 0) allSeasons.add(sn)
        }
        val seasonList = allSeasons.filter { it > 0 }.distinct().sorted()

        val map = java.util.concurrent.ConcurrentHashMap<String, TEp>()
        seasonList.amap { sn ->
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

    // Bölüm sayfası gerçekten var mı (oynatıcı seçeneği içeriyor mu)?
    private suspend fun epPageExists(url: String): Boolean {
        val r = fetch(url, "$mainUrl/", true) ?: return false
        return r.code == 200 && (r.text.contains("data-frame") || r.text.contains("srcframe") || r.text.contains("playercont"))
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
        // Sitedeki bölüm adreslerinde kullanılan gerçek slug (sayfa slug'ından farklı olabilir)
        var epSlug: String? = null
        val slugNorm = slug.removeSuffix("-izle")
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href").trim()
            if (raw.startsWith("http", true) && !raw.contains("zdizi.com", true)) continue
            val ep = pathOf(raw)
            val m = epRe.find(ep) ?: continue
            val gs = m.groupValues[1].removeSuffix("-izle")
            if (gs != slugNorm) continue
            if (epSlug == null) epSlug = m.groupValues[1]
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

        // TMDB: Türkçe bölüm adı, açıklama, bölüm görseli, süre (+ tüm sezon listesi)
        val tm = try {
            tmdbShow(title, year, eps.mapNotNull { it.season }.distinct())
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }

        // Sitedeki dizi sayfasında listelenmeyen sezonları tamamla:
        // TMDB'de var, sayfada yok -> o sezonun 1. bölüm sayfası sitede açılıyorsa tüm bölümleri ekle
        if (tm != null) {
            val have = eps.mapNotNull { it.season }.toSet()
            val tmSeasons = tm.eps.keys.mapNotNull { it.substringBefore("_").toIntOrNull() }
                .distinct().filter { it > 0 && it !in have }.sorted()
            val baseSlug = epSlug ?: slugNorm
            if (tmSeasons.isNotEmpty()) {
                val found = tmSeasons.amap { sn ->
                    val nums = tm.eps.keys.filter { it.startsWith("${sn}_") }
                        .mapNotNull { it.substringAfter("_").toIntOrNull() }.sorted()
                    if (nums.isEmpty()) return@amap null
                    var ok = false
                    for (probe in nums.take(2)) {
                        if (epPageExists("$mainUrl/$baseSlug-$sn-sezon-$probe-bolum-izle")) {
                            ok = true
                            break
                        }
                    }
                    if (ok) sn to nums else null
                }.filterNotNull()
                for ((sn, nums) in found) {
                    for (n in nums) {
                        val t = tm.eps["${sn}_$n"]
                        // Yayınlanmamış (gelecek tarihli) bölümleri ekleme
                        val airMillis = try {
                            t?.air?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)?.time }
                        } catch (ex: Exception) {
                            null
                        }
                        if (airMillis != null && airMillis > today.time) continue
                        val epUrl = "$mainUrl/$baseSlug-$sn-sezon-$n-bolum-izle"
                        if (!seen.add(epUrl)) continue
                        eps.add(newEpisode(epUrl) {
                            this.season = sn
                            this.episode = n
                        })
                    }
                }
            }
        }

        eps.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

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

        // Adres JavaScript ile üretiliyor ve TEK KULLANIMLIK: kendi WebView'ımızda açıp m3u isteklerini
        // biz çekeriz (WebView'a aynı yanıtı veririz), içeriği saklar ve yerel sunucudan oynatıcıya sunarız.
        if (found.hls.isEmpty() && found.mp4.isEmpty()) {
            if (captureAndServe(embedUrl, label, origin, hints, cookies, subtitleCallback, callback)) return true
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

    // ------------------------------------------------------------------
    // Kendi WebView'ımız + yerel HLS sunucusu (tek kullanımlık m3u adresleri için)
    // ------------------------------------------------------------------
    private class CapBody(val code: Int, val mime: String, val bytes: ByteArray)

    private fun looksM3u(b: ByteArray): Boolean =
        String(b, 0, minOf(b.size, 40), Charsets.ISO_8859_1).trimStart().startsWith("#EXTM3U")

    private fun appCtx(): android.content.Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.app.Application
    } catch (e: Throwable) {
        null
    }

    private val capRe = Regex("(?i)/(m3u|list|hls|playlist|manifest|master|stream|source)s?/|\\.m3u8|\\.m3u(\\?|$)")

    // Oynatıcı sayfasını kendi WebView'ımızda açar. m3u / list isteklerini OkHttp ile BİZ çeker
    // (tek kullanımlık jeton bizde harcanır), aynı yanıtı WebView'a da verir ve içeriği saklar.
    private fun ownWebCapture(embedUrl: String, timeoutMs: Long): LinkedHashMap<String, CapBody> {
        val result = LinkedHashMap<String, CapBody>()
        val ctx = appCtx() ?: run {
            dbg = "context yok ($dbg)"
            return result
        }
        val caps = ConcurrentHashMap<String, CapBody>()
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val holder = arrayOfNulls<WebView>(1)
        val created = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val client = okhttp3.OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

        main.post {
            try {
                val wv = WebView(ctx)
                holder[0] = wv
                val st = wv.settings
                st.javaScriptEnabled = true
                st.domStorageEnabled = true
                st.mediaPlaybackRequiresUserGesture = false
                st.userAgentString = ua
                try {
                    st.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                } catch (e: Throwable) {
                }
                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                } catch (e: Throwable) {
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        try {
                            view?.evaluateJavascript(playScript, null)
                        } catch (e: Throwable) {
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (request == null) return null
                        val u = request.url.toString()
                        if (request.method != "GET" || !capRe.containsMatchIn(u)) return null
                        if (u.contains("googletagmanager", true) || u.contains("google-analytics", true) ||
                            u.contains("cloudfront.net", true) || u.contains("cdn.jsdelivr", true)
                        ) return null
                        return try {
                            val rb = okhttp3.Request.Builder().url(u)
                            for ((k, v) in request.requestHeaders) {
                                if (k.equals("Accept-Encoding", true) || k.equals("Range", true) ||
                                    k.equals("Host", true) || k.equals("Connection", true)
                                ) continue
                                rb.header(k, v)
                            }
                            val ck = CookieManager.getInstance().getCookie(u)
                            if (!ck.isNullOrBlank()) rb.header("Cookie", ck)
                            client.newCall(rb.build()).execute().use { resp ->
                                val bytes = resp.body?.bytes() ?: ByteArray(0)
                                var mime = resp.header("Content-Type")?.substringBefore(";")?.trim().orEmpty()
                                if (looksM3u(bytes)) mime = "application/vnd.apple.mpegurl"
                                if (mime.isBlank()) mime = "application/octet-stream"
                                caps[u] = CapBody(resp.code, mime, bytes)
                                order.add(u)
                                d("capture ${resp.code} ${bytes.size}b m3u=${looksM3u(bytes)} ${u.takeLast(40)}")
                                val hd = HashMap<String, String>()
                                hd["Access-Control-Allow-Origin"] = "*"
                                val code = if (resp.code in 200..299 || resp.code in 400..599) resp.code else 200
                                WebResourceResponse(
                                    mime, "utf-8", code,
                                    resp.message.ifBlank { "OK" }, hd, ByteArrayInputStream(bytes)
                                )
                            }
                        } catch (e: Exception) {
                            d("capture hata ${e.javaClass.simpleName} ${u.takeLast(40)}")
                            null
                        }
                    }
                }
                wv.loadUrl(embedUrl, mapOf("Referer" to "$mainUrl/"))
            } catch (e: Throwable) {
                d("webview olusturulamadi ${e.javaClass.simpleName} ${e.message}")
            } finally {
                created.countDown()
            }
        }
        try {
            created.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
        }

        val end = System.currentTimeMillis() + timeoutMs
        var firstHit = 0L
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                break
            }
            val has = synchronized(order) { order.any { u -> caps[u]?.let { it.code in 200..299 && looksM3u(it.bytes) } == true } }
            if (has) {
                if (firstHit == 0L) firstHit = System.currentTimeMillis()
                // ikinci jeton (kalite) ve alt listeler de gelsin diye kısa bekle
                if (System.currentTimeMillis() - firstHit > 2500) break
            }
        }
        main.post {
            try {
                holder[0]?.stopLoading()
                holder[0]?.destroy()
            } catch (e: Throwable) {
            }
        }
        synchronized(order) { order.toList() }.forEach { u -> caps[u]?.let { result[u] = it } }
        return result
    }

    private suspend fun captureAndServe(
        embedUrl: String,
        label: String,
        origin: String,
        hints: List<Pair<String, String>>,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val caps = try {
            ownWebCapture(embedUrl, 20_000L)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg = "wv yakalama hata ${e.javaClass.simpleName} ($dbg)"
            return false
        }
        val lists = LinkedHashMap<String, CapBody>()
        for ((u, b) in caps) if (b.code in 200..299 && looksM3u(b.bytes)) lists[u] = b
        if (lists.isEmpty()) {
            dbg = "wv: m3u yok, istek=${caps.keys.map { it.takeLast(24) }} | $dbg"
            return false
        }
        if (!HlsProxy.ensure()) {
            dbg = "yerel sunucu baslamadi | $dbg"
            return false
        }

        // WebView oturum çerezleri (m3u / parça adresleri oturuma bağlı olabilir)
        val ckMap = LinkedHashMap<String, String>()
        ckMap.putAll(cookies)
        try {
            val cm = CookieManager.getInstance()
            for (cu in listOf(embedUrl, origin)) {
                cm.getCookie(cu)?.split(";")?.forEach { kv ->
                    val k = kv.substringBefore("=").trim()
                    val v = kv.substringAfter("=", "").trim()
                    if (k.isNotBlank() && v.isNotBlank()) ckMap[k] = v
                }
            }
        } catch (e: Exception) {
        }
        val ck = ckMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val hd = LinkedHashMap<String, String>()
        hd["User-Agent"] = ua
        hd["Referer"] = embedUrl
        hd["Origin"] = origin
        if (ck.isNotBlank()) hd["Cookie"] = ck

        // Yakalanan içerikleri sunucuya kaydet; başka bir listenin içinden çağrılanları "alt liste" say
        val referenced = HashSet<String>()
        for ((u, b) in lists) HlsProxy.refsOf(String(b.bytes, Charsets.UTF_8), u).forEach { referenced.add(it) }
        for ((u, b) in lists) HlsProxy.register(u, hd, b.bytes)
        var tops = lists.keys.filter { it !in referenced }
        if (tops.isEmpty()) tops = lists.keys.toList()
        // alt listeleri (kalite listeleri) hemen çekip sakla: jeton süresi dolmadan
        for (u in tops) HlsProxy.prefetchRefs(String(lists[u]!!.bytes, Charsets.UTF_8), u, hd)

        emitSubs(Found(), embedUrl, origin, hints, cookies, subtitleCallback)

        var n = 0
        for (u in tops.take(3)) {
            val pu = HlsProxy.urlFor(u, hd, null)
            val nm = if (n == 0) label else "$label ${n + 1}"
            d("yerel sunucu $nm -> $pu (${u.takeLast(30)})")
            callback(
                newExtractorLink(name, nm, pu, ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = hd
                }
            )
            n++
        }
        return n > 0
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

// ----------------------------------------------------------------------
// Yerel HLS sunucusu: 127.0.0.1 üzerinde, tek kullanımlık m3u içeriklerini saklayıp oynatıcıya
// (ve CloudStream'in önizleme yükleyicisine) istediği kadar kez sunar. Liste içindeki adresleri
// mutlak adrese çevirir; alt listeleri / anahtarları yine bu sunucudan geçirir.
// ----------------------------------------------------------------------
private object HlsProxy {
    private class Entry(val url: String, val hdr: Map<String, String>, @Volatile var raw: ByteArray?) {
        @Volatile
        var out: ByteArray? = null

        @Volatile
        var mime: String = "application/vnd.apple.mpegurl"
    }

    private val store = ConcurrentHashMap<String, Entry>()
    private val byUrl = ConcurrentHashMap<String, String>()
    private val client = okhttp3.OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port = 0

    @Synchronized
    fun ensure(): Boolean {
        val cur = server
        if (cur != null && !cur.isClosed) return true
        return try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            val t = Thread {
                while (!s.isClosed) {
                    try {
                        val c = s.accept()
                        val w = Thread { serve(c) }
                        w.isDaemon = true
                        w.start()
                    } catch (e: Exception) {
                        if (s.isClosed) break
                    }
                }
            }
            t.isDaemon = true
            t.name = "zdizi-hls-proxy"
            t.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun idFor(real: String, hdr: Map<String, String>, raw: ByteArray?): String {
        val id = byUrl.getOrPut(real) {
            val n = java.lang.Long.toString(System.nanoTime(), 36)
            store[n] = Entry(real, hdr, raw)
            n
        }
        if (raw != null) store[id]?.let { if (it.raw == null) it.raw = raw }
        return id
    }

    fun register(real: String, hdr: Map<String, String>, raw: ByteArray) {
        idFor(real, hdr, raw)
    }

    fun urlFor(real: String, hdr: Map<String, String>, raw: ByteArray?): String =
        "http://127.0.0.1:$port/p/${idFor(real, hdr, raw)}"

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        return try {
            java.net.URI(base).resolve(ref).toString()
        } catch (e: Exception) {
            val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: ""
            when {
                ref.startsWith("//") -> "https:$ref"
                ref.startsWith("/") -> origin + ref
                else -> base.substringBeforeLast('/') + "/" + ref
            }
        }
    }

    private val uriRe = Regex("URI=\"([^\"]+)\"")

    // Bir ana listedeki alt liste / ses / anahtar adresleri (mutlak)
    fun refsOf(text: String, base: String): List<String> {
        val out = ArrayList<String>()
        var next = false
        for (ln in text.lines()) {
            val t = ln.trim()
            if (t.startsWith("#EXT-X-STREAM-INF")) {
                next = true
                continue
            }
            if (t.startsWith("#")) {
                if (t.startsWith("#EXT-X-MEDIA")) {
                    uriRe.find(t)?.let { out.add(resolve(base, it.groupValues[1])) }
                }
                continue
            }
            if (t.isEmpty()) continue
            if (next) out.add(resolve(base, t))
            next = false
        }
        return out
    }

    // Alt listeleri jeton süresi dolmadan hemen çekip saklar
    fun prefetchRefs(text: String, base: String, hdr: Map<String, String>) {
        for (u in refsOf(text, base)) {
            val id = byUrl[u]
            val e = id?.let { store[it] }
            if (e != null && e.raw != null) continue
            val b = fetchBytes(u, hdr) ?: continue
            idFor(u, hdr, b)
        }
    }

    private fun fetchBytes(url: String, hdr: Map<String, String>): ByteArray? = try {
        val rb = okhttp3.Request.Builder().url(url)
        for ((k, v) in hdr) rb.header(k, v)
        client.newCall(rb.build()).execute().use { r ->
            if (r.code in 200..299) r.body?.bytes() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun looksM3u(b: ByteArray): Boolean =
        String(b, 0, minOf(b.size, 40), Charsets.ISO_8859_1).trimStart().startsWith("#EXTM3U")

    private fun rewrite(text: String, base: String, hdr: Map<String, String>): String {
        val sb = StringBuilder()
        var next = false
        for (ln in text.lines()) {
            val t = ln.trim()
            if (t.startsWith("#EXT-X-STREAM-INF")) {
                next = true
                sb.append(ln).append('\n')
                continue
            }
            if (t.startsWith("#")) {
                if (t.contains("URI=\"")) {
                    val viaProxy = t.startsWith("#EXT-X-MEDIA") || t.startsWith("#EXT-X-KEY")
                    val nl = uriRe.replace(ln) { m ->
                        val a = resolve(base, m.groupValues[1])
                        "URI=\"" + (if (viaProxy) urlFor(a, hdr, null) else a) + "\""
                    }
                    sb.append(nl).append('\n')
                } else sb.append(ln).append('\n')
                continue
            }
            if (t.isEmpty()) {
                sb.append(ln).append('\n')
                continue
            }
            val a = resolve(base, t)
            if (next) sb.append(urlFor(a, hdr, null)).append('\n') else sb.append(a).append('\n')
            next = false
        }
        return sb.toString()
    }

    private fun bodyOf(e: Entry): ByteArray? {
        e.out?.let { return it }
        synchronized(e) {
            e.out?.let { return it }
            var raw = e.raw
            if (raw == null) {
                raw = fetchBytes(e.url, e.hdr)
                if (raw != null) e.raw = raw
            }
            if (raw == null) return null
            if (looksM3u(raw)) {
                e.mime = "application/vnd.apple.mpegurl"
                e.out = rewrite(String(raw, Charsets.UTF_8), e.url, e.hdr).toByteArray(Charsets.UTF_8)
            } else {
                e.mime = "application/octet-stream"
                e.out = raw
            }
            return e.out
        }
    }

    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val reqLine = inp.readLine() ?: return
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
            }
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            val id = path.removePrefix("/p/").substringBefore("?")
            val os = sock.getOutputStream()
            val e = store[id]
            val body = if (e != null) bodyOf(e) else null
            if (e == null || body == null) {
                val code = if (e == null) "404 Not Found" else "502 Bad Gateway"
                os.write("HTTP/1.1 $code\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                os.flush()
                return
            }
            val head = "HTTP/1.1 200 OK\r\nContent-Type: ${e.mime}\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nAccept-Ranges: none\r\nConnection: close\r\n\r\n"
            os.write(head.toByteArray())
            if (method != "HEAD") os.write(body)
            os.flush()
        } catch (ex: Exception) {
        } finally {
            try {
                sock.close()
            } catch (ex: Exception) {
            }
        }
    }
}
