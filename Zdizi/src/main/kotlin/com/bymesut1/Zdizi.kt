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
    override val supportedTypes = setOf(TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // Bağlantı bulunamazsa kaynak listesine "DEBUG ..." satırı ekler (sorun bitince false yap)
    private val debug = false

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

    // Sitenin kendi bölüm bilgisi (bölüm sayfasındaki / dizi sayfasındaki liste)
    private class SEp(
        val season: Int,
        val episode: Int,
        val url: String,
        val slug: String,
        val name: String,
        val date: Long?
    )

    private val epRe = Regex("^/(.+)-(\\d+)-sezon-(\\d+)-bolum-izle/?$")

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

    // 1 = dizi, 3 = bölüm, 0 = diğer (filmler desteklenmiyor)
    private fun kindOf(path: String): Int {
        if (Regex("^/diziler/[^/]+$").matches(path)) return 1
        if (epRe.containsMatchIn(path)) return 3
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

    // "Gibi 7.1 2022" gibi sonuna yapışan puan / yıl metnini siler
    private fun stripMeta(s: String): String =
        s.replace(Regex("\\s*%?\\s*\\d+(?:[.,]\\d+)?\\s+(?:19|20)\\d{2}\\s*$"), "").trim()

    private fun looksTitle(s: String): Boolean =
        s.isNotBlank() && Regex("\\p{L}").containsMatchIn(s)

    // Sitede isim bulunamazsa adresten üretir: /diziler/la-casa-de-papel-izle -> La Casa De Papel
    private fun slugTitle(path: String): String =
        path.substringAfterLast("/").removeSuffix("-izle").split("-").filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }

    // "7. Bölüm", "Bölüm 7", "Episode 7" gibi genel (anlamsız) bölüm adları
    private fun isGenericName(n: String): Boolean =
        n.isBlank() || Regex("^(?:\\d+\\s*\\.?\\s*)?(?:B[öo]l[üu]m|Episode)(?:\\s*\\d+)?$", RegexOption.IGNORE_CASE)
            .matches(n.trim())

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

    // Sayfadaki dizi kartları (yan menü, üst menü ve alt bilgi alanı hariç). Filmler alınmaz.
    private fun cards(doc: Document): List<SearchResponse> {
        val titles = LinkedHashMap<String, String>()
        val posters = HashMap<String, String>()
        val scope = doc.selectFirst("#content") ?: doc
        for (a in scope.select("a[href]")) {
            if (a.parents().any { it.tagName() == "aside" || it.tagName() == "footer" || it.tagName() == "header" }) continue
            val raw = a.attr("href").trim()
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript", true)) continue
            if (raw.startsWith("http", true) && !raw.contains("zdizi.com", true)) continue
            var path = pathOf(raw)
            val k0 = kindOf(path)
            if (k0 == 0) continue
            val isEp = k0 == 3
            if (isEp) path = epToSeries(path)
            val url = mainUrl + path
            if (!titles.containsKey(url)) titles[url] = ""

            // Başlık: sitedeki asıl isim (puan / yıl metinleri isim sayılmaz). Bölüm bağlantılarından alınmaz.
            if (!isEp && titles[url].isNullOrBlank()) {
                val cand = listOf(
                    a.attr("title"),
                    a.selectFirst("h1,h2,h3,h4,h5,.title,.name")?.text().orEmpty(),
                    a.selectFirst("img")?.attr("alt").orEmpty(),
                    a.text()
                ).map { cleanTitle(stripMeta(it)) }.firstOrNull { looksTitle(it) }
                if (cand != null) titles[url] = cand
            }
            if (!posters.containsKey(url)) {
                val poster = imgUrl(a.selectFirst("img") ?: a.parent()?.selectFirst("img"))
                    ?: bgImage(a) ?: bgImage(a.parent())
                if (poster != null) posters[url] = poster
            }
        }
        val out = ArrayList<SearchResponse>()
        for ((url, t) in titles) {
            val title = if (t.isNotBlank()) t else slugTitle(pathOf(url))
            if (title.isBlank()) continue
            val poster = posters[url]
            out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
        }
        return out
    }

    // Bir sayfadaki bölüm bağlantılarını (ad + tarih dahil) toplar. Slug'ı eşleşmeyen diziler alınmaz.
    private fun collectEps(doc: Document, slugNorm: String, df: SimpleDateFormat): LinkedHashMap<String, SEp> {
        val out = LinkedHashMap<String, SEp>()
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href").trim()
            if (raw.startsWith("http", true) && !raw.contains("zdizi.com", true)) continue
            val ep = pathOf(raw)
            val m = epRe.find(ep) ?: continue
            if (m.groupValues[1].removeSuffix("-izle") != slugNorm) continue
            val s = m.groupValues[2].toIntOrNull() ?: continue
            val e = m.groupValues[3].toIntOrNull() ?: continue
            val key = "${s}_$e"

            var rest = a.text().trim()
            var dateText = a.selectFirst("date")?.text()?.trim().orEmpty()
            val dm = Regex("(\\d{1,2}\\s+\\p{L}+\\s+\\d{4})\\s*$").find(rest)
            if (dm != null) {
                if (dateText.isBlank()) dateText = dm.groupValues[1]
                rest = rest.substring(0, dm.range.first).trim()
            }
            var epName = a.selectFirst("small, .truncate")?.text()?.trim().orEmpty()
            if (epName.isBlank()) {
                epName = Regex("^\\d+\\s*\\.\\s*B[öo]l[üu]m\\s*(.*)$", RegexOption.IGNORE_CASE)
                    .find(rest)?.groupValues?.get(1)?.trim().orEmpty()
            }
            epName = epName.replace(Regex("\\s*\\d{4,}\\s*$"), "").trim()
            if (isGenericName(epName)) epName = ""

            var millis: Long? = null
            if (dateText.isNotBlank()) {
                try {
                    millis = df.parse(dateText)?.time
                } catch (ex: Exception) {
                }
            }
            val old = out[key]
            if (old != null && (old.name.isNotBlank() || epName.isBlank())) continue
            out[key] = SEp(s, e, mainUrl + ep, m.groupValues[1], epName, millis ?: old?.date)
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
                val nm = r.name.replace(Regex("(?i)\\bizle\\b"), "").replace(Regex("\\(\\d{4}\\)"), "")
                    .replace(Regex("\\s+"), " ").trim()
                val q = URLEncoder.encode(nm, "UTF-8")
                val j = tmdbJson("$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&query=$q")
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
            if (r.isNotEmpty()) {
                fillPosters(r)
                return r
            }
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
                    if (kind != 1) continue
                    val url = mainUrl + path
                    if (!seen.add(url)) continue
                    var title = cleanTitle(stripMeta(Jsoup.parse(o.optString("title", "")).text()))
                    if (!looksTitle(title)) title = slugTitle(path)
                    if (title.isBlank()) continue
                    out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { })
                }
            } catch (e: Exception) {
            }
        }
        fillPosters(out)
        return out
    }

    // ------------------------------------------------------------------
    // TMDB: Türkçe bölüm adı / açıklama / bölüm görseli
    // ------------------------------------------------------------------
    // nameTr / ovTr: ad / açıklama TMDB'de gerçekten Türkçe mi (değilse sonradan Türkçeye çevrilir)
    private class TEp(
        val name: String,
        val overview: String,
        val still: String?,
        val runtime: Int?,
        val air: String?,
        val nameTr: Boolean,
        val ovTr: Boolean
    )

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

        // Dizinin TÜM sezonlarını TMDB'den öğren
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
                val trName = if (tr != null) o.optString("name") else ""
                val enName = e?.optString("name").orEmpty()
                val name = trName.ifBlank { enName.ifBlank { o.optString("name") } }
                // Türkçe karşılığı yoksa TMDB aynı (İngilizce) adı döndürür
                val nameTr = trName.isNotBlank() && !trName.equals(enName, true)
                val trOv = if (tr != null) o.optString("overview") else ""
                val enOv = e?.optString("overview").orEmpty()
                val ov = trOv.ifBlank { enOv.ifBlank { o.optString("overview") } }
                val ovTr = trOv.isNotBlank()
                val still = o.optString("still_path").ifBlank { e?.optString("still_path").orEmpty() }
                    .takeIf { it.isNotBlank() && it != "null" }
                val rt = o.optInt("runtime", 0).takeIf { it > 0 }
                val air = o.optString("air_date").takeIf { it.isNotBlank() && it != "null" }
                map["${sn}_$n"] = TEp(name, ov, still?.let { "$tmdbImg/w780$it" }, rt, air, nameTr, ovTr)
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
    // Türkçeye çeviri (TMDB'de Türkçe karşılığı olmayan bölüm adı / açıklaması için)
    // ------------------------------------------------------------------
    private val trCache = ConcurrentHashMap<String, String>()

    private suspend fun gtr(text: String): String? {
        return try {
            val r = app.post(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=tr&dt=t",
                data = mapOf("q" to text),
                timeout = 20L
            ).text
            val a0 = JSONArray(r).optJSONArray(0)
            val sb = StringBuilder()
            if (a0 != null) for (i in 0 until a0.length()) sb.append(a0.optJSONArray(i)?.optString(0).orEmpty())
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }
    }

    // Metinleri toplu çevirir (chunk adet metin tek istekte); sonuç: özgün metin -> Türkçe
    private suspend fun trMany(list: List<String>, chunk: Int): Map<String, String> {
        val sep = "\n###\n"
        val todo = list.filter { it.isNotBlank() && !trCache.containsKey(it) }.distinct()
        for (ch in todo.chunked(chunk)) {
            var done = false
            if (ch.size > 1) {
                val r = gtr(ch.joinToString(sep))
                if (r != null) {
                    val parts = r.split(Regex("\\s*###\\s*")).map { it.trim() }
                    if (parts.size == ch.size) {
                        ch.forEachIndexed { i, s -> trCache[s] = parts[i].ifBlank { s } }
                        done = true
                    }
                }
            }
            if (!done) {
                ch.amap { s -> gtr(s)?.let { trCache[s] = it } }
            }
        }
        val out = HashMap<String, String>()
        for (s in list) out[s] = trCache[s] ?: s
        return out
    }

    // Bölüm sayfası gerçekten var mı (oynatıcı seçeneği içeriyor mu)?
    private suspend fun epPageExists(url: String): Boolean {
        val r = fetch(url, "$mainUrl/", true) ?: return false
        return r.code == 200 && (r.text.contains("data-frame") || r.text.contains("srcframe") || r.text.contains("playercont"))
    }

    // Verilen bölüm numaralarından sitede gerçekten açılanları döndürür (6'şarlı gruplar halinde)
    private suspend fun probeEps(base: String, sn: Int, nums: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        for (ch in nums.chunked(6)) {
            val r = ch.amap { n ->
                if (epPageExists("$mainUrl/$base-$sn-sezon-$n-bolum-izle")) n else null
            }
            r.filterNotNull().forEach { out.add(it) }
        }
        return out
    }

    // start'tan başlayıp 5'erli gruplarla ilerler; bir grupta hiç bölüm bulunmazsa durur
    private suspend fun extendFrom(base: String, sn: Int, start: Int, limit: Int): List<Int> {
        val out = ArrayList<Int>()
        var n = start
        while (n < start + limit) {
            val hit = probeEps(base, sn, (n until n + 5).toList())
            if (hit.isEmpty()) break
            out.addAll(hit)
            n += 5
        }
        return out
    }

    private suspend fun addProbed(
        eps: ArrayList<Episode>,
        seen: HashSet<String>,
        have: HashMap<Int, MutableSet<Int>>,
        base: String,
        sn: Int,
        nums: List<Int>
    ) {
        for (n in nums) {
            val set = have.getOrPut(sn) { HashSet() }
            if (!set.add(n)) continue
            val u = "$mainUrl/$base-$sn-sezon-$n-bolum-izle"
            if (!seen.add(u)) continue
            eps.add(newEpisode(u) {
                this.season = sn
                this.episode = n
            })
        }
    }

    // ------------------------------------------------------------------
    // Detay sayfası
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        var path = pathOf(url)
        if (kindOf(path) == 3) path = epToSeries(path)
        val kind = kindOf(path)
        if (kind != 1) return null
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

        val today = Date()
        val df = SimpleDateFormat("d MMMM yyyy", Locale("tr", "TR"))
        val slugNorm = slug.removeSuffix("-izle")

        // 1) Dizi sayfasındaki (çoğu zaman eksik) bölüm listesi
        val pageMap = collectEps(doc, slugNorm, df)

        // 2) Sitenin kendi TAM listesi: herhangi bir bölüm sayfası dizinin bütün sezon / bölümlerini listeler
        var full: LinkedHashMap<String, SEp>? = null
        val seedUrl = pageMap.values.minWithOrNull(compareBy<SEp>({ it.season }, { it.episode }))?.url
            ?: "$mainUrl/$slugNorm-1-sezon-1-bolum-izle"
        val sr = fetch(seedUrl, pageUrl, true)
        if (sr != null && sr.code == 200) {
            val fm = collectEps(Jsoup.parse(sr.text, mainUrl), slugNorm, df)
            if (fm.isNotEmpty()) full = fm
        }
        val siteComplete = full != null

        // Birleştir: tam liste temel, dizi sayfasındaki bilgiler eksikleri tamamlar
        val merged = LinkedHashMap<String, SEp>()
        if (full != null) merged.putAll(full)
        for ((k, v) in pageMap) {
            val o = merged[k]
            if (o == null) {
                merged[k] = v
            } else if (o.name.isBlank() && v.name.isNotBlank()) {
                merged[k] = SEp(o.season, o.episode, o.url, o.slug, v.name, o.date ?: v.date)
            }
        }
        var epSlug: String? = merged.values.firstOrNull()?.slug

        val seen = HashSet<String>()
        val eps = ArrayList<Episode>()
        for (v in merged.values.sortedWith(compareBy<SEp>({ it.season }, { it.episode }))) {
            val dt = v.date
            // Gelecek tarihli bölümler listeye eklenmez
            if (dt != null && dt > today.time) continue
            if (!seen.add(v.url)) continue
            val nm = if (isGenericName(v.name)) "" else v.name
            eps.add(newEpisode(v.url) {
                this.season = v.season
                this.episode = v.episode
                if (nm.isNotBlank()) this.name = nm
                if (dt != null) this.date = dt
            })
        }

        // TMDB: Türkçe bölüm adı, açıklama, bölüm görseli, süre
        val tm = try {
            tmdbShow(title, year, eps.mapNotNull { it.season }.distinct())
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }

        // Sitenin tam listesi alınamadıysa (yedek yöntem): TMDB + sitede yoklama ile eksikleri tamamla
        if (!siteComplete) {
            if (tm != null) {
                val baseSlug = epSlug ?: slugNorm
                val haveKeys = eps.map { "${it.season}_${it.episode}" }.toHashSet()
                val df3 = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val tmSeasons = tm.eps.keys.mapNotNull { it.substringBefore("_").toIntOrNull() }
                    .distinct().filter { it > 0 }.sorted()
                val filled = tmSeasons.amap { sn ->
                    val nums = tm.eps.keys.filter { it.startsWith("${sn}_") }
                        .mapNotNull { it.substringAfter("_").toIntOrNull() }.sorted()
                    val missing = nums.filter { n ->
                        if ("${sn}_$n" in haveKeys) return@filter false
                        val airMillis = try {
                            tm.eps["${sn}_$n"]?.air?.let { df3.parse(it)?.time }
                        } catch (ex: Exception) {
                            null
                        }
                        airMillis == null || airMillis <= today.time
                    }
                    if (missing.isEmpty()) return@amap null
                    var ok = false
                    for (probe in listOf(missing.first(), missing.last()).distinct()) {
                        if (epPageExists("$mainUrl/$baseSlug-$sn-sezon-$probe-bolum-izle")) {
                            ok = true
                            break
                        }
                    }
                    if (ok) sn to missing else null
                }.filterNotNull()
                for ((sn, nums) in filled) {
                    for (n in nums) {
                        val epUrl = "$mainUrl/$baseSlug-$sn-sezon-$n-bolum-izle"
                        if (!seen.add(epUrl)) continue
                        eps.add(newEpisode(epUrl) {
                            this.season = sn
                            this.episode = n
                        })
                    }
                }
            }

            // Sitenin kendisinden yoklama: aradaki eksikler, son sezonun devamı, listede olmayan sezonlar
            val baseSlug2 = epSlug ?: slugNorm
            val bySeason = HashMap<Int, MutableSet<Int>>()
            for (e in eps) {
                val s = e.season ?: continue
                val n = e.episode ?: continue
                bySeason.getOrPut(s) { HashSet() }.add(n)
            }
            if (bySeason.isNotEmpty()) {
                for ((sn, set) in bySeason.toList()) {
                    val mx = set.maxOrNull() ?: continue
                    val gaps = (1..mx).filter { it !in set }
                    if (gaps.isEmpty()) continue
                    addProbed(eps, seen, bySeason, baseSlug2, sn, probeEps(baseSlug2, sn, gaps.take(60)))
                }
                val maxS = bySeason.keys.maxOrNull() ?: 0
                if (maxS > 0) {
                    val mxLast = bySeason[maxS]?.maxOrNull() ?: 0
                    addProbed(eps, seen, bySeason, baseSlug2, maxS, extendFrom(baseSlug2, maxS, mxLast + 1, 30))
                    for (sn in 1..(maxS + 1)) {
                        if (bySeason.containsKey(sn)) continue
                        addProbed(eps, seen, bySeason, baseSlug2, sn, extendFrom(baseSlug2, sn, 1, 80))
                    }
                }
            }
        }

        eps.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        // TMDB eşleşmesi tutarlı mı? (site bölümlerinin en az %60'ı TMDB'de varsa). Tutarsızsa TMDB hiç kullanılmaz.
        val tmUse: TShow? = if (tm != null && (eps.isEmpty() ||
                eps.count { tm.eps.containsKey("${it.season}_${it.episode}") }.toDouble() / eps.size >= 0.6)
        ) tm else null

        // Bölüm adı / açıklaması: TMDB'de Türkçe olanlar aynen, olmayanlar Türkçeye çevrilir
        val trNames = ArrayList<Pair<Episode, String>>()
        val trDescs = ArrayList<Pair<Episode, String>>()
        val df2 = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (ep in eps) {
            val t = tmUse?.eps?.get("${ep.season}_${ep.episode}")
            if (t != null) {
                if (!isGenericName(t.name)) {
                    if (t.nameTr) ep.name = t.name else trNames.add(ep to t.name)
                } else {
                    val cur = ep.name
                    if (!cur.isNullOrBlank()) trNames.add(ep to cur)
                }
                if (t.overview.isNotBlank()) {
                    if (t.ovTr) ep.description = t.overview else trDescs.add(ep to t.overview)
                }
                if (t.still != null) ep.posterUrl = t.still
                if (t.runtime != null) ep.runTime = t.runtime
                if (t.air != null) {
                    try {
                        df2.parse(t.air)?.time?.let { ep.date = it }
                    } catch (ex: Exception) {
                    }
                }
            } else {
                // TMDB'de karşılığı yok: sitenin kendi (çoğunlukla İngilizce) adını Türkçeleştir
                val cur = ep.name
                if (!cur.isNullOrBlank()) trNames.add(ep to cur)
            }
        }
        if (trNames.isNotEmpty()) {
            val m = trMany(trNames.map { it.second }.take(300), 25)
            for ((ep, src) in trNames) ep.name = m[src] ?: src
        }
        if (trDescs.isNotEmpty()) {
            val m = trMany(trDescs.map { it.second }.take(120), 6)
            for ((ep, src) in trDescs) ep.description = m[src] ?: src
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, eps) {
            this.posterUrl = tmUse?.poster ?: poster
            this.backgroundPosterUrl = tmUse?.backdrop ?: cover
            this.plot = plot ?: tmUse?.overview
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

        // Altyazı klasörü için yıl/ay ipuçları (bölüm görselinin yüklendiği ay + son 48 ay)
        val hints = ArrayList<Pair<String, String>>()
        val rx = Regex("/wp-content/uploads/(\\d{4})/(\\d{2})/episodes/")
        rx.findAll(p.text).forEach { m ->
            val pr = m.groupValues[1] to m.groupValues[2]
            if (!hints.contains(pr)) hints.add(pr)
        }
        val cal = Calendar.getInstance()
        for (i in 0 until 48) {
            val pr = cal.get(Calendar.YEAR).toString() to String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1)
            if (!hints.contains(pr)) hints.add(pr)
            cal.add(Calendar.MONTH, -1)
        }

        var any = false
        for (entry in embeds.entries.take(5)) {
            val embedUrl = entry.key
            val label = entry.value
            if (resolveEmbed(embedUrl, label, hints, subtitleCallback, callback)) {
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
                if (resolveEmbed(entry.key, entry.value, hints, subtitleCallback, callback, true)) {
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

        // Oynatıcı sayfasında geçen yükleme klasörü (yıl/ay) en güvenilir altyazı ipucudur: başa koy
        val hints2 = ArrayList<Pair<String, String>>()
        texts.forEach { tx ->
            Regex("/upload/videos/(\\d{4})/(\\d{2})/").findAll(fixText(tx)).forEach { m ->
                val pr = m.groupValues[1] to m.groupValues[2]
                if (!hints2.contains(pr)) hints2.add(pr)
            }
        }
        for (h in hints) if (!hints2.contains(h)) hints2.add(h)

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
            if (captureAndServe(embedUrl, label, origin, hints2, cookies, found, subtitleCallback, callback)) return true
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

        emitSubs(found, embedUrl, origin, hints2, cookies, subtitleCallback)

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

    private val subReq = Regex("(?i)\\.(vtt|srt)(\\?|$)")

    // jwplayer'ın oynatma listesindeki altyazı parçalarını JSON olarak verir
    private val trackScript = """
        (function(){
          try{
            var p=jwplayer();
            var pl=p.getPlaylist();
            var out=[];
            if(pl&&pl.length){
              var tr=pl[0].tracks||[];
              for(var i=0;i<tr.length;i++){out.push({f:tr[i].file,l:tr[i].label,k:tr[i].kind});}
            }
            return JSON.stringify(out);
          }catch(e){return '[]';}
        })()
    """.trimIndent()

    // evaluateJavascript(trackScript) sonucunu okuyup altyazı adreslerini toplar
    private fun parseTracks(res: String?, origin: String, out: ConcurrentHashMap<String, String>) {
        try {
            if (res.isNullOrBlank() || res == "null") return
            val txt = (org.json.JSONTokener(res).nextValue() as? String) ?: return
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = o.optString("f").trim()
                val k = o.optString("k").trim()
                if (f.isBlank()) continue
                if (k.isNotBlank() && !k.equals("captions", true) && !k.equals("subtitles", true)) continue
                if (k.isBlank() && !subReq.containsMatchIn(f)) continue
                val u = when {
                    f.startsWith("http", true) -> f
                    f.startsWith("//") -> "https:$f"
                    f.startsWith("/") -> origin + f
                    else -> continue
                }
                out.putIfAbsent(u, o.optString("l"))
            }
        } catch (e: Throwable) {
        }
    }

    // Oynatıcı sayfasını kendi WebView'ımızda açar. m3u / list isteklerini OkHttp ile BİZ çeker
    // (tek kullanımlık jeton bizde harcanır), aynı yanıtı WebView'a da verir ve içeriği saklar.
    // Oynatıcının kendiliğinden çekmediği alt listeleri (ör. ikinci ses / ikinci video) sayfanın
    // içinden fetch() ile tarayıcı gibi çektirir; böylece her ses / video parçası kullanılabilir olur.
    // Altyazı adresleri (jwplayer parça listesi + .vtt / .srt istekleri) subOut'a toplanır.
    private fun ownWebCapture(
        embedUrl: String,
        timeoutMs: Long,
        subOut: ConcurrentHashMap<String, String>
    ): LinkedHashMap<String, CapBody> {
        val result = LinkedHashMap<String, CapBody>()
        val ctx = appCtx() ?: run {
            dbg = "context yok ($dbg)"
            return result
        }
        val origin = originOf(embedUrl)
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
                        // Altyazı dosyası isteği: adresi not al, isteği olduğu gibi bırak
                        if (subReq.containsMatchIn(u)) {
                            if (!u.contains("thumb", true) && !u.contains("sprite", true) && !u.contains("chapter", true)) {
                                subOut.putIfAbsent(u, "")
                            }
                            return null
                        }
                        if (request.method != "GET" || !capRe.containsMatchIn(u)) return null
                        if (u.contains("googletagmanager", true) || u.contains("google-analytics", true) ||
                            u.contains("cloudfront.net", true) || u.contains("cdn.jsdelivr", true)
                        ) return null
                        // Daha önce başarıyla alınmış adres tek kullanımlık olabilir: tekrar istemeden saklanan içeriği ver
                        val cached = caps[u]
                        if (cached != null && cached.code in 200..299) {
                            val hc = HashMap<String, String>()
                            hc["Access-Control-Allow-Origin"] = "*"
                            return WebResourceResponse(
                                cached.mime, "utf-8", cached.code, "OK", hc, ByteArrayInputStream(cached.bytes)
                            )
                        }
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

        // Alınan listelerin içinde adı geçen ama henüz hiç istenmemiş alt listeler (ses / video)
        fun pending(): List<String> {
            val out = ArrayList<String>()
            val snap = synchronized(order) { order.toList() }
            for (u in snap) {
                val b = caps[u] ?: continue
                if (b.code !in 200..299 || !looksM3u(b.bytes)) continue
                for (r in HlsProxy.refsOf(String(b.bytes, Charsets.UTF_8), u)) {
                    if (!caps.containsKey(r) && r !in out) out.add(r)
                }
            }
            return out
        }

        val end = System.currentTimeMillis() + timeoutMs
        var firstHit = 0L
        var jsAt = 0L
        var lastTrk = 0L
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()

            // altyazı parçalarını oynatıcıdan (jwplayer) düzenli olarak oku
            if (now - lastTrk > 1000) {
                lastTrk = now
                main.post {
                    try {
                        holder[0]?.evaluateJavascript(trackScript) { res -> parseTracks(res, origin, subOut) }
                    } catch (e: Throwable) {
                    }
                }
            }

            val has = synchronized(order) { order.any { u -> caps[u]?.let { it.code in 200..299 && looksM3u(it.bytes) } == true } }
            if (!has) continue
            if (firstHit == 0L) firstHit = now
            if (jsAt == 0L) {
                // oynatıcının kendi isteklerinin bitmesi için kısa bekle, sonra eksik alt listeleri sayfadan çektir
                if (now - firstHit > 2500) {
                    val missing = pending()
                    if (missing.isEmpty()) break
                    jsAt = now
                    val arr = JSONArray()
                    missing.forEach { arr.put(it) }
                    val js = "(function(us){us.forEach(function(u){try{fetch(u).then(function(r){return r.text();}).catch(function(){});}catch(e){}});})(" +
                        arr.toString() + ")"
                    d("js ile ${missing.size} alt liste cekiliyor")
                    main.post {
                        try {
                            holder[0]?.evaluateJavascript(js, null)
                        } catch (e: Throwable) {
                        }
                    }
                }
            } else {
                if (pending().isEmpty() && now - jsAt > 1200) break
                if (now - jsAt > 7000) break
            }
        }
        // son bir kez altyazı parçalarını oku
        main.post {
            try {
                holder[0]?.evaluateJavascript(trackScript) { res -> parseTracks(res, origin, subOut) }
            } catch (e: Throwable) {
            }
        }
        try {
            Thread.sleep(400)
        } catch (e: InterruptedException) {
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
        found0: Found,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val subOut = ConcurrentHashMap<String, String>()
        val caps = try {
            ownWebCapture(embedUrl, 20_000L, subOut)
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

        // Altyazı adreslerini topla: oynatıcı parça listesi, .vtt/.srt istekleri ve oynatma listelerinin içi
        for ((u, lb) in subOut) if (!found0.subs.containsKey(u)) found0.subs[u] = lb
        try {
            val tmp = Found()
            for ((_, b) in lists) scan(String(b.bytes, Charsets.UTF_8), origin, tmp)
            for ((u, lb) in tmp.subs) if (!found0.subs.containsKey(u)) found0.subs[u] = lb
        } catch (e: Exception) {
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
        // hâlâ alınamamış alt listeleri (kalite / ses listeleri) hemen çekip sakla: jeton süresi dolmadan
        for (u in tops) HlsProxy.prefetchRefs(String(lists[u]!!.bytes, Charsets.UTF_8), u, hd)

        emitSubs(found0, embedUrl, origin, hints, cookies, subtitleCallback)

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

    private fun isVtt(s: String): Boolean =
        s.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("WEBVTT")

    // Bulunan altyazıları verir. Türkçe yoksa /upload/videos/<yıl>/<ay>/<id>/altyazi/tur_sub.vtt klasörünü
    // olası yıl/ay'larda (12'şerli gruplar halinde, ilk bulunana kadar) yoklar.
    private suspend fun emitSubs(
        found: Found,
        embedUrl: String,
        origin: String,
        hints: List<Pair<String, String>>,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val done = HashSet<String>()
        var hasTr = false
        for ((u, lb) in found.subs) {
            if (!done.add(u)) continue
            val nm = subName(lb, u)
            if (nm == "Türkçe") hasTr = true
            subtitleCallback(SubtitleFile(nm, u))
        }
        if (hasTr) return
        val id = Regex("/embed/([A-Za-z0-9_-]+)").find(embedUrl)?.groupValues?.get(1) ?: return
        val cands = ArrayList<String>()
        for (h in hints) cands.add("$origin/upload/videos/${h.first}/${h.second}/$id/altyazi/tur_sub.vtt")
        var folder: String? = null
        for (ch in cands.distinct().chunked(12)) {
            val ok = ch.amap { c ->
                val r = fetch(c, embedUrl, false, cookies = cookies)
                if (r != null && r.code == 200 && isVtt(r.text)) c else null
            }.filterNotNull()
            if (ok.isNotEmpty()) {
                ok.forEach { if (done.add(it)) subtitleCallback(SubtitleFile("Türkçe", it)) }
                folder = ok[0].substringBeforeLast("/")
                break
            }
        }
        // Aynı klasörde İngilizce altyazı da olabilir
        if (folder != null) {
            val en = "$folder/eng_sub.vtt"
            val r = fetch(en, embedUrl, false, cookies = cookies)
            if (r != null && r.code == 200 && isVtt(r.text) && done.add(en)) {
                subtitleCallback(SubtitleFile("İngilizce", en))
            }
        }
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

    private fun log(m: String) {
        try {
            android.util.Log.d("ZdiziDbg", m.replace("\n", " ").take(500))
        } catch (e: Throwable) {
        }
    }

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
            if (r.code in 200..299) {
                r.body?.bytes()
            } else {
                log("proxy fetch ${r.code} ${url.takeLast(30)}")
                null
            }
        }
    } catch (e: Exception) {
        log("proxy fetch hata ${e.javaClass.simpleName} ${url.takeLast(30)}")
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
                log("proxy $code ${e?.url?.takeLast(30) ?: id}")
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
