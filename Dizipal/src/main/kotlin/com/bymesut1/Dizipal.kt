package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizipal : MainAPI() {
    override var mainUrl = "https://dizipal2142.com"
    override var name = "Dizipal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Site adresi değişirse sıradakine geçilir (duyuru: sonraki adres dizipal2143.com)
    private val bases = listOf("https://dizipal2142.com", "https://dizipal2143.com")

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

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
    }

    private class Cfg(val t: String, val v: String, val raw: String)

    private class Var(val url: String, val bw: Int, val h: Int)

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

    private suspend fun fetchSite(path: String): Fetched? {
        val order = listOf(mainUrl) + bases.filter { it != mainUrl }
        for (b in order) {
            try {
                val r = app.get(b + path, headers = hdr("$b/"))
                if (r.code == 200 && r.text.isNotBlank()) {
                    mainUrl = b
                    return Fetched(b, r.text, r.cookies)
                }
            } catch (e: Exception) {
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
    // Ana sayfa / arama
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("home:")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
            val f = fetchSite("/") ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
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
        val f = fetchSite(path) ?: return null
        val doc = Jsoup.parse(f.text, f.base)

        val title = cleanTitle(
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
        )
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        val plot = (doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content"))
            ?.takeIf { it.isNotBlank() }
        val tags = doc.select("a[href*=/kategori/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val fullUrl = f.base + path

        if (isMovie) {
            return newMovieLoadResponse(title, fullUrl, TvType.Movie, fullUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
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
                            val r = app.get(f.base + p, headers = hdr("${f.base}/"))
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

        return newTvSeriesLoadResponse(title, fullUrl, TvType.TvSeries, eps) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
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
        val f = fetchSite(path) ?: return false
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
                if (emitHls(u, found.origin, f.base, found.subs, subtitleCallback, callback)) got = true
            }
            for (u in found.mp4.take(2)) {
                emitMp4(u, found.origin, callback); got = true
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
        return parseCfg(rc)
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
        val html = fetchText(embedUrl, hdr(pageUrl))

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
            if (fid != null) fireVideo(embedUrl, pageUrl, fid, found)
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

    private suspend fun fireVideo(embedUrl: String, pageUrl: String, id: String, found: Found) {
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
        for (rf in listOf(pageUrl, "")) {
            val t = try {
                app.post(url, headers = h, data = mapOf("hash" to id, "r" to rf)).text
            } catch (e: Exception) {
                ""
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

    private suspend fun emitMp4(url: String, refBase: String, callback: (ExtractorLink) -> Unit) {
        val ref = refBase.trimEnd('/') + "/"
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Referer" to ref, "Origin" to ref.trimEnd('/'))
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        fun hdrFor(r: String) = mapOf("User-Agent" to ua, "Referer" to r, "Origin" to r.trimEnd('/'))
        var ref = refBase.trimEnd('/') + "/"
        var body = fetchText(url, hdrFor(ref))
        if (!body.contains("#EXTM3U") && pageBase.trimEnd('/') != refBase.trimEnd('/')) {
            val alt = pageBase.trimEnd('/') + "/"
            val b2 = fetchText(url, hdrFor(alt))
            if (b2.contains("#EXTM3U")) {
                body = b2; ref = alt
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
