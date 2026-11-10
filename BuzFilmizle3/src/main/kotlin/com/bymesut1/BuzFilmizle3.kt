package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

// Hata ayıklama: true iken kaynak listesinde "🐞 DEBUG" satırları görünür
// ve logcat'e "BuzFilmizle3" etiketiyle kayıt düşer. Bitince false yap.
private const val DEBUG = true
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
        "disqus", "youtube", "twitter", "cloudflare"
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
    private fun dumpBody(tag: String, text: String) {
        val t = text.take(30000)
        t.chunked(3000).forEachIndexed { i, c -> Log.d(TAG, "BODY[$tag#$i] $c") }
    }

    private fun cleanTitle(t: String): String =
        t.replace(
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

    private fun candidates(text: String, selfUrl: String): List<String> =
        urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '\\') }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { u ->
                u != selfUrl &&
                    badFrameHosts.none { u.contains(it, true) } &&
                    !assetRegex.containsMatchIn(u) &&
                    !u.contains("w3.org") &&
                    !u.contains("schema.org") &&
                    !u.contains("www.buzfilmizle3.com")
            }
            .distinct()
            .toList()

    private suspend fun emitM3u8(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = try {
            M3u8Helper.generateM3u8(source = name, streamUrl = url, referer = referer)
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
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun emitVideo(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        log("video ($name): $url")
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun emitMedia(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val l = url.lowercase()
        if (l.contains(".mp4")) emitVideo(name, url, referer, callback)
        else emitM3u8(name, url, referer, callback) // m3u8 / master.txt / playlist.txt
    }

    // Sayfa JS ile oynatıcı kuruyorsa: gizli WebView ile açıp ağdan m3u8/mp4 isteğini yakalar
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
                timeout = 25_000L
            )
            val captured = app.get(url, referer = referer, interceptor = resolver).url
            log("WebView yakaladı: $captured")
            if (mediaRegex.containsMatchIn(captured)) {
                emitMedia(name, captured, originOf(url), callback)
                true
            } else {
                dbg("WebView medya yakalayamadı ($name) son=$captured")
                false
            }
        } catch (e: Exception) {
            dbg("WebView hata ($name): ${e.message}")
            false
        }
    }

    // Sayfadaki JS'in çağırdığı olası API adreslerini dener (fetch / ajax / url:)
    private suspend fun probeApis(
        text: String,
        pageUrl: String,
        referer: String
    ): String {
        val paths = LinkedHashSet<String>()
        listOf(
            Regex("""fetch\(\s*["']([^"']+)["']"""),
            Regex("""\.(?:get|post|getJSON)\(\s*["']([^"']+)["']"""),
            Regex("""url\s*:\s*["']([^"']+)["']"""),
            Regex("""["'](/(?:api|source|sources|player|embed|get|video|stream)[^"'\s]*)["']""")
        ).forEach { r -> r.findAll(text).forEach { paths.add(it.groupValues[1]) } }

        val sb = StringBuilder()
        for (p in paths.filter { !assetRegex.containsMatchIn(it) && !it.startsWith("data:") }.take(5)) {
            val full = runCatching { URI(pageUrl).resolve(p).toString() }.getOrNull() ?: continue
            if (badFrameHosts.any { full.contains(it, true) }) continue
            val hdr = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*")
            val body = try {
                app.get(full, referer = pageUrl, headers = hdr).text
            } catch (e: Exception) {
                try {
                    app.post(full, referer = pageUrl, headers = hdr).text
                } catch (e2: Exception) {
                    log("api hata $full: ${e2.message}")
                    ""
                }
            }
            if (body.isNotBlank()) {
                log("api $full -> ${body.take(200).replace("\n", " ")}")
                sb.append("\n").append(expand(body))
            }
        }
        return sb.toString()
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

        // Doğrudan medya adresi
        if (mediaRegex.containsMatchIn(lower)) {
            emitMedia(name, url, referer, callback)
            return true
        }

        // Hazır çıkarıcılar (Vidmoly vb.) - oynat.buzfilmizle3 sarmalayıcısı için atlanır
        if (!lower.contains("oynat.buzfilmizle3")) {
            val extracted = try {
                loadExtractor(url, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                log("loadExtractor hata: ${e.message}")
                false
            }
            log("loadExtractor sonuç=$extracted url=$url")
            if (extracted) return true
        }

        if (depth > 3) {
            dbg("derinlik sınırı aşıldı: $url")
            return false
        }

        val res = try {
            app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,*/*",
                    "Sec-Fetch-Dest" to "iframe"
                )
            )
        } catch (e: Exception) {
            dbg("istek hatası ($name): ${e.message}")
            return viaWebView(name, url, referer, callback)
        }

        val finalUrl = res.url
        log("fetch kod=${res.code} final=$finalUrl")
        if (finalUrl.isNotBlank() && finalUrl != url) {
            if (handleEmbed(finalUrl, label, referer, subtitleCallback, callback, depth + 1)) return true
        }

        if (depth == 0) dumpBody(name, res.text)

        var text = expand(res.text)
        val embedReferer = originOf(url)

        // 1) Sayfada doğrudan medya var mı
        var medias = mediaRegex.findAll(text).map { it.value }.distinct().toList()
        if (medias.isEmpty()) {
            // 2) JS'in çağırdığı API'ler
            val api = probeApis(res.text, url, referer)
            if (api.isNotBlank()) {
                text += "\n$api"
                medias = mediaRegex.findAll(text).map { it.value }.distinct().toList()
            }
        }
        log("medya bulundu=${medias.size} $medias")
        if (medias.isNotEmpty()) {
            medias.forEach { emitMedia(name, it, embedReferer, callback) }
            return true
        }

        // 3) Sayfadaki iframe'ler + metin içindeki tüm dış adresler (Vidmoly, matrudas vb.)
        val next = LinkedHashSet<String>()
        res.document.select("iframe").forEach { f ->
            val src = (f.attr("src").ifBlank { f.attr("data-src") }).trim()
            if (src.isNotBlank() && !src.startsWith("data:") && !src.startsWith("about:")) {
                runCatching { URI(url).resolve(src).toString() }.getOrNull()?.let { next.add(it) }
            }
        }
        next.addAll(candidates(text, url))

        var any = false
        for (n in next.take(6)) {
            if (badFrameHosts.any { n.contains(it, true) }) continue
            log("aday: $n")
            if (handleEmbed(n, label, url, subtitleCallback, callback, depth + 1)) {
                any = true
                break
            }
        }
        if (any) return true

        // 4) Son çare: gerçek tarayıcı motoru (JS çalıştır, ağdan medya adresini yakala)
        dbg("statik çıkarım başarısız ($name) kod=${res.code} aday=${next.size} -> WebView")
        return viaWebView(name, url, referer, callback)
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
        val firstDoc = app.get("$base/", referer = "$mainUrl/").document

        val pages = LinkedHashMap<String, String>()
        pages["$base/"] = "Vidmoly"
        pages["$base/2/"] = "FASTM"
        pages["$base/3/"] = "BRONZ"

        // Sitede başka "part" sayfaları varsa onları da ekle
        firstDoc.select(
            ".keremiya_part a, div.keremiya_part a, .pagination a, .post-page-numbers, .sources a, .part a"
        ).forEach { a ->
            val href = fixUrlNull(a.attr("href"))
            if (href != null) {
                val h = href.trimEnd('/')
                val known = pages.keys.any { it.trimEnd('/') == h } || h == "$base/1".trimEnd('/')
                if (h.startsWith(base) && h != base && !known) {
                    pages[href] = a.text().trim()
                }
            }
        }
        log("kaynak sayfa=${pages.size}: ${pages.entries.joinToString(" | ") { "${it.value}:${it.key}" }}")

        var found = false
        val seen = HashSet<String>()

        for ((pageUrl, label) in pages) {
            val doc = if (pageUrl == "$base/") {
                firstDoc
            } else {
                try {
                    app.get(pageUrl, referer = "$base/").document
                } catch (e: Exception) {
                    dbg("part sayfası açılamadı $pageUrl: ${e.message}")
                    continue
                }
            }

            val frames = extractFrames(doc)
            log("[$label] iframe=${frames.size}: ${frames.joinToString(" | ")}")

            for (frame in frames) {
                if (!seen.add(frame)) continue
                val ok = try {
                    handleEmbed(frame, label, pageUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    dbg("handleEmbed istisna ($label): ${e.message}")
                    false
                }
                if (ok) found = true else dbg("BAŞARISIZ [$label]: $frame")
            }
        }

        // Hiç kaynak yoksa, nedenini gösteren satırları listeye ekle (gerçek kaynak varsa eklenmez)
        if (!found && DEBUG) {
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
