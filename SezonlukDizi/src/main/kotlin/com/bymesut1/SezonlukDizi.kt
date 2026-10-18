package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SezonlukDizi : MainAPI() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // CAPTCHA / Cloudflare "tarayıcı kontrolü" killer:
    // Site bir kontrol sayfası döndürürse otomatik geçer, çerezi saklar ve sonraki isteklerde kullanır.
    // Kontrol yoksa isteği olduğu gibi geçirir. Sitedeki TÜM isteklerde (liste, arama, ajax) kullanılıyor.
    private val cf = CloudflareKiller()

    // Hata ayıklama: true iken arama / oynatma başarısız olursa sebep ekranda hata mesajı olarak görünür.
    // Her şey düzelince false yap.
    private val debug = true
    private val dbg = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun d(m: String) { dbg.add(m.replace("\n", " ").take(260)) }
    private fun dbgText(): String = dbg.joinToString(" | ").take(1200)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val liste = "$mainUrl/diziler.asp"

    // data alanı: "soncikan" / "populer" (ana sayfa) ya da sayfalanabilir liste adresi (sonuna &s=<sayfa> eklenir)
    override val mainPage = mainPageOf(
        "soncikan" to "Son Çıkan Bölümler",
        "populer" to "Popüler Diziler",
        "$liste?dil=0&siralama_tipi=id&siralama_turu=desc" to "Türkçe Dublaj Diziler",
        "$liste?siralama_tipi=id&siralama_turu=desc" to "Yeni Eklenenler",
        "$liste?kat=1&siralama_tipi=imdb&siralama_turu=desc" to "Yabancı Diziler (IMDb)",
        "$liste?kat=2&siralama_tipi=imdb&siralama_turu=desc" to "Yerli Diziler",
        "$liste?kat=4&siralama_tipi=imdb&siralama_turu=desc" to "Animasyonlar",
        "$liste?kat=5&siralama_tipi=imdb&siralama_turu=desc" to "Animeler"
    )

    // ---------------- Yardımcılar ----------------

    private fun hdr(): Map<String, String> = mapOf("User-Agent" to ua)

    private fun ajaxHdr(referer: String): Map<String, String> = hdr() + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer
    )

    // Önce düz istek atılır (hızlı, WebView açmaz). Sadece Cloudflare kontrol sayfası gelirse CloudflareKiller devreye girer.
    // Eskiden her istek CloudflareKiller'dan geçiyordu; kontrol sayfasında WebView sonsuz bekleyip arama/oynatmayı donduruyordu.
    private fun isChallenge(code: Int, body: String): Boolean =
        code == 403 || code == 503 ||
            body.contains("<title>Just a moment", true) ||
            body.contains("cf-browser-verification", true) ||
            body.contains("/cdn-cgi/challenge-platform/h/b/orchestrate", true)

    private suspend fun get(url: String, referer: String = "$mainUrl/") =
        try {
            val r = app.get(url, headers = hdr(), referer = referer, timeout = 15L)
            if (isChallenge(r.code, r.text)) throw Exception("cf")
            r
        } catch (e: Exception) {
            app.get(url, headers = hdr(), referer = referer, interceptor = cf, timeout = 20L)
        }

    // Sayfadan ve ajax yanıtlarından gelen oturum çerezleri (ASP oturumu) sonraki ajax isteklerine eklenir
    @Volatile private var sessionCookies: Map<String, String> = emptyMap()

    private suspend fun post(url: String, form: Map<String, String>, referer: String, t: Long = 15L) =
        try {
            val r = app.post(url, data = form, headers = ajaxHdr(referer), cookies = sessionCookies, timeout = t)
            if (isChallenge(r.code, r.text)) throw Exception("cf")
            if (r.cookies.isNotEmpty()) sessionCookies = sessionCookies + r.cookies
            r
        } catch (e: Exception) {
            app.post(url, data = form, headers = ajaxHdr(referer), interceptor = cf, timeout = t)
        }

    private fun abs(u: String): String = if (u.startsWith("http")) u else fixUrl(u)

    // Site windows-1254 kullanıyor; Türkçe harfli aramalar için aynı kodlama denenir
    private fun encQ(q: String): String = try {
        URLEncoder.encode(q, "windows-1254")
    } catch (e: Exception) {
        URLEncoder.encode(q, "UTF-8")
    }

    // /diziler/mobland.html
    private val reSeries = Regex("""^(?:https?://sezonlukdizi\.[a-z]+)?(/diziler/[^/?#]+\.html)$""")

    // /mobland/2-sezon-5-bolum.html  veya  /mobland/dublaj/2-sezon-5-bolum.html
    private val reEp =
        Regex("""^(?:https?://sezonlukdizi\.[a-z]+)?/([a-z0-9-]+)/(?:dublaj/)?(\d+)-sezon-(\d+)-bolum\.html""")

    // 2-sezon-5-bolum.html (başında dizi adı olmayan göreli bağlantı)
    private val reEpBare = Regex("""^(?:\./)?(?:dublaj/)?(\d+)-sezon-(\d+)-bolum\.html""")

    private fun slugOf(seriesUrl: String): String =
        Regex("""/diziler/([^/?#]+)\.html""").find(seriesUrl)?.groupValues?.get(1)?.removeSuffix("-izle") ?: ""

    private fun cleanTitle(t: String): String =
        t.replace(Regex("""\s*(izle)?\s*[-|–]\s*SezonlukDizi.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
            .trim()

    // Listelerde resim "data-src" içinde (src sadece boş yer tutucu); ana sayfada doğrudan src
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

    // Sayfadaki dizi kartları (poster + bağlantı). Ana sayfadaki "bölüm" kartları bölüm adresiyle kalır,
    // gerçek dizi sayfası load() sırasında bölüm sayfasından bulunur.
    private fun cards(root: Element?): List<SearchResponse> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in root.select("a[href]")) {
            val href = a.attr("href").trim()
            val img = a.selectFirst("img") ?: continue
            val epm = reEp.find(href)
            val key: String
            val link: String
            when {
                reSeries.containsMatchIn(href) -> {
                    link = mainUrl + reSeries.find(href)!!.groupValues[1]
                    key = "s:" + slugOf(link)
                }
                epm != null -> {
                    link = abs(href)
                    key = "s:" + epm.groupValues[1]
                }
                else -> continue
            }
            if (out.containsKey(key)) continue
            val title = listOf(
                a.selectFirst(".box-title .title")?.text(),
                a.selectFirst(".description")?.text(),
                a.selectFirst(".title")?.text(),
                cleanTitle(a.attr("title")),
                img.attr("alt")
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: continue
            out[key] = newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = posterOf(img)
            }
        }
        return out.values.toList()
    }

    // Sayfalama çubuğundaki en büyük sayfa numarası
    private fun maxPage(doc: Document): Int =
        doc.select("#filtresayfalama a.item").mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull() ?: 1

    // ---------------- Ana sayfa ----------------

    private fun homeOf(title: String, list: List<SearchResponse>, hasNext: Boolean): HomePageResponse =
        newHomePageResponse(
            list = HomePageList(name = title, list = list, isHorizontalImages = false),
            hasNext = hasNext
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data
        if (key == "soncikan" || key == "populer") {
            if (page > 1) return homeOf(request.name, emptyList(), false)
            val doc = get(mainUrl).document
            val list = if (key == "soncikan") {
                cards(doc.selectFirst("#soncikan"))
            } else {
                cards(doc.selectFirst("#enler .active.tab") ?: doc.selectFirst("#enler"))
            }
            return homeOf(request.name, list, false)
        }
        val doc = get("$key&s=$page").document
        val list = cards(doc.selectFirst(".afis") ?: doc.body())
        return homeOf(request.name, list, list.isNotEmpty() && page < maxPage(doc))
    }

    // ---------------- Arama ----------------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.length < 2) return newSearchResponseList(emptyList(), false)   // site en az 2 karakter istiyor
        dbg.clear()
        val sfx = if (page > 1) "&s=$page" else ""
        val urls = listOf(
            "$liste?adi=${encQ(q)}$sfx",
            "$liste?adi=${URLEncoder.encode(q, "UTF-8")}$sfx"
        ).distinct()
        for (u in urls) {
            val r = try {
                get(u)
            } catch (e: Exception) {
                d("istek hatası ${e.javaClass.simpleName}: ${e.message}")
                null
            } ?: continue
            val doc = r.document
            val list = cards(doc.selectFirst(".afis") ?: doc.body())
            d("kod=${r.code} uzunluk=${r.text.length} başlık='${doc.title()}' afis=${doc.select(".afis").size} kart=${list.size}")
            if (list.isNotEmpty()) return newSearchResponseList(list, page < maxPage(doc))
        }
        if (debug) throw ErrorLoadingException("Arama boş: " + dbgText())
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    // ---------------- Dizi sayfası ----------------

    // Bölüm adresinden gerçek dizi sayfasını bulur (dizi adresi bölüm adresindeki addan farklı olabilir)
    private suspend fun resolveSeriesUrl(url: String): String {
        reSeries.find(url)?.let { return mainUrl + it.groupValues[1] }
        if (reEp.containsMatchIn(url)) {
            val doc = try { get(abs(url)).document } catch (e: Exception) { null }
            val href = doc?.selectFirst("h1 a[href*=\"/diziler/\"]")?.attr("href")
                ?: doc?.selectFirst("a[href^=\"/diziler/\"][href$=\".html\"]")?.attr("href")
            if (!href.isNullOrBlank()) reSeries.find(href)?.let { return mainUrl + it.groupValues[1] }
            val slug = reEp.find(url)!!.groupValues[1]
            return "$mainUrl/diziler/$slug.html"
        }
        return url
    }

    // Bulunan bölüm bağlantısı sayısını döndürür (sezon dolu mu boş mu anlamak için)
    private fun parseEpisodes(doc: Document, into: MutableMap<String, Episode>, slugHint: String): Int {
        var matched = 0
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            val slug: String
            val s: Int
            val e: Int
            val m = reEp.find(href)
            if (m != null) {
                slug = m.groupValues[1]
                s = m.groupValues[2].toIntOrNull() ?: continue
                e = m.groupValues[3].toIntOrNull() ?: continue
            } else {
                val b = reEpBare.find(href) ?: continue
                slug = slugHint
                s = b.groupValues[1].toIntOrNull() ?: continue
                e = b.groupValues[2].toIntOrNull() ?: continue
            }
            matched++
            val key = "$s-$e"
            if (into.containsKey(key)) continue
            val epUrl = "$mainUrl/$slug/$s-sezon-$e-bolum.html"   // dublaj bağlantısı olsa da normal adrese çevrilir
            val label = a.text().trim().ifBlank { a.attr("title").trim() }
            into[key] = newEpisode(epUrl) {
                this.season = s
                this.episode = e
                this.name = label.ifBlank { null }
            }
        }
        return matched
    }

    override suspend fun load(url: String): LoadResponse? {
        val seriesUrl = resolveSeriesUrl(url)
        val res = get(seriesUrl)
        val html = res.text
        val doc = res.document
        val hint = slugOf(seriesUrl)

        val title = (doc.selectFirst("h1")?.text()?.let { cleanTitle(it) }?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanTitle(it) }
            ?: cleanTitle(doc.title())).ifBlank { return null }

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[src*=\"/i/dizi/\"]")?.attr("src")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val eps = LinkedHashMap<String, Episode>()
        parseEpisodes(doc, eps, hint)

        // Dizi numarası (did): sayfadaki did özelliği ya da poster adresinden (/i/dizi/y/b/2595.jpg)
        val did = Regex("""did\s*[=:]\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""/i/dizi/(?:[a-z]/)+(\d+)\.jpg""").find(html)?.groupValues?.get(1)

        if (did != null) {
            val endpoint = Regex("""/ajax/dataIzleBolumler\w*\.asp""").find(html)?.value
                ?: "/ajax/dataIzleBolumler.asp"

            suspend fun season(sno: Int): Document? = try {
                post(mainUrl + endpoint, mapOf("did" to did, "sno" to sno.toString()), seriesUrl).document
            } catch (e: Exception) {
                null
            }

            // Sezonlar 8'erli gruplar halinde paralel denenir; grubun son sezonu boşsa durulur
            var start = 1
            while (start <= 40) {
                val chunk = (start until start + 8).toList()
                val docs = chunk.amap { season(it) }
                var lastCount = 0
                docs.forEachIndexed { i, d ->
                    if (d != null) {
                        val c = parseEpisodes(d, eps, hint)
                        if (i == chunk.lastIndex) lastCount = c
                    }
                }
                if (lastCount == 0) break
                start += 8
            }
        }

        val list = eps.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, list) {
            this.posterUrl = poster?.let { abs(it) }
            this.plot = plot
        }
    }

    // ---------------- Oynatma ----------------

    private val reIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private var altEndpoints: List<String>? = null
    private var embedEndpoints: List<String>? = null

    // Sitenin ajax servis adları zamanla değişiyor (dataAlternatif3, dataAlternatif22 ...).
    // Adları sayfadaki ve site.min.js içindeki kodlardan okuyup kullanıyoruz.
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
            .ifEmpty { listOf("/ajax/dataAlternatif3.asp") }
        embedEndpoints = Regex("""/ajax/dataEmbed\w*\.asp""").findAll(all).map { it.value }.distinct().toList()
            .ifEmpty { listOf("/ajax/dataEmbed.asp") }
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

    private val reAsset = Regex(""".*\.(js|css|png|jpe?g|gif|svg|ico|woff2?)(\?.*)?$""", RegexOption.IGNORE_CASE)

    private fun embedOf(txt0: String): String? {
        val txt = txt0.replace("\\/", "/")
        reIframe.find(txt)?.let { return it.groupValues[1] }
        // iframe yoksa src / data-src / file gibi alanlardaki adresler (göreli olanlar dahil)
        Regex("""(?:src|data-src|file|source|url)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(txt).map { it.groupValues[1] }
            .firstOrNull { (it.startsWith("http") || it.startsWith("/")) && !reAsset.matches(it) }
            ?.let { return it }
        return Regex("""(?:https?:)?//[^"'\\\s<>]+""").findAll(txt)
            .map { it.value }.firstOrNull { !it.contains("sezonlukdizi") && !reAsset.matches(it) }
    }

    // WebView yedeği yavaş olduğu için bir bölümde en fazla bu kadar kaynakta denenir
    private val webviewLeft = AtomicInteger(0)

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.get(1) ?: mainUrl

    // https://filemoon.sx/e/abc -> "Filemoon"
    private fun hostLabel(u: String): String {
        val h = Regex("""^https?://(?:www\.)?([^./]+)""").find(u)?.groupValues?.get(1) ?: return "Player"
        return h.replaceFirstChar { it.uppercase() }
    }

    // Dil adını kaynak adının önüne ekler: "Dublaj - Filemoon (1080p)"
    private suspend fun relabel(l: ExtractorLink, label: String): ExtractorLink =
        newExtractorLink(l.source, "$label - ${l.name}", l.url, l.type) {
            this.referer = l.referer
            this.quality = l.quality
            this.headers = l.headers
        }

    // CDN bağlantıları (SprintCDN vb.) Referer/Origin olmadan, süresi dolunca ya da başka IP'den açılınca
    // 404 verir. Bu yüzden oynatıcıya gömülü sayfanın Referer ve Origin'i ile verilir.
    private suspend fun emitM3u8(
        m3u8: String,
        label: String,
        embed: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(embed)
        callback(
            newExtractorLink(name, "$label - ${hostLabel(embed)}", m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$origin/",
                    "Origin" to origin
                )
            }
        )
    }

    // ---- bysejikuar.com (Byse) oynatıcısı: API şifreli (AES-256-GCM) JSON döndürür ----
    private fun b64u(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return android.util.Base64.decode(t, android.util.Base64.DEFAULT)
    }

    private suspend fun extractBysejikuar(
        embed: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(embed)
        val id = embed.substringAfterLast("/e/").substringBefore("?").substringBefore("/")
        if (id.isBlank() || !embed.contains("/e/")) {
            d("byse id bulunamadı: $embed")
            return false
        }

        // Çalışan eklentideki gibi: sadece UA + Referer
        val h = mapOf("User-Agent" to ua, "Referer" to embed)
        val txt = try {
            app.get("$origin/api/videos/$id", headers = h, timeout = 15L).text
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
            d("byse playback yok: ${txt.take(120)}")
            return false
        }

        val arr = p.optJSONArray("key_parts")
        if (arr == null || arr.length() < 1) {
            d("byse key_parts yok, alanlar=${p.keys().asSequence().toList()}")
            return false
        }
        val parts = (0 until arr.length()).map { b64u(arr.getString(it)) }
        d("byse version=${p.optInt("version")} parça=${parts.size}")

        // Anahtar: 30 parçadan sadece ikisi gerçek anahtar (iki adet 16 baytlık parça, toplam 32 bayt = AES-256).
        // Loglarda gözlenen: parts[version-1] ve parts[n-version]. Emin olmak için geçerli boy veren tüm ikili
        // kombinasyonlar denenir; GCM etiketi doğru anahtarı kendiliğinden doğrular.
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
            d("byse iv/payload yok: ${e.message}")
            return false
        }
        val iv = ivEnc.first
        val enc = ivEnc.second

        var json: JSONObject? = null
        var failed = 0
        for (key in keys) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                val o = JSONObject(String(cipher.doFinal(enc), Charsets.UTF_8))
                if (o.optJSONArray("sources") != null) {
                    json = o
                    break
                }
            } catch (e: Exception) {
                failed++
            }
        }
        val srcs = json?.optJSONArray("sources")
        if (srcs == null) {
            d("byse çözülemedi, denenen anahtar=${keys.size} hatalı=$failed")
            return false
        }

        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        var ok = false
        for (i in 0 until srcs.length()) {
            val o = srcs.optJSONObject(i) ?: continue
            val url = o.optString("url").replace("\\/", "/")
            if (!url.startsWith("http")) continue
            val ql = o.optString("label").ifBlank { "1080p" }
            val tag = "$label ($ql)"
            val mime = o.optString("mime_type")
            if (url.contains(".m3u8") || mime.contains("mpegurl", true)) {
                val links = try {
                    M3u8Helper.generateM3u8(name, url, "$origin/", headers = headers)
                } catch (e: Exception) {
                    emptyList()
                }
                if (links.isNotEmpty()) links.forEach { callback(relabel(it, tag)) }
                else emitM3u8(url, tag, embed, callback)
            } else {
                callback(
                    newExtractorLink(name, "$tag - Byse", url, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = getQualityFromName(ql)
                        this.headers = headers
                    }
                )
            }
            ok = true
        }
        d("byse kaynak sayısı=${srcs.length()} ok=$ok")
        return ok
    }

    // Bir gömülü oynatıcı adresini çözer. Sırayla:
    // 1) CloudStream'in hazır çözücüleri (Filemoon, Vidmoly, Ok.ru ...)
    // 2) Sayfayı CloudflareKiller ile açıp içindeki .m3u8 adresini bulmak
    // 3) Görünmez WebView ile sayfayı çalıştırıp ağdan geçen .m3u8'i yakalamak
    private suspend fun emitEmbed(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val u = when {
            embed.startsWith("//") -> "https:$embed"
            embed.startsWith("/") -> mainUrl + embed
            else -> embed
        }
        if (!u.startsWith("http")) return false

        // 0) Byse (bysejikuar.com): CloudStream'de hazır çözücü yok, kendi çözücümüz
        if (u.contains("byse", ignoreCase = true)) {
            val okByse = try {
                extractBysejikuar(u, label, callback)
            } catch (e: Exception) {
                d("byse istisna ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (okByse) return true
        }

        // 1) hazır çözücüler
        val tmp = ArrayList<ExtractorLink>()
        try {
            loadExtractor(u, "$mainUrl/", subtitleCallback) { synchronized(tmp) { tmp.add(it) } }
        } catch (e: Exception) {
        }
        if (tmp.isNotEmpty()) {
            tmp.forEach { callback(relabel(it, label)) }
            return true
        }

        // 2) CloudflareKiller ile sayfayı aç, .m3u8 ara
        try {
            val page = app.get(u, referer = "$mainUrl/", timeout = 10L)
                .text.replace("\\/", "/")
            val m = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""").find(page)
            if (m != null) {
                emitM3u8(m.value.replace("&amp;", "&"), label, u, callback)
                return true
            }
        } catch (e: Exception) {
        }

        // 3) WebView ile yakala
        if (webviewLeft.getAndDecrement() > 0) {
            try {
                val r = app.get(
                    u,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(Regex("""\.m3u8"""), timeout = 15_000L)
                )
                if (r.url.contains(".m3u8")) {
                    emitM3u8(r.url, label, u, callback)
                    return true
                }
            } catch (e: Exception) {
            }
        }
        return false
    }

    // Sayfadaki oynatıcı adresleri (öncelik sırasıyla). iframe src yerine data-src de olabilir;
    // hiç gerçek oynatıcı yoksa sayfa kaynağında Byse adresi ve diğer iframe'ler de aranır.
    private fun pageEmbeds(doc: Document, html: String): List<String> {
        val out = LinkedHashSet<String>()
        for (e in doc.select("iframe#hs, #embed iframe")) {
            listOf(e.attr("src"), e.attr("data-src")).forEach { if (it.isNotBlank()) out.add(it.trim()) }
        }
        if (out.none { !it.contains("reCAPTCHA", true) }) {
            Regex("""(?:https?:)?//[^"'\\\s<>]*byse[^"'\\\s<>]*/e/[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(html.replace("\\/", "/")).forEach { out.add(it.value) }
            for (e in doc.select("iframe")) {
                val u = listOf(e.attr("src"), e.attr("data-src")).firstOrNull { it.isNotBlank() }?.trim() ?: continue
                if (u.startsWith("about:")) continue
                if (listOf("google", "facebook", "youtube", "doubleclick", "histats").any { u.contains(it, true) }) continue
                out.add(u)
            }
        }
        return out.toList().take(3)
    }

    // reCAPTCHA kapısı dönen adresin içinde ya da (oturum çerezleriyle açılan) sayfasında gerçek oynatıcı adresi var mı bakar
    private suspend fun captchaInner(u: String, ref: String): String? {
        val full = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl + u
            else -> u
        }
        val dec = try { java.net.URLDecoder.decode(full, "UTF-8") } catch (e: Exception) { full }
        Regex("""https?://[^\s"'&<>]+""").findAll(dec.substringAfter("?", "")).map { it.value }
            .firstOrNull { !it.contains("sezonlukdizi", true) && !it.contains("recaptcha", true) }
            ?.let { return it }
        return null   // captcha sayfasından gerçek adres çıkmıyor; ek istek atılmaz (Cloudflare hız sınırını tetikler)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        webviewLeft.set(2)
        dbg.clear()
        sessionCookies = emptyMap()

        fun dilName(x: String) = if (x == "0") "Dublaj" else "Altyazı"
        var any = false
        val seen = HashSet<String>()
        val captchaSeen = HashSet<String>()
        val got = HashSet<String>()            // kaynağı bulunan diller: "0" = dublaj, "1" = altyazı
        val retryQ = ArrayList<Triple<String, String, String>>()   // captcha / boş dönenler: (dil, id, referer)
        val okCnt = HashMap<String, Int>()     // dil başına çalışan kaynak sayısı
        val srcLog = ArrayList<String>()       // kaynak başına kısa sonuç (hata ayıklama)
        val pageLog = ArrayList<String>()

        fun short(x: String?, n: Int = 50): String =
            (x ?: "").replace(Regex("""\s+"""), " ").trim().take(n)

        // Bir gömülü oynatıcıyı verilen dil etiketiyle yayınlar (captcha kapısıysa içindeki adresi arar)
        suspend fun emit(dil: String, embed0: String): Boolean {
            var embed = embed0
            if (embed.isBlank()) return false
            if (embed.contains("reCAPTCHA", true)) {
                if (!captchaSeen.add(embed)) return false
                val inner = try { captchaInner(embed, "$mainUrl/") } catch (e: Exception) { null }
                d("captcha iç=$inner")
                if (inner.isNullOrBlank()) return false
                embed = inner
            }
            if (!seen.add(embed)) return false
            // Doğrudan .m3u8 adresi verilmişse hazır çözücüye gerek yok
            if (Regex("""\.m3u8(\?|$)""").containsMatchIn(embed)) {
                val full = abs(embed)
                emitM3u8(full, dilName(dil), full, callback)
                any = true
                got.add(dil)
                okCnt[dil] = (okCnt[dil] ?: 0) + 1
                d("kaynak ${dilName(dil)} m3u8 -> true")
                return true
            }
            val ok = emitEmbed(embed, dilName(dil), subtitleCallback, callback)
            d("kaynak ${dilName(dil)} $embed -> $ok")
            if (ok) {
                any = true
                got.add(dil)
                okCnt[dil] = (okCnt[dil] ?: 0) + 1
            }
            return ok
        }

        // Cloudflare hız sınırı ("error code: 1015") yanıtı mı?
        fun rl(x: String) = x.contains("1015") || x.contains("rate limit", true) || x.contains("too many", true)

        // Hız sınırında beklemek için toplam süre bütçesi (ms)
        var waitBudget = 40_000L

        // Kaynak id'sinden gömülü oynatıcı adresini ister: (adres, ham yanıt).
        // Cloudflare 1015 dönerse bekleyip aynı kaynağı tekrar dener.
        suspend fun fetchEmbed(id: String, ref: String): Pair<String?, String> {
            var found: String? = null
            var raw = ""
            for (attempt in 0..2) {
                for (ep in embedEndpoints.orEmpty()) {
                    val r = try {
                        post(mainUrl + ep, mapOf("id" to id), ref).text
                    } catch (e: Exception) {
                        "HATA ${e.javaClass.simpleName}"
                    }
                    raw = r
                    found = embedOf(r)
                    if (found != null) break
                }
                if (found != null || !rl(raw) || waitBudget <= 0) break
                val w = 5000L + attempt * 3000L
                waitBudget -= w
                d("hız sınırı id=$id bekle=${w}ms")
                try { Thread.sleep(w) } catch (e: Exception) { }
            }
            return Pair(found, raw)
        }

        // Bir kaynağı dener ve sonucunu kısa bir satır olarak kaydeder
        suspend fun tryId(dil: String, id: String, ref: String, tagPrefix: String) {
            val (f, raw) = fetchEmbed(id, ref)
            val tag = "$tagPrefix$dil:$id"
            if (f.isNullOrBlank()) {
                srcLog.add("$tag BOŞ[${short(raw)}]")
                retryQ.add(Triple(dil, id, ref))
                return
            }
            val cap = f.contains("reCAPTCHA", true)
            val host = if (cap) "CAPTCHA" else (Regex("""^(?:https?:)?//([^/]+)""").find(f)?.groupValues?.get(1)
                ?: short(f, 25))
            val ok = emit(dil, f)
            srcLog.add("$tag $host ${if (ok) "OK" else "yok"}")
        }

        // 1) Hem normal (altyazı) hem /dublaj/ bölüm sayfası açılır; her ikisindeki açık oynatıcı kendi diliyle eklenir.
        val m = reEp.find(data)
        val pageUrls = if (m != null) {
            val slug = m.groupValues[1]
            val s = m.groupValues[2]
            val e = m.groupValues[3]
            listOf(
                "$mainUrl/$slug/$s-sezon-$e-bolum.html",
                "$mainUrl/$slug/dublaj/$s-sezon-$e-bolum.html"
            )
        } else listOf(data)

        val pages = ArrayList<Triple<String, String, String>>()   // (sayfa adresi, bid, aktif dil)
        for (pu in pageUrls) {
            val res = try {
                get(pu)
            } catch (e: Exception) {
                d("sayfa hata $pu ${e.javaClass.simpleName}: ${e.message}")
                pageLog.add("HATA ${short(pu.substringAfter(".cc"), 40)}")
                null
            } ?: continue
            if (res.code !in 200..299) {
                d("sayfa kod=${res.code} $pu")
                pageLog.add("kod=${res.code}")
                continue
            }
            if (res.cookies.isNotEmpty()) sessionCookies = sessionCookies + res.cookies
            val doc = res.document
            val bid = doc.selectFirst("#dilsec")?.attr("data-id").orEmpty()
            val activeDil = doc.selectFirst("#dilsec .item.active")?.attr("data-dil")
                ?.takeIf { it == "0" || it == "1" }
                ?: if (res.url.contains("/dublaj/")) "0" else "1"
            discoverEndpoints(doc, res.text)
            val embeds = pageEmbeds(doc, res.text)
            d("sayfa kod=${res.code} bid='$bid' dil=$activeDil açık=${embeds.size}")
            pageLog.add("dil=$activeDil bid=$bid açık=${embeds.size} çerez=${sessionCookies.size}")
            pages.add(Triple(pu, bid, activeDil))
            for (f in embeds) emit(activeDil, f)
            try { Thread.sleep(250) } catch (e: Exception) { }
        }

        // 2) Ajax ile alternatif kaynaklar: önce henüz kaynağı bulunmamış dil denenir.
        //    Dublaj istekleri /dublaj/ sayfasını, altyazı istekleri normal sayfayı Referer olarak kullanır.
        for (dil in listOf("1", "0").sortedBy { it in got }) {
            val refUrl = pages.map { it.first }.firstOrNull { (dil == "0") == it.contains("/dublaj/") }
                ?: pages.firstOrNull()?.first ?: data
            val bid = pages.firstOrNull { it.first == refUrl }?.second?.takeIf { it.isNotBlank() }
                ?: pages.map { it.second }.firstOrNull { it.isNotBlank() }
                ?: continue

            var alts: List<Pair<String, String>> = emptyList()
            for (ep in altEndpoints.orEmpty()) {
                var txt = ""
                for (att in 0..2) {
                    txt = try {
                        post(mainUrl + ep, mapOf("bid" to bid, "dil" to dil), refUrl).text
                    } catch (e: Exception) {
                        ""
                    }
                    if (!rl(txt) || waitBudget <= 0) break
                    waitBudget -= 5000L
                    try { Thread.sleep(5000L) } catch (e: Exception) { }
                }
                alts = altList(txt)
                if (alts.isEmpty()) d("alt boş dil=$dil yanıt='${short(txt, 60)}'")
                if (alts.isNotEmpty()) break
            }
            d("bid=$bid dil=$dil alt=${alts.size}")
            srcLog.add("$dil: alt=${alts.size}")

            // Embedler SIRAYLA istenir (paralel istek sitede captcha tetikliyor)
            for ((id, _) in alts) {
                if ((okCnt[dil] ?: 0) >= 2) break
                tryId(dil, id, refUrl, "")
                try { Thread.sleep(1000) } catch (e: Exception) { }
            }
        }

        // 3) Hâlâ kaynağı çıkmayan dil varsa captcha'ya takılan / boş dönen kaynaklar bekleyerek yeniden denenir
        for (dil in listOf("0", "1")) {
            if (dil in got) continue
            val todo = retryQ.filter { it.first == dil }.take(4)
            for (attempt in 1..2) {
                if (dil in got) break
                for ((_, id, ref) in todo) {
                    if (dil in got) break
                    try { Thread.sleep(1500L * attempt) } catch (e: Exception) { }
                    tryId(dil, id, ref, "R$attempt ")
                }
            }
        }

        val missing = listOf("0", "1").filter { it !in got }
        d("özet dublaj=${"0" in got} altyazı=${"1" in got}")

        // Hata ayıklama: bir dil eksik kalırsa sebebi kaynak listesinde kısa "DEBUG" satırları olarak gösterilir
        if (debug && any && missing.isNotEmpty()) {
            val eksik = missing.joinToString("+") { dilName(it) }
            val lines = listOf(
                "sayfalar: " + pageLog.joinToString(" | "),
                "dublaj: " + srcLog.filter { it.contains("0:") && !it.contains("1:") }.joinToString(" ; "),
                "altyazı: " + srcLog.filter { it.contains("1:") }.joinToString(" ; "),
                "byse: " + dbg.filter { it.startsWith("byse") || it.startsWith("captcha") }.takeLast(6).joinToString(" / ")
            )
            for ((i, line) in lines.withIndex()) {
                callback(
                    newExtractorLink(
                        name,
                        "DEBUG ${i + 1} [$eksik yok] " + line.take(420),
                        "https://debug.invalid/$i",
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        if (!any && debug) throw ErrorLoadingException("Kaynak yok: " + dbgText())
        return any
    }
}
