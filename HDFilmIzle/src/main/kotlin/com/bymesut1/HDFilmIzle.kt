package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class HDFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.live"
    override var name = "HDFilmİzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val cf = CloudflareKiller()
    @Volatile private var needCf = false

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    // Tanı kayıtları logcat'e "HDFDbg" etiketiyle yazılır
    private fun d(m: String) {
        try { android.util.Log.d("HDFDbg", m.replace("\n", " ").take(900)) } catch (e: Throwable) { }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/tur/aile-1/" to "Aile Filmleri",
        "$mainUrl/tur/aksiyon-1/" to "Aksiyon Filmleri",
        "$mainUrl/tur/animasyon-1/" to "Animasyon Filmleri",
        "$mainUrl/tur/belgesel-1/" to "Belgesel Filmleri",
        "$mainUrl/tur/bilim-kurgu-1/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/dram-1/" to "Dram Filmleri",
        "$mainUrl/tur/fantastik-1/" to "Fantastik Filmleri",
        "$mainUrl/tur/gerilim-1/" to "Gerilim Filmleri",
        "$mainUrl/tur/gizem-1/" to "Gizem Filmleri",
        "$mainUrl/tur/komedi-1/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-1/" to "Korku Filmleri",
        "$mainUrl/tur/macera-1/" to "Macera Filmleri",
        "$mainUrl/tur/muzik-1/" to "Müzik Filmleri",
        "$mainUrl/tur/romantik-1/" to "Romantik Filmler",
        "$mainUrl/tur/savas-1/" to "Savaş Filmleri",
        "$mainUrl/tur/suc-1/" to "Suç Filmleri",
        "$mainUrl/tur/tarih-1/" to "Tarih Filmleri",
        "$mainUrl/tur/vahsi-bati-1/" to "Vahşi Batı Filmleri",
        "$mainUrl/tur/yerli-film-izle-1/" to "Yerli Filmler"
    )

    // ---------------- Yardımcılar ----------------

    private fun hdr(): Map<String, String> = mapOf("User-Agent" to ua)

    private fun isChallenge(code: Int, body: String): Boolean =
        code == 403 || code == 503 ||
            body.contains("<title>Just a moment", true) ||
            body.contains("cf-browser-verification", true) ||
            body.contains("/cdn-cgi/challenge-platform/h/b/orchestrate", true)

    // Site Cloudflare kontrolü isterse sonraki istekler doğrudan CloudflareKiller ile atılır
    private suspend fun get(url: String, referer: String = "$mainUrl/", t: Long = 15L) =
        try {
            if (needCf) throw Exception("cf")
            val r = app.get(url, headers = hdr(), referer = referer, timeout = t)
            if (isChallenge(r.code, r.text)) {
                needCf = true
                throw Exception("cf")
            }
            r
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            app.get(url, headers = hdr(), referer = referer, interceptor = cf, timeout = 20L)
        }

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.get(1) ?: mainUrl

    private val reIframe = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // ---------------- Ana sayfa ----------------

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.title")?.text()?.trim()
            ?: this.selectFirst("h2")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (title.isBlank()) return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.replace(Regex("-1/?$"), "-$page/")
        val document = get(pageUrl).document
        val home = document.select("div#moviesListResult a.poster, a.poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    // ---------------- Arama ----------------
    // Site aramayı /search/ adresine POST ile yapar ve JSON dizi döndürür

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val text = app.post(
            "$mainUrl/search/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to ua),
            referer = "$mainUrl/",
            data = mapOf("query" to query)
        ).text.trim()

        val out = ArrayList<SearchResponse>()
        try {
            val arr = if (text.startsWith("[")) JSONArray(text)
            else JSONObject(text).let { it.optJSONArray("results") ?: it.optJSONArray("data") ?: JSONArray() }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("name").trim()
                val href = fixUrlNull(o.optString("slug")) ?: continue
                if (title.isBlank()) continue
                val poster = fixUrlNull(
                    o.optString("thumb_url").ifBlank { o.optString("thumbUrl") }
                        .ifBlank { o.optString("thumb_webp") }.ifBlank { o.optString("thumbWebp") }
                )
                out.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
            }
        } catch (e: Exception) {
            d("arama ayrıştırma hatası: ${e.javaClass.simpleName} ${e.message}")
        }
        return newSearchResponseList(out, false)
    }

    // ---------------- Film sayfası ----------------

    override suspend fun load(url: String): LoadResponse? {
        val document = get(url).document
        val orgTitle = document.selectFirst("div.page-title h1")?.text()?.trim().orEmpty()
        val altTitle = document.selectFirst("div.page-title small.text-muted.alt-name")?.text()?.trim().orEmpty()
        val title = if (altTitle.isNotEmpty() && orgTitle != altTitle) "$orgTitle - $altTitle" else orgTitle

        val poster = fixUrlNull(document.selectFirst("picture.poster-auto img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("picture.poster-auto img")?.attr("src"))
        val tags = document.select("div.pb-2.genres a").map { it.text().trim() }.filter { it.isNotBlank() }

        // Yıl, "(2021)" biçiminde; aynı sınıfı paylaşan diğer küçük yazılar atlanır
        val year = document.select("div.page-title small.text-muted")
            .mapNotNull { Regex("""\(?(\d{4})\)?""").matchEntire(it.text().trim())?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull()

        val description = document.selectFirst("article.text-white > p")?.text()?.trim()

        val actors = document.select("div.stories-wrapper a").mapNotNull {
            val nm = it.selectFirst("div.story-item-title")?.text()?.trim().orEmpty()
            if (nm.isBlank()) null
            else ActorData(actor = Actor(nm, fixUrlNull(it.select("img").attr("data-src"))))
        }

        val recommendations = document.select("div#swiper-wrapper-benzer").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPoster = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newMovieSearchResponse(recName, recHref, TvType.Movie) { this.posterUrl = recPoster }
        }

        val trailer = document.selectFirst("div.nav-link")?.attr("data-trailer")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            if (year != null) this.year = year
            this.plot = description
            if (tags.isNotEmpty()) this.tags = tags
            if (actors.isNotEmpty()) this.actors = actors
            if (recommendations.isNotEmpty()) this.recommendations = recommendations
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
    }

    // ---------------- Oynatma: kaynak listesi ----------------

    private data class Part(val name: String, val lang: String, val url: String)

    // "[" ile başlayan JavaScript dizisini, tırnakları dikkate alarak eşleşen "]" işaretine kadar keser
    private fun arrayAt(t: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        var q = '"'
        for (i in start until t.length) {
            val c = t[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == q) inStr = false
            } else if (c == '"' || c == '\'') {
                inStr = true
                q = c
            } else if (c == '[') {
                depth++
            } else if (c == ']') {
                depth--
                if (depth == 0) return t.substring(start, i + 1)
            }
        }
        return null
    }

    private fun langLabel(l: String): String {
        val s = l.lowercase()
        return when {
            s.isBlank() -> "Kaynak"
            s.contains("dub") -> "Türkçe Dublaj"
            s.contains("alt") || s.contains("sub") -> "Türkçe Altyazılı"
            else -> l.trim()
        }
    }

    private fun normUrl(u0: String): String {
        val u = u0.trim().replace("\\/", "/").replace("&amp;", "&")
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl + u
            else -> u
        }
    }

    // Sayfadaki "let parts = [...]" listesi: her parçanın adı, dili ve iframe içeren HTML'i var
    private fun collectParts(doc: Document): List<Part> {
        val out = ArrayList<Part>()
        for (sc in doc.select("script")) {
            val t = sc.data()
            val st = t.indexOf("let parts = [")
            if (st < 0) continue
            val js = t.indexOf('[', st)
            val json = arrayAt(t, js) ?: continue
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val data = o.optString("data")
                    val raw = reIframe.find(data)?.groupValues?.get(1)
                        ?: Regex("""https?://[^\s"'<>]+""").find(data)?.value
                        ?: continue
                    out.add(Part(o.optString("name").ifBlank { "Kaynak" }, langLabel(o.optString("lang")), normUrl(raw)))
                }
            } catch (e: Exception) {
                d("parts ayrıştırma hatası: ${e.javaClass.simpleName} ${e.message}")
            }
            if (out.isNotEmpty()) break
        }
        d("parts=${out.size} ${out.joinToString(",") { it.name }}")
        return out
    }

    // ---------------- Oynatma: kaynakları video bağlantısına çevirme ----------------

    // Doğrudan mp4 adresi
    private suspend fun emitMp4(u: String, label: String, src: String, cb: (ExtractorLink) -> Unit): Boolean {
        val h = mapOf("User-Agent" to ua, "Referer" to "${originOf(u)}/")
        cb(
            newExtractorLink(name, "$label - $src", u, ExtractorLinkType.VIDEO) {
                this.referer = "${originOf(u)}/"
                this.quality = Qualities.Unknown.value
                this.headers = h
            }
        )
        return true
    }

    // Oynatıcıda otomatik "oynat" tıklaması yapan betik (video isteği ancak oynatınca atılıyor)
    private val clickScript = """
        (function(){
          var n = 0;
          var t = setInterval(function(){
            try {
              var sels = ['.jw-icon-display','.jw-display-icon-container','.vjs-big-play-button',
                '.plyr__control--overlaid','button[aria-label*="Play"]','.play-button','#play','.play',
                '[class*="play"]','[id*="play"]','video'];
              sels.forEach(function(s){
                document.querySelectorAll(s).forEach(function(e){ try { e.click(); } catch(x){} });
              });
              document.querySelectorAll('video').forEach(function(v){ try { v.muted = true; v.play(); } catch(x){} });
              var ev = new MouseEvent('click', {bubbles:true, cancelable:true, view:window,
                clientX: window.innerWidth/2, clientY: window.innerHeight/2});
              var el = document.elementFromPoint(window.innerWidth/2, window.innerHeight/2);
              if (el) el.dispatchEvent(ev);
              if (document.body) document.body.click();
            } catch(e) {}
            if (++n > 20) clearInterval(t);
          }, 1000);
        })();
    """.trimIndent()

    // Hiçbir çözücü sonuç vermezse: oynatıcı sayfası görünmez bir WebView'de açılır, ağdan video adresi yakalanır
    private suspend fun webViewVideo(u: String, label: String, src: String, cb: (ExtractorLink) -> Unit): Boolean {
        val cap = try {
            app.get(
                u,
                interceptor = WebViewResolver(
                    Regex("""(?i)(\.m3u8|\.mp4|/master\.txt|/playlist\.txt)"""),
                    userAgent = null,
                    useOkhttp = false,
                    script = clickScript,
                    timeout = 25_000L
                ),
                referer = "$mainUrl/",
                timeout = 35L
            ).url.takeIf { it != u }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("webview hata ${e.javaClass.simpleName}")
            null
        } ?: return false
        d("webview yakalama: ${cap.take(120)}")
        // WebView'in gördüğü adres gerçekten oynatıcının kullandığı adrestir; canlılık testi uygulanmaz
        return if (cap.contains(".mp4", true) && !cap.contains(".m3u8", true)) emitMp4(cap, label, src, cb)
        else emitHls(cap, label, src, originOf(u), cb, probe = false)
    }

    // Bir kaynağı adresine göre uygun çözücüyle açar
    private suspend fun resolve(
        p: Part,
        subCb: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        var u = p.url
        val isVidrame = p.name.equals("Vidrame", true) || u.contains("vidrame", true)
        // VidRame oynatıcısı otomatik oynatma parametresiyle (ap=1) açılır
        if (isVidrame) {
            if (!u.contains("ap=1")) u = if (u.contains("?")) "$u&ap=1" else "$u?ap=1"
        }
        return when {
            isVidrame -> extractVidrame(u, p.lang, subCb, cb) || webViewVideo(u, p.lang, p.name, cb)
            u.contains("vidmoly", true) || u.contains("moly", true) -> extractVidmoly(u, p.lang, cb)
            u.contains("ok.ru", true) -> extractOkRu(u, p.lang, cb) || viaExtractor(u, p.lang, subCb, cb)
            u.contains(".m3u8", true) -> emitHls(u, p.lang, p.name, originOf(u), cb)
            u.contains(".mp4", true) -> emitMp4(u, p.lang, p.name, cb)
            else -> extractGeneric(u, p.lang, p.name, 0, cb) ||
                viaExtractor(u, p.lang, subCb, cb) ||
                webViewVideo(u, p.lang, p.name, cb)
        }
    }

    private fun langRank(l: String): Int = when (l) {
        "Türkçe Dublaj" -> 0
        "Türkçe Altyazılı" -> 1
        else -> 2
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        d("data » $data")
        val document = get(data).document
        var parts = collectParts(document)

        // "parts" listesi yoksa sayfadaki iframe'ler denenir
        if (parts.isEmpty()) {
            parts = document.select("iframe").mapNotNull { el ->
                el.attr("data-src").ifBlank { el.attr("src") }.takeIf { it.isNotBlank() }
            }.distinct().map { Part("Kaynak", "Kaynak", normUrl(it)) }
        }
        if (parts.isEmpty()) {
            d("kaynak bulunamadı")
            return false
        }

        // Çıkan bağlantılar önce toplanır, sonra sıralanıp verilir: Dublaj, Altyazı, diğerleri; sayfadaki sırayla
        val out = java.util.Collections.synchronizedList(ArrayList<Pair<Int, ExtractorLink>>())
        val seen = ConcurrentHashMap.newKeySet<String>()
        val oks = parts.withIndex().toList().amap { (i, p) ->
            if (!seen.add(p.url)) return@amap false
            val rank = langRank(p.lang) * 1000 + i
            val ok = try {
                resolve(p, subtitleCallback) { l -> out.add(rank to l) }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("çözüm istisna ${p.name}: ${e.javaClass.simpleName}")
                false
            }
            d("${p.lang} ${p.name} ${p.url.take(120)} -> $ok")
            ok
        }

        val items = synchronized(out) { ArrayList(out) }
        items.sortedWith(compareBy<Pair<Int, ExtractorLink>>({ it.first }, { -it.second.quality })).forEach { callback(it.second) }
        return oks.any { it }
    }

    // ---- Vidrame (vidrame.live / vidrame.pro ...) ----
    // Oynatıcı sayfasındaki "sources: [{ file: ... }]" XOR ile gizlenmiş:
    //   file: (function(d,k){...})([sayılar], "anahtar")
    // Eski sürümde ise: file: EE.dd("...") (base64 + ROT13 + ters çevirme)
    // Altyazılar "configs.tracks" içinde aynı XOR yöntemiyle gizli.

    // Sayı dizisini anahtarla çözer: karakter = veri xor anahtar[i % uzunluk] xor ((i*17 + 13) and 255)
    private fun vrDecodeXor(data: List<Int>, key: String): String {
        val sb = StringBuilder(data.size)
        data.forEachIndexed { i, v ->
            val k = key[i % key.length].code
            sb.append(((v xor k) xor ((i * 17 + 13) and 255)).toChar())
        }
        return sb.toString()
    }

    // Eski biçim: URL-safe base64 -> UTF-8 metin -> ROT13 -> ters çevir
    private fun vrDecodeOld(s: String): String {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        val raw = String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8)
        val rot = StringBuilder(raw.length)
        for (c in raw) {
            rot.append(
                when (c) {
                    in 'a'..'z' -> (((c - 'a') + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> (((c - 'A') + 13) % 26 + 'A'.code).toChar()
                    else -> c
                }
            )
        }
        return rot.toString().reversed()
    }

    private fun vrParseXor(script: String): String? {
        val m = Regex(
            """file:\s*\(function\(d,k\).*?\}\)\(\s*\[([0-9,\s]+)\]\s*,\s*"([^"]+)"\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(script) ?: return null
        val nums = m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
        val key = m.groupValues[2]
        if (nums.isEmpty() || key.isEmpty()) return null
        return vrDecodeXor(nums, key)
    }

    private fun vrSubtitles(script: String, subCb: (SubtitleFile) -> Unit) {
        val tracks = Regex("""configs\.tracks\s*=\s*(\[[\s\S]*?\]);""").find(script)?.groupValues?.get(1) ?: return
        val re = Regex(
            """"kind"\s*:\s*"captions"\s*,\s*"label"\s*:\s*"([^"]*)"\s*,\s*"language"\s*:\s*"([^"]*)"\s*,\s*"fx"\s*:\s*\{\s*"d"\s*:\s*\[([^\]]*)\]\s*,\s*"k"\s*:\s*"([^"]+)""""
        )
        for (m in re.findAll(tracks)) {
            try {
                val label = m.groupValues[1]
                val language = m.groupValues[2]
                val nums = m.groupValues[3].split(",").mapNotNull { it.trim().toIntOrNull() }
                val key = m.groupValues[4]
                if (nums.isEmpty() || key.isEmpty()) continue
                val url = vrDecodeXor(nums, key)
                if (url.startsWith("http")) {
                    subCb(SubtitleFile(label.ifBlank { language }, url))
                }
            } catch (e: Exception) {
                d("vidrame altyazı hatası: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun vrFromScript(script: String, subCb: (SubtitleFile) -> Unit): String? {
        try { vrSubtitles(script, subCb) } catch (e: Throwable) { }
        val xor = try { vrParseXor(script) } catch (e: Throwable) { null }
        if (xor != null && xor.startsWith("http")) return xor
        val old = Regex("""file:\s*EE\.dd\("([^"]+)"\)""").find(script)?.groupValues?.get(1)
        if (old != null) {
            val dec = try { vrDecodeOld(old) } catch (e: Throwable) { null }
            if (dec != null && dec.startsWith("http")) return dec
        }
        return null
    }

    private suspend fun extractVidrame(
        u: String,
        label: String,
        subCb: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(u)
        val vh = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.8"
        )
        // Sayfa önce film sitesinin Referer'i ile, olmazsa oynatıcının kendi adresiyle istenir
        var html: String? = null
        for (ref in listOf("$mainUrl/", "$origin/")) {
            html = try {
                app.get(u, headers = vh, referer = ref, timeout = 15L).text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("vidrame sayfa hata ${e.javaClass.simpleName} ref=$ref")
                null
            }
            if (html != null && html.contains("sources:") && html.contains("file:")) break
        }
        if (html.isNullOrBlank()) return false

        val doc = org.jsoup.Jsoup.parse(html)
        val script = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("sources:") && it.contains("file:") }
        if (script == null) {
            d("vidrame kaynak betiği yok uzunluk=${html.length}")
            return false
        }
        val m3u8 = vrFromScript(script, subCb)
        if (m3u8.isNullOrBlank()) {
            d("vidrame m3u8 çözülemedi betik=${script.replace(Regex("""\s+"""), " ").take(300)}")
            return false
        }
        d("vidrame m3u8: ${m3u8.take(120)}")

        // Bağlantı hangi Referer ile canlıysa o verilir; hiçbiri doğrulanamazsa oynatıcı adresiyle yine de verilir
        for (o in listOf(origin, mainUrl)) {
            if (emitHls(m3u8, label, "Vidrame", o, cb)) return true
        }
        return emitHls(m3u8, label, "Vidrame", origin, cb, probe = false)
    }

    // ---- Gizlenmiş adresleri açan yardımcılar ----

    // \xHH, \uHHHH kaçışlarını ve \/ işaretlerini düzeltir
    private fun deobf(s: String): String {
        var t = s.replace("\\/", "/").replace("&amp;", "&")
        t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        return t.replace("\\/", "/")
    }

    // Tırnak içindeki uzun base64 metinleri çözer; içinde http geçenleri geri verir
    private fun b64Candidates(s: String): String {
        val sb = StringBuilder()
        var n = 0
        for (m in Regex("""["']([A-Za-z0-9+/_\-]{40,}={0,2})["']""").findAll(s)) {
            if (n++ > 60) break
            try {
                val dec = String(b64u(m.groupValues[1]), Charsets.UTF_8)
                if (dec.contains("http", true) || dec.contains(".m3u8", true) || dec.contains(".mp4", true)) {
                    sb.append(dec).append('\n')
                }
            } catch (e: Throwable) { }
        }
        // atob("...") çağrıları
        for (m in Regex("""atob\(\s*["']([^"']+)["']\s*\)""").findAll(s)) {
            try {
                sb.append(String(b64u(m.groupValues[1]), Charsets.UTF_8)).append('\n')
            } catch (e: Throwable) { }
        }
        return sb.toString()
    }

    private fun absUrl(u: String, origin: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> origin + u
        else -> u
    }

    private fun findM3u8(h: String, origin: String): String? {
        val r = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(h)?.groupValues?.get(1)
            ?: Regex("""["'](https?:(?://|\\/\\/)[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(h)?.groupValues?.get(1)
            ?: Regex("""["'](/[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(h)?.groupValues?.get(1)
            ?: Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").find(h)?.groupValues?.get(1)
        return r?.let { absUrl(it, origin) }
    }

    // Uzantısı olmayan ama anahtar adından anlaşılan akış adresleri (probe ile doğrulanır)
    private fun findKeyedStream(h: String, origin: String): String? {
        val r = Regex(
            """["']?(?:hls|hlsUrl|hls_url|m3u8|stream_url|streaming_url|streamUrl|video_url|videoUrl|playlist)["']?\s*[:=]\s*["']((?:https?:)?//[^"'\s<>]+|/[^"'\s<>]+)["']""",
            RegexOption.IGNORE_CASE
        ).find(h)?.groupValues?.get(1)
        return r?.let { absUrl(it, origin) }
    }

    private fun findMp4(h: String, origin: String): String? {
        val r = Regex("""(?:file|src|source|video_url|videoUrl)["']?\s*[:=]\s*["']((?:https?:)?//[^"'\s<>]+\.mp4[^"'\s<>]*)["']""").find(h)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"'\s<>]+\.mp4[^"'\s<>]*)["']""").find(h)?.groupValues?.get(1)
        return r?.let { absUrl(it, origin) }
    }

    // Tanınmayan oynatıcılar: sayfada .m3u8 / .mp4 ya da iç iframe aranır
    private suspend fun extractGeneric(u: String, label: String, src: String, depth: Int, cb: (ExtractorLink) -> Unit): Boolean {
        if (depth > 2) return false
        val origin = originOf(u)
        val html = try {
            app.get(u, headers = mapOf("User-Agent" to ua, "Sec-Fetch-Dest" to "iframe"), referer = "$mainUrl/", timeout = 12L).text
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("generic hata ${e.javaClass.simpleName} $u")
            return false
        }
        val unp = try {
            if (html.contains("eval(function(p,a,c,k,e,")) unpackJs(html) else ""
        } catch (e: Throwable) {
            ""
        }
        val b64 = try { b64Candidates(html + "\n" + unp) } catch (e: Throwable) { "" }
        val hay = deobf(unp + "\n" + html + "\n" + b64)

        val m3u8 = findM3u8(hay, origin)
        if (!m3u8.isNullOrBlank() && emitHls(m3u8, label, src, origin, cb)) return true

        val mp4 = findMp4(hay, origin)
        if (!mp4.isNullOrBlank()) return emitMp4(mp4, label, src, cb)

        val keyed = findKeyedStream(hay, origin)
        if (!keyed.isNullOrBlank() && emitHls(keyed, label, src, origin, cb)) return true

        val inner = reIframe.find(hay)?.groupValues?.get(1)
        if (inner != null) {
            val fu = when {
                inner.startsWith("//") -> "https:$inner"
                inner.startsWith("/") -> origin + inner
                else -> inner
            }
            if (fu != u && fu.startsWith("http")) {
                if (extractGeneric(fu, label, src, depth + 1, cb)) return true
            }
        }

        // Bulunamadıysa kesin çözüm için sayfanın özetini logla
        val flat = html.replace(Regex("""\s+"""), " ")
        d("generic yok $u uzunluk=${html.length} baş='${flat.take(200)}'")
        for (k in listOf("m3u8", "mp4", "atob", "eval(", "sources", "file:", "fetch(", "/api/", "player")) {
            val i = flat.indexOf(k, ignoreCase = true)
            if (i >= 0) d("generic ipucu [$k] ...${flat.substring(maxOf(0, i - 60), minOf(flat.length, i + 140))}")
        }
        return false
    }

    private fun heightOf(w: Int): Int = when {
        w >= 3000 -> 2160
        w >= 2300 -> 1440
        w >= 1800 -> 1080
        w >= 1200 -> 720
        w >= 800 -> 480
        w >= 600 -> 360
        else -> 240
    }

    private suspend fun fetchTxt(u: String, h: Map<String, String>): String? {
        for (att in 0..1) {
            try {
                return app.get(u, headers = h, timeout = 10L).text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }
        return null
    }

    // Bağlantı gerçekten canlı mı: master liste geçerli olmalı ve ilk alt liste gerçek parçalar içermeli.
    // Ölü bağlantılar kaynak listesine hiç konmaz. Ayrıca çözünürlük (genişliğe göre) okunur.
    private suspend fun probeHls(m3u8: String, headers: Map<String, String>): Pair<Boolean, Int?> {
        val t = fetchTxt(m3u8, headers) ?: return false to null
        if (!t.contains("#EXTM3U")) return false to null
        val w = Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(t).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        val lines = t.lines().map { it.trim() }
        val si = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (si >= 0) {
            val v = lines.drop(si + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            if (v != null) {
                val vu = when {
                    v.startsWith("http") -> v
                    v.startsWith("/") -> originOf(m3u8) + v
                    else -> m3u8.substringBefore("?").substringBeforeLast('/') + "/" + v
                }
                val vt = fetchTxt(vu, headers)
                // Ağ hatası/zaman aşımı tek başına "ölü" sayılmaz; sadece alt liste geçerli bir liste değilse elenir
                if (vt != null && !vt.contains("#EXTM3U")) return false to null
            }
        }
        return true to w?.let { heightOf(it) }
    }

    // CDN bağlantıları Referer/Origin olmadan, süresi dolunca ya da başka ağdan açılınca 404 verir.
    // Bu yüzden oynatıcı sayfasının Referer ve Origin'i ile verilir. Ölü bağlantı verilmez (false döner).
    // probe=false ise (WebView'in yakaladığı ya da çözülen adres) test başarısız olsa bile bağlantı verilir.
    private suspend fun emitHls(
        m3u8: String,
        label: String,
        src: String,
        origin: String,
        callback: (ExtractorLink) -> Unit,
        probe: Boolean = true
    ): Boolean {
        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        val (alive, q) = probeHls(m3u8, headers)
        if (!alive && probe) {
            d("$src ölü bağlantı atlandı: ${m3u8.take(100)}")
            return false
        }
        val qName = if (q != null) " (${q}p)" else ""
        callback(
            newExtractorLink(name, "$label - $src$qName", m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = q ?: Qualities.Unknown.value
                this.headers = headers
            }
        )
        return true
    }

    // ---- Ok.Ru: videoembed sayfasındaki data-options içinde HLS ve mp4 adresleri var ----
    private suspend fun extractOkRu(u: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(u)?.groupValues?.get(1)
            ?: Regex("""/video/(\d+)""").find(u)?.groupValues?.get(1) ?: return false
        val html = try {
            app.get("https://ok.ru/videoembed/$id", headers = mapOf("User-Agent" to ua), referer = "$mainUrl/", timeout = 10L).text
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("okru sayfa hata ${e.javaClass.simpleName}")
            return false
        }
        val opts = org.jsoup.Jsoup.parse(html).selectFirst("[data-options]")?.attr("data-options")
        if (opts.isNullOrBlank()) {
            d("okru data-options yok uzunluk=${html.length}")
            return false
        }
        val meta = try {
            val fv = JSONObject(opts).getJSONObject("flashvars")
            val m = fv.opt("metadata")
            if (m is JSONObject) m else JSONObject(m.toString())
        } catch (e: Exception) {
            d("okru metadata hata ${e.javaClass.simpleName}")
            return false
        }
        val h = mapOf("User-Agent" to ua, "Referer" to "https://ok.ru/")
        val hls = meta.optString("hlsManifestUrl").ifBlank { meta.optString("hlsMasterPlaylistUrl") }.replace("\\/", "/")
        if (hls.startsWith("http") && emitHls(hls, label, "Ok.Ru", "https://ok.ru", callback)) return true
        val vids = meta.optJSONArray("videos") ?: return false
        var ok = false
        for (i in 0 until vids.length()) {
            val o = vids.optJSONObject(i) ?: continue
            val url = o.optString("url").replace("\\/", "/")
            if (!url.startsWith("http")) continue
            val q = when (o.optString("name")) {
                "mobile" -> 144; "lowest" -> 240; "low" -> 360; "sd" -> 480
                "hd" -> 720; "full" -> 1080; "quad" -> 1440; "ultra" -> 2160
                else -> null
            }
            callback(
                newExtractorLink(name, "$label - Ok.Ru${if (q != null) " (${q}p)" else ""}", url, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = q ?: Qualities.Unknown.value
                    this.headers = h
                }
            )
            ok = true
        }
        return ok
    }


    private suspend fun relabel(l: ExtractorLink, label: String): ExtractorLink =
        newExtractorLink(l.source, "$label - ${l.name}", l.url, l.type) {
            this.referer = l.referer
            this.quality = l.quality
            this.headers = l.headers
        }

    // ---- Vidmoly (vidmoly.biz / .to / .me / .net): oynatıcı sayfasında açık .m3u8 var ----
    // Örnek adres: https://vidmoly.biz/embed-m90baavtu9m6.html
    private suspend fun extractVidmoly(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val host0 = Regex("""^https?://([^/]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val id = Regex("""(?:embed-|/w/|/e/|/embed/)([A-Za-z0-9]{6,})""").find(embed)?.groupValues?.get(1)
        // Alan adı sık değişir: önce gelen adres, olmazsa aynı kimlikle diğer alan adları denenir
        val hosts = (listOf(host0) + listOf("vidmoly.biz", "vidmoly.to", "vidmoly.me", "vidmoly.net")).distinct()

        for (host in hosts) {
            val url = when {
                host == host0 -> embed
                id != null -> "https://$host/embed-$id.html"
                else -> continue
            }
            val origin = "https://$host"
            val html = try {
                app.get(
                    url,
                    referer = "$mainUrl/",
                    headers = mapOf("User-Agent" to ua, "Sec-Fetch-Dest" to "iframe"),
                    timeout = 8L
                ).text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("vidmoly $host hata: ${e.javaClass.simpleName}")
                continue
            }
            val clean = html.replace("\\/", "/")
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(clean)?.groupValues?.get(1)
                ?: Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(clean)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) {
                d("vidmoly $host m3u8 yok uzunluk=${html.length} '${html.replace(Regex("""\s+"""), " ").take(60)}'")
                continue
            }
            if (!emitHls(m3u8.replace("&amp;", "&"), label, "Vidmoly", origin, callback)) {
                d("vidmoly $host bağlantı ölü, diğer alan adı denenecek")
                continue
            }
            d("vidmoly $host tamam")
            return true
        }
        return false
    }

    private fun b64u(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return android.util.Base64.decode(t, android.util.Base64.DEFAULT)
    }

    // eval(function(p,a,c,k,e,d){...}('payload',radix,count,'kelime|kelime'.split('|'),0,{})) biçimini açar
    private fun unpackJs(src: String): String {
        val m = Regex(
            """\}\('((?:\\'|[^'])*)',\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\'|[^'])*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(src) ?: return ""
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toIntOrNull() ?: return ""
        val words = m.groupValues[4].split("|")
        fun unbase(t: String): Int {
            var n = 0
            for (c in t) {
                val v = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'z' -> c - 'a' + 10
                    in 'A'..'Z' -> c - 'A' + 36
                    else -> return -1
                }
                n = n * radix + v
            }
            return n
        }
        return Regex("""\b\w+\b""").replace(payload) { t ->
            val i = unbase(t.value)
            if (i >= 0 && i < words.size && words[i].isNotEmpty()) words[i] else t.value
        }
    }


    // Hazır CloudStream çözücüleri (diğer kaynaklar için yedek)
    private suspend fun viaExtractor(
        u: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmp = ArrayList<ExtractorLink>()
        loadExtractor(u, "$mainUrl/", subtitleCallback) { synchronized(tmp) { tmp.add(it) } }
        for (l in tmp) callback(relabel(l, label))
        return tmp.isNotEmpty()
    }
}
