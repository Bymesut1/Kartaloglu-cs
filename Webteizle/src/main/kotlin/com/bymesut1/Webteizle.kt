package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Webteizle : MainAPI() {
    override var mainUrl = "https://webteizle.info"
    override var name = "Webteizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    // Site Cloudflare kontrolü isterse devreye girer (önce düz istek atılır)
    private val cf = CloudflareKiller()

    // Hata ayıklama: true iken liste/arama boş döner ya da kaynak bulunamazsa sebep ekranda hata mesajı olarak görünür.
    // Her şey düzelince false yap.
    private val debug = true
    private val dbg = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun d(m: String) { dbg.add(m.replace("\n", " ").take(260)) }
    private fun dbgText(): String = dbg.joinToString(" | ").take(1200)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/trend" to "Trend Filmler",
        "$mainUrl/tr-dublaj-filmler" to "Türkçe Dublaj",
        "$mainUrl/tr-altyazili-filmler" to "Altyazılı Filmler",
        "$mainUrl/tavsiye-filmler" to "Tavsiye Filmler",
        "$mainUrl/odullu-filmler" to "Ödüllü Filmler",
        "$mainUrl/imdb-top-250-izle" to "IMDb Top 250",
        "$mainUrl/imdb/8/" to "IMDb 8+",
        "$mainUrl/yil/2026/" to "2026 Filmleri",
        "$mainUrl/yil/2025/" to "2025 Filmleri",
        "$mainUrl/1080p-film-izle" to "1080p Filmler",
        "$mainUrl/4k-film-izle" to "4K Filmler",
        "$mainUrl/boxset-filmler" to "Boxset"
    )

    // ---------------- Yardımcılar ----------------

    private fun hdr(): Map<String, String> = mapOf("User-Agent" to ua)

    private fun ajaxHdr(referer: String): Map<String, String> = hdr() + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer
    )

    private fun isChallenge(code: Int, body: String): Boolean =
        code == 403 || code == 503 ||
            body.contains("<title>Just a moment", true) ||
            body.contains("cf-browser-verification", true) ||
            body.contains("/cdn-cgi/challenge-platform/h/b/orchestrate", true)

    // Site Cloudflare kontrolü istediyse sonraki istekler doğrudan CloudflareKiller ile atılır
    @Volatile private var needCf = false

    // Sayfadan ve ajax yanıtlarından gelen oturum çerezleri (ASP oturumu) sonraki ajax isteklerine eklenir
    @Volatile private var sessionCookies: Map<String, String> = emptyMap()

    private suspend fun get(url: String, referer: String = "$mainUrl/", t: Long = 15L) =
        try {
            if (needCf) throw Exception("cf")
            val r = app.get(url, headers = hdr(), referer = referer, cookies = sessionCookies, timeout = t)
            if (isChallenge(r.code, r.text)) {
                needCf = true
                throw Exception("cf")
            }
            r
        } catch (e: Exception) {
            app.get(url, headers = hdr(), referer = referer, interceptor = cf, timeout = 20L)
        }

    private suspend fun post(url: String, form: Map<String, String>, referer: String, t: Long = 15L) =
        try {
            val r = app.post(url, data = form, headers = ajaxHdr(referer), cookies = sessionCookies, timeout = t)
            if (isChallenge(r.code, r.text)) throw Exception("cf")
            if (r.cookies.isNotEmpty()) sessionCookies = sessionCookies + r.cookies
            r
        } catch (e: Exception) {
            app.post(url, data = form, headers = ajaxHdr(referer), interceptor = cf, timeout = t)
        }

    private fun rl(t: String): Boolean =
        (t.contains("1015") && t.contains("error", true)) ||
            t.contains("rate limit", true) || t.contains("too many", true)

    // Hız sınırına (Cloudflare 1015) takılırsa bekleyip tekrar dener
    private suspend fun postRl(url: String, form: Map<String, String>, ref: String): String {
        var txt = ""
        for (att in 0..2) {
            txt = try {
                post(url, form, ref).text
            } catch (e: Exception) {
                d("post hata ${e.javaClass.simpleName}")
                ""
            }
            if (!rl(txt)) break
            d("hız sınırı, bekleniyor")
            try { Thread.sleep(5000L + att * 3000L) } catch (e: Exception) { }
        }
        return txt
    }

    private fun abs(u: String): String = if (u.startsWith("http")) u else fixUrl(u)

    // Site windows-1254 kullanıyor; Türkçe harfli aramalar için aynı kodlama
    private fun encQ(q: String): String = try {
        URLEncoder.encode(q, "windows-1254")
    } catch (e: Exception) {
        URLEncoder.encode(q, "UTF-8")
    }

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.get(1) ?: mainUrl

    // /izle/dublaj/eye-for-an-eye   /izle/altyazi/eye-for-an-eye   /hakkinda/eye-for-an-eye
    private val reFilm = Regex("""^(?:https?://[^/]+)?/(?:izle/(?:dublaj|altyazi)|hakkinda)/([^/?#]+)""")

    private fun cleanTitle(t: String): String =
        t.replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "").trim()

    // Listelerde resim "data-src" içinde olabilir (src boş yer tutucu)
    private fun posterOf(img: Element): String? {
        val ds = img.attr("data-src").trim()
        val s = img.attr("src").trim()
        val u = when {
            ds.isNotBlank() -> ds
            s.isNotBlank() && !s.startsWith("data:") -> s
            else -> return null
        }
        return abs(u)
    }

    // Sayfadaki film kartları: film adresine giden ve resmi olan bağlantılar
    private fun cards(root: Element?): List<SearchResponse> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in root.select("a[href]")) {
            val href = a.attr("href").trim()
            val m = reFilm.find(href) ?: continue
            val slug = m.groupValues[1]
            if (out.containsKey(slug)) continue
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: continue
            val title = listOf(
                a.selectFirst(".title")?.text(),
                a.selectFirst(".header")?.text(),
                a.selectFirst(".filmname")?.text(),
                a.attr("title"),
                img.attr("alt")
            ).map { cleanTitle(it.orEmpty()) }.firstOrNull { it.isNotBlank() } ?: continue
            out[slug] = newMovieSearchResponse(title, abs(href), TvType.Movie) {
                this.posterUrl = posterOf(img)
            }
        }
        return out.values.toList()
    }

    // ---------------- Sayfalama ----------------
    // Sayfalama çubuğundaki "2" bağlantısından sayfa adresi kalıbı öğrenilir ({n} yerine sayfa no gelir)

    private val pageTpl = ConcurrentHashMap<String, String>()

    private fun learnTemplate(base: String, doc: Document) {
        val a2 = doc.select("a[href]").firstOrNull {
            it.text().trim() == "2" && Regex("""[?&=/]2/?(?:&.*)?$""").containsMatchIn(it.attr("href"))
        } ?: return
        val href = abs(a2.attr("href"))
        val tpl = href.replace(Regex("""(?<=[?&=/])2(?=/?(?:&.*)?$)"""), "{n}")
        if (tpl.contains("{n}")) pageTpl[base] = tpl
    }

    private fun pageUrl(base: String, page: Int): String =
        pageTpl[base]?.replace("{n}", page.toString())
            ?: if (base.contains("?")) "$base&p=$page" else "$base?p=$page"

    // ---------------- Ana sayfa ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) base else pageUrl(base, page)
        val r = get(url)
        val doc = r.document
        if (page <= 1) learnTemplate(base, doc)
        val list = cards(doc)
        if (list.isEmpty() && debug && page <= 1) {
            val links = doc.select("a[href]")
            throw ErrorLoadingException(
                "Liste boş: kod=${r.code} uzunluk=${r.text.length} başlık='${doc.title().take(40)}' " +
                    "bağlantı=${links.size} film=${links.count { reFilm.containsMatchIn(it.attr("href")) }}"
            )
        }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = list.isNotEmpty() && pageTpl.containsKey(base)
        )
    }

    // ---------------- Arama ----------------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.length < 2) return newSearchResponseList(emptyList(), false)
        val base = "$mainUrl/filtre?a=${encQ(q)}"
        val url = if (page <= 1) base else "$base&p=$page"
        val r = get(url)
        val doc = r.document
        val list = cards(doc)
        if (list.isEmpty() && debug) {
            val links = doc.select("a[href]")
            throw ErrorLoadingException(
                "Arama boş: kod=${r.code} uzunluk=${r.text.length} başlık='${doc.title().take(40)}' " +
                    "bağlantı=${links.size} film=${links.count { reFilm.containsMatchIn(it.attr("href")) }}"
            )
        }
        return newSearchResponseList(list, list.isNotEmpty())
    }

    // ---------------- Film sayfası ----------------

    // Film sayfasında JSON-LD (schema.org) var; sonunda fazladan ">" olabildiği için süslü parantezle kesilir
    private fun ldMovie(doc: Document): JSONObject? {
        for (s in doc.select("script[type=application/ld+json]")) {
            val raw = s.data()
            val i = raw.indexOf('{')
            val j = raw.lastIndexOf('}')
            if (i < 0 || j <= i) continue
            try {
                val o = JSONObject(raw.substring(i, j + 1))
                return o.optJSONObject("mainEntity") ?: o
            } catch (e: Exception) {
            }
        }
        return null
    }

    // Önce altyazı, olmazsa dublaj, olmazsa hakkında sayfası
    private suspend fun fetchFilmPage(slug: String): Triple<String, Document, String>? {
        for (p in listOf("izle/altyazi", "izle/dublaj", "hakkinda")) {
            val u = "$mainUrl/$p/$slug"
            val r = try {
                get(u)
            } catch (e: Exception) {
                d("sayfa hata $p: ${e.javaClass.simpleName}")
                null
            } ?: continue
            if (r.cookies.isNotEmpty()) sessionCookies = sessionCookies + r.cookies
            val doc = r.document
            val ok = r.code in 200..299 && (doc.selectFirst("#dilsec") != null || p == "hakkinda")
            if (ok) return Triple(u, doc, r.text)
            d("sayfa kod=${r.code} $p")
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = reFilm.find(url)?.groupValues?.get(1) ?: url.trimEnd('/').substringAfterLast('/')
        val page = fetchFilmPage(slug) ?: throw ErrorLoadingException("Film sayfası açılamadı: $slug")
        val pageUrl = page.first
        val doc = page.second
        val ld = ldMovie(doc)

        val h1 = doc.selectFirst("h1 a")?.ownText()?.trim().orEmpty()
        val h2 = doc.selectFirst("h2")?.ownText()?.trim().orEmpty()
        val title = listOf(
            h2,
            h1,
            ld?.optString("name").orEmpty().substringBefore(" - "),
            cleanTitle(doc.title())
        ).firstOrNull { it.isNotBlank() } ?: slug

        val year = Regex("""\((\d{4})\)""").find(doc.selectFirst("h1")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: ld?.optString("datePublished")?.take(4)?.toIntOrNull()

        val poster = doc.selectFirst(".filmbanner img")?.let { posterOf(it) }
            ?: ld?.optString("image")?.takeIf { it.isNotBlank() }?.let { abs(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val back = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }

        val tags = (doc.select(".genre span").map { it.text().trim() } + listOf(ld?.optString("genre").orEmpty()))
            .filter { it.isNotBlank() }.distinct()

        // PT101M ya da PT1H41M
        val duration = ld?.optString("duration")?.let { s ->
            val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""").find(s)
            val h = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val mm = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
            (h * 60 + mm).takeIf { it > 0 }
        } ?: Regex("""(\d+)\s*dk""").find(doc.selectFirst(".time")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()

        val castList = ArrayList<Actor>()
        val arr: JSONArray? = ld?.optJSONArray("actor")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i)?.optString("name").orEmpty()
                if (n.isNotBlank()) castList.add(Actor(n))
            }
        }

        val yt = doc.selectFirst("#dilsec a[data-ytid]")?.attr("data-ytid").orEmpty()

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.backgroundPosterUrl = back
            this.plot = plot
            if (year != null) this.year = year
            if (tags.isNotEmpty()) this.tags = tags
            if (duration != null) this.duration = duration
            if (castList.isNotEmpty()) this.actors = castList.map { ActorData(actor = it) }
            if (yt.isNotBlank()) addTrailer("https://www.youtube.com/watch?v=$yt")
        }
    }

    // ---------------- Oynatma: ajax adları ----------------

    @Volatile private var altEndpoints: List<String>? = null
    @Volatile private var embedEndpoints: List<String>? = null
    @Volatile private var altKeys: List<String> = emptyList()
    @Volatile private var altOk: Pair<String, Int>? = null

    private val reIframe = Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val reAsset = Regex(""".*\.(js|css|png|jpe?g|gif|svg|ico|woff2?)(\?.*)?$""", RegexOption.IGNORE_CASE)

    // Sitenin ajax servis adları ve istek alanları sayfadaki ve site.min.js içindeki koddan okunur
    private suspend fun discoverEndpoints(doc: Document, html: String) {
        if (altEndpoints != null && embedEndpoints != null) return
        val sb = StringBuilder(html)
        doc.select("script[src]").map { it.attr("src") }.filter { it.contains("site") }.take(3).forEach { src ->
            try {
                sb.append('\n').append(get(abs(src)).text)
            } catch (e: Exception) {
            }
        }
        val all = sb.toString()
        altEndpoints = Regex("""/ajax/dataAlternatif\w*\.asp""").findAll(all).map { it.value }.distinct().toList()
            .ifEmpty { listOf("/ajax/dataAlternatif3.asp", "/ajax/dataAlternatif.asp") }
        embedEndpoints = Regex("""/ajax/dataEmbed\w*\.asp""").findAll(all).map { it.value }.distinct().toList()
            .ifEmpty { listOf("/ajax/dataEmbed.asp", "/ajax/dataEmbed3.asp") }

        // JS'deki istek alanları: dataAlternatif...asp", {filmid: t, dil: e, ...}
        val i = all.indexOf("dataAlternatif")
        if (i >= 0) {
            val seg = all.substring(i, minOf(all.length, i + 400))
            val obj = Regex("""\{([^{}]{0,300})\}""").find(seg)?.groupValues?.get(1)
            if (obj != null) {
                altKeys = Regex("""([A-Za-z_]\w*)\s*:""").findAll(obj).map { it.groupValues[1] }.toList()
            }
            d("alt js: ${seg.take(150)}")
        }
        d("alt=$altEndpoints embed=$embedEndpoints alanlar=$altKeys")
    }

    // Alan adına göre değer: dil, film numarası (data-id), #dilsec'teki data-* değerleri; diğerleri boş
    private fun formValue(key: String, filmId: String, dil: String, attrs: Map<String, String>): String {
        val k = key.lowercase()
        return when {
            k == "dil" || k == "lang" || k == "d" -> dil
            attrs.containsKey(k) -> attrs[k].orEmpty()
            k.contains("film") || k == "bid" || k == "vid" || k == "f" -> filmId
            else -> ""
        }
    }

    // Alternatif listesi: JSON ({"data":[{"id":..,"baslik":..}]}) ya da HTML (data-id'li öğeler)
    private fun altList(txt: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val t = txt.trim()
        try {
            val arr: JSONArray? = when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    o.optJSONArray("data") ?: o.optJSONArray("veri") ?: o.optJSONArray("list")
                }
                else -> null
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val nm = o.optString("baslik").ifBlank { o.optString("name") }
                    if (id.isNotBlank()) out.add(id to nm)
                }
            }
        } catch (e: Exception) {
        }
        if (out.isEmpty()) {
            Regex("""data-id=["'](\d+)["'][^>]*>([^<]*)<""").findAll(t)
                .forEach { out.add(it.groupValues[1] to it.groupValues[2].trim()) }
        }
        return out
    }

    private fun embedOf(txt0: String): String? {
        val txt = txt0.replace("\\/", "/")
        reIframe.find(txt)?.let { return it.groupValues[1] }
        Regex("""(?:src|data-src|file|source|url)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(txt).map { it.groupValues[1] }
            .firstOrNull { (it.startsWith("http") || it.startsWith("/")) && !reAsset.matches(it) }
            ?.let { return it }
        return Regex("""(?:https?:)?//[^"'\\\s<>]+""").findAll(txt)
            .map { it.value }.firstOrNull { !it.contains("webteizle") && !reAsset.matches(it) }
    }

    // Verilen dil için kaynak listesi. Çalışan uç nokta + alan kombinasyonu hatırlanır.
    private suspend fun fetchAlts(
        filmId: String,
        dil: String,
        attrs: Map<String, String>,
        referer: String
    ): List<Pair<String, String>> {
        val forms = ArrayList<Map<String, String>>()
        if (altKeys.isNotEmpty()) forms.add(altKeys.associateWith { formValue(it, filmId, dil, attrs) })
        forms.add(mapOf("filmid" to filmId, "dil" to dil, "s" to "", "b" to "", "bd" to ""))
        forms.add(mapOf("bid" to filmId, "dil" to dil))
        forms.add(mapOf("id" to filmId, "dil" to dil))
        val uniq = forms.distinct()

        // Daha önce çalışan kombinasyon varsa sadece o denenir (boş liste "bu dilde kaynak yok" demektir)
        val ok = altOk
        if (ok != null && ok.second < uniq.size) {
            val txt = postRl(mainUrl + ok.first, uniq[ok.second], referer)
            val alts = altList(txt)
            if (alts.isEmpty()) d("alt boş dil=$dil yanıt='${txt.replace(Regex("""\s+"""), " ").take(60)}'")
            return alts
        }

        for (ep in altEndpoints.orEmpty()) {
            for (i in uniq.indices) {
                val txt = postRl(mainUrl + ep, uniq[i], referer)
                val alts = altList(txt)
                if (alts.isNotEmpty()) {
                    altOk = ep to i
                    d("alt çalıştı ep=${ep.substringAfterLast('/')} form=$i")
                    return alts
                }
                d("alt boş ep=${ep.substringAfterLast('/')} form=$i yanıt='${txt.replace(Regex("""\s+"""), " ").take(60)}'")
                try { Thread.sleep(200) } catch (e: Exception) { }
            }
        }
        return emptyList()
    }

    private suspend fun fetchEmbed(id: String, referer: String): String? {
        for (ep in embedEndpoints.orEmpty()) {
            val txt = postRl(mainUrl + ep, mapOf("id" to id), referer)
            val e = embedOf(txt)
            if (e != null) return e
            d("embed boş ep=${ep.substringAfterLast('/')} yanıt='${txt.replace(Regex("""\s+"""), " ").take(60)}'")
        }
        return null
    }

    // ---------------- Oynatma: video bağlantısı üretme ----------------

    // Master listeden çözünürlük okunur (genişliğe göre): 1920x960 -> 1080p
    private suspend fun hlsHeight(m3u8: String, headers: Map<String, String>): Int? = try {
        val t = app.get(m3u8, headers = headers, timeout = 10L).text
        val w = Regex("""RESOLUTION=(\d+)x(\d+)""").findAll(t).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        w?.let {
            when {
                it >= 3000 -> 2160
                it >= 2300 -> 1440
                it >= 1800 -> 1080
                it >= 1200 -> 720
                it >= 800 -> 480
                it >= 600 -> 360
                else -> 240
            }
        }
    } catch (e: Exception) {
        null
    }

    // CDN bağlantıları Referer/Origin olmadan, süresi dolunca ya da başka ağdan açılınca 404 verir.
    // Bu yüzden oynatıcı sayfasının Referer ve Origin'i ile verilir.
    private suspend fun emitHls(
        m3u8: String,
        label: String,
        src: String,
        origin: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        val q = hlsHeight(m3u8, headers)
        val qName = if (q != null) " (${q}p)" else ""
        callback(
            newExtractorLink(name, "$label - $src$qName", m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = q ?: Qualities.Unknown.value
                this.headers = headers
            }
        )
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
                    timeout = 12L
                ).text
            } catch (e: Exception) {
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
            emitHls(m3u8.replace("&amp;", "&"), label, "Vidmoly", origin, callback)
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

    // ---- Filemoon: klasik sayfa (packed JS içinde m3u8) ya da yeni Byse (şifreli API) ----
    private suspend fun extractMoon(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val h = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8", "Sec-Fetch-Dest" to "iframe")
        var pageUrl = embed
        var page = try {
            app.get(embed, headers = h, referer = "$mainUrl/", timeout = 15L).text
        } catch (e: Exception) {
            d("moon sayfa hata ${e.javaClass.simpleName}")
            return extractByse(embed, label, callback)
        }

        // İç iframe varsa (klasik Filemoon: /e/ sayfasının içinde asıl oynatıcı) onu da aç
        val inner = reIframe.find(page)?.groupValues?.get(1)
        if (inner != null) {
            val fu = when {
                inner.startsWith("//") -> "https:$inner"
                inner.startsWith("/") -> originOf(embed) + inner
                else -> inner
            }
            if (fu.startsWith("http") && (fu.contains("/e/") || fu.contains("/d/") || originOf(fu) == originOf(embed))) {
                try {
                    page = app.get(fu, headers = h, referer = embed, timeout = 15L).text
                    pageUrl = fu
                } catch (e: Exception) {
                    d("moon iframe hata ${e.javaClass.simpleName}")
                }
            }
        }

        val unpacked = try {
            if (page.contains("eval(function(p,a,c,k,e,")) unpackJs(page) else ""
        } catch (e: Throwable) {
            ""
        }
        val hay = (unpacked + "\n" + page).replace("\\/", "/")
        val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(hay)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(hay)?.groupValues?.get(1)
        if (!m3u8.isNullOrBlank()) {
            emitHls(m3u8.replace("&amp;", "&"), label, "Filemoon", originOf(pageUrl), callback)
            d("moon klasik tamam")
            return true
        }
        d("moon m3u8 yok uzunluk=${page.length}, Byse deneniyor")
        return extractByse(embed, label, callback)
    }

    // Byse: /api/videos/<ID> şifreli JSON döndürür (AES-256-GCM)
    private suspend fun extractByse(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val origin = originOf(embed)
        val id = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val txt = try {
            app.get("$origin/api/videos/$id", headers = mapOf("User-Agent" to ua, "Referer" to embed), timeout = 15L).text
        } catch (e: Exception) {
            d("byse api hata: ${e.message}")
            return false
        }
        val p = try {
            JSONObject(txt).optJSONObject("playback")
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            d("byse playback yok: ${txt.take(100)}")
            return false
        }
        val arr = p.optJSONArray("key_parts")
        if (arr == null || arr.length() < 1) {
            d("byse key_parts yok")
            return false
        }
        val parts = (0 until arr.length()).map { b64u(arr.getString(it)) }
        val v = p.optInt("version")
        val n = parts.size
        val valid = intArrayOf(16, 24, 32)
        val cands = ArrayList<ByteArray>()
        if (v in 1..n && (n - v) in 0 until n && v - 1 != n - v) cands.add(parts[v - 1] + parts[n - v])
        for (i in parts.indices) for (j in parts.indices) {
            if (i != j && (parts[i].size + parts[j].size) in valid) cands.add(parts[i] + parts[j])
        }
        parts.forEach { if (it.size in valid) cands.add(it) }
        val keys = cands.distinctBy { it.toList() }
        if (keys.isEmpty()) {
            d("byse geçerli anahtar boyu yok")
            return false
        }
        val ivEnc = try {
            Pair(b64u(p.getString("iv")), b64u(p.getString("payload")))
        } catch (e: Exception) {
            d("byse iv/payload yok")
            return false
        }
        var json: JSONObject? = null
        for (key in keys) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ivEnc.first))
                val o = JSONObject(String(cipher.doFinal(ivEnc.second), Charsets.UTF_8))
                if (o.optJSONArray("sources") != null) {
                    json = o
                    break
                }
            } catch (e: Exception) {
            }
        }
        val srcs = json?.optJSONArray("sources")
        if (srcs == null) {
            d("byse çözülemedi (anahtar=${keys.size})")
            return false
        }
        var ok = false
        for (i in 0 until srcs.length()) {
            val o = srcs.optJSONObject(i) ?: continue
            val url = o.optString("url").replace("\\/", "/")
            if (!url.startsWith("http")) continue
            emitHls(url, label, "Filemoon", origin, callback)
            ok = true
        }
        return ok
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

    // Bir oynatıcı adresini video bağlantısına çevirir
    private suspend fun emitEmbed(
        embed0: String,
        nm: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val u = when {
            embed0.startsWith("//") -> "https:$embed0"
            embed0.startsWith("/") -> mainUrl + embed0
            else -> embed0
        }
        val moonHint = nm.contains("moon", true) || nm.contains("byse", true) ||
            u.contains("filemoon", true) || u.contains("byse", true)
        return try {
            when {
                u.contains("vidmoly", true) -> extractVidmoly(u, label, callback)
                moonHint -> extractMoon(u, label, callback) || viaExtractor(u, label, subtitleCallback, callback)
                else -> viaExtractor(u, label, subtitleCallback, callback) || extractMoon(u, label, callback)
            }
        } catch (e: Exception) {
            d("çözüm istisna ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---------------- Oynatma ----------------
    // Her dil (Türkçe Dublaj / Türkçe Altyazı) için Filemoon ve Vidmoly ayrı bağlantı olarak verilir.
    // Bu iki kaynak hiç çıkmazsa diğer kaynaklar (Okru vb.) yedek olarak denenir.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        dbg.clear()
        sessionCookies = emptyMap()
        val slug = reFilm.find(data)?.groupValues?.get(1) ?: data.trimEnd('/').substringAfterLast('/')
        val page = fetchFilmPage(slug)
        if (page == null) {
            if (debug) throw ErrorLoadingException("Film sayfası açılamadı: " + dbgText())
            return false
        }
        val pageUrl = page.first
        val doc = page.second
        discoverEndpoints(doc, page.third)

        val ds = doc.selectFirst("#dilsec")
        val filmId = ds?.attr("data-id").orEmpty()
        val attrs = ds?.attributes()?.asList().orEmpty()
            .filter { it.key.startsWith("data-") }
            .associate { it.key.removePrefix("data-").lowercase() to it.value }
        var dils = doc.select("#dilsec a[data-dil]").map { it.attr("data-dil") }
            .filter { it == "0" || it == "1" }.distinct()
        if (dils.isEmpty()) dils = listOf("0", "1")
        d("film=$filmId diller=$dils sayfa=${pageUrl.removePrefix(mainUrl)}")
        if (filmId.isBlank()) {
            if (debug) throw ErrorLoadingException("Film numarası (#dilsec data-id) yok: " + dbgText())
            return false
        }

        var any = false
        val missing = ArrayList<String>()
        val seen = HashSet<String>()

        for (dil in dils.sorted()) {   // "0" dublaj, "1" altyazı
            val label = if (dil == "0") "Türkçe Dublaj" else "Türkçe Altyazı"
            val alts = fetchAlts(filmId, dil, attrs, pageUrl)
            d("dil=$dil alt=${alts.size} adlar=${alts.joinToString(",") { it.second.take(12) }}")

            var okMoon = false
            var okMoly = false

            fun wanted(nm: String) = nm.contains("moon", true) || nm.contains("moly", true) || nm.contains("byse", true)

            // 1) Filemoon ve Vidmoly
            for ((id, nm) in alts.filter { wanted(it.second) }.sortedBy { if (it.second.contains("moly", true)) 1 else 0 }) {
                if (okMoon && okMoly) break
                val embed = fetchEmbed(id, pageUrl)
                if (embed.isNullOrBlank()) {
                    d("dil=$dil id=$id embed yok")
                    continue
                }
                if (embed.contains("reCAPTCHA", true)) {
                    d("dil=$dil id=$id captcha kapısı")
                    continue
                }
                val isMoly = embed.contains("vidmoly", true)
                if ((isMoly && okMoly) || (!isMoly && okMoon)) continue
                if (!seen.add(embed)) continue
                val ok = emitEmbed(embed, nm, label, subtitleCallback, callback)
                d("dil=$dil $nm ${embed.take(40)} -> $ok")
                if (ok) {
                    if (isMoly) okMoly = true else okMoon = true
                }
                try { Thread.sleep(250) } catch (e: Exception) { }
            }

            // 2) Yedek: Filemoon / Vidmoly hiç çıkmadıysa diğer kaynaklar
            if (!okMoon && !okMoly) {
                for ((id, nm) in alts.filter { !wanted(it.second) }.take(4)) {
                    val embed = fetchEmbed(id, pageUrl)
                    if (embed.isNullOrBlank() || embed.contains("reCAPTCHA", true) || !seen.add(embed)) continue
                    val ok = emitEmbed(embed, nm, label, subtitleCallback, callback)
                    d("dil=$dil yedek $nm ${embed.take(40)} -> $ok")
                    if (ok) {
                        okMoon = true
                        break
                    }
                    try { Thread.sleep(250) } catch (e: Exception) { }
                }
            }

            if (okMoon || okMoly) any = true else missing.add(label)
        }

        // Hata ayıklama: bir dil eksik kalırsa sebebi kaynak listesinde kısa DEBUG satırları olarak gösterilir
        if (debug && any && missing.isNotEmpty()) {
            val parts = dbgText().chunked(380).take(3)
            for ((i, part) in parts.withIndex()) {
                callback(
                    newExtractorLink(
                        name,
                        "DEBUG ${i + 1} [${missing.joinToString("+")} yok] $part",
                        "https://debug.invalid/$i",
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        if (!any) {
            if (debug) throw ErrorLoadingException("Kaynak yok: " + dbgText())
            return false
        }
        return true
    }
}
