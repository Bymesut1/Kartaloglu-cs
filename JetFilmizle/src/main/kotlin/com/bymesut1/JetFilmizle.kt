package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.now"
    override var name = "JetFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val cf = CloudflareKiller()
    @Volatile private var needCf = false

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    // Tanı kayıtları logcat'e "JetDbg" etiketiyle yazılır
    private fun d(m: String) {
        try { android.util.Log.d("JetDbg", m.replace("\n", " ").take(900)) } catch (e: Throwable) { }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Tüm Filmler",
        "$mainUrl/turkce-dublaj" to "Türkçe Dublaj",
        "$mainUrl/turkce-altyazili" to "Türkçe Altyazılı",
        "$mainUrl/yerli-filmler" to "Yerli Filmler",
        "$mainUrl/orijinal-filmler" to "Orijinal Filmler",
        "$mainUrl/filmler/en-cok-izlenenler" to "En Çok İzlenenler",
        "$mainUrl/filmler/en-cok-begenilenler" to "En Çok Beğenilenler",
        "$mainUrl/tavsiye-filmler" to "Tavsiye Filmler",
        "$mainUrl/oscar" to "Oscar Ödüllü Filmler",
        "$mainUrl/yil/2026" to "2026 Filmleri",
        "$mainUrl/yil/2025" to "2025 Filmleri",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/dram" to "Dram"
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

    private fun abs(u: String): String = if (u.startsWith("http")) u else fixUrl(u)

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.get(1) ?: mainUrl


    private val reIframe = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // /film/lee-cronin-den-mumya
    private val reFilm = Regex("""^(?:https?://[^/]*)?/film/([^/?#]+)""")

    // "Düşler (Dreams) 2025 HD Film Afişi" -> "Düşler"
    private fun cardTitle(alt: String): String {
        var t = alt.replace('\u00A0', ' ').trim()
        t = t.replace(
            Regex("""\s+(?:\d{4}\s+)?(?:HD|Türkçe Dublaj|Türkçe Altyazılı)?\s*(?:1080p\s+)?Film Afişi\s*$""", RegexOption.IGNORE_CASE),
            ""
        )
        t = t.replace(Regex("""\s*\([^()]*\)\s*$"""), "")
        return t.trim()
    }

    // Resim "data-src" içinde olabilir (src boş yer tutucu)
    private fun posterOf(img: Element): String? {
        val ds = img.attr("data-src").trim().ifBlank { img.attr("data-original").trim() }
        val s = img.attr("src").trim()
        val u = when {
            ds.isNotBlank() -> ds
            s.isNotBlank() && !s.startsWith("data:") -> s
            else -> return null
        }
        return abs(u)
    }

    // Sayfadaki film kartları: /film/... adresine giden ve resmi olan bağlantılar
    private fun cards(root: Element?): List<SearchResponse> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in root.select("a[href]")) {
            val href = a.attr("href").trim()
            val m = reFilm.find(href) ?: continue
            val slug = m.groupValues[1]
            if (out.containsKey(slug)) continue
            var found: Element? = a.selectFirst("img")
            var p: Element? = a.parent()
            var up = 0
            while (found == null && p != null && up < 3) {
                found = p.selectFirst("img")
                p = p.parent()
                up++
            }
            val im: Element = found ?: continue
            val title = listOf(cardTitle(im.attr("alt")), cardTitle(a.attr("title")), a.text().trim())
                .firstOrNull { it.isNotBlank() } ?: continue
            val poster = posterOf(im)
            out[slug] = newMovieSearchResponse(title, abs(href), TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return out.values.toList()
    }

    // ---------------- Sayfalama ----------------
    // Sayfalama çubuğundaki "2" bağlantısından sayfa adresi kalıbı öğrenilir ({n} yerine sayfa no gelir)

    private val pageTpl = ConcurrentHashMap<String, String>()

    private fun learnTemplate(base: String, doc: Document) {
        val a2 = doc.select("a[href]").firstOrNull {
            it.text().trim() == "2" && Regex("""[?&=/]2/?(?:[?&].*)?$""").containsMatchIn(it.attr("href"))
        } ?: return
        val href = abs(a2.attr("href"))
        val tpl = href.replace(Regex("""(?<=[?&=/])2(?=/?(?:[?&].*)?$)"""), "{n}")
        if (tpl.contains("{n}")) pageTpl[base] = tpl
    }

    private fun pageUrl(base: String, page: Int): String =
        pageTpl[base]?.replace("{n}", page.toString())
            ?: if (base.contains("?")) "$base&page=$page" else "$base?page=$page"

    // ---------------- Ana sayfa ve arama ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) base else pageUrl(base, page)
        val r = get(url)
        val doc = r.document
        if (page <= 1) learnTemplate(base, doc)
        val list = cards(doc)
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = list.isNotEmpty() && pageTpl.containsKey(base)
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.length < 2) return newSearchResponseList(emptyList(), false)
        val base = "$mainUrl/arama?q=${URLEncoder.encode(q, "UTF-8")}"
        val url = if (page <= 1) base else pageUrl(base, page)
        val r = get(url)
        val doc = r.document
        if (page <= 1) learnTemplate(base, doc)
        val list = cards(doc)
        return newSearchResponseList(list, list.isNotEmpty() && pageTpl.containsKey(base))
    }

    // ---------------- Film sayfası ----------------

    // "AÇIKLAMA" gibi bir başlığın hemen sonrasındaki metin
    private fun sectionText(doc: Document, label: String): String? {
        val h = doc.getElementsMatchingOwnText("(?iu)^\\s*$label\\s*$").firstOrNull() ?: return null
        val sib = h.nextElementSibling() ?: h.parent()?.nextElementSibling()
        val t = sib?.text()?.trim()
        return if (!t.isNullOrBlank() && t.length > 20) t else null
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = reFilm.find(url)?.groupValues?.get(1) ?: url.trimEnd('/').substringAfterLast('/')
        val r = get(url)
        val doc = r.document
        val html = r.text

        val posterImg = doc.select("img").firstOrNull { it.attr("alt").contains("Film Afişi", true) }
        val title = listOf(
            posterImg?.attr("alt")?.let { cardTitle(it) },
            doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace(Regex("""(?i)\s+izle.*$"""), "")?.trim(),
            doc.selectFirst("h1")?.text()?.trim()
        ).firstOrNull { !it.isNullOrBlank() } ?: slug

        val poster = posterImg?.let { posterOf(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val text = doc.text()
        val year = Regex("""Yıl:\s*(\d{4})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b((?:19|20)\d{2})\s+\d+\s*dk""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex("""Süre:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d+)\s*dk""").find(text)?.groupValues?.get(1)?.toIntOrNull()

        val plot = sectionText(doc, "açıklama")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }

        val tags = doc.getElementsMatchingOwnText("(?iu)^\\s*Kategoriler:?\\s*$").firstOrNull()?.parent()
            ?.select("a[href*=/tur/]")?.map { it.text().trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()

        val actors = doc.select("a[href*=/oyuncu/]").map { it.text().trim() }
            .filter { it.length in 2..40 }.distinct().take(20).map { ActorData(actor = Actor(it)) }

        val yt = Regex("""youtube\.com/embed/([\w-]{11})""").find(html)?.groupValues?.get(1)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            if (year != null) this.year = year
            if (tags.isNotEmpty()) this.tags = tags
            if (duration != null) this.duration = duration
            if (actors.isNotEmpty()) this.actors = actors
            if (!yt.isNullOrBlank()) addTrailer("https://www.youtube.com/watch?v=$yt")
        }
    }

    // ---------------- Oynatma: kaynak listesi ----------------

    private data class Src(val lang: String, val name: String, val url: String)

    private val langNames = listOf("Türkçe Dublaj", "Türkçe Altyazılı", "Dil+")

    private val reUrlIn = Regex("""(?:https?:)?//[^\s"'<>\\)]+""")

    // Bir metinden (düz, kaçışlı ya da base64) oynatıcı adresi çıkarır
    private fun urlOf(raw0: String): String? {
        val raw = raw0.trim().replace("\\/", "/").replace("&amp;", "&")
        if (raw.isBlank() || raw == "#") return null
        reUrlIn.find(raw)?.value?.let { return if (it.startsWith("//")) "https:$it" else it }
        if (raw.length >= 16 && Regex("""^[A-Za-z0-9+/=_-]+$""").matches(raw)) {
            try {
                val dec = String(b64u(raw))
                reUrlIn.find(dec)?.value?.let { return if (it.startsWith("//")) "https:$it" else it }
            } catch (e: Exception) {
            }
        }
        return null
    }

    // Buton öğesinin taşıdığı oynatıcı adresi (data-* öznitelikleri, onclick ya da dış bağlantı)
    private fun srcOfEl(el: Element): String? {
        for (at in el.attributes()) {
            if (!at.key.lowercase().startsWith("data-")) continue
            urlOf(at.value)?.let { return it }
            val v = at.value.trim()
            if (v.startsWith("/") && Regex("""(?i)player|embed|source|kaynak|video""").containsMatchIn(v)) return mainUrl + v
        }
        val oc = el.attr("onclick")
        if (oc.isNotBlank()) urlOf(oc)?.let { return it }
        val href = el.attr("href").trim()
        if (href.startsWith("http") && !href.contains("jetfilmizle", true)) return href
        return null
    }

    // Atalarda "dublaj" ya da "altyazı" geçen kimlik/sınıf varsa dil buradan anlaşılır
    private fun langHint(el: Element): String? {
        var p: Element? = el
        var up = 0
        while (p != null && up < 6) {
            val s = (p.id() + " " + p.className() + " " + p.attr("data-lang") + " " + p.attr("data-dil")).lowercase()
            val sub = s.contains("altyaz") || s.contains("subtitle") || Regex("""(^|[\s_-])sub([\s_-]|$)""").containsMatchIn(s)
            val dub = s.contains("dublaj") || Regex("""(^|[\s_-])dub([\s_-]|$)""").containsMatchIn(s)
            if (sub && !dub) return "Türkçe Altyazılı"
            if (dub && !sub) return "Türkçe Dublaj"
            p = p.parent()
            up++
        }
        return null
    }

    // Sayfadaki kaynak butonları. Her dilin paneli "Fragmanı İzle" butonuyla biter; paneller dil
    // butonlarının sırasıyla eşleştirilir (Türkçe Dublaj, Türkçe Altyazılı, Dil+).
    private fun collectSources(doc: Document, html: String): List<Src> {
        val order = ArrayList<String>()
        for (el in doc.allElements) {
            val t = el.ownText().trim()
            if (t in langNames && t !in order) order.add(t)
        }

        val found = ArrayList<Pair<Element, String>>()
        for (el in doc.allElements) {
            if (el.attributes().none { it.key.startsWith("data-") } && el.attr("onclick").isBlank() && !el.hasAttr("href")) continue
            val t = el.text().trim()
            if (t.isEmpty() || t.length > 24) continue
            if (t in langNames || t.contains("Fragman", true)) continue
            if (el.children().size > 2) continue
            val u = srcOfEl(el) ?: continue
            found.add(el to u)
        }
        d("diller=$order butonlu_kaynak=${found.size}")

        val urlByEl = HashMap<Element, String>()
        for ((el, u) in found) urlByEl[el] = u

        val out = ArrayList<Src>()
        var seg = 0
        for (el in doc.allElements) {
            if (el.ownText().trim().contains("Fragmanı İzle", true)) {
                seg++
                continue
            }
            val u = urlByEl[el] ?: continue
            val lang = langHint(el) ?: order.getOrNull(seg) ?: "Kaynak"
            out.add(Src(lang, el.text().trim(), u))
        }

        // Sayfanın içine gömülü bilinen oynatıcı adresleri (örn. varsayılan oynatıcı iframe'i)
        val known = Regex("""(?i)(godrive|workers\.dev|ok\.ru|vidmoly|moly|streamtape|strtape|stape|mail\.ru|dzen|voe\.sx|dood|mixdrop|filemoon|byse|pixeldrain|upstream|sibnet|vmpx|videopark)""")
        val clean = html.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
        val have = out.map { it.url }.toHashSet()
        for (m in Regex("""https?://[^\s"'<>\\]+""").findAll(clean)) {
            val u = m.value
            if (u in have || !known.containsMatchIn(u) || u.contains("youtube", true) || u.contains("jetfilmizle", true)) continue
            have.add(u)
            val nm = when {
                u.contains("dplayer", true) && u.contains("workers.dev", true) -> "Vip"
                u.contains("workers.dev/m/", true) && u.contains("//ok.", true) -> "OPlay"
                u.contains("godrive", true) || u.contains("workers.dev", true) -> "Gold"
                u.contains("vmpx", true) -> "Moly"
                u.contains("ok.ru", true) -> "OkRu"
                u.contains("moly", true) -> "Moly"
                u.contains("tape", true) -> "STape"
                else -> Regex("""^https?://([^/]+)""").find(u)?.groupValues?.get(1) ?: "Kaynak"
            }
            out.add(Src("Kaynak", nm, u))
        }

        // Tanı: ilk birkaç kaynak butonunun HTML'i (sonraki düzeltmeler için)
        var n = 0
        for (el in doc.allElements) {
            val t = el.text().trim()
            if (t in listOf("Gold", "Vip", "OPlay", "OkRu", "Moly", "STape", "Aby", "Ok.ru", "TR-EN") && el.children().size <= 2) {
                d("buton: ${el.outerHtml().take(350)}")
                if (++n >= 6) break
            }
        }
        d("toplam kaynak=${out.size}")
        return out
    }

    // Hiçbir kaynak bulunamazsa sayfayı görünmez bir WebView'de açıp ağ isteklerinden video adresi yakalar
    private suspend fun webViewCapture(pageUrl: String): String? = try {
        val r = app.get(
            pageUrl,
            interceptor = WebViewResolver(
                Regex("""(?i)(\.m3u8|\.mp4|godrive|workers\.dev/v/|ok\.ru/videoembed|vidmoly|streamtape|strtape|byse|filemoon)"""),
                userAgent = null,
                useOkhttp = false,
                timeout = 20_000L
            ),
            timeout = 30L
        )
        r.url.takeIf { it != pageUrl }
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        d("webview hata ${e.javaClass.simpleName}")
        null
    }

    // ---------------- Oynatma: kaynakları video bağlantısına çevirme ----------------

    // godrive gibi doğrudan mp4 veren adresler: ?q=1080 ve diğer kaliteler denenir
    private suspend fun extractDirect(u: String, label: String, src: String, cb: (ExtractorLink) -> Unit): Boolean {
        val h = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
        val q0 = Regex("""[?&]q=(\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull()
        suspend fun add(url: String, q: Int?) {
            cb(
                newExtractorLink(name, "$label - $src${if (q != null) " (${q}p)" else ""}", url, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = q ?: Qualities.Unknown.value
                    this.headers = h
                }
            )
        }
        add(u, q0)
        if (q0 != null) {
            val base = u.substringBefore("?")
            for (q in listOf(1080, 720, 480, 360)) {
                if (q == q0) continue
                val alt = "$base?q=$q"
                val ok = try {
                    val r = app.get(alt, headers = h + mapOf("Range" to "bytes=0-1"), timeout = 8L)
                    val ct = r.headers["Content-Type"].orEmpty()
                    r.code in 200..206 && (ct.startsWith("video", true) || ct.contains("octet-stream", true))
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    false
                }
                if (ok) add(alt, q)
            }
        }
        return true
    }

    // Tanınmayan oynatıcılar: sayfada .m3u8 / .mp4 ya da iç iframe aranır
    private suspend fun extractGeneric(u: String, label: String, src: String, depth: Int, cb: (ExtractorLink) -> Unit): Boolean {
        if (depth > 2) return false
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
        val hay = (unp + "\n" + html).replace("\\/", "/").replace("&amp;", "&")
        val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(hay)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(hay)?.groupValues?.get(1)
        if (!m3u8.isNullOrBlank() && emitHls(m3u8, label, src, originOf(u), cb)) return true

        val mp4 = Regex("""(?:file|src|source)["']?\s*[:=]\s*["'](https?://[^"'\s<>]+\.mp4[^"'\s<>]*)["']""").find(hay)?.groupValues?.get(1)
        if (!mp4.isNullOrBlank()) {
            val h = mapOf("User-Agent" to ua, "Referer" to "${originOf(u)}/")
            cb(
                newExtractorLink(name, "$label - $src", mp4, ExtractorLinkType.VIDEO) {
                    this.referer = "${originOf(u)}/"
                    this.quality = Qualities.Unknown.value
                    this.headers = h
                }
            )
            return true
        }

        val inner = reIframe.find(hay)?.groupValues?.get(1) ?: return false
        val fu = when {
            inner.startsWith("//") -> "https:$inner"
            inner.startsWith("/") -> originOf(u) + inner
            else -> inner
        }
        if (fu == u || !fu.startsWith("http")) return false
        return extractGeneric(fu, label, src, depth + 1, cb)
    }

    // Bir kaynağı (Gold, Vip, OkRu, Moly, STape...) adresine göre uygun çözücüyle açar
    private suspend fun resolve(
        s: Src,
        subCb: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        val u = s.url
        return when {
            u.contains("videopark.top", true) -> extractVideopark(u, s.lang, s.name, cb)
            // Vip / OPlay: workers.dev/m/... sayfası içinde <video><source ...mpegurl> var
            u.contains("workers.dev/m/", true) -> extractWorkerHls(u, s.lang, s.name, 0, cb)
            u.contains("godrive", true) || u.contains("workers.dev", true) || u.contains(".mp4", true) ->
                extractDirect(u, s.lang, s.name, cb)
            // Vidmoly CDN (vmpx.online) master.m3u8: Referer vidmoly olmalı
            u.contains(".m3u8", true) && (u.contains("vmpx", true) || u.contains("vidmoly", true)) ->
                emitHlsAny(u, s.lang, "Vidmoly", vidmolyOrigins, cb)
            u.contains(".m3u8", true) -> emitHls(u, s.lang, s.name, originOf(u), cb)
            u.contains("ok.ru", true) -> extractOkRu(u, s.lang, cb) || viaExtractor(u, s.lang, subCb, cb)
            u.contains("vidmoly", true) || u.contains("moly", true) || u.contains("vmpx", true) -> extractVidmoly(u, s.lang, cb)
            else -> viaExtractor(u, s.lang, subCb, cb) || extractGeneric(u, s.lang, s.name, 0, cb)
        }
    }

    private fun langRank(l: String): Int = when (l) {
        "Türkçe Dublaj" -> 0
        "Türkçe Altyazılı" -> 1
        "Dil+" -> 2
        else -> 3
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val r = get(data)
        val srcs = collectSources(r.document, r.text).toMutableList()
        if (srcs.isEmpty()) {
            val cap = webViewCapture(data)
            d("webview yakalama: $cap")
            if (cap != null) srcs.add(Src("Kaynak", "JetFilmizle", cap))
        }
        if (srcs.isEmpty()) {
            d("kaynak bulunamadı; sayfa uzunluk=${r.text.length} script=${r.document.select("script[src]").map { it.attr("src") }.take(8)}")
            return false
        }

        // Çıkan bağlantılar önce toplanır, sonra sıralanıp verilir: Dublaj, Altyazı, diğerleri; sayfadaki sırayla
        val out = java.util.Collections.synchronizedList(ArrayList<Pair<Int, ExtractorLink>>())
        val seen = ConcurrentHashMap.newKeySet<String>()
        val oks = srcs.withIndex().toList().amap { (i, s) ->
            if (!seen.add(s.url)) return@amap false
            val rank = langRank(s.lang) * 1000 + i
            val ok = try {
                resolve(s, subtitleCallback) { l -> out.add(rank to l) }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("çözüm istisna ${s.name}: ${e.javaClass.simpleName}")
                false
            }
            d("${s.lang} ${s.name} ${s.url.take(120)} -> $ok")
            ok
        }

        if (!oks.any { it } && synchronized(out) { out.isEmpty() }) {
            val cap = webViewCapture(data)
            d("son çare webview: $cap")
            if (cap != null && cap.contains(".m3u8", true)) {
                emitHlsAny(cap, "Kaynak", "JetFilmizle", listOf(originOf(cap), mainUrl), callback)
                return true
            }
        }

        val items = synchronized(out) { ArrayList(out) }
        items.sortedWith(compareBy<Pair<Int, ExtractorLink>>({ it.first }, { -it.second.quality })).forEach { callback(it.second) }
        return oks.any { it }
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
    private suspend fun emitHls(
        m3u8: String,
        label: String,
        src: String,
        origin: String,
        callback: (ExtractorLink) -> Unit,
        force: Boolean = false
    ): Boolean {
        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        val (alive, q) = probeHls(m3u8, headers)
        if (!alive && !force) {
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


    private val vidmolyOrigins = listOf("https://vidmoly.biz", "https://vidmoly.net", "https://vidmoly.to", "https://vidmoly.me")

    // Farklı Referer/Origin'lerle dener; hiçbiri doğrulanamazsa yine de ilkiyle verir ("bağlantı yok" dememesi için)
    private suspend fun emitHlsAny(
        url: String, label: String, src: String, origins: List<String>, cb: (ExtractorLink) -> Unit
    ): Boolean {
        for (o in origins) if (emitHls(url, label, src, o, cb)) return true
        return emitHls(url, label, src, origins.first(), cb, force = true)
    }

    // ---- Vip / OPlay (workers.dev/m/...): HTML sayfa -> <source src=...> (mpegurl) -> m3u8 ----
    private suspend fun extractWorkerHls(
        u: String, label: String, src: String, depth: Int, cb: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 3) return false
        val origins = listOf(mainUrl, originOf(u))
        for (o in origins) {
            val r = try {
                app.get(
                    u,
                    headers = mapOf("User-Agent" to ua, "Sec-Fetch-Dest" to if (depth == 0) "iframe" else "video"),
                    referer = "$o/",
                    timeout = 12L
                )
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("worker hata ${e.javaClass.simpleName} $src")
                continue
            }
            val ct = r.headers["Content-Type"].orEmpty().lowercase()
            val len = r.headers["Content-Length"]?.toLongOrNull() ?: 0L
            val finalUrl = r.url
            // Doğrudan video akışı
            if ((ct.startsWith("video/") && !ct.contains("mpegurl")) || len > 2_000_000L) {
                cb(
                    newExtractorLink(name, "$label - $src", finalUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$o/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to ua, "Referer" to "$o/")
                    }
                )
                return true
            }
            val body = r.text
            // Doğrudan m3u8
            if (body.trimStart().startsWith("#EXTM3U")) {
                return emitHlsAny(finalUrl, label, src, origins, cb)
            }
            // HTML sayfası: <video><source src=...>
            val doc = org.jsoup.Jsoup.parse(body, finalUrl)
            val inner = doc.selectFirst("video source[src], source[src], video[src]")?.absUrl("src")
                ?.takeIf { it.startsWith("http") }
                ?: Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""")
                    .find(body.replace("\\/", "/"))?.groupValues?.get(1)
            if (inner != null && inner != u && inner != finalUrl) {
                d("worker $src ic kaynak: ${inner.take(90)}")
                if (extractWorkerHls(inner, label, src, depth + 1, cb)) return true
                // İç adres alınamadı/doğrulanamadı: mpegurl olarak yine de ver
                return emitHlsAny(inner, label, src, origins, cb)
            }
        }
        return false
    }

    // ---- videopark.top/vip/?id=..  ve  /oplayer/?id=.. ----
    private suspend fun extractVideopark(u: String, label: String, src: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = try {
            app.get(u, headers = mapOf("User-Agent" to ua, "Sec-Fetch-Dest" to "iframe"), referer = "$mainUrl/", timeout = 12L).text
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("videopark hata ${e.javaClass.simpleName}")
            null
        }
        if (html != null) {
            d("videopark $src uzunluk=${html.length} ${html.replace(Regex("""\s+"""), " ").take(500)}")
            val unp = try { if (html.contains("eval(function(p,a,c,k,e,")) unpackJs(html) else "" } catch (e: Throwable) { "" }
            val hay = (unp + "\n" + html).replace("\\/", "/").replace("&amp;", "&")
            val urls = Regex("""https?://[^\s"'<>\\]+""").findAll(hay).map { it.value }.distinct().toList()
            for (x in urls) {
                if (x.contains("videopark.top", true) && !x.contains(".m3u8", true)) continue
                val ok = when {
                    x.contains("workers.dev/m/", true) -> extractWorkerHls(x, label, src, 0, cb)
                    x.contains(".m3u8", true) -> emitHlsAny(x, label, src, listOf(originOf(u), mainUrl), cb)
                    x.contains(".mp4", true) -> extractDirect(x, label, src, cb)
                    x.contains("vidmoly", true) || x.contains("vmpx", true) -> extractVidmoly(x, label, cb)
                    else -> false
                }
                if (ok) return true
            }
            if (extractGeneric(u, label, src, 0, cb)) return true
        }
        // Adres sayfada açık değilse (JS ile geliyorsa) WebView ile yakala
        val cap = webViewHls(u, "$mainUrl/") ?: return false
        return emitHlsAny(cap, label, src, listOf(originOf(u), mainUrl), cb)
    }

    private suspend fun webViewHls(pageUrl: String, referer: String): String? = try {
        val r = app.get(
            pageUrl,
            referer = referer,
            interceptor = WebViewResolver(Regex("""(?i)\.m3u8"""), userAgent = null, useOkhttp = false, timeout = 15_000L),
            timeout = 25L
        )
        r.url.takeIf { it != pageUrl && it.contains(".m3u8", true) }
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        null
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
        // Gömülü sayfa m3u8 vermediyse görünmez WebView ile yakala
        val cap = webViewHls(embed, "$mainUrl/")
        if (cap != null) return emitHlsAny(cap, label, "Vidmoly", vidmolyOrigins, callback)
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
