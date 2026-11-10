package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

// DEBUG: logcat'e "BuzFilmizle3" etiketiyle kayıt düşer (sorun çözülünce false yap).
private const val DEBUG = true
// true yaparsan hiç kaynak bulunamayınca listede "🐞" hata satırları görünür.
private const val SHOW_DEBUG_LINKS = false
private const val TAG = "BuzFilmizle3"

class BuzFilmizle3 : MainAPI() {
    override var mainUrl = "https://www.buzfilmizle3.com"
    override var name = "BuzFilmizle3"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenen Filmler",
        "$mainUrl/aksiyon-filmleri/" to "Aksiyon",
        "$mainUrl/aile-filmleri/" to "Aile",
        "$mainUrl/animasyon-filmleri/" to "Animasyon",
        "$mainUrl/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "$mainUrl/dram-filmleri/" to "Dram",
        "$mainUrl/fantastik-filmler/" to "Fantastik",
        "$mainUrl/gerilim-filmleri/" to "Gerilim",
        "$mainUrl/komedi-filmleri/" to "Komedi",
        "$mainUrl/korku-filmleri/" to "Korku",
        "$mainUrl/macera-filmleri/" to "Macera",
        "$mainUrl/romantik-filmler/" to "Romantik",
        "$mainUrl/savas-filmleri/" to "Savaş",
        "$mainUrl/suc-filmleri/" to "Suç",
        "$mainUrl/cizgi-filmler/" to "Çizgi Filmler"
    )

    // ---------------------------------------------------------------- Yardımcılar

    private val mediaRegex = Regex(
        """https?://[^\s"'<>\\]+?(?:\.(?:m3u8|mp4|mpd)|/(?:master|playlist|index)\.txt)[^\s"'<>\\]*"""
    )

    private val urlRegex = Regex("""(?:https?:)?//[^\s"'<>\\)]+""")

    private val assetRegex =
        Regex("""(?i)\.(?:js|css|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|otf|map)(?:[?#].*)?$""")

    private val yearRegex = Regex("""\b(?:19|20)\d{2}\b""")

    private val badFrameHosts = listOf(
        "google", "facebook", "doubleclick", "aarems", "wargamings",
        "disqus", "youtube", "twitter", "cloudflare", "jsdelivr", "bootstrapcdn",
        "jquery", "w3.org", "w3c", "fontawesome", "gstatic", "googleapis",
        "schema.org", "cdnjs", "unpkg", "devtools"
    )

    private fun log(msg: String) {
        if (DEBUG) Log.d(TAG, msg)
    }

    // Hata satırları tampona yazılır; sadece HİÇ kaynak bulunamazsa listeye eklenir.
    // (Eskiden her zaman ekleniyordu, oynatıcı sahte "debug.local" linkini açıp
    //  "Source error / ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" veriyordu.)
    private val dbgBuf = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun dbg(msg: String) {
        Log.d(TAG, msg)
        if (DEBUG) dbgBuf.add(msg)
    }

    // Uzun sayfa gövdesini logcat'e parça parça yazar (logcat ~4000 karakterde keser)
    private fun dumpBody(tag: String, text: String, limit: Int = 30000) {
        if (!DEBUG) return
        text.take(limit).chunked(3000).forEachIndexed { i, c -> Log.d(TAG, "BODY[$tag#$i] $c") }
    }

    private fun cleanTitle(t: String): String =
        t.replace(Regex("""\s*Anasayfa\s*>.*$"""), "").replace(
            Regex("""(?i)\s*(Full\s*HD\s*Film|Full\s*Film\s*HD|Full\s*Film|Full\s*HD|Türkçe\s*Dublaj|Türkçe\s*Altyazılı)?\s*izle\s*$"""),
            ""
        ).trim()

    private fun originOf(url: String): String =
        runCatching {
            val u = URI(url)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("$mainUrl/")

    private fun sourceName(url: String, label: String): String {
        if (label.isNotBlank()) return label
        val u = url.lowercase()
        return when {
            "matrudas" in u || "vidmoly" in u -> "Vidmoly"
            "drewimplemnt" in u -> "Bronz"
            "97bf1" in u -> "FASTM"
            else -> runCatching { URI(url).host.removePrefix("www.") }.getOrDefault("BuzFilm")
        }
    }

    // ---------------------------------------------------------------- Ana sayfa / arama

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("div.movief a") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = cleanTitle(
            a.text().trim().ifBlank { this.selectFirst("img")?.attr("alt")?.trim() ?: "" }
        )
        if (title.isBlank()) return null

        val img = this.selectFirst("img[data-src]") ?: this.selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.startsWith("http") }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select("div.moviefilm").mapNotNull { it.toSearchResult() }
        log("getMainPage url=$url kart=${items.size}")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/", params = mapOf("s" to query), referer = "$mainUrl/").document
        val items = doc.select("div.moviefilm").mapNotNull { it.toSearchResult() }
        log("search q=$query sonuç=${items.size}")
        return items
    }

    // ---------------------------------------------------------------- Detay

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: run {
                log("load: başlık bulunamadı url=$url")
                return null
            }
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.filmcontent img[data-src]")?.attr("data-src")
            ?: doc.selectFirst("div.filmcontent img")?.attr("src")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val year = yearRegex.findAll(rawTitle).lastOrNull()?.value?.toIntOrNull()
        val tags = doc.select("a[rel=category tag]").map { it.text().trim() }.filter { it.isNotBlank() }

        log("load: title=$title year=$year poster=$poster iframe=${doc.select("iframe").size}")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------- Kaynaklar

    private fun extractFrames(doc: Document): List<String> {
        val out = LinkedHashSet<String>()

        doc.select("iframe").forEach { f ->
            listOf("src", "data-src", "data-lazy-src", "data-fpo-src").forEach { attr ->
                val v = f.attr(attr).trim()
                if (v.isNotBlank() && !v.startsWith("data:") && !v.startsWith("about:")) {
                    val full = fixUrl(v.replace("&amp;", "&"))
                    if (badFrameHosts.none { full.contains(it, true) }) out.add(full)
                }
            }
        }

        // Script ile yazılan / gizli kalan oynatıcı adresleri
        val html = doc.html().replace("\\/", "/").replace("&amp;", "&")
        Regex("""https?://[^\s"'<>\\]*(?:oynat\.buzfilmizle3|matrudas|vidmoly|drewimplemnt)[^\s"'<>\\]*""")
            .findAll(html)
            .forEach { out.add(it.value) }

        return out.toList()
    }

    // \/ \uXXXX \xXX kaçışlarını açar, packed JS'i çözer, base64 içindeki adresleri ortaya çıkarır
    private fun expand(raw: String): String {
        var t = raw.replace("\\/", "/").replace("&amp;", "&")
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }

        val extra = StringBuilder()

        if (raw.contains("eval(function(p,a,c,k,e,d)")) {
            try {
                extra.append("\n").append(getAndUnpack(raw).replace("\\/", "/"))
                log("packed JS çözüldü")
            } catch (e: Exception) {
                log("unpack hata: ${e.message}")
            }
        }

        // atob("...") ve uzun base64 dizeleri
        val b64 = Regex("""["']([A-Za-z0-9+/_-]{24,}={0,2})["']""")
        b64.findAll(t).take(40).forEach { m ->
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

    private val blockedHosts = listOf(
        "jsdelivr", "bootstrapcdn", "cloudflare", "googleapis", "gstatic", "jquery",
        "w3.org", "schema.org", "fontawesome", "google-analytics", "googletagmanager",
        "devtools", "facebook.com", "twitter.com", "instagram.com"
    )

    private val hostRegex =
        Regex("""^https?://[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?::\d+)?(?:/|$)""")

    private fun candidates(text: String, selfUrl: String): List<String> =
        urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '\\') }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { u ->
                u != selfUrl &&
                    hostRegex.containsMatchIn(u) &&
                    blockedHosts.none { u.contains(it, true) } &&
                    badFrameHosts.none { u.contains(it, true) } &&
                    !assetRegex.containsMatchIn(u) &&
                    !u.contains("/assets/") &&
                    !u.contains("www.buzfilmizle3.com")
            }
            .distinct()
            .sortedByDescending { if (it.contains("player", true) || it.contains("embed", true)) 1 else 0 }
            .toList()

    private suspend fun emitM3u8(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String> = emptyMap()
    ): Boolean {
        val links = try {
            M3u8Helper.generateM3u8(source = name, streamUrl = url, referer = referer, headers = headers)
        } catch (e: Exception) {
            log("generateM3u8 hata: ${e.message} url=$url")
            null
        }

        if (!links.isNullOrEmpty()) {
            log("m3u8 ($name) ${links.size} kalite")
            links.forEach(callback)
        } else {
            log("m3u8 ($name) tek link olarak veriliyor: $url")
            callback(
                newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    private suspend fun emitVideo(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("video ($name): $url")
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // m3u8'i hangi Referer ile açabiliyorsak onu bulur (Vidmoly 2004 BAD_HTTP_STATUS çözümü)
    private suspend fun probeReferer(url: String, candidates: List<String>): String? {
        for (r in candidates) {
            val ok = try {
                val t = app.get(
                    url,
                    referer = r,
                    headers = mapOf("Origin" to r.trimEnd('/'), "User-Agent" to UA),
                    timeout = 10L
                ).text
                t.trimStart().startsWith("#EXTM3U")
            } catch (e: Exception) {
                false
            }
            log("probe referer=$r ok=$ok")
            if (ok) return r
        }
        return null
    }

    private suspend fun emitMedia(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val l = url.lowercase()
        val hls = l.contains(".m3u8") || Regex("""/(?:master|playlist|index)\.txt""").containsMatchIn(l)

        val refs = LinkedHashSet<String>()
        refs.add(referer)
        if (name.equals("Vidmoly", true)) {
            refs.add("https://vidmoly.to/")
            refs.add("https://vidmoly.net/")
            refs.add("https://vidmoly.me/")
        }
        refs.add("$mainUrl/")

        return if (hls) {
            val good = probeReferer(url, refs.toList()) ?: referer
            val hdr = mapOf("Origin" to good.trimEnd('/'), "User-Agent" to UA)
            emitM3u8(name, url, good, callback, hdr)
        } else {
            emitVideo(name, url, referer, callback)
        }
    }

    // FirePlayer: sayfa videoyu "?do=getVideo" POST isteğiyle veriyor.
    // Cevap {"videoSrc":"https://..."} (bazı oynatıcılarda videoSource / securedLink) döner.
    // Buradan sadece gerçek kaynağın ADRESİ alınır; adresin çözümü handleEmbed'de yapılır.
    private suspend fun fireGetVideo(url: String, referer: String): String? {
        val clean = url.substringBefore("?").substringBefore("#")
        val id = clean.trimEnd('/').substringAfterLast("/")
        val origin = originOf(clean) // sonunda "/" var

        var cookies: Map<String, String> = emptyMap()
        val apis = LinkedHashSet<String>()
        try {
            val page = app.get(clean, referer = referer, headers = mapOf("Sec-Fetch-Dest" to "iframe"), timeout = 15L)
            cookies = page.cookies
            val html = page.text.replace("\\/", "/")
            log("fireplayer sayfa kod=${page.code} uzunluk=${html.length}")
            Regex("""["']([^"'\s]*do=getVideo[^"'\s]*)["']""").findAll(html).forEach { m ->
                val g = m.groupValues[1]
                if (!g.contains("+") && !g.startsWith("&")) {
                    runCatching { URI(clean).resolve(g).toString() }.getOrNull()?.let { apis.add(it) }
                }
            }
        } catch (e: Exception) {
            dbg("fireplayer sayfa hatası: ${e.message}")
        }
        apis.add("$clean?do=getVideo")
        apis.add("${origin}player/index.php?data=$id&do=getVideo")

        val refs = listOf(referer, "").distinct()
        for (api in apis) {
            for (r in refs) {
                val body = try {
                    app.post(
                        api,
                        data = mapOf("hash" to id, "r" to r),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "*/*",
                            "Origin" to origin.trimEnd('/')
                        ),
                        referer = clean,
                        cookies = cookies,
                        timeout = 15L
                    ).text
                } catch (e: Exception) {
                    dbg("fireplayer istek hatası: ${e.message}")
                    ""
                }
                log("fireplayer $api r=$r -> ${body.take(400).replace("\n", " ")}")
                val txt = body.replace("\\/", "/").replace("\\u0026", "&")
                val src = listOf("videoSrc", "videoSource", "securedLink").mapNotNull { k ->
                    Regex("\"$k\"\\s*:\\s*\"(https?:[^\"]+)\"").find(txt)?.groupValues?.get(1)
                }.firstOrNull { it.isNotBlank() }
                    ?: mediaRegex.find(txt)?.value
                if (src != null) return src
                dbg("fireplayer boş: ${body.take(120).replace("\n", " ")}")
            }
        }
        dbg("fireplayer cevap vermedi: $clean")
        return null
    }

    // BRONZ kaynağı: myplayersvideo.xyz/bronze/index.php?id=https://hexload.com/<id>
    // Hexload sayfasından / indirme isteğinden gerçek video adresi alınmaya çalışılır.
    private suspend fun hexload(
        name: String,
        hexUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = hexUrl.trimEnd('/').substringAfterLast("/").removePrefix("embed-").substringBefore(".")
        val origin = originOf(hexUrl).trimEnd('/')
        log("hexload id=$id url=$hexUrl")

        var cookies: Map<String, String> = emptyMap()
        try {
            val page = app.get(hexUrl, referer = referer, timeout = 15L)
            cookies = page.cookies
            log("hexload sayfa kod=${page.code} uzunluk=${page.text.length}")
            dumpBody("hexload", page.text, 8000)
            val txt = expand(page.text)
            val m = mediaRegex.find(txt)?.value
            if (m != null) return emitMedia(name, m, "$origin/", callback)
        } catch (e: Exception) {
            dbg("hexload sayfa hatası: ${e.message}")
        }

        try {
            val body = app.post(
                "$origin/download",
                data = mapOf(
                    "op" to "download3",
                    "id" to id,
                    "ajax" to "1",
                    "method_free" to "1",
                    "dataType" to "json"
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to origin
                ),
                referer = hexUrl,
                cookies = cookies,
                timeout = 15L
            ).text
            log("hexload download -> ${body.take(500).replace("\n", " ")}")
            val txt = body.replace("\\/", "/").replace("\\u0026", "&")
            val src = Regex("\"url\"\\s*:\\s*\"(https?:[^\"]+)\"").find(txt)?.groupValues?.get(1)
                ?: mediaRegex.find(txt)?.value
            if (src != null) return emitMedia(name, src, "$origin/", callback)
        } catch (e: Exception) {
            dbg("hexload istek hatası: ${e.message}")
        }

        return viaWebView(name, hexUrl, referer, callback)
    }

    // Son çare: gizli WebView (sadece bir kez, kısa süreli)
    private suspend fun viaWebView(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            log("WebView deneniyor: $url")
            val resolver = WebViewResolver(
                Regex("""(?i)\.(?:m3u8|mp4|mpd)(?:[?#].*)?$|/(?:master|playlist|index)\.txt"""),
                timeout = 15_000L
            )
            val captured = app.get(url, referer = referer, interceptor = resolver).url
            log("WebView yakaladı: $captured")
            if (mediaRegex.containsMatchIn(captured)) {
                emitMedia(name, captured, originOf(url), callback)
            } else {
                dbg("WebView medya yakalayamadı ($name)")
                false
            }
        } catch (e: Exception) {
            dbg("WebView hata ($name): ${e.message}")
            false
        }
    }

    private suspend fun handleEmbed(
        url: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        val name = sourceName(url, label)
        val lower = url.lowercase()
        log("handleEmbed[$depth] $name -> $url (referer=$referer)")

        if (mediaRegex.containsMatchIn(lower)) {
            return emitMedia(name, url, referer, callback)
        }

        // matrudas.com/m3/<base64> gibi adresler: son parça base64 ile gerçek adresi saklıyor
        if (lower.contains("/m3/")) {
            val seg = url.substringAfter("/m3/").substringBefore("?").substringBefore("#").trimEnd('/')
            val dec = runCatching {
                var s = seg.replace('-', '+').replace('_', '/')
                while (s.length % 4 != 0) s += "="
                String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrNull()
            if (dec != null && dec.startsWith("http") && dec != url) {
                log("m3 çözüldü: $dec")
                if (handleEmbed(dec, label, referer, subtitleCallback, callback, depth + 1)) return true
            }
        }

        // BRONZ: .../bronze/index.php?id=https://hexload.com/<id>
        val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val hexUrl = Regex("""https?://(?:www\.)?hexload\.[a-z]+/[A-Za-z0-9_./-]+""").find(decoded)?.value
        if (hexUrl != null) {
            if (hexload(name, hexUrl, url, callback)) return true
            dbg("hexload çözülemedi ($name): $hexUrl")
            return false
        }

        // FirePlayer: getVideo cevabındaki adres (matrudas / vevocloud / bronze ...) ayrıca çözülür
        var fireTried = false
        if (lower.contains("/fireplayer/video/") || lower.contains("do=getvideo")) {
            fireTried = true
            val src = fireGetVideo(url, referer)
            if (src != null) {
                log("fireplayer kaynak ($name): $src")
                if (handleEmbed(src, label, url, subtitleCallback, callback, depth + 1)) return true
                dbg("fireplayer kaynağı çözülemedi ($name): $src")
                return false
            }
        }

        if (!lower.contains("oynat.buzfilmizle3") && !fireTried) {
            val extracted = try {
                loadExtractor(url, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                log("loadExtractor hata: ${e.message}")
                false
            }
            log("loadExtractor sonuç=$extracted url=$url")
            if (extracted) return true
        }

        if (depth > 4) return false

        val res = try {
            app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,*/*",
                    "Sec-Fetch-Dest" to "iframe"
                ),
                timeout = 15L
            )
        } catch (e: Exception) {
            dbg("istek hatası ($name): ${e.message}")
            return false
        }

        log("fetch kod=${res.code} final=${res.url}")
        // Asıl oynatıcı sayfaları (matrudas, vevocloud ...) bir sonraki hata ayıklama için loglanır
        if (depth >= 2) dumpBody("$name-d$depth", res.text, 12000)

        val text = expand(res.text)
        val embedReferer = originOf(url)

        val medias = mediaRegex.findAll(text).map { it.value }.distinct()
            .sortedByDescending { if (it.contains("master", true)) 1 else 0 }
            .toList()
        log("medya bulundu=${medias.size} $medias")
        // Aynı kaynaktan tek video yeter (listede çift "Vidmoly" görünmesin)
        for (m in medias) {
            if (emitMedia(name, m, embedReferer, callback)) return true
        }

        // Sayfa FirePlayer ise doğrudan sor
        if (!fireTried && text.contains("do=getVideo")) {
            val src = fireGetVideo(url, referer)
            if (src != null && src != url) {
                if (handleEmbed(src, label, url, subtitleCallback, callback, depth + 1)) return true
            }
        }

        // İç iframe / adaylar (en fazla 4, sadece ilk katmanda aday taraması)
        val next = LinkedHashSet<String>()
        res.document.select("iframe").forEach { f ->
            val src = (f.attr("src").ifBlank { f.attr("data-src") }).trim()
            if (src.isNotBlank() && !src.startsWith("data:") && !src.startsWith("about:")) {
                runCatching { URI(url).resolve(src).toString() }.getOrNull()?.let { next.add(it) }
            }
        }
        if (depth == 0) next.addAll(candidates(text, url))

        for (n in next.take(4)) {
            if (badFrameHosts.any { n.contains(it, true) }) continue
            log("aday: $n")
            if (handleEmbed(n, label, url, subtitleCallback, callback, depth + 1)) return true
        }

        // Gerçek oynatıcı sayfasında (matrudas / vevocloud ...) video çıkmadıysa gizli WebView dene.
        // oynat.buzfilmizle3 ve fireplayer sayfaları tıklama bekleyen kabuklar; onlarda WebView denenmez.
        if (!lower.contains("oynat.buzfilmizle3") && !lower.contains("/fireplayer/video/")) {
            dbg("statik çıkarım başarısız ($name) kod=${res.code} aday=${next.size} url=$url")
            return viaWebView(name, url, referer, callback)
        }
        return false
    }

    private suspend fun processPage(
        pageUrl: String,
        label: String,
        firstDoc: Document,
        base: String,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = if (pageUrl == "$base/") {
            firstDoc
        } else {
            try {
                app.get(pageUrl, referer = "$base/").document
            } catch (e: Exception) {
                dbg("part sayfası açılamadı $pageUrl: ${e.message}")
                callback(
                    newExtractorLink(label, label, pageUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$base/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return false
            }
        }

        val frames = extractFrames(doc)
        log("[$label] iframe=${frames.size}: ${frames.joinToString(" | ")}")

        var ok = false
        for (frame in frames) {
            if (!seen.add(frame)) continue
            val r = try {
                handleEmbed(frame, label, pageUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                dbg("handleEmbed istisna ($label): ${e.message}")
                false
            }
            if (r) ok = true else dbg("BAŞARISIZ [$label]: $frame")
        }
        // Çözülemese bile kaynak listede görünsün (sitedeki gibi: çalışanı kullanıcı seçer)
        if (!ok) {
            callback(
                newExtractorLink(label, label, frames.firstOrNull() ?: pageUrl, ExtractorLinkType.VIDEO) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return ok
    }

    // Film sayfasındaki kaynak (part) bağlantılarını bulur; bulunamazsa 2=FASTM, 3=BRONZ varsayılır
    private fun partPages(doc: Document, base: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        map["$base/"] = "Vidmoly"
        val defaults = mapOf(2 to "FASTM", 3 to "BRONZ")
        val basePath = runCatching { URI(base).path.trimEnd('/') }.getOrDefault("")
        if (basePath.isNotBlank()) {
            val rx = Regex("^" + Regex.escape(basePath) + "/(\\d{1,2})/?$")
            doc.select("a[href]").forEach { a ->
                val abs = fixUrlNull(a.attr("href").trim().substringBefore("#")) ?: return@forEach
                val path = runCatching { URI(abs).path }.getOrNull() ?: return@forEach
                val n = rx.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                if (n < 2) return@forEach
                val text = a.text().trim()
                val label = text.takeIf { it.length in 3..20 && it.any { c -> c.isLetter() } }
                    ?: defaults[n] ?: "Kaynak $n"
                map.putIfAbsent("$base/$n/", label)
            }
        }
        defaults.forEach { (n, l) -> map.putIfAbsent("$base/$n/", l) }
        return map
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("loadLinks data=$data")
        dbgBuf.clear()

        // Sıra: 1 = Vidmoly, 2 = FASTM, 3 = BRONZ
        val base = data.trimEnd('/').removeSuffix("/1")
        val firstDoc = try {
            app.get("$base/", referer = "$mainUrl/").document
        } catch (e: Exception) {
            dbg("film sayfası açılamadı: ${e.message}")
            org.jsoup.Jsoup.parse("")
        }

        val pages = partPages(firstDoc, base)
        log("kaynak sayfa=${pages.size}: ${pages.entries.joinToString(" | ") { "${it.value}:${it.key}" }}")

        val seen: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        // Üç kaynak AYNI ANDA denenir (CloudStream'in amap yardımcısı)
        val results = pages.entries.toList().amap { entry ->
            try {
                processPage(entry.key, entry.value, firstDoc, base, seen, subtitleCallback, callback)
            } catch (e: Exception) {
                dbg("kaynak hatası (${entry.value}): ${e.message}")
                false
            }
        }
        val found = results.any { it }

        if (!found && DEBUG && SHOW_DEBUG_LINKS) {
            dbgBuf.toList().takeLast(8).forEach { msg ->
                callback(
                    newExtractorLink("DEBUG", "🐞 ${msg.take(300)}", "https://debug.local/", ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        log("loadLinks bitti found=$found")
        return found
    }
}
