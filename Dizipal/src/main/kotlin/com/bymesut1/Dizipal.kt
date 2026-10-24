package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizipal : MainAPI() {
    // dizipal2142.com artık SSL el sıkışmasında kapanıyor; çalışan adres 2143
    override var mainUrl = "https://dizipal2143.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Site adresi değişirse sıradakine geçilir
    private val bases = listOf(
        "https://dizipal2143.com",
        "https://dizipal2144.com",
        "https://dizipal2145.com",
        "https://dizipal2142.com"
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    // TMDB (Türkçe bölüm adı, açıklama, resim, süre, tarih). Boş/placeholder ise kapalı.
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"

    // Link bulunamazsa kaynak listesine "DEBUG ..." satırı ekler (sorun bitince false yap)
    private val debug = true

    // Siteden gelen doğrulama çerezleri (Erişim doğrulanıyor ekranı için)
    private val sessionCookies = ConcurrentHashMap<String, String>()

    @Volatile
    private var dbg = ""

    @Volatile
    private var chDbg = ""

    // ---- Kalıcı oturum (doğrulama çerezi) ----
    // Çerezler diske yazılır; açılışta geri yüklenir; cfMaxAgeMs'den eskiyse kullanılmaz.
    private val cfMaxAgeMs = 6L * 60 * 60 * 1000
    private val sessionFile = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "dizipal_session.txt")

    @Volatile
    private var sessRestored = false

    @Volatile
    private var sessFromDisk = false

    // İlk çözüm sırasında onlarca istek aynı anda doğrulama yapmasın (kotlinx yok, Semaphore kullanılır)
    private val solveLock = Semaphore(1)

    // Cloudflare 403/503 durumunda yedek çözücü
    @Volatile
    private var cfKiller: CloudflareKiller? = null

    private fun killer(): CloudflareKiller {
        var k = cfKiller
        if (k == null) {
            k = CloudflareKiller()
            cfKiller = k
        }
        return k
    }

    private fun sessRestore() {
        if (sessRestored) return
        sessRestored = true
        try {
            if (!sessionFile.exists()) return
            val lines = sessionFile.readLines()
            val ts = lines.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - ts > cfMaxAgeMs) {
                sessionFile.delete()
                println("Dizipal: oturum kaydı eski, silindi")
                return
            }
            var n = 0
            for (ln in lines.drop(1)) {
                val i = ln.indexOf('\t')
                if (i <= 0) continue
                sessionCookies[ln.substring(0, i)] = ln.substring(i + 1)
                n++
            }
            if (n > 0) {
                sessFromDisk = true
                println("Dizipal: oturum diskten yüklendi ($n çerez)")
            }
        } catch (e: Exception) {
        }
    }

    private fun sessSave() {
        try {
            val sb = StringBuilder()
            sb.append(System.currentTimeMillis()).append('\n')
            sessionCookies.forEach { (k, v) -> sb.append(k).append('\t').append(v).append('\n') }
            sessionFile.writeText(sb.toString())
            println("Dizipal: oturum diske kaydedildi")
        } catch (e: Exception) {
        }
    }

    private fun sessClear() {
        sessionCookies.clear()
        sessFromDisk = false
        try {
            sessionFile.delete()
        } catch (e: Exception) {
        }
        println("Dizipal: oturum sıfırlandı")
    }

    override val mainPage = mainPageOf(
        "home:Trend Diziler" to "Trend Diziler",
        "home:Son Eklenen Diziler" to "Son Eklenen Diziler",
        "home:Son Bölümler" to "Son Bölümler",
        "home:Trend Filmler" to "Trend Filmler",
        "home:Son Eklenen Filmler" to "Son Eklenen Filmler",
        "/diziler" to "Diziler",
        "/filmler" to "Filmler",
        "/anime" to "Anime"
    )

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------
    private class Fetched(val base: String, val text: String, val cookies: Map<String, String>)

    private class Found(val origin: String) {
        val hls = LinkedHashSet<String>()
        val mp4 = LinkedHashSet<String>()
        val subs = LinkedHashMap<String, String>() // adres -> etiket
        val cookies = LinkedHashMap<String, String>() // sadece fireplayer_* oturum çerezi
    }

    private class Cfg(val t: String, val v: String, val raw: String)

    private class Var(val url: String, val bw: Int, val h: Int)

    private class Tm(
        val id: Int,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val runtime: Int?
    )

    private fun hdr(ref: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = "tr-TR,tr;q=0.9,en;q=0.8"
        if (ref != null) h["Referer"] = ref
        h.putAll(extra)
        return h
    }

    private fun pathOf(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http", true)) {
            try {
                val x = URI(u)
                (x.rawPath ?: "/") + (if (x.rawQuery != null) "?" + x.rawQuery else "")
            } catch (e: Exception) {
                u
            }
        } else if (u.startsWith("/")) u else "/$u"
    }

    private fun fixAbs(h: String): String =
        if (h.startsWith("http", true)) h
        else mainUrl.trimEnd('/') + (if (h.startsWith("/")) h else "/$h")

    private fun originOf(u: String): String =
        Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1) ?: ""

    private fun hostOf(u: String): String = originOf(u).substringAfter("://").lowercase()

    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\s*[-|–]\\s*Dizipal.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+izle$", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun fixText(s: String): String =
        s.replace("\\u002F", "/", true)
            .replace("\\u0026", "&", true)
            .replace("\\/", "/")
            .replace("&amp;", "&")

    private fun resolveUrl(base: String, ref: String): String =
        try {
            if (ref.startsWith("http", true)) ref else URI(base).resolve(ref).toString()
        } catch (e: Exception) {
            ref
        }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String =
        try {
            app.get(url, headers = headers).text
        } catch (e: Exception) {
            ""
        }

    private suspend fun fetchTextCk(
        url: String,
        headers: Map<String, String>,
        cookies: Map<String, String> = emptyMap()
    ): Pair<String, Map<String, String>> =
        try {
            val r = app.get(url, headers = headers, cookies = cookies)
            Pair(r.text, r.cookies)
        } catch (e: Exception) {
            Pair("", emptyMap())
        }

    private fun titleOf(t: String): String =
        Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.trim() ?: ""

    // "Erişim doğrulanıyor" / "Doğrulama" ara sayfası mı?
    private fun isChallenge(t: String): Boolean {
        // Sayfa başlığı "Doğrulama · DiziPAL" ise kesin doğrulama sayfasıdır
        val title = titleOf(t)
        if (title.contains("Doğrulama", true) || title.contains("Dogrulama", true)) return true
        val phrase = t.contains("Erişim doğrulanıyor", true) ||
            t.contains("Erisim dogrulaniyor", true)
        if (!phrase) return false
        // Gerçek sayfalarda da gizli bir "Erişim doğrulanıyor" katmanı olabilir: içerik varsa sorun yok
        val real = t.contains("content-card") || t.contains("data-cfg") ||
            t.contains("trending-item") || t.contains("episode-list-item")
        return !real
    }

    private fun logBig(tag: String, t: String) {
        try {
            var i = 0
            var n = 0
            while (i < t.length && n < 40) {
                println("$tag[$n] " + t.substring(i, minOf(t.length, i + 900)).replace("\n", " "))
                i += 900
                n++
            }
        } catch (e: Exception) {
        }
    }

    private fun metaOf(doc: org.jsoup.nodes.Document, n: String): String =
        doc.selectFirst("meta[name=$n]")?.attr("content")?.trim() ?: ""

    private fun sha256Hex(str: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val h = md.digest(str.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (x in h) {
            val v = x.toInt() and 255
            sb.append(hex[v shr 4]).append(hex[v and 15])
        }
        return sb.toString()
    }

    // Sitenin PoW'u: sha256(nonce + ":" + tahmin) onaltılık çıktısı "0" * zeros ile başlamalı
    private fun solvePow(nonce: String, zeros: Int): String? {
        val pre = "0".repeat(zeros)
        var i = 0
        while (i <= 8000000) {
            if (sha256Hex("$nonce:$i").startsWith(pre)) return i.toString()
            i++
        }
        return null
    }

    // Doğrulama ekranı (/_bcs script'inin yaptığı iş). Alan adları, uç nokta ve ayraç mümkünse
    // çözülmüş script'ten okunur (site değiştirirse otomatik uyum), yoksa bilinen değerler kullanılır.
    private suspend fun passChallenge(base: String, text: String, pageUrl: String) {
        val doc = Jsoup.parse(text, base)

        // /_bcs script'ini oturum çerezleriyle iste ve gövdesini al
        var scriptText = ""
        try {
            val srcs = doc.select("script[src]").map { it.attr("abs:src") }
                .filter { it.startsWith("http") && hostOf(it) == hostOf(base) }
                .take(2)
            for (u in srcs) {
                val rs = try {
                    app.get(
                        u,
                        headers = hdr(
                            pageUrl,
                            mapOf(
                                "Accept" to "*/*",
                                "Sec-Fetch-Dest" to "script",
                                "Sec-Fetch-Mode" to "no-cors",
                                "Sec-Fetch-Site" to "same-origin"
                            )
                        ),
                        cookies = sessionCookies.toMap()
                    )
                } catch (e: Exception) {
                    null
                }
                if (rs != null) {
                    sessionCookies.putAll(rs.cookies)
                    scriptText += "\n" + rs.text
                }
            }
        } catch (e: Exception) {
        }

        // Gizli gövdeyi çöz: var k="..",p=".."
        var dec = ""
        try {
            val m = Regex("""var k=["]([0-9A-Za-z]{8,64})["],p=["]([A-Za-z0-9+/=_-]{40,})["]""").find(scriptText)
            if (m != null) {
                val key = m.groupValues[1]
                val bytes = b64bin(m.groupValues[2])
                val sb = StringBuilder()
                for (i in bytes.indices) sb.append(((bytes[i].toInt() and 255) xor key[i % key.length].code).toChar())
                dec = sb.toString()
            }
        } catch (e: Exception) {
        }

        // Meta adları: var B=meta("_bc"),N=meta("_bn"),Z=parseInt(meta("_bk")..),R=meta("_br")
        var nBc = "_bc"
        var nBn = "_bn"
        var nBk = "_bk"
        var nBr = "_br"
        Regex("""B=meta\("([^"]+)"\),N=meta\("([^"]+)"\),Z=parseInt\(meta\("([^"]+)"\)""").find(dec)?.let {
            nBc = it.groupValues[1]; nBn = it.groupValues[2]; nBk = it.groupValues[3]
        }
        Regex("""R=meta\("([^"]+)"\)""").find(dec)?.let { nBr = it.groupValues[1] }
        val endpoint = Regex("""fetch\(["'](/[^"']+)["']""").find(dec)?.groupValues?.get(1) ?: "/_bc_verify"
        val sep = Regex("""sha256Hex\(nonce\+["'](.)["']\+guess\)""").find(dec)?.groupValues?.get(1) ?: ":"

        val bc = doc.selectFirst("meta[name=$nBc]")?.attr("content") ?: ""
        val bn = doc.selectFirst("meta[name=$nBn]")?.attr("content") ?: ""
        val zeros = doc.selectFirst("meta[name=$nBk]")?.attr("content")?.toIntOrNull() ?: 4
        val ret = doc.selectFirst("meta[name=$nBr]")?.attr("content").let { if (it.isNullOrBlank()) "/" else it }
        if (bc.isBlank() || bn.isBlank()) {
            chDbg = "meta eksik ($nBc/$nBn)"
            return
        }

        // Sayfa 350-600 ms bekleyip çözmeye başlıyor
        val t0 = System.currentTimeMillis()
        try {
            Thread.sleep(500)
        } catch (e: Exception) {
        }
        val pre = "0".repeat(zeros)
        var ans: String? = null
        var i = 0
        while (i <= 8000000) {
            if (sha256Hex("$bn$sep$i").startsWith(pre)) {
                ans = i.toString(); break
            }
            i++
        }
        if (ans == null) {
            chDbg = "pow bulunamadi"
            return
        }
        val elapsed = (System.currentTimeMillis() - t0).toInt() + 150

        val o = JSONObject()
        o.put("b", bc)
        o.put("a", ans)
        o.put("t", elapsed)
        o.put("m", 9)
        o.put("tc", 6)
        o.put("sc", 1)
        o.put("c", "7f3a9c2e5b1d4068")
        o.put("w", "Qualcomm|Adreno (TM) 619|WebGL 1.0 (OpenGL ES 2.0 Chromium)")
        o.put("au", "124.04347")
        o.put("pl", 0)
        o.put("hw", 8)
        o.put("dp", 2.75)
        o.put("sw", "393x873")
        o.put("tz", -180)
        o.put("tn", "Europe/Istanbul")
        o.put("lg", "tr-TR")
        o.put("wd", 0)
        o.put("ck", 1)
        o.put("pm", "prompt")
        o.put("vs", "visible")
        o.put("r", ret)

        try {
            val body = o.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val r = app.post(
                base + endpoint,
                headers = hdr(
                    pageUrl,
                    mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to base,
                        "Accept" to "*/*"
                    )
                ),
                requestBody = body,
                cookies = sessionCookies.toMap()
            )
            sessionCookies.putAll(r.cookies)
            chDbg = "verify code=${r.code} ck=${r.cookies.keys} body=${r.text.take(160)}"
            println("Dizipal: $chDbg")
        } catch (e: Exception) {
            chDbg = "verify hata ${e.message}"
            println("Dizipal: $chDbg")
        }
    }

    // tries: doğrulama ekranı çıkarsa kaç kez çözülsün (0 = hiç)
    private suspend fun fetchSite(path: String, tries: Int = 1): Fetched? {
        sessRestore()
        val order = listOf(mainUrl) + bases.filter { it != mainUrl }
        for (b in order) {
            try {
                var r = app.get(b + path, headers = hdr("$b/"), cookies = sessionCookies.toMap())
                sessionCookies.putAll(r.cookies)

                // Cloudflare 403/503: yedek olarak CloudflareKiller (WebView ile çözer)
                if (r.code == 403 || r.code == 503) {
                    println("Dizipal: CF ${r.code}, CloudflareKiller deneniyor")
                    cfKiller = null
                    try {
                        r = app.get(b + path, headers = hdr("$b/"), cookies = sessionCookies.toMap(), interceptor = killer())
                        sessionCookies.putAll(r.cookies)
                    } catch (e: Exception) {
                    }
                }

                // Diskten gelen eski çerezle doğrulama ekranı çıktıysa: kaydı sil, temiz oturumla yeniden iste
                if (isChallenge(r.text) && sessFromDisk) {
                    sessClear()
                    r = app.get(b + path, headers = hdr("$b/"))
                    sessionCookies.putAll(r.cookies)
                }

                var n = 0
                while (isChallenge(r.text) && n < tries) {
                    solveLock.acquire()
                    try {
                        // Başka bir istek bu arada çözmüş olabilir
                        r = app.get(b + path, headers = hdr("$b/"), cookies = sessionCookies.toMap())
                        sessionCookies.putAll(r.cookies)
                        if (isChallenge(r.text)) {
                            passChallenge(b, r.text, b + path)
                            r = app.get(b + path, headers = hdr("$b/"), cookies = sessionCookies.toMap())
                            sessionCookies.putAll(r.cookies)
                            if (!isChallenge(r.text)) {
                                sessSave()
                                sessFromDisk = true
                            }
                        }
                    } finally {
                        solveLock.release()
                    }
                    n++
                }
                val ch = isChallenge(r.text)
                dbg = "${hostOf(b)} code=${r.code} ch=$ch ck=${sessionCookies.keys.joinToString(",")}" +
                    (if (ch) " $chDbg" else "")
                if (r.code == 200 && r.text.isNotBlank() && !ch) {
                    mainUrl = b
                    return Fetched(b, r.text, sessionCookies.toMap())
                }
                // Site ayakta ama doğrulama ekranı verdi: diğer adrese geçmek süreyi boşa harcar
                if (r.code == 200) return null
            } catch (e: Exception) {
                dbg = "${hostOf(b)} hata: ${e.message}"
            }
        }
        return null
    }

    private fun Element.poster(): String? {
        val img = selectFirst("img") ?: return null
        val s = img.attr("data-src").ifBlank { img.attr("src") }
        return if (s.startsWith("http")) s else null
    }

    private fun Element.toSearch(): SearchResponse? {
        val a = (if (tagName() == "a") this else selectFirst("a[href]")) ?: return null
        val href = a.attr("href")
        if (href.isBlank() || href.startsWith("#")) return null
        var url = fixAbs(href)
        // "Son Bölümler" kartı bölüm sayfasına gider -> diziye çevir
        if (url.contains("/bolum/")) {
            url = url.replace("/bolum/", "/dizi/").replace(Regex("-\\d+-sezon-\\d+-bolum/?$"), "")
        }
        if (!url.contains("/film/") && !url.contains("/dizi/") && !url.contains("/anime/")) return null
        val title = cleanTitle(
            selectFirst(".card-title, .trending-title, .ep-title")?.text()
                ?: selectFirst("img")?.attr("alt")
                ?: return null
        )
        if (title.isBlank()) return null
        val poster = poster()
        return if (url.contains("/film/")) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    // ------------------------------------------------------------------
    // TMDB
    // ------------------------------------------------------------------
    private fun tmdbOn(): Boolean = tmdbKey.isNotBlank() && !tmdbKey.startsWith("BURAYA")

    private suspend fun tmdbGet(path: String): JSONObject? {
        if (!tmdbOn()) return null
        return try {
            val sep = if (path.contains("?")) "&" else "?"
            val t = app.get(
                "https://api.themoviedb.org/3$path${sep}api_key=$tmdbKey&language=tr-TR",
                headers = mapOf("User-Agent" to ua)
            ).text
            JSONObject(t)
        } catch (e: Exception) {
            null
        }
    }

    private fun jStr(o: JSONObject, k: String): String? {
        val s = o.optString(k, "")
        return if (s.isBlank() || s == "null") null else s
    }

    private suspend fun tmdbInfo(title: String, movie: Boolean): Tm? {
        if (!tmdbOn()) return null
        val kind = if (movie) "movie" else "tv"
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val res = tmdbGet("/search/$kind?query=$q&include_adult=false")?.optJSONArray("results") ?: return null
        if (res.length() == 0) return null
        var pick: JSONObject? = null
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            val n = o.optString(if (movie) "title" else "name", "")
            val on = o.optString(if (movie) "original_title" else "original_name", "")
            if (n.equals(title, true) || on.equals(title, true)) {
                pick = o
                break
            }
        }
        if (pick == null) pick = res.optJSONObject(0)
        val p = pick ?: return null
        val id = p.optInt("id", 0)
        if (id == 0) return null
        val d = tmdbGet("/$kind/$id") ?: p
        val date = jStr(d, if (movie) "release_date" else "first_air_date")
        val year = date?.take(4)?.toIntOrNull()
        val runtime = if (movie) {
            d.optInt("runtime", 0).takeIf { it > 0 }
        } else {
            d.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        }
        return Tm(
            id,
            jStr(d, "overview"),
            jStr(d, "poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            jStr(d, "backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            year,
            runtime
        )
    }

    // bölüm numarası -> TMDB bölüm bilgisi
    private suspend fun tmdbSeason(id: Int, season: Int): Map<Int, JSONObject> {
        val arr = tmdbGet("/tv/$id/season/$season")?.optJSONArray("episodes") ?: return emptyMap()
        val m = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            m[o.optInt("episode_number", -1)] = o
        }
        return m
    }

    // ------------------------------------------------------------------
    // Ana sayfa / arama
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("home:")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
            val f = fetchSite("/", 1) ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
            val doc = Jsoup.parse(f.text)
            val title = request.data.removePrefix("home:")
            val sec = doc.select("section").firstOrNull {
                it.selectFirst(".section-title")?.text()?.contains(title, true) == true
            }
            val items = sec?.select("a.trending-item, li.content-card, a.episode-list-item")
                ?.mapNotNull { it.toSearch() }
                ?.distinctBy { it.url }
                ?: emptyList()
            return newHomePageResponse(request.name, items, false)
        }
        val path = request.data + if (page > 1) "?page=$page" else ""
        val f = fetchSite(path) ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val items = Jsoup.parse(f.text).select(".content-card")
            .mapNotNull { it.toSearch() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val f = fetchSite("/arama?q=$q") ?: return emptyList()
        return Jsoup.parse(f.text).select(".content-card")
            .mapNotNull { it.toSearch() }
            .distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Detay sayfası
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        var path = pathOf(url)
        if (path.contains("/bolum/")) {
            path = path.replace("/bolum/", "/dizi/").replace(Regex("-\\d+-sezon-\\d+-bolum/?$"), "")
        }
        val isMovie = path.contains("/film/")
        val f = fetchSite(path, 1)

        if (f == null) {
            // Detay sayfası alınamadı (doğrulama / ağ). Film ise adresten basit bir sayfa kur,
            // böylece film açılır ve oynatma aşamasında yeniden denenir.
            if (isMovie) {
                val slug0 = path.substringAfterLast("/").substringBefore("?")
                val t0 = slug0.split("-").filter { it.isNotBlank() }
                    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                val full0 = mainUrl.trimEnd('/') + path
                return newMovieLoadResponse(t0, full0, TvType.Movie, full0) {
                    this.plot = "Site detay sayfası alınamadı (doğrulama ekranı). Oynatmayı deneyin."
                }
            }
            return null
        }

        val doc = Jsoup.parse(f.text, f.base)

        val h1 = doc.select("h1").map { it.text().trim() }
            .firstOrNull { it.isNotBlank() && !it.contains("doğrulan", true) }
        val title = cleanTitle(
            h1
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
        )
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        val sitePlot = (doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content"))
            ?.takeIf { it.isNotBlank() }
        val tags = doc.select("a[href*=/kategori/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val fullUrl = f.base + path

        val tm = tmdbInfo(title, isMovie)
        val poster = tm?.poster ?: sitePoster
        val plot = tm?.overview ?: sitePlot

        if (isMovie) {
            return newMovieLoadResponse(title, fullUrl, TvType.Movie, fullUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tm?.backdrop
                this.plot = plot
                this.tags = tags
                this.year = tm?.year
                this.duration = tm?.runtime
            }
        }

        val slug = path.substringAfterLast("/").substringBefore("?")
        val epRegex = Regex("-(\\d+)-sezon-(\\d+)-bolum")
        val all = doc.select("a[href*=/bolum/]")
        val own = all.filter { it.attr("href").contains("/bolum/$slug-") }
        val source = if (own.isNotEmpty()) own else all

        val seen = HashSet<String>()
        val eps = ArrayList<Episode>()
        for (a in source) {
            val href = a.attr("href")
            val m = epRegex.find(href) ?: continue
            val epUrl = fixAbs(href)
            if (!seen.add(epUrl)) continue
            val s = m.groupValues[1].toInt()
            val e = m.groupValues[2].toInt()
            eps.add(newEpisode(epUrl) {
                this.season = s
                this.episode = e
            })
        }

        // Sayfada bölüm bağlantısı yoksa: /bolum/<slug>-S-sezon-E-bolum adreslerini yoklayarak bul
        if (eps.isEmpty()) {
            for (s in 1..15) {
                var found = 0
                var start = 1
                while (start < 300) {
                    val batch = (start until start + 10).toList()
                    val hits = batch.amap { e ->
                        val p = "/bolum/$slug-$s-sezon-$e-bolum"
                        val ok = try {
                            val r = app.get(f.base + p, headers = hdr("${f.base}/"), cookies = sessionCookies.toMap())
                            r.code == 200 && r.text.contains("data-cfg")
                        } catch (ex: Exception) {
                            false
                        }
                        if (ok) e else null
                    }.filterNotNull()
                    hits.forEach { e ->
                        eps.add(newEpisode(f.base + "/bolum/$slug-$s-sezon-$e-bolum") {
                            this.season = s
                            this.episode = e
                        })
                    }
                    found += hits.size
                    if (hits.size < batch.size) break
                    start += 10
                }
                if (found == 0) break
            }
        }

        eps.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        // TMDB: bölüm adı, açıklama, resim, süre, tarih (gelecek tarihli bölümler gizlenir)
        if (tm != null) {
            val seasons = eps.mapNotNull { it.season }.distinct()
            val maps = seasons.amap { s -> s to tmdbSeason(tm.id, s) }.toMap()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val out = ArrayList<Episode>()
            for (ep in eps) {
                val j = maps[ep.season]?.get(ep.episode)
                if (j != null) {
                    val air = jStr(j, "air_date")
                    if (air != null && air > today) continue
                    jStr(j, "name")?.let { ep.name = it }
                    jStr(j, "overview")?.let { ep.description = it }
                    jStr(j, "still_path")?.let { ep.posterUrl = "https://image.tmdb.org/t/p/w300$it" }
                    val rt = j.optInt("runtime", 0)
                    if (rt > 0) ep.runTime = rt
                    if (air != null) {
                        try {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(air)?.time?.let { ep.date = it }
                        } catch (e: Exception) {
                        }
                    }
                }
                out.add(ep)
            }
            eps.clear()
            eps.addAll(out)
        }

        return newTvSeriesLoadResponse(title, fullUrl, TvType.TvSeries, eps) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tm?.backdrop
            this.plot = plot
            this.tags = tags
            this.year = tm?.year
        }
    }

    // ------------------------------------------------------------------
    // Linkler
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val path = pathOf(data)
        val f = fetchSite(path, 2)
        if (f == null) {
            if (debug) {
                callback(
                    newExtractorLink(name, "DEBUG sayfa alinamadi: $dbg".take(1000), "https://debug.invalid/x.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            return false
        }
        val pageUrl = f.base + path
        var any = false

        val cfg = playerConfig(f, pageUrl)
        val embeds = ArrayList<String>()
        val pageFound = Found(f.base)

        if (cfg != null) {
            scan(cfg.raw + " " + cfg.v, f.base, pageFound)
            val t = cfg.t.lowercase()
            val v = fixText(cfg.v).trim()
            if (t == "m3u8") {
                if (emitHls(v, f.base, f.base, pageFound.subs, subtitleCallback, callback)) any = true
            } else if (t == "mp4") {
                emitMp4(v, f.base, callback); any = true
            } else if (v.contains("<iframe", true)) {
                Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(v)
                    ?.groupValues?.get(1)?.let { embeds.add(resolveUrl(f.base, fixText(it)).substringBefore("#")) }
            } else if (v.startsWith("http", true) || v.startsWith("//")) {
                val u = if (v.startsWith("//")) "https:$v" else v
                if (u.contains(".m3u8", true)) {
                    if (emitHls(u, f.base, f.base, pageFound.subs, subtitleCallback, callback)) any = true
                } else embeds.add(u.substringBefore("#"))
            }
        }

        if (embeds.isEmpty() && !any) {
            Jsoup.parse(f.text).select("iframe[src]").forEach {
                val u = resolveUrl(f.base, fixText(it.attr("src")))
                if (u.startsWith("http") && !isAdHost(u, f.base)) embeds.add(u)
            }
        }

        for (embed in embeds.distinct().take(3)) {
            val found = resolveEmbed(embed, pageUrl, 0)
            found.subs.putAll(pageFound.subs)
            var got = false
            for (u in found.hls.take(3)) {
                if (emitHls(u, found.origin, f.base, found.subs, subtitleCallback, callback, found.cookies)) got = true
            }
            for (u in found.mp4.take(2)) {
                emitMp4(u, found.origin, callback, found.cookies); got = true
            }
            if (!got) {
                // son çare: CloudStream'in hazır çıkarıcıları
                try {
                    if (loadExtractor(embed, pageUrl, subtitleCallback, callback)) got = true
                } catch (e: Exception) {
                }
            }
            if (got) any = true
        }

        if (!any && debug) {
            val info = "cfg=${cfg?.t ?: "yok"} embed=${embeds.size} ${embeds.firstOrNull()?.take(60) ?: ""} | $dbg"
            callback(
                newExtractorLink(name, "DEBUG $info".take(1000), "https://debug.invalid/x.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return any
    }

    private fun isAdHost(u: String, siteBase: String): Boolean {
        val h = hostOf(u)
        if (h == hostOf(siteBase) || bases.any { hostOf(it) == h }) return true
        return Regex("google|youtube|facebook|twitter|blogger|googletagmanager|cloudflare|shrgo|t\\.ly|bit\\.ly|cutt\\.ly|sivo\\.|rebrand|gshort|girgara|rogagir")
            .containsMatchIn(h)
    }

    // ---------------- Oyuncu ayarı: /ajax-player-config (AES-CBC, anahtar = k1 XOR k2) ----------------
    private suspend fun playerConfig(f: Fetched, pageUrl: String): Cfg? {
        val doc = Jsoup.parse(f.text)
        val el = doc.selectFirst("#videoContainer[data-cfg]") ?: doc.selectFirst("[data-cfg]") ?: return null
        val cfg = el.attr("data-cfg")
        if (cfg.isBlank()) return null
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val url = f.base + "/ajax-player-config"

        // A: tarayıcıdaki gibi
        val hA = hdr(
            pageUrl,
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to f.base,
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
        )
        val ra = try {
            app.post(url, headers = hA, data = mapOf("cfg" to cfg), cookies = f.cookies).text
        } catch (e: Exception) {
            ""
        }
        parseCfg(ra)?.let { return it }

        // B: ajax-token + csrf
        val tr = try {
            app.get(
                f.base + "/ajax-token",
                headers = hdr(pageUrl, mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json")),
                cookies = f.cookies
            )
        } catch (e: Exception) {
            null
        }
        val tok = try {
            JSONObject(tr?.text ?: "").optString("t", "")
        } catch (e: Exception) {
            ""
        }
        val ck = HashMap<String, String>(f.cookies)
        tr?.cookies?.let { ck.putAll(it) }
        if (tok.isNotBlank() && tok != "null") ck["_ct"] = tok
        val hB = HashMap<String, String>(hA)
        val xt = csrf.ifBlank { tok }
        if (xt.isNotBlank() && xt != "null") hB["X-CSRF-Token"] = xt
        val body = HashMap<String, String>()
        body["cfg"] = cfg
        if (tok.isNotBlank() && tok != "null") {
            body["token"] = tok; body["csrf_token"] = tok
        } else if (csrf.isNotBlank()) body["csrf_token"] = csrf
        val rb = try {
            app.post(url, headers = hB, data = body, cookies = ck).text
        } catch (e: Exception) {
            ""
        }
        parseCfg(rb)?.let { return it }

        // C: sade istek
        val rc = try {
            app.post(
                url,
                headers = mapOf("User-Agent" to ua, "Referer" to pageUrl),
                data = mapOf("cfg" to cfg)
            ).text
        } catch (e: Exception) {
            ""
        }
        val res = parseCfg(rc)
        if (res == null) dbg = "$dbg | cfgA=${ra.take(60)} cfgB=${rb.take(60)}"
        return res
    }

    private fun parseCfg(text: String): Cfg? {
        if (text.isBlank()) return null
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        val c = o.optJSONObject("config")
        var t = c?.optString("t", "") ?: o.optString("t", "")
        var v = c?.optString("v", "") ?: ""
        if (t == "null") t = ""
        if (v == "null") v = ""
        o.optJSONObject("enc")?.let { enc ->
            val d = decryptEnc(enc)
            if (d != null) v = d
        }
        if (v.isBlank()) return null
        return Cfg(t, v, text)
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

    private fun aesTry(key: ByteArray, iv: ByteArray, ct: ByteArray): String? =
        try {
            if (key.size != 16 && key.size != 24 && key.size != 32) null
            else {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                String(cipher.doFinal(ct), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }

    private fun decryptEnc(enc: JSONObject): String? {
        return try {
            val k1 = b64bin(enc.optString("k1"))
            val k2 = b64bin(enc.optString("k2"))
            val iv = b64bin(enc.optString("iv"))
            val ct = b64bin(enc.optString("c"))
            val n = minOf(k1.size, k2.size)
            val key = ByteArray(n) { i -> (k1[i].toInt() xor k2[i].toInt()).toByte() }
            aesTry(key, iv, ct) ?: if (n > 32) aesTry(key.copyOf(32), iv, ct) else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Embed çözme ----------------
    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String, depth: Int): Found {
        val origin = originOf(embedUrl)
        val found = Found(origin)
        val (html, embedCk) = fetchTextCk(embedUrl, hdr(pageUrl))
        // Fire HLS Player (imagestoo vb.): oturum çerezi (fireplayer_player) master ve parçalar için gerekli
        embedCk.forEach { (k, v) -> if (k.contains("fireplayer", true)) found.cookies[k] = v }

        val texts = ArrayList<String>()
        texts.add(html)
        texts.addAll(unpackAll(html))
        texts.addAll(deepTexts(texts))
        val xored = LinkedHashSet<String>()
        texts.toList().forEach { xored.addAll(xorTexts(it)) }
        texts.addAll(xored)
        texts.forEach { scan(it, origin, found) }
        // XOR ile çözülen düz adres (.m3u8 uzantısı olmasa da) HLS sayılır
        xored.forEach { d ->
            val u = Regex("^\\s*(https?://[^\\s\"'<>\\\\]+)").find(d)?.groupValues?.get(1)
            if (u != null && !found.hls.contains(u) && !found.mp4.contains(u) &&
                !Regex("\\.(?:vtt|srt)(?:\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u)
            ) found.hls.add(u)
        }

        // FirePlayer: POST <embed>/player/index.php?data=<id>&do=getVideo
        if (found.hls.isEmpty() && found.mp4.isEmpty()) {
            val joined = texts.joinToString("\n")
            var fid = Regex("FirePlayer\\(\\s*[\"']([A-Za-z0-9]{16,64})[\"']").find(joined)?.groupValues?.get(1)
            if (fid == null && Regex("FirePlayer|fireplay", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                fid = Regex("/video/([A-Za-z0-9]{16,64})").find(embedUrl)?.groupValues?.get(1)
            }
            if (fid == null) {
                fid = Regex("index\\.php\\?data=([A-Za-z0-9]{16,64})").find(joined)?.groupValues?.get(1)
            }
            if (fid != null) fireVideo(embedUrl, pageUrl, fid, found, embedCk)
        }

        // iç içe iframe
        if (found.hls.isEmpty() && found.mp4.isEmpty() && depth < 1) {
            val inner = Jsoup.parse(html).select("iframe[src]")
                .map { resolveUrl(embedUrl, fixText(it.attr("src"))) }
                .firstOrNull { it.startsWith("http") && !isAdHost(it, mainUrl) }
            if (inner != null) return resolveEmbed(inner, embedUrl, depth + 1)
        }
        return found
    }

    private suspend fun fireVideo(embedUrl: String, pageUrl: String, id: String, found: Found, ck: Map<String, String>) {
        val origin = originOf(embedUrl)
        val url = "$origin/player/index.php?data=$id&do=getVideo"
        val h = hdr(
            embedUrl,
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to origin,
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
        )
        val sendCk = HashMap<String, String>(ck)
        for (rf in listOf(pageUrl, "")) {
            val resp = try {
                app.post(url, headers = h, data = mapOf("hash" to id, "r" to rf), cookies = sendCk)
            } catch (e: Exception) {
                null
            }
            val t = resp?.text ?: ""
            resp?.cookies?.forEach { (k, v) ->
                sendCk[k] = v
                if (k.contains("fireplayer", true)) found.cookies[k] = v
            }
            try {
                val o = JSONObject(t)
                for (k in listOf("videoSource", "securedLink", "source", "file", "url")) {
                    val v = o.optString(k, "")
                    if (v.isBlank() || v == "null") continue
                    if (!Regex("^(?:https?:)?//", RegexOption.IGNORE_CASE).containsMatchIn(v)) continue
                    val u = fixText(v).let { if (it.startsWith("//")) "https:$it" else it }
                    if (Regex("\\.mp4(?:\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u) && !o.optBoolean("hls", false)) {
                        found.mp4.add(u)
                    } else found.hls.add(u)
                }
            } catch (e: Exception) {
            }
            scan(t, origin, found)
            if (found.hls.isNotEmpty() || found.mp4.isNotEmpty()) return
        }
    }

    // Metin içinden m3u8 / mp4 / altyazı adreslerini topla
    private fun scan(raw: String, origin: String, f: Found) {
        val t = fixText(raw)
        Regex("https?://[^\\s\"'<>\\\\)]+?\\.m3u8(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.hls.add(it.value) }
        Regex("[\"'](/[^\"'\\s]+?\\.m3u8[^\"'\\s]*)[\"']", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.hls.add(origin + it.groupValues[1]) }
        // uzantısız HLS: https://host/m3/<şifreli-token>
        Regex("https?://[^\\s\"'<>\\\\)]+?/m3/[A-Za-z0-9+/=_%\\-.]{24,}")
            .findAll(t).forEach { f.hls.add(it.value) }
        // Fire HLS Player: .../cdn/hls/<kod>/master.txt
        Regex("https?://[^\\s\"'<>\\\\)]+?/master\\.txt(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.hls.add(it.value) }
        Regex("[\"'](/[^\"'\\s]+?/master\\.txt[^\"'\\s]*)[\"']", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.hls.add(origin + it.groupValues[1]) }
        Regex("https?://[^\\s\"'<>\\\\)]+?\\.mp4(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { f.mp4.add(it.value) }
        Regex("https?://[^\\s\"'<>\\\\)]+?\\.(?:vtt|srt)(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
            .findAll(t).forEach { m ->
                if (Regex("thumb|sprite|chapter", RegexOption.IGNORE_CASE).containsMatchIn(m.value)) return@forEach
                val ctx = t.substring(maxOf(0, m.range.first - 150), minOf(t.length, m.range.last + 150))
                val lb = Regex("label[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(ctx)?.groupValues?.get(1)
                if (!f.subs.containsKey(m.value)) f.subs[m.value] = lb ?: ""
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

    // XOR + base64 şifreli player adresi: _x("BASE64...") ve anahtar fonksiyon içinde
    private fun xorDecode(b64: String, key: String): String {
        val b = b64bin(b64)
        val sb = StringBuilder()
        for (i in b.indices) sb.append(((b[i].toInt() and 255) xor key[i % key.length].code).toChar())
        return sb.toString()
    }

    private fun xorTexts(t: String): List<String> {
        val out = LinkedHashSet<String>()
        val names = HashMap<String, String>()
        val keys = LinkedHashSet<String>()
        Regex("""(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*function\s*\(\s*\w+\s*\)\s*\{\s*var\s+\w+\s*=\s*(["'])([^"']{3,64})\2""")
            .findAll(t).forEach { names[it.groupValues[1]] = it.groupValues[3]; keys.add(it.groupValues[3]) }
        Regex("""function\s+([A-Za-z_$][\w$]*)\s*\(\s*\w+\s*\)\s*\{\s*var\s+\w+\s*=\s*(["'])([^"']{3,64})\2""")
            .findAll(t).forEach { names[it.groupValues[1]] = it.groupValues[3]; keys.add(it.groupValues[3]) }
        Regex("""(?:var|let|const)\s+\w+\s*=\s*(["'])([A-Za-z0-9]{6,24})\1""")
            .findAll(t).forEach { keys.add(it.groupValues[2]) }
        Regex("""([A-Za-z_$][\w$]*)\s*\(\s*(["'])([A-Za-z0-9+/=_-]{24,})\2\s*\)""")
            .findAll(t).forEach { m ->
                val fn = m.groupValues[1]
                val data = m.groupValues[3]
                val tryKeys = names[fn]?.let { listOf(it) } ?: keys.take(8)
                for (k in tryKeys) {
                    val d = xorDecode(data, k)
                    if (Regex("https?://|\\.m3u8|\\.mp4|\\.vtt|\"file\"|sources", RegexOption.IGNORE_CASE).containsMatchIn(d)) {
                        out.add(d)
                    }
                }
            }
        return out.toList()
    }

    // ---------------- Çıkış ----------------
    private fun subName(label: String?, url: String): String {
        val l = (label ?: "").lowercase()
        return when {
            l.contains("turk") || l.contains("türk") || l == "tr" || l == "tur" -> "Türkçe"
            l.contains("engl") || l.contains("ingiliz") || l == "en" || l == "eng" -> "İngilizce"
            else -> {
                val code = Regex("_([a-z]{2,3})\\.(?:vtt|srt)", RegexOption.IGNORE_CASE)
                    .find(url)?.groupValues?.get(1)?.lowercase()
                when (code) {
                    "tur", "tr" -> "Türkçe"
                    "eng", "en" -> "İngilizce"
                    else -> if (!label.isNullOrBlank()) label else "Türkçe"
                }
            }
        }
    }

    // /hls2/01/00009/<kod>_h/...  ->  /vtt/01/00009/<kod>_tur.vtt
    private fun guessVtt(u: String): List<String> {
        val m = Regex("^(https?://[^/]+)/hls\\d*/(\\d+)/(\\d+)/([a-z0-9]+)_[^/]*/", RegexOption.IGNORE_CASE).find(u)
            ?: return emptyList()
        val (host, a, b, code) = m.destructured
        return listOf("tur", "tr", "eng").map { "$host/vtt/$a/$b/${code}_$it.vtt" }
    }

    private suspend fun emitSubs(
        subs: Map<String, String>,
        extraGuess: List<String>,
        ref: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val h = mapOf("User-Agent" to ua, "Referer" to ref, "Origin" to ref.trimEnd('/'))
        val done = HashSet<String>()
        for ((u, lb) in subs) {
            if (done.add(u)) subtitleCallback(SubtitleFile(subName(lb, u), u))
        }
        for (g in extraGuess) {
            if (subs.containsKey(g) || !done.add(g)) continue
            val t = fetchText(g, h)
            if (t.trimStart().startsWith("WEBVTT") && t.contains("-->")) {
                subtitleCallback(SubtitleFile(subName(null, g), g))
            }
        }
    }

    private fun cookieHeader(c: Map<String, String>): Map<String, String> =
        if (c.isEmpty()) emptyMap() else mapOf("Cookie" to c.entries.joinToString("; ") { "${it.key}=${it.value}" })

    private suspend fun emitMp4(
        url: String,
        refBase: String,
        callback: (ExtractorLink) -> Unit,
        cookies: Map<String, String> = emptyMap()
    ) {
        val ref = refBase.trimEnd('/') + "/"
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Referer" to ref, "Origin" to ref.trimEnd('/')) + cookieHeader(cookies)
            }
        )
    }

    // Ana liste (master): ses seçenekleri, kalite varyantları ve yerleşik altyazı satırları
    private suspend fun emitHls(
        url: String,
        refBase: String,
        pageBase: String,
        subs: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        cookies: Map<String, String> = emptyMap()
    ): Boolean {
        var ck: Map<String, String> = cookies
        fun hdrFor(r: String) = mapOf("User-Agent" to ua, "Referer" to r, "Origin" to r.trimEnd('/')) + cookieHeader(ck)
        var ref = refBase.trimEnd('/') + "/"
        var body = fetchText(url, hdrFor(ref))
        if (!body.contains("#EXTM3U") && pageBase.trimEnd('/') != refBase.trimEnd('/')) {
            val alt = pageBase.trimEnd('/') + "/"
            val b2 = fetchText(url, hdrFor(alt))
            if (b2.contains("#EXTM3U")) {
                body = b2; ref = alt
            }
        }
        // Yedek: oynatıcı sunucusu çerez istiyorsa (örn. imagestoo "security error") ana adresine
        // uğrayıp fireplayer_* çerezini al ve yeniden dene. Sadece liste alınamadığında çalışır.
        if (!body.contains("#EXTM3U") && ck.isEmpty()) {
            val own = originOf(url) + "/"
            val (_, c2) = fetchTextCk(own, hdr(refBase.trimEnd('/') + "/"))
            val fp = c2.filterKeys { it.contains("fireplayer", true) }
            if (fp.isNotEmpty()) {
                ck = fp
                val b3 = fetchText(url, hdrFor(own))
                if (b3.contains("#EXTM3U")) {
                    body = b3; ref = own
                }
            }
        }
        val headers = hdrFor(ref)

        suspend fun send(u: String, h: Int, label: String) {
            callback(
                newExtractorLink(name, label, u, ExtractorLinkType.M3U8) {
                    this.referer = ref
                    this.quality = if (h > 0) h else Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }

        val allSubs = LinkedHashMap<String, String>(subs)
        val vars = ArrayList<Var>()
        var hasAudio = false
        val lines = body.lines()
        for (i in lines.indices) {
            val ln = lines[i]
            if (ln.startsWith("#EXT-X-STREAM-INF:")) {
                var uri = ""
                for (j in i + 1 until lines.size) {
                    val l = lines[j].trim()
                    if (l.isNotEmpty() && !l.startsWith("#")) {
                        uri = l; break
                    }
                }
                if (uri.isNotEmpty()) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(ln)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val h = Regex("RESOLUTION=\\d+x(\\d+)").find(ln)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    vars.add(Var(resolveUrl(url, uri), bw, h))
                }
            } else if (ln.startsWith("#EXT-X-MEDIA:")) {
                if (ln.contains("TYPE=AUDIO")) hasAudio = true
                else if (ln.contains("TYPE=SUBTITLES")) {
                    val su = Regex("URI=\"([^\"]+)\"").find(ln)?.groupValues?.get(1)
                    val nm = Regex("NAME=\"([^\"]+)\"").find(ln)?.groupValues?.get(1) ?: ""
                    if (su != null && Regex("\\.(vtt|srt)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(su)) {
                        allSubs[resolveUrl(url, su)] = nm
                    }
                }
            }
        }

        val guesses = ArrayList<String>()
        guesses.addAll(guessVtt(url))
        vars.firstOrNull()?.let { guesses.addAll(guessVtt(it.url)) }
        emitSubs(allSubs, guesses.distinct(), ref, subtitleCallback)

        if (vars.isEmpty()) {
            send(url, 0, name)
            return true
        }

        val best = vars.maxWithOrNull(compareBy<Var>({ it.h }, { it.bw }))
        val byAudio = HashMap<Int, Var>()
        for (v in vars) {
            val m = Regex("-a(\\d+)\\.m3u8", RegexOption.IGNORE_CASE).find(v.url) ?: continue
            val k = m.groupValues[1].toInt()
            val cur = byAudio[k]
            if (cur == null || v.h > cur.h || (v.h == cur.h && v.bw > cur.bw)) byAudio[k] = v
        }

        if (hasAudio || byAudio.isEmpty()) {
            send(url, best?.h ?: 0, name)
        } else {
            val keys = byAudio.keys.sorted()
            for (k in keys) {
                val v = byAudio[k]!!
                send(v.url, v.h, if (keys.size > 1) "$name Ses $k" else name)
            }
        }
        return true
    }
}
