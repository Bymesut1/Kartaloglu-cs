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

    // Hata ayıklama: true iken liste/arama boş döner ya da kaynak bulunamazsa sebep ekranda görünür.
    // Normal kullanımda false kalmalı.
    private val debug = false
    private val dbg = java.util.Collections.synchronizedList(ArrayList<String>())
    // Tanı kayıtları hem ekran hata ayıklamasına (debug=true) hem de logcat'e ("WebteizleDbg" etiketi) yazılır
    private fun d(m: String) {
        val t = m.replace("\n", " ").take(900)
        dbg.add(t)
        try { android.util.Log.d("WebteizleDbg", t) } catch (e: Throwable) { }
    }
    private fun dbgText(): String = dbg.joinToString(" | ").take(4500)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle/1" to "Son Eklenenler",
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "trend:en-cok-hit-alan:hafta" to "Trend (Bu Hafta)",
        "trend:en-cok-izlenen:ay" to "En Çok İzlenen (Bu Ay)",
        "trend:en-favori:tum" to "En Favori (Tüm Zamanlar)",
        "$mainUrl/tr-dublaj-filmler" to "Türkçe Dublaj",
        "$mainUrl/tr-altyazili-filmler" to "Altyazılı Filmler",
        "$mainUrl/tavsiye-filmler" to "Tavsiye Filmler",
        "$mainUrl/odullu-filmler" to "Ödüllü Filmler",
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
                val pr = post(url, form, ref)
                if (pr.text.isBlank()) d("post kod=${pr.code} boş yanıt")
                pr.text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("post hata ${e.javaClass.simpleName}")
                ""
            }
            if (!rl(txt)) break
            d("hız sınırı, bekleniyor")
            Thread.sleep(3000L + att * 2000L)
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
        t.replace(Regex("""[\s\u00A0]+izle\b.*$""", RegexOption.IGNORE_CASE), "").replace('\u00A0', ' ').trim()

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
                a.parent()?.selectFirst(".filmname")?.text(),
                a.selectFirst(".filmtitle")?.text(),
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
            it.text().trim() == "2" && Regex("""[?&=/]2/?(?:[?&].*)?$""").containsMatchIn(it.attr("href"))
        } ?: return
        val href = abs(a2.attr("href"))
        val tpl = href.replace(Regex("""(?<=[?&=/])2(?=/?(?:[?&].*)?$)"""), "{n}")
        if (tpl.contains("{n}")) pageTpl[base] = tpl
    }

    private fun pageUrl(base: String, page: Int): String =
        pageTpl[base]?.replace("{n}", page.toString())
            ?: if (base.contains("?")) "$base&p=$page" else "$base?p=$page"

    // Liste boş çıkarsa sebebini görmek için kısa tanı bilgisi
    private fun diag(code: Int, html: String, doc: Document): String {
        val links = doc.select("a[href]")
        val sample = doc.select("#sol a[href], #wrapper a[href], .cards a[href]")
            .map { it.attr("href") }.distinct().take(5)
        val ajax = Regex("""/ajax/\w+\.asp""").findAll(html).map { it.value }.distinct().take(3).toList()
        return "kod=$code uzunluk=${html.length} başlık='${doc.title().take(40)}' bağlantı=${links.size} " +
            "film=${links.count { reFilm.containsMatchIn(it.attr("href")) }} img=${doc.select("img[data-src]").size} " +
            "card=${doc.select(".card").size} pfl=${html.contains("pageFilmListesi")} ajax=$ajax örnek=$sample"
    }

    // ---------------- Ana sayfa ----------------

    // Hata ayıklama: boş bölüm yerine nedenini gösteren tek kart
    private suspend fun debugSection(name: String, info: String): HomePageResponse {
        val card = newMovieSearchResponse("⚠ $name: $info", mainUrl, TvType.Movie)
        return newHomePageResponse(
            list = HomePageList(name = name, list = listOf(card), isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data

        // Trend sayfası film listesini sayfada vermez; site /ajax/dataTrend.asp'ye POST atıp doldurur
        if (base.startsWith("trend:")) {
            val parts = base.removePrefix("trend:").split(":")
            val sekme = parts.getOrElse(0) { "en-cok-hit-alan" }
            val tarih = parts.getOrElse(1) { "hafta" }
            val r = post(
                "$mainUrl/ajax/dataTrend.asp?sekme=$sekme&tarih=$tarih&s=$page",
                emptyMap(),
                "$mainUrl/trend"
            )
            val doc = org.jsoup.Jsoup.parse(r.text, mainUrl)
            val list = cards(doc)
            if (list.isEmpty() && debug && page <= 1) return debugSection(request.name, diag(r.code, r.text, doc))
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
                hasNext = list.isNotEmpty() && page < 10
            )
        }

        val url = if (page <= 1) base else pageUrl(base, page)
        val r = get(url)
        val doc = r.document
        if (page <= 1) learnTemplate(base, doc)
        val list = cards(doc)
        if (list.isEmpty() && debug && page <= 1) return debugSection(request.name, diag(r.code, r.text, doc))
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
        val url = if (page <= 1) base else pageUrl(base, page)
        val r = get(url)
        val doc = r.document
        if (page <= 1) learnTemplate(base, doc)
        val list = cards(doc)
        if (list.isEmpty() && debug) {
            throw ErrorLoadingException("Arama boş: " + diag(r.code, r.text, doc))
        }
        return newSearchResponseList(list, list.isNotEmpty() && pageTpl.containsKey(base))
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

        val bannerImg: Element? = doc.selectFirst(".filmbanner img")
        val poster = (if (bannerImg != null) posterOf(bannerImg) else null)
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
    // JS'de sabit yazılmış alan değerleri: {filmid:e,dil:t,s:a,b:n,bot:0} -> bot=0
    @Volatile private var altLits: Map<String, String> = emptyMap()
    @Volatile private var altOk: Pair<String, Int>? = null
    @Volatile private var embedKeys: List<String> = emptyList()
    @Volatile private var embedLits: Map<String, String> = emptyMap()
    @Volatile private var playerJsDone = false

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
                val lits = HashMap<String, String>()
                Regex("""([A-Za-z_]\w*)\s*:\s*([^,}]+)""").findAll(obj).forEach { m ->
                    val v = m.groupValues[2].trim()
                    if (Regex("""-?\d+""").matches(v)) {
                        lits[m.groupValues[1]] = v
                    } else if (v.length >= 2 && (v.startsWith("\"") || v.startsWith("'"))) {
                        lits[m.groupValues[1]] = v.substring(1, v.length - 1)
                    }
                }
                altLits = lits
            }
            d("alt js: ${seg.take(150)}")
        }
        // dataEmbed isteğinin alanları: dataEmbed.asp", {id:e,bot:0,...}
        val em = Regex("""dataEmbed\w*\.asp["']\s*,\s*\{([^{}]{0,200})\}""").find(all)
        if (em != null) {
            val eo = em.groupValues[1]
            embedKeys = Regex("""([A-Za-z_]\w*)\s*:""").findAll(eo).map { it.groupValues[1] }.toList()
            val el = HashMap<String, String>()
            Regex("""([A-Za-z_]\w*)\s*:\s*([^,}]+)""").findAll(eo).forEach { m ->
                val v = m.groupValues[2].trim()
                if (Regex("""-?\d+""").matches(v)) {
                    el[m.groupValues[1]] = v
                } else if (v.length >= 2 && (v.startsWith("\"") || v.startsWith("'"))) {
                    el[m.groupValues[1]] = v.substring(1, v.length - 1)
                }
            }
            embedLits = el
            d("embed js: ${em.value.take(160)}")
        } else {
            d("embed js bulunamadı")
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
            val t2 = t.replace("\\\"", "\"").replace("\\/", "/")
            Regex("""data-id=["'](\d+)["'][^>]*>([^<]*)<""").findAll(t2)
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
        if (altKeys.isNotEmpty()) forms.add(altKeys.associateWith { altLits[it] ?: formValue(it, filmId, dil, attrs) })
        forms.add(mapOf("filmid" to filmId, "dil" to dil, "s" to "", "b" to "", "bot" to "0"))
        forms.add(mapOf("filmid" to filmId, "dil" to dil, "bot" to "0"))
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
                Thread.sleep(200)
            }
        }
        return emptyList()
    }

    // Cevap <div ...> ile geliyorsa (player.js doldurur): öznitelik değerlerinden adres çıkarmayı dener
    private fun decodeDivEmbed(txt: String): String? {
        val doc = org.jsoup.Jsoup.parse(txt)
        val urlRe = Regex("""https?://[^\s"'<>]+""")
        for (el in doc.allElements) {
            for (at in el.attributes()) {
                val v = at.value.trim()
                if (v.length < 6 || at.key == "src") continue
                val cands = ArrayList<String>()
                cands.add(v)
                try { cands.add(java.net.URLDecoder.decode(v, "UTF-8")) } catch (e: Exception) { }
                try { cands.add(String(b64u(v))) } catch (e: Exception) { }
                try { cands.add(String(b64u(v.reversed()))) } catch (e: Exception) { }
                try { cands.add(String(b64u(v)).reversed()) } catch (e: Exception) { }
                for (c in cands) {
                    val m = urlRe.find(c.replace("\\/", "/")) ?: continue
                    if (!m.value.contains("webteizle", true) && !m.value.contains("recaptcha", true)) return m.value
                }
            }
        }
        return null
    }

    // \\xNN ile gizlenmiş betik: var vid = '...'; iframe adresinin ön eki ile birleştirilir (Dzen)
    private fun decodeHexEmbed(txt: String): String? {
        val vid = Regex("""var\s+vid\s*=\s*['"]([^'"]+)['"]""").find(txt)?.groupValues?.get(1) ?: return null
        val dec = Regex("""\\x([0-9A-Fa-f]{2})""").replace(txt) { it.groupValues[1].toInt(16).toChar().toString() }
        val pre = Regex("""src=['"](https?://[^'"\s]+)""").find(dec)?.groupValues?.get(1) ?: return null
        return pre + vid
    }

    // player.js içindeki vidmoly(id, div) gibi fonksiyonların oluşturduğu iframe adres öneklerini okur
    @Volatile private var playerMap: Map<String, String>? = null

    private suspend fun loadPlayerMap(txt: String) {
        if (playerMap != null) return
        val src = Regex("""<script[^>]+src=["']([^"']*player[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(txt)?.groupValues?.get(1) ?: return
        val js = try {
            get(abs(src)).text
        } catch (e: Exception) {
            d("player.js hata ${e.javaClass.simpleName}")
            playerMap = emptyMap()
            return
        }
        val map = HashMap<String, String>()
        val fns = Regex("""function\s+(\w+)\s*\(\s*id\s*,\s*div\s*\)""").findAll(js).toList()
        for ((i, m) in fns.withIndex()) {
            val end = if (i + 1 < fns.size) fns[i + 1].range.first else js.length
            val body = js.substring(m.range.last, end)
            val pre = Regex("""src=\\?["']([^"']*)["']\s*\+\s*id""").find(body)?.groupValues?.get(1)
            if (pre != null) map[m.groupValues[1].lowercase()] = pre
        }
        playerMap = map
        d("player.js uzunluk=${js.length} harita=$map")
    }

    // vidmoly('58b4n33oez93','rv1') gibi satırdan oynatıcı adresi üretir
    private fun embedFromFn(kind: String, id: String): String? {
        val direct = when {
            kind.contains("vidmoly") -> "https://vidmoly.biz/embed-$id.html"
            kind.startsWith("ok") -> "https://ok.ru/videoembed/$id"
            kind.contains("pixel") -> "https://pixeldrain.com/u/$id"
            kind.contains("moon") || kind.contains("byse") -> "https://bysezoxexe.com/e/$id"
            else -> null
        }
        val proxy = playerMap?.get(kind)?.let { abs(it + id) }
        if (direct == null && proxy == null) return null
        return (direct ?: "") + (if (proxy != null) "#wt=$proxy" else "")
    }

    // Reklam/captcha sarmalayıcısı: adresin içindeki gerçek oynatıcı adresini çıkarır
    private fun unwrapCaptcha(e: String): String? {
        val dec = try { java.net.URLDecoder.decode(e, "UTF-8") } catch (x: Exception) { e }
        return Regex("""https?://[^\s"'<>&]+""").findAll(dec).map { it.value }
            .firstOrNull { !it.contains("recaptcha", true) && !it.contains("google.com", true) && !it.contains("webteizle", true) }
    }

    // Daha önce çalışan uç nokta + alan kombinasyonu bir sonraki isteklerde ilk olarak denenir
    @Volatile private var embedOk: Pair<String, Int>? = null

    private suspend fun fetchEmbed(id: String, referer: String): String? {
        val forms = ArrayList<Map<String, String>>()
        if (embedKeys.isNotEmpty()) {
            forms.add(embedKeys.associateWith { embedLits[it] ?: if (it.lowercase().contains("id")) id else "" })
        }
        forms.add(mapOf("id" to id))
        forms.add(mapOf("id" to id, "bot" to "0"))
        val uniq = forms.distinct()

        val order = ArrayList<Pair<String, Int>>()
        val first = embedOk
        if (first != null && first.second < uniq.size) order.add(first)
        for (ep in embedEndpoints.orEmpty()) for (fi in uniq.indices) {
            if ((ep to fi) != first) order.add(ep to fi)
        }

        for ((ep, fi) in order) {
            val txt = postRl(mainUrl + ep, uniq[fi], referer)
            loadPlayerMap(txt)
            val fm = Regex("""<script>\s*(\w+)\(\s*'([^']+)'""").find(txt)
            if (fm != null) {
                val r = embedFromFn(fm.groupValues[1].lowercase(), fm.groupValues[2])
                if (r != null) {
                    embedOk = ep to fi
                    return r
                }
                d("fonksiyon bilinmiyor: ${fm.groupValues[1]}")
            }
            val e = embedOf(txt) ?: decodeDivEmbed(txt) ?: decodeHexEmbed(txt)
            if (e != null && !e.contains("recaptcha", true)) {
                embedOk = ep to fi
                return e
            }
            if (e != null) {
                d("embed captcha form=$fi ham='${txt.replace(Regex("""\s+"""), " ").take(300)}'")
                return e
            }
            d("embed boş ep=${ep.substringAfterLast('/')} form=$fi yanıt='${txt.replace(Regex("""\s+"""), " ").take(500)}'")
        }
        return null
    }

    // ---------------- Oynatma: video bağlantısı üretme ----------------

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

    // ---- Mail.Ru: video bilgisi JSON olarak gelir; oynatmak için yanıttaki video_key çerezi gerekir ----
    private suspend fun extractMailRu(path: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val p = path.trim('/')
        if (p.isBlank()) return false
        val urls = listOf(
            "https://my.mail.ru/+/video/meta/$p",
            "https://videoapi.my.mail.ru/videos/$p.json"
        )
        for (mu in urls) {
            val r = try {
                app.get(mu, headers = mapOf("User-Agent" to ua, "Referer" to "https://my.mail.ru/"), timeout = 10L)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("mailru hata ${e.javaClass.simpleName}")
                continue
            }
            val arr = try { JSONObject(r.text).optJSONArray("videos") } catch (e: Exception) { null }
            if (arr == null || arr.length() == 0) {
                d("mailru videos yok: ${r.text.take(120)}")
                continue
            }
            val vk = r.cookies["video_key"]
            var ok = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                var url = o.optString("url").replace("\\/", "/")
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http")) continue
                val q = o.optString("key").filter { it.isDigit() }.toIntOrNull()
                val hh = HashMap<String, String>()
                hh["User-Agent"] = ua
                hh["Referer"] = "https://my.mail.ru/"
                if (!vk.isNullOrBlank()) hh["Cookie"] = "video_key=$vk"
                callback(
                    newExtractorLink(name, "$label - Mail.Ru${if (q != null) " (${q}p)" else ""}", url, ExtractorLinkType.VIDEO) {
                        this.referer = "https://my.mail.ru/"
                        this.quality = q ?: Qualities.Unknown.value
                        this.headers = hh
                    }
                )
                ok = true
            }
            if (ok) return true
        }
        return false
    }

    // ---- Dzen: gömülü sayfada .m3u8 / .mpd adresi aranır ----
    private suspend fun extractDzen(u: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = try {
            app.get(u, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/", timeout = 10L).text
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("dzen hata ${e.javaClass.simpleName}")
            return false
        }
        val clean = html.replace("\\u002F", "/").replace("\\u002f", "/").replace("\\/", "/").replace("&amp;", "&")
        val m3u8 = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""").find(clean)?.value
        if (m3u8 != null && emitHls(m3u8, label, "Dzen", "https://dzen.ru", callback)) return true
        val mpd = Regex("""https?://[^"'\s<>\\]+\.mpd[^"'\s<>\\]*""").find(clean)?.value
        if (mpd != null) {
            val h = mapOf("User-Agent" to ua, "Referer" to "https://dzen.ru/")
            callback(
                newExtractorLink(name, "$label - Dzen", mpd, ExtractorLinkType.DASH) {
                    this.referer = "https://dzen.ru/"
                    this.quality = Qualities.Unknown.value
                    this.headers = h
                }
            )
            return true
        }
        d("dzen video adresi yok uzunluk=${html.length}")
        return false
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

    // ---- Filemoon: klasik sayfa (packed JS içinde m3u8) ya da yeni Byse (şifreli API) ----
    private suspend fun extractMoon(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Byse adreslerinde klasik sayfayı beklemek zaman kaybı: önce doğrudan şifreli API denenir
        val byseHost = embed.contains("byse", true)
        // (klasik sayfa Byse'de boş bir kabuk döndürdüğü için bu adreslerde başka yol denenmez)
        if (byseHost) return extractByse(embed, label, callback)
        val h = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8", "Sec-Fetch-Dest" to "iframe")
        var pageUrl = embed
        var page = try {
            app.get(embed, headers = h, referer = "$mainUrl/", timeout = 10L).text
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("moon sayfa hata ${e.javaClass.simpleName}")
            return if (byseHost) false else extractByse(embed, label, callback)
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
                    page = app.get(fu, headers = h, referer = embed, timeout = 10L).text
                    pageUrl = fu
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
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
            if (emitHls(m3u8.replace("&amp;", "&"), label, "Filemoon", originOf(pageUrl), callback)) {
                d("moon klasik tamam")
                return true
            }
        }
        d("moon m3u8 yok uzunluk=${page.length}")
        return if (byseHost) false else extractByse(embed, label, callback)
    }

    // Byse: /api/videos/<ID> şifreli JSON döndürür (AES-256-GCM)
    private suspend fun extractByse(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val origin = originOf(embed)
        val id = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(embed)?.groupValues?.get(1) ?: return false
        var txt = ""
        for (att in 0..1) {
            txt = try {
                app.get("$origin/api/videos/$id", headers = mapOf("User-Agent" to ua, "Referer" to embed), timeout = 10L).text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("byse api hata: ${e.message}")
                ""
            }
            if (txt.contains("playback")) break
            if (att == 0) Thread.sleep(800)
        }
        val p = try {
            JSONObject(txt).optJSONObject("playback")
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            d("byse playback yok: ${txt.take(300)}")
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
            if (emitHls(url, label, "Filemoon", origin, callback)) ok = true
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

    // Sitenin kendi oynatıcı sayfası (/player/...): içinde .m3u8 ya da iç iframe aranır
    private suspend fun extractProxy(
        url: String,
        label: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 2) return false
        val html = try {
            app.get(url, headers = mapOf("User-Agent" to ua, "Sec-Fetch-Dest" to "iframe"), referer = "$mainUrl/", timeout = 15L).text
        } catch (e: Exception) {
            d("proxy hata ${e.javaClass.simpleName}")
            return false
        }
        d("proxy ${url.takeLast(40)} uzunluk=${html.length} '${html.replace(Regex("""\s+"""), " ").take(300)}'")
        val clean = html.replace("\\/", "/")
        val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(clean)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(clean)?.groupValues?.get(1)
        if (!m3u8.isNullOrBlank()) {
            return emitHls(m3u8.replace("&amp;", "&"), label, "Webteizle", originOf(url), callback)
        }
        val inner = reIframe.find(clean)?.groupValues?.get(1) ?: return false
        val fu = when {
            inner.startsWith("//") -> "https:$inner"
            inner.startsWith("/") -> originOf(url) + inner
            else -> inner
        }
        if (fu == url || fu.contains("recaptcha", true)) return false
        return if (fu.startsWith(mainUrl + "/player/")) extractProxy(fu, label, depth + 1, subtitleCallback, callback)
        else emitEmbed(fu, "", label, subtitleCallback, callback)
    }

    // "adres#wt=proxyAdres": önce doğrudan adres, olmazsa sitenin kendi oynatıcı sayfası denenir
    private suspend fun emitEmbed(
        embed0: String,
        nm: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = embed0.split("#wt=")
        if (parts[0].isNotBlank() && emitEmbed0(parts[0], nm, label, subtitleCallback, callback)) return true
        if (parts.size > 1 && parts[1].contains("video-mail-ru", true)) {
            if (extractMailRu(parts[1].substringAfter("?v=", ""), label, callback)) return true
        }
        if (parts.size > 1) return extractProxy(parts[1], label, 0, subtitleCallback, callback)
        return false
    }

    // Bir oynatıcı adresini video bağlantısına çevirir
    private suspend fun emitEmbed0(
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
                // Netu (hqq) oynatıcısı çözülemiyor
                u.contains("hqq.", true) || u.contains("netu.", true) -> false
                u.contains("ok.ru", true) -> extractOkRu(u, label, callback) || viaExtractor(u, label, subtitleCallback, callback)
                u.contains("dzen.", true) -> extractDzen(u, label, callback)
                // Filemoon/Byse: hazır CloudStream çözücüsü eski kaldığı için açılmayan, kalitesiz bir
                // "Filemoon" bağlantısı üretiyordu; sadece kendi çözücümüz kullanılır
                moonHint -> extractMoon(u, label, callback)
                else -> viaExtractor(u, label, subtitleCallback, callback) || extractMoon(u, label, callback)
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("çözüm istisna ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---------------- Oynatma ----------------
    // Her dil (Türkçe Dublaj / Türkçe Altyazı) ve sitedeki her alternatif (Filemoon, Vidmoly, Pixel,
    // [EskiDub] sürümleri...) ayrı bağlantı olarak verilir. Hepsi aynı anda çözülür, böylece uzun
    // sürüp uygulama tarafından iptal edilmez; çözülemeyen sessizce atlanır.

    // Hata ayıklama: sebep, kaynak listesinde "DEBUG" satırları olarak görünür (hata ekranı yerine)
    private suspend fun emitDebug(tag: String, callback: (ExtractorLink) -> Unit) {
        if (dbg.isEmpty()) d("kayıt yok")
        val parts = dbgText().chunked(380).take(12)
        for ((i, part) in parts.withIndex()) {
            callback(
                newExtractorLink(
                    name,
                    "DEBUG ${i + 1} [$tag] $part",
                    "https://debug.invalid/$i",
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    // ---------------- Doğrulama kapısı (Cloudflare Turnstile) ----------------
    // Pixel / Ok.Ru / Filemoon için dataEmbed bazen gerçek adres yerine /ajax/reCAPTCHADATA.asp kapısını döndürür.
    // Kapı sayfası Turnstile yükler; çözülünce oturum doğrulanır. Kapı görünmez WebView'de açılıp kendi kendine
    // çözmesi için süre tanınır (useOkhttp = false: WebView kendi ağ yığınını ve çerez yöneticisini kullanır).
    // Doğrulanmış oturum çerezleri bir süre saklanır, çözülemezse kısa süre tekrar denenmez.
    @Volatile private var gateCookies: Map<String, String> = emptyMap()
    @Volatile private var gateOkUntil = 0L
    @Volatile private var gateFailUntil = 0L

    private fun isGate(e: String?): Boolean = !e.isNullOrBlank() && e.contains("recaptcha", true)

    private suspend fun solveGate(ref: String, gateUrl: String?) {
        val gate = gateUrl?.let { g ->
            when {
                g.startsWith("//") -> "https:$g"
                g.startsWith("/") -> mainUrl + g
                else -> g
            }
        } ?: "$mainUrl/ajax/reCAPTCHADATA.asp"
        val cm = try { android.webkit.CookieManager.getInstance() } catch (e: Throwable) { null }
        try {
            cm?.setAcceptCookie(true)
            sessionCookies.forEach { (k, v) -> cm?.setCookie(mainUrl, "$k=$v; Path=/") }
            cm?.flush()
        } catch (e: Throwable) {
            d("captcha çerez hata ${e.javaClass.simpleName}")
        }
        d("captcha kapı=${gate.take(140)}")
        val t0 = System.currentTimeMillis()
        try {
            app.get(
                gate,
                headers = hdr(),
                referer = ref,
                cookies = sessionCookies,
                interceptor = WebViewResolver(
                    Regex("""zz-asla-eslesmez-zz"""),
                    userAgent = null,
                    useOkhttp = false,
                    timeout = 14_000L
                )
            )
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("captcha webview ${e.javaClass.simpleName}")
        }
        try {
            val before = sessionCookies.size
            cm?.getCookie(mainUrl)?.split(";")?.forEach { c ->
                val kv = c.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) sessionCookies = sessionCookies + (kv[0] to kv[1])
            }
            d("captcha çözüm denendi süre=${System.currentTimeMillis() - t0}ms çerez $before->${sessionCookies.size} adlar=${sessionCookies.keys.joinToString(",").take(80)}")
        } catch (e: Throwable) {
            d("captcha çerez geri alma hata ${e.javaClass.simpleName}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        dbg.clear()
        sessionCookies = if (System.currentTimeMillis() < gateOkUntil) gateCookies else emptyMap()
        val slug = reFilm.find(data)?.groupValues?.get(1) ?: data.trimEnd('/').substringAfterLast('/')
        val page = fetchFilmPage(slug)
        if (page == null) {
            if (debug) { emitDebug("sayfa açılamadı", callback); return true }
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
        d("film=$filmId diller=$dils sayfa=${pageUrl.removePrefix(mainUrl)} html=${page.third.length} dilsec=${ds != null} attrs=$attrs")
        if (filmId.isBlank()) {
            if (debug) { emitDebug("#dilsec data-id yok", callback); return true }
            return false
        }

        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val fails = java.util.Collections.synchronizedList(ArrayList<String>())

        // 1) Her dilin kaynak listesi aynı anda alınır ("0" dublaj, "1" altyazı)
        val langs = dils.sorted().amap { dil ->
            val label = if (dil == "0") "Türkçe Dublaj" else "Türkçe Altyazı"
            val alts = fetchAlts(filmId, dil, attrs, pageUrl)
            d("dil=$dil alt=${alts.size} adlar=${alts.joinToString(",") { it.first + ":" + it.second.take(18) }}")
            Triple(dil, label, alts)
        }

        // 2) Oynatıcı adresleri sırayla alınır (siteye aynı anda çok istek gitmesin, kapı tek seferde çözülsün)
        val jobs = ArrayList<Triple<String, String, String>>()   // (kaynak adı, etiket, oynatıcı adresi)
        val gated = ArrayList<Triple<String, String, String>>()  // kapıya takılanlar: (id, kaynak adı, etiket)
        var gateUrl: String? = null

        fun addJob(embed: String?, nm: String, lbl: String) {
            if (embed.isNullOrBlank()) {
                fails.add("$lbl $nm")
                return
            }
            if (!seen.add(embed)) return
            jobs.add(Triple(nm, lbl, embed))
        }

        for ((dil, label, alts) in langs) {
            for ((id, nm) in alts) {
                // [EskiDub] gibi sürümler ayrı etiketle gösterilir
                val lbl = if (nm.contains("eski", true)) "$label (Eski Dub)" else label
                var embed = fetchEmbed(id, pageUrl)
                d("dil=$dil id=$id $nm embed=${embed.orEmpty().take(150)}")
                if (isGate(embed)) {
                    val un = unwrapCaptcha(embed.orEmpty())
                    if (un != null) {
                        embed = un
                    } else {
                        if (gateUrl == null) gateUrl = embed
                        gated.add(Triple(id, nm, lbl))
                        continue
                    }
                }
                addJob(embed, nm, lbl)
            }
        }

        // Eldeki oynatıcılar (ör. Vidmoly) kapı beklenmeden hemen çözülür; çözülemeyen sessizce atlanır
        val oksAll = ArrayList<Boolean>()

        // Bağlantılar önce arabelleğe alınır, sonra sıralanıp verilir:
        // Filemoon dublaj, Filemoon altyazı, Vidmoly dublaj, Vidmoly altyazı, sonra diğerleri (dublaj/altyazı sırayla)
        val buffer = java.util.Collections.synchronizedList(ArrayList<ExtractorLink>())
        fun srcRank(n: String): Int = when {
            n.contains("Filemoon", true) -> 0
            n.contains("Vidmoly", true) -> 1
            n.contains("Ok.Ru", true) -> 2
            n.contains("Mail.Ru", true) -> 3
            n.contains("Pixel", true) -> 4
            n.contains("Dzen", true) -> 5
            else -> 9
        }
        fun langRank(n: String): Int = when {
            n.startsWith("Türkçe Dublaj (Eski") -> 2
            n.startsWith("Türkçe Dublaj") -> 0
            n.startsWith("Türkçe Altyazı") -> 1
            else -> 3
        }
        fun flush() {
            val items = synchronized(buffer) {
                val c = ArrayList(buffer)
                buffer.clear()
                c
            }
            items.sortedWith(
                compareBy<ExtractorLink>({ srcRank(it.name) }, { langRank(it.name) }, { -it.quality })
            ).forEach { callback(it) }
        }

        suspend fun runJobs() {
            val batch = ArrayList(jobs)
            jobs.clear()
            oksAll.addAll(batch.amap { (nm, lbl, embed) ->
                val ok = emitEmbed(embed, nm, lbl, subtitleCallback) { l -> buffer.add(l) }
                d("$lbl $nm ${embed.take(150)} -> $ok")
                if (!ok) fails.add("$lbl $nm")
                ok
            })
            flush()
        }
        runJobs()

        // 3) Kapıya takılanlar varsa kapı bir kez çözülür, sonra o kaynaklar yeniden istenir
        if (gated.isNotEmpty()) {
            if (System.currentTimeMillis() >= gateFailUntil) {
                solveGate(pageUrl, gateUrl)
                var solved = false
                for ((id, nm, lbl) in gated) {
                    val e = fetchEmbed(id, pageUrl)
                    d("kapı sonrası id=$id $nm embed=${e.orEmpty().take(150)}")
                    if (isGate(e)) {
                        fails.add("$lbl $nm (kapı)")
                    } else {
                        solved = true
                        addJob(e, nm, lbl)
                    }
                }
                if (solved) {
                    gateCookies = sessionCookies
                    gateOkUntil = System.currentTimeMillis() + 15 * 60_000L
                    d("kapı çözüldü")
                } else {
                    gateFailUntil = System.currentTimeMillis() + 90_000L
                    d("kapı çözülemedi")
                }
            } else {
                d("kapı yakın zamanda çözülemedi, atlandı")
                gated.forEach { fails.add("${it.third} ${it.second} (kapı)") }
            }
        }

        // 4) Kapıdan sonra gelen oynatıcılar (Filemoon, Pixel, Ok.Ru...) çözülür
        runJobs()
        val any = oksAll.any { it }

        // Hata ayıklama (yalnızca debug=true iken): başarısız kaynaklar gösterilir
        if (debug && any && fails.isNotEmpty()) emitDebug(fails.joinToString("+") + " yok", callback)

        if (!any) {
            if (debug) { emitDebug("kaynak yok", callback); return true }
            return false
        }
        return true
    }
}
