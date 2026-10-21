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
    private data class SrcCand(val idx: Int, val el: Element, val direct: String?, val internal: String?)

    private val langNames = listOf("Türkçe Dublaj", "Türkçe Altyazılı", "Dil+")

    private val reUrlIn = Regex("""(?:https?:)?//[^\s"'<>\\)]+""")

    // Oynatıcı olamayacak adresler: reklam/analitik/sosyal/varlık dosyaları
    private val junkHost = Regex(
        """(?i)youtube|youtu\.be|googletagmanager|google-analytics|googleapis|gstatic|googlesyndication|doubleclick|facebook|twitter|instagram|cloudflare|cdn-cgi|gravatar|jquery|bootstrap|fontawesome|cdnjs|jsdelivr|unpkg|histats|yandex|adsterra|popads|schema\.org|w3\.org|tmdb\.org|themoviedb|imdb\.com|t\.me|telegram|whatsapp|disqus|addthis|sharethis|gmpg\.org|wordpress|/wp-content/|/wp-includes/"""
    )
    private val assetExt = Regex("""(?i)\.(?:css|js|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|eot|map|xml)(?:\?|#|$)""")

    private fun playerOk(u: String): Boolean {
        if (!u.startsWith("http", true)) return false
        return !junkHost.containsMatchIn(u) && !assetExt.containsMatchIn(u)
    }

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

    private val skipAttrs = setOf(
        "class", "style", "id", "title", "alt", "target", "rel", "type", "role", "tabindex", "name", "width", "height", "href"
    )
    private val reSrcPath = Regex("""(?i)player|embed|source|kaynak|video|ajax|part|film|izle""")

    // Buton öğesinin taşıdığı oynatıcı adresi (herhangi bir öznitelik, onclick ya da dış bağlantı)
    private fun srcOfEl(el: Element): String? {
        for (at in el.attributes()) {
            val k = at.key.lowercase()
            if (k in skipAttrs || k.startsWith("aria-")) continue
            val v = at.value.trim()
            if (v.isBlank()) continue
            urlOf(v)?.let { if (playerOk(it)) return it }
            if (k.startsWith("data-") && v.startsWith("/") && reSrcPath.containsMatchIn(v)) return mainUrl + v
        }
        val oc = el.attr("onclick")
        if (oc.isNotBlank()) {
            urlOf(oc)?.let { if (playerOk(it)) return it }
            Regex("""['"](/[^'"\s]*(?:player|embed|source|kaynak|video|ajax|part)[^'"\s]*)['"]""", RegexOption.IGNORE_CASE)
                .find(oc)?.groupValues?.get(1)?.let { return mainUrl + it }
        }
        val href = el.attr("href").trim()
        if (href.startsWith("http") && !href.contains("jetfilmizle", true) && playerOk(href)) return href
        return null
    }

    // Aynı siteye giden kaynak bağlantısı (örn. /film/dusler/2): sayfası açılıp içindeki oynatıcı bulunur
    private fun internalHref(el: Element, pageUrl: String): String? {
        val h = el.attr("href").trim()
        if (h.isBlank() || h.startsWith("#") || h.startsWith("javascript", true) ||
            h.startsWith("mailto", true) || h.startsWith("tel", true)
        ) return null
        val u = if (h.startsWith("//")) "https:$h" else abs(h)
        if (!u.contains("jetfilmizle", true)) return null
        if (u.trimEnd('/') == pageUrl.trimEnd('/')) return null
        val path = u.replace(Regex("""^https?://[^/]+"""), "").substringBefore("#")
        if (path.isBlank() || path == "/") return null
        val nav = Regex(
            """(?i)^/(?:filmler|kategoriler|giris|kayit|uye|login|register|iletisim|dmca|tur/|yil/|oyuncu/|turkce-dublaj|turkce-altyazili|yerli-filmler|orijinal-filmler|oscar|tavsiye)"""
        )
        if (nav.containsMatchIn(path)) return null
        return u
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

    // Sayfadaki kaynaklar. Her dilin paneli "Fragmanı İzle" butonuyla biter; paneller dil
    // butonlarının sırasıyla eşleştirilir (Türkçe Dublaj, Türkçe Altyazılı, Dil+).
    // Kaynak şu yollarla aranır: buton öznitelikleri / onclick / dış bağlantı, aynı siteye giden kaynak
    // bağlantıları, sayfadaki varsayılan oynatıcı iframe'i, betikteki "vip: url" biçimleri ve bilinen oynatıcı adresleri.
    private fun collectSources(doc: Document, html: String, pageUrl: String): List<Src> {
        val els: List<Element> = doc.allElements
        val order = ArrayList<String>()
        for (el in els) {
            val t = el.ownText().trim()
            if (t in langNames && t !in order) order.add(t)
        }

        // "Fragmanı İzle" düğmeleri panel sınırı; ilk dil düğmesinden son Fragman'a kadar olan alan "kaynak penceresi"
        val fragIdx = ArrayList<Int>()
        for ((i, el) in els.withIndex()) {
            if (el.ownText().trim().contains("Fragmanı İzle", true)) fragIdx.add(i)
        }
        var lastLang = -1
        if (fragIdx.isNotEmpty()) {
            for (i in 0 until fragIdx[0]) {
                if (els[i].ownText().trim() in langNames) lastLang = i
            }
        }
        fun segOf(i: Int): Int = fragIdx.count { it < i }
        fun inWindow(i: Int): Boolean = fragIdx.isNotEmpty() && i > lastLang && i < fragIdx[fragIdx.size - 1]

        val cands = ArrayList<SrcCand>()
        for ((i, el) in els.withIndex()) {
            if (el.attributes().none { it.key.startsWith("data-") } && el.attr("onclick").isBlank() && !el.hasAttr("href")) continue
            val t = el.text().trim()
            if (t.isEmpty() || t.length > 24) continue
            if (t in langNames || t.contains("Fragman", true)) continue
            if (el.children().size > 2) continue
            val direct = srcOfEl(el)
            val inner = if (direct == null && inWindow(i)) internalHref(el, pageUrl) else null
            if (direct == null && inner == null) continue
            cands.add(SrcCand(i, el, direct, inner))
        }
        d("diller=$order fragman=${fragIdx.size} pencere=$lastLang dogrudan=${cands.count { it.direct != null }} ic=${cands.count { it.internal != null }}")

        val out = ArrayList<Src>()
        for (c in cands) {
            val lang = langHint(c.el) ?: order.getOrNull(segOf(c.idx)) ?: "Kaynak"
            out.add(Src(lang, c.el.text().trim(), c.direct ?: c.internal.orEmpty()))
        }
        val have = out.map { it.url }.toHashSet()

        // Varsayılan oynatıcı: sayfadaki iframe (etkin dil ve etkin kaynak düğmesinin adıyla)
        val activeLang = order.firstOrNull() ?: "Kaynak"
        var activeName = "Vip"
        if (fragIdx.isNotEmpty() && lastLang >= 0) {
            for (i in (lastLang + 1) until fragIdx[0]) {
                val el = els[i]
                val t = el.text().trim()
                if (t.isNotEmpty() && t.length <= 16 && t !in langNames &&
                    Regex("""active|selected|current|warning""").containsMatchIn(el.className().lowercase())
                ) {
                    activeName = t
                    break
                }
            }
        }
        var iframes = 0
        for (f in doc.select("div#movie iframe, iframe")) {
            val raw = f.attr("data-src").ifBlank { f.attr("data-lazy-src") }.ifBlank { f.attr("src") }.trim()
            if (raw.isBlank() || raw.startsWith("about:") || raw.startsWith("javascript", true)) continue
            val u = if (raw.startsWith("//")) "https:$raw" else abs(raw)
            if (!playerOk(u) || u in have) continue
            have.add(u)
            out.add(Src(activeLang, activeName, u))
            iframes++
        }

        val clean = html.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")

        // Betikte "vip": "https://..." ya da base64 biçiminde tutulan kaynaklar
        var scriptSrc = 0
        val keyRe = Regex("""(?i)["'](vip|oplay|okru|ok\.ru|stape|moly|gold|aby|pixeldrain|tr-en)["']\s*[:=]\s*["']([^"']{8,}?)["']""")
        for (m in keyRe.findAll(clean)) {
            val u = urlOf(m.groupValues[2]) ?: continue
            if (!playerOk(u) || u in have) continue
            have.add(u)
            out.add(Src(activeLang, m.groupValues[1], u))
            scriptSrc++
        }

        // Sayfanın içine gömülü bilinen oynatıcı adresleri (örn. varsayılan oynatıcı iframe'i)
        val known = Regex("""(?i)(godrive|workers\.dev|ok\.ru|vidmoly|moly|streamtape|strtape|stape|mail\.ru|dzen|voe\.sx|dood|mixdrop|filemoon|byse|pixeldrain|upstream|sibnet)""")
        for (m in Regex("""https?://[^\s"'<>\\]+""").findAll(clean)) {
            val u = m.value
            if (u in have || !known.containsMatchIn(u) || u.contains("youtube", true) || u.contains("jetfilmizle", true)) continue
            have.add(u)
            val nm = when {
                u.contains("godrive", true) || u.contains("workers.dev", true) -> "Gold"
                u.contains("ok.ru", true) -> "OkRu"
                u.contains("moly", true) -> "Moly"
                u.contains("tape", true) -> "STape"
                else -> Regex("""^https?://([^/]+)""").find(u)?.groupValues?.get(1) ?: "Kaynak"
            }
            out.add(Src("Kaynak", nm, u))
        }

        // Hâlâ hiç kaynak yoksa: sayfadaki, reklam/varlık olmayan tüm dış adresler ve base64 gömülü adresler aday olur
        var generic = 0
        if (out.isEmpty()) {
            val hint = Regex("""(?i)player|embed|kaynak|source|video""")
            for (m in Regex("""https?://[^\s"'<>\\]+""").findAll(clean)) {
                val u = m.value.trimEnd(',', ';', ')', ']', '}')
                if (!playerOk(u) || u in have) continue
                if (u.contains("jetfilmizle", true) && !hint.containsMatchIn(u)) continue
                have.add(u)
                val nm = Regex("""^https?://(?:www\.)?([^/]+)""").find(u)?.groupValues?.get(1) ?: "Kaynak"
                out.add(Src(activeLang, nm, u))
                if (++generic >= 8) break
            }
            if (generic == 0) {
                for (m in Regex("""["']([A-Za-z0-9+/=_-]{28,})["']""").findAll(clean).take(300)) {
                    val u = urlOf(m.groupValues[1]) ?: continue
                    if (!playerOk(u) || u in have) continue
                    have.add(u)
                    out.add(Src(activeLang, Regex("""^https?://(?:www\.)?([^/]+)""").find(u)?.groupValues?.get(1) ?: "Kaynak", u))
                    if (++generic >= 6) break
                }
            }
        }
        d("iframe=$iframes betik=$scriptSrc genel=$generic")

        // Tanı: panel yapısı ve ilk birkaç kaynak butonunun HTML'i (sonraki düzeltmeler için)
        if (fragIdx.isNotEmpty()) {
            val fr = els[fragIdx[0]]
            val box = fr.parent()?.parent() ?: fr.parent()
            d("panel: ${box?.outerHtml()?.replace(Regex("""\s+"""), " ")?.take(700)}")
        }
        var n = 0
        for (el in els) {
            val t = el.text().trim()
            if (t in listOf("Gold", "Vip", "OPlay", "OkRu", "Moly", "STape", "Aby", "Ok.ru", "TR-EN") && el.children().size <= 2) {
                d("buton: ${el.outerHtml().take(350)}")
                if (++n >= 6) break
            }
        }
        for (s in out.take(12)) d("kaynak: ${s.lang} | ${s.name} | ${s.url.take(110)}")
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

    // Tanınmayan oynatıcılar / site içi kaynak sayfaları: sayfada .m3u8 / .mp4 ya da iç iframe aranır.
    // İç iframe bulunursa o adres de aynı çözücülerle (OkRu, Vidmoly, hazır çıkarıcılar...) açılır.
    private suspend fun extractGeneric(
        u: String,
        label: String,
        src: String,
        depth: Int,
        subCb: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 2) return false
        val html = try {
            if (u.startsWith(mainUrl, true)) {
                get(u, "$mainUrl/", 12L).text
            } else {
                app.get(u, headers = mapOf("User-Agent" to ua, "Sec-Fetch-Dest" to "iframe"), referer = "$mainUrl/", timeout = 12L).text
            }
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

        for (m in reIframe.findAll(hay)) {
            val inner = m.groupValues[1]
            val fu = when {
                inner.startsWith("//") -> "https:$inner"
                inner.startsWith("/") -> originOf(u) + inner
                else -> inner
            }
            if (fu == u || !fu.startsWith("http") || !playerOk(fu)) continue
            if (resolveUrl(fu, label, src, depth + 1, subCb, cb)) return true
        }
        return false
    }

    // Bir adresi, türüne göre uygun çözücüyle açar
    private suspend fun resolveUrl(
        u: String,
        lang: String,
        nm: String,
        depth: Int,
        subCb: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            u.contains("godrive", true) || u.contains("workers.dev", true) || u.contains(".mp4", true) ->
                extractDirect(u, lang, nm, cb)
            u.contains(".m3u8", true) -> emitHls(u, lang, nm, originOf(u), cb)
            u.contains("ok.ru", true) -> extractOkRu(u, lang, cb) || viaExtractor(u, lang, subCb, cb)
            u.contains("vidmoly", true) || u.contains("moly", true) -> extractVidmoly(u, lang, cb)
            else -> viaExtractor(u, lang, subCb, cb) || extractGeneric(u, lang, nm, depth, subCb, cb)
        }
    }

    // Bir kaynağı (Gold, Vip, OkRu, Moly, STape...) adresine göre uygun çözücüyle açar
    private suspend fun resolve(
        s: Src,
        subCb: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean = resolveUrl(s.url, s.lang, s.name, 0, subCb, cb)

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
        val srcs = collectSources(r.document, r.text, data).toMutableList()
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

        val items = synchronized(out) { ArrayList(out) }
        items.sortedWith(compareBy<Pair<Int, ExtractorLink>>({ it.first }, { -it.second.quality })).forEach { callback(it.second) }
        var any = oks.any { it }

        // Hiçbir bağlantı çıkmadıysa: oynatıcı sayfalarını görünmez WebView'de açıp ağ isteğinden video adresi yakala
        if (!any) {
            var tried = 0
            for (s in srcs) {
                if (tried >= 2) break
                if (s.url.contains("jetfilmizle", true) && !Regex("""(?i)player|embed""").containsMatchIn(s.url)) continue
                tried++
                val cap = webViewCapture(s.url) ?: continue
                d("webview ${s.name}: ${cap.take(120)}")
                val ok = when {
                    cap.contains(".m3u8", true) -> emitHls(cap, s.lang, s.name, originOf(s.url), callback)
                    cap.contains(".mp4", true) -> {
                        val h = mapOf("User-Agent" to ua, "Referer" to "${originOf(s.url)}/")
                        callback(
                            newExtractorLink(name, "${s.lang} - ${s.name}", cap, ExtractorLinkType.VIDEO) {
                                this.referer = "${originOf(s.url)}/"
                                this.quality = Qualities.Unknown.value
                                this.headers = h
                            }
                        )
                        true
                    }
                    else -> false
                }
                if (ok) {
                    any = true
                    break
                }
            }
        }
        return any
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        val (alive, q) = probeHls(m3u8, headers)
        if (!alive) {
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
