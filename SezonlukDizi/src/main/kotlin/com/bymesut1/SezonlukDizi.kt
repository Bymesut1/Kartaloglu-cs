package com.bymesut1

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi.cc"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override var supportedTypes       = setOf(TvType.TvSeries)

    // the search call (diziler.asp?adi=...) sits behind a Cloudflare challenge
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.text().contains("Just a moment...")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?kat=2&s="                     to "Yerli Diziler",
        "${mainUrl}/diziler.asp?kat=1&s="                     to "Yabanci Diziler",
        "${mainUrl}/diziler.asp?kat=3&s="                     to "Asya Dizileri",
        "${mainUrl}/diziler.asp?kat=4&s="                     to "Animasyonlar",
        "${mainUrl}/diziler.asp?kat=5&s="                     to "Animeler",
        "${mainUrl}/diziler.asp?kat=6&s="                     to "Belgeseller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.afis a[href*='/diziler/']").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    // "Dizi Adi (2025)" -> "Dizi Adi" (SearchResponse yil alanina sahip degil)
    private val yilSonu = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")

    override suspend fun search(query: String): List<SearchResponse> {
        // /ajax/arama.asp (POST q=) -> JSON; bu uc Cloudflare'siz calisiyor.
        // Eski diziler.asp?adi= sayfasi datacenter IP'ye 403 verdigi icin yalnizca yedek.
        val arama = app.post(
            "${mainUrl}/ajax/arama.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("q" to query)
        ).parsedSafe<Arama>()

        val sonuclar = arama?.results?.values
            ?.flatMap { it.results ?: emptyList() }
            ?.filter { it.url.contains("/diziler/") }
            ?.mapNotNull { sonuc ->
                val ad   = sonuc.title.replace(yilSonu, "").trim().ifEmpty { return@mapNotNull null }
                val href = fixUrlNull(sonuc.url) ?: return@mapNotNull null
                newTvSeriesSearchResponse(ad, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(sonuc.image)
                }
            } ?: emptyList()
        if (sonuclar.isNotEmpty()) return sonuclar

        val document = app.get(
            "${mainUrl}/diziler.asp?adi=${java.net.URLEncoder.encode(query, "UTF-8")}",
            interceptor = interceptor
        ).document
        return document.select("div.afis a[href*='/diziler/']").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title       = document.selectFirst("div.header")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("[property='og:title']")?.attr("content")?.trim()
            ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("div.image img")?.attr("data-src")
                ?: document.selectFirst("div.image img")?.attr("src")
                ?: document.selectFirst("[property='og:image']")?.attr("content")
        ) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(document.select("div.extra.content").text())?.value?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()
        val endpoint    = url.split("/").last()
        val actorsReq   = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors      = actorsReq.select("div.doubling div.ui").mapNotNull {
            val name = it.selectFirst("div.header")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }
        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epHref = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")
                    ?: bolum.selectFirst("td:nth-of-type(3) a")?.attr("href")) ?: continue
                val epName = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epEpisode = Regex("""(\d+)""").find(
                    bolum.selectFirst("td:nth-of-type(3)")?.text() ?: ""
                )?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)""").find(
                    bolum.selectFirst("td:nth-of-type(2)")?.text() ?: ""
                )?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) { this.name = epName; this.season = epSeason; this.episode = epEpisode })
            }
        }
        // Yalnizca bolum satirlari TMDB ile zenginlestirilir (Turkce ad, aciklama, bolum gorseli); hata olursa site listesi aynen kalir
        val bolumler = try { tmdbBolumleri(title, episodes) } catch (e: Exception) { episodes }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, bolumler) {
            this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.duration = duration; this.actors = actors.map { ActorData(it) }
        }
    }

    // ---------------- TMDB: yalnizca dizi sayfasindaki bolum satirlari icin ----------------
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val reGenericEp = Regex("""(?iu)^\s*(bölüm|episode|ep\.?)\s*\d+\s*$""")

    private fun norm(t: String): String = t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private fun dateMs(s: String?): Long? = try {
        if (s.isNullOrBlank()) null else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    private suspend fun tmdbSearch(q: String): List<TmdbTvResult> = try {
        app.get(
            "$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${java.net.URLEncoder.encode(q, "UTF-8")}",
            timeout = 15L
        ).parsedSafe<TmdbSearchResponse>()?.results.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun tmdbDetails(id: Int): TmdbTvDetails? = try {
        app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=tr-TR", timeout = 15L).parsedSafe<TmdbTvDetails>()
    } catch (e: Exception) {
        null
    }

    // Sitedeki diziyi TMDB'deki dogru diziyle esler; ayni adli birden fazla dizi varsa sezon basina bolum sayisi en yakin olan secilir
    private suspend fun tmdbMatch(title: String, siteCounts: Map<Int, Int>): TmdbTvDetails? {
        val key = norm(title)
        if (key.isEmpty()) return null
        val results = tmdbSearch(title)
        if (results.isEmpty()) return null
        val exact = results.filter {
            norm(it.name.orEmpty()) == key || norm(it.original_name.orEmpty()) == key
        }
        val cands = if (exact.isNotEmpty()) exact.take(3) else listOfNotNull(
            results.firstOrNull {
                val n = norm(it.name.orEmpty())
                n.isNotEmpty() && (n.contains(key) || key.contains(n))
            }
        )
        if (cands.isEmpty()) return null
        if (cands.size == 1) return cands[0].id?.let { tmdbDetails(it) }
        val details = cands.amap { c -> c.id?.let { tmdbDetails(it) } }.filterNotNull()
        if (details.isEmpty()) return null
        return details.minByOrNull { d ->
            val cnt = d.seasons.orEmpty().associate { (it.season_number ?: -1) to (it.episode_count ?: 0) }
            var score = 0
            for ((sn, c) in siteCounts) score += kotlin.math.abs(c - (cnt[sn] ?: 0))
            score * 1000 - (d.popularity ?: 0.0).toInt().coerceAtMost(999)
        }
    }

    private suspend fun tmdbSeason(id: Int, sn: Int, lang: String): Map<Int, TmdbEpisode> = try {
        app.get("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=$lang", timeout = 15L)
            .parsedSafe<TmdbSeasonDetails>()?.episodes.orEmpty()
            .mapNotNull { e -> e.episode_number?.let { it to e } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    // Sitedeki her bolum satirina TMDB'den Turkce ad, aciklama, gorsel, sure ve tarih ekler. Sitede olmayan bolum eklenmez.
    private suspend fun tmdbBolumleri(title: String, siteEps: List<Episode>): List<Episode> {
        if (siteEps.isEmpty()) return siteEps
        val counts = siteEps.groupBy { it.season ?: 0 }.mapValues { it.value.size }
        val tm = tmdbMatch(title, counts) ?: return siteEps
        val tid = tm.id ?: return siteEps
        val rows = counts.keys.filter { it > 0 }.amap { sn ->
            Triple(sn, tmdbSeason(tid, sn, "tr-TR"), tmdbSeason(tid, sn, "en-US"))
        }
        val trMap = rows.associate { it.first to it.second }
        val enMap = rows.associate { it.first to it.third }

        return siteEps.map { old ->
            val sn = old.season ?: 0
            val en = old.episode ?: 0
            val tr = trMap[sn]?.get(en)
            val us = enMap[sn]?.get(en)
            if (tr == null && us == null) return@map old
            // Turkce ad yoksa ("Bolum 3" gibi) Ingilizce ad, o da yoksa sitenin adi
            val epName = tr?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: us?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: old.name
            val overview = tr?.overview?.takeIf { it.isNotBlank() } ?: us?.overview?.takeIf { it.isNotBlank() }
            val still = (tr?.still_path ?: us?.still_path)?.let { "$tmdbImg/w500$it" }
            val runtime = tr?.runtime ?: us?.runtime
            val airMs = dateMs(tr?.air_date ?: us?.air_date)
            newEpisode(old.data) {
                this.season = old.season
                this.episode = old.episode
                this.name = epName
                this.description = overview
                this.posterUrl = still
                if (runtime != null && runtime > 0) this.runTime = runtime
                if (airMs != null) this.date = airMs
            }
        }
    }

    // ---------------- Kaynak (oynatici) yardimcilari: Byse (Filemoon), Vidmoly ----------------

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private fun hdr(): Map<String, String> = mapOf("User-Agent" to ua)

    // Logcat'te "SezonlukDizi" etiketiyle görünür (kaynak / kapı hata ayıklama)
    private fun lg(m: String) {
        try { android.util.Log.i("SezonlukDizi", m.take(300)) } catch (e: Throwable) { }
    }

    // Sayfadan ve ajax yanitlarindan gelen oturum cerezleri (ASP oturumu) sonraki ajax isteklerine eklenir
    @Volatile private var sessionCookies: Map<String, String> = emptyMap()

    // Turnstile kapisi: dogrulanmis oturum cerezleri bir sure saklanir, cozulemezse kisa bir sure tekrar denenmez
    @Volatile private var gateCookies: Map<String, String> = emptyMap()
    @Volatile private var gateOkUntil = 0L
    @Volatile private var gateFailUntil = 0L

    // Captcha kapi sayfasi bolum basina yalnizca bir kez incelenir (hiz icin)
    private val gateProbed = AtomicBoolean(false)

    // Hata ayiklama: Vidmoly cikmazsa sebebi kaynak listesinde "DEBUG" satiri olarak gorunur. Sorun cozulunce false yap.
    private val debug = true
    private val dbgNotes = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun note(m: String) { dbgNotes.add(m.replace("\n", " ").take(200)); lg(m) }

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.get(1) ?: mainUrl

    // https://filemoon.sx/e/abc -> "Filemoon"
    private fun hostLabel(u: String): String {
        val h = Regex("""^https?://(?:www\.)?([^./]+)""").find(u)?.groupValues?.get(1) ?: return "Player"
        return h.replaceFirstChar { it.uppercase() }
    }

    // Kaynak adini (AltYazi - Filemoon gibi) baglantinin onune ekler
    private suspend fun relabel(l: ExtractorLink, label: String, q: Int? = null): ExtractorLink =
        newExtractorLink(l.source, "$label - ${l.name}", l.url, l.type) {
            this.referer = l.referer
            this.quality = q ?: l.quality
            this.headers = l.headers
        }

    // Hazir cozuculerden gelen baglantiyi kaynak adiyla yayinlar (eski davranis: source = name = kaynakAdi)
    private suspend fun rename(l: ExtractorLink, nm: String): ExtractorLink =
        newExtractorLink(nm, nm, l.url, l.type) {
            this.referer = l.referer
            this.quality = l.quality
            this.headers = l.headers
        }

    // CDN baglantilari Referer/Origin olmadan 404 verir; oynaticinin Referer ve Origin'i ile verilir.
    private suspend fun emitM3u8(
        m3u8: String,
        label: String,
        embed: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(embed)
        callback(
            newExtractorLink(
                name,
                if (label.contains(hostLabel(embed), ignoreCase = true)) label else "$label - ${hostLabel(embed)}",
                m3u8,
                ExtractorLinkType.M3U8
            ) {
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

    // ---- bysejikuar.com (Byse / Filemoon) oynaticisi: API sifreli (AES-GCM) JSON dondurur ----
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
        if (id.isBlank() || !embed.contains("/e/")) return false

        val h = mapOf("User-Agent" to ua, "Referer" to embed)
        val txt = try {
            app.get("$origin/api/videos/$id", headers = h, timeout = 15L).text
        } catch (e: Exception) {
            return false
        }
        val p = try {
            JSONObject(txt).optJSONObject("playback")
        } catch (e: Exception) {
            null
        } ?: return false

        val arr = p.optJSONArray("key_parts")
        if (arr == null || arr.length() < 1) return false
        val parts = (0 until arr.length()).map { b64u(arr.getString(it)) }

        // Anahtar: parcalardan iki tanesi gercek anahtar (16+16 bayt = AES-256). GCM etiketi dogru anahtari dogrular.
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
        if (keys.isEmpty()) return false

        val ivEnc = try {
            Pair(b64u(p.getString("iv")), b64u(p.getString("payload")))
        } catch (e: Exception) {
            return false
        }
        val iv = ivEnc.first
        val enc = ivEnc.second

        var json: JSONObject? = null
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
            }
        }
        val srcs = json?.optJSONArray("sources") ?: return false

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
                if (links.isNotEmpty()) links.forEach { callback(relabel(it, tag, Qualities.P720.value)) }
                else emitM3u8(url, tag, embed, callback)
            } else {
                callback(
                    newExtractorLink(name, "$tag - Byse", url, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = Qualities.P720.value
                        this.headers = headers
                    }
                )
            }
            ok = true
        }
        return ok
    }

    // ---- Vidmoly (vidmoly.biz / .to / .me / .net): oynatici sayfasinda acik .m3u8 var ----
    private suspend fun extractVidmoly(
        embed: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host0 = Regex("""^https?://([^/]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val id = Regex("""(?:embed-|/w/|/e/|/embed/)([A-Za-z0-9]{6,})""").find(embed)?.groupValues?.get(1)
        // Alan adi sik degisir: once gelen adres, olmazsa ayni kimlikle diger alan adlari denenir
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
                continue
            }
            val clean = html.replace("\\/", "/")
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(clean)?.groupValues?.get(1)
                ?: Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(clean)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) continue
            val link = m3u8.replace("&amp;", "&")
            val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
            val q = Regex("""label\s*:\s*["'](\d{3,4})p""").find(clean)?.groupValues?.get(1)
            val tag = if (q != null) "$label ${q}p" else label

            // Ana (master) liste: oynatici kaliteyi kendi secer
            emitM3u8(link, tag, "$origin/", callback)
            // Kalite adlarini (720p vb.) gorebilmek icin alt listeler de eklenir
            try {
                M3u8Helper.generateM3u8(name, link, "$origin/", headers = headers)
                    .forEach { callback(relabel(it, label, Qualities.Unknown.value)) }
            } catch (e: Exception) {
            }
            lg("vidmoly tamam host=$host")
            return true
        }
        return false
    }

    // Bir gomulu oynaticiyi cozer: Byse -> Vidmoly -> CloudStream'in hazir cozuculeri (eski davranis)
    private suspend fun emitSource(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (embed.contains("byse", ignoreCase = true)) {
            val okByse = try {
                extractBysejikuar(embed, label, callback)
            } catch (e: Exception) {
                false
            }
            if (okByse) return true
        }
        if (embed.contains("vidmoly", ignoreCase = true)) {
            val okVm = try {
                extractVidmoly(embed, label, callback)
            } catch (e: Exception) {
                false
            }
            if (okVm) return true
        }
        val tmp = ArrayList<ExtractorLink>()
        try {
            loadExtractor(embed, "${mainUrl}/", subtitleCallback) { synchronized(tmp) { tmp.add(it) } }
        } catch (e: Exception) {
        }
        tmp.forEach { callback(rename(it, label)) }
        return tmp.isNotEmpty()
    }

    // ---- Turnstile kapisi (Vidmoly kaynagi bu kapinin arkasinda) ----
    // Kapi adresi gorunmez WebView'de acilir ve kendi kendine cozmesi icin sure taninir.
    // useOkhttp = false: WebView kendi ag yigini ve cerez yoneticisini kullanir, dogrulama cerezi
    // WebView'in CookieManager'ina yazilir; sonra geri alinip oturum cerezlerine eklenir.
    private suspend fun solveGate(ref: String, gateUrl: String) {
        val gate = when {
            gateUrl.startsWith("//") -> "https:$gateUrl"
            gateUrl.startsWith("/") -> mainUrl + gateUrl
            else -> gateUrl
        }
        val cm = try { android.webkit.CookieManager.getInstance() } catch (e: Throwable) { null }
        try {
            cm?.setAcceptCookie(true)
            sessionCookies.forEach { (k, v) -> cm?.setCookie(mainUrl, "$k=$v; Path=/") }
            cm?.flush()
        } catch (e: Throwable) {
        }
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
                    timeout = 15_000L
                )
            )
        } catch (e: Exception) {
        }
        try {
            val before = sessionCookies.size
            cm?.getCookie(mainUrl)?.split(";")?.forEach { c ->
                val kv = c.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) sessionCookies = sessionCookies + (kv[0] to kv[1])
            }
            lg("kapi webview bitti cerez $before->${sessionCookies.size} adlar=${sessionCookies.keys.joinToString(",")}")
        } catch (e: Throwable) {
        }
    }

    // Kapi adresinin icinde ya da (oturum cerezleriyle acilan) sayfasinda gercek oynatici adresi var mi bakar
    private suspend fun captchaInner(u: String, ref: String, force: Boolean = false): String? {
        val full = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl + u
            else -> u
        }
        val dec = try { java.net.URLDecoder.decode(full, "UTF-8") } catch (e: Exception) { full }
        Regex("""https?://[^\s"'&<>]+""").findAll(dec.substringAfter("?", "")).map { it.value }
            .firstOrNull { !it.contains("sezonlukdizi", true) && !it.contains("recaptcha", true) }
            ?.let { return it }
        if (force || gateProbed.compareAndSet(false, true)) {
            try {
                val h = app.get(full, headers = hdr(), referer = ref, cookies = sessionCookies, timeout = 10L).text
                Regex("""https?://[^\s"'<>\\]*(?:vidmoly|embed-)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(h.replace("\\/", "/"))?.value?.let { return it }
            } catch (e: Exception) {
            }
        }
        return null
    }

    // Embed adresini ister (Cloudflare 1015 hiz siniri gelirse kisa bekleyip tekrar dener)
    private suspend fun embedSrc(id: Int, ref: String): String {
        for (att in 0..2) {
            val r = try {
                app.post(
                    "${mainUrl}/ajax/dataEmbed22.asp",
                    headers = mapOf("User-Agent" to ua, "X-Requested-With" to "XMLHttpRequest", "Referer" to ref),
                    cookies = sessionCookies,
                    data = mapOf("id" to "$id")
                )
            } catch (e: Exception) {
                null
            }
            if (r != null && r.cookies.isNotEmpty()) sessionCookies = sessionCookies + r.cookies
            val src = r?.document?.selectFirst("iframe")?.attr("src")?.trim().orEmpty()
            if (src.isNotEmpty()) return src
            val raw = r?.text.orEmpty()
            val limited = raw.contains("1015") || raw.contains("rate limit", true) || raw.contains("too many", true)
            if (!limited || att == 2) break
            try { Thread.sleep(3000L * (att + 1)) } catch (e: Exception) { }
        }
        return ""
    }

    // Kapi (reCAPTCHA / Turnstile) donen bir Vidmoly adresinden gercek oynatici adresini cikarir
    private suspend fun resolveGate(gateUrl: String, ref: String, id: Int): String? {
        lg("vidmoly kapi url=$gateUrl")
        // baska bir kaynak kapiyi az once cozduyse, cerezlerle dogrudan yeniden dene
        if (System.currentTimeMillis() < gateOkUntil) {
            val again = embedSrc(id, ref)
            if (again.isNotEmpty() && !again.contains("reCAPTCHA", true)) return again
        }
        var inner: String? = try { captchaInner(gateUrl, ref, true) } catch (e: Exception) { null }
        if (!inner.isNullOrBlank()) {
            lg("vidmoly kapidan adres cikti: $inner")
            return inner
        }
        if (System.currentTimeMillis() < gateFailUntil) {
            lg("vidmoly kapi yakin zamanda cozulemedi, atlandi")
            return null
        }
        solveGate(ref, gateUrl)
        val f2 = embedSrc(id, ref)
        inner = if (f2.isNotEmpty() && !f2.contains("reCAPTCHA", true)) f2
        else try { captchaInner(f2.ifEmpty { gateUrl }, ref, true) } catch (e: Exception) { null }
        if (inner.isNullOrBlank()) {
            gateFailUntil = System.currentTimeMillis() + 3 * 60_000L
            lg("vidmoly kapi COZULEMEDI (sonraki deneme 3 dk sonra) f2=$f2")
            return null
        }
        gateCookies = sessionCookies
        gateOkUntil = System.currentTimeMillis() + 15 * 60_000L
        lg("vidmoly kapi cozuldu: $inner")
        return inner
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        gateProbed.set(false)
        dbgNotes.clear()
        sessionCookies = if (System.currentTimeMillis() < gateOkUntil) gateCookies else emptyMap()
        // Calisan surumdeki gibi: her dil icin o dilin kendi bolum sayfasi (Dublaj: /dublaj/, Altyazi: normal) acilir;
        // oturum cerezleri iki sayfadan toplanir, bid ve Referer o dilin sayfasindan alinir (kapi bunu kontrol ediyor).
        val m = Regex("""^(https?://[^/]+)/([a-z0-9-]+)/(?:dublaj/)?(\d+)-sezon-(\d+)-bolum\.html""").find(data)
        val sayfaAlt = if (m != null) "${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}-sezon-${m.groupValues[4]}-bolum.html" else data
        val sayfaDub = if (m != null) "${m.groupValues[1]}/${m.groupValues[2]}/dublaj/${m.groupValues[3]}-sezon-${m.groupValues[4]}-bolum.html" else data
        val sayfalar = mapOf("0" to sayfaDub, "1" to sayfaAlt)
        val bidler = HashMap<String, String>()
        for ((dil, pu) in sayfalar) {
            try {
                val res = app.get(pu, headers = hdr(), referer = "$mainUrl/")
                if (res.cookies.isNotEmpty()) sessionCookies = sessionCookies + res.cookies
                val b = res.document.selectFirst("div#dilsec")?.attr("data-id")?.trim().orEmpty()
                if (b.isNotEmpty()) bidler[dil] = b
            } catch (e: Exception) {
                lg("sayfa hata dil=$dil ${e.message}")
            }
            if (sayfaDub == sayfaAlt) break
        }
        val bidYedek = bidler.values.firstOrNull().orEmpty()
        if (bidYedek.isEmpty()) return false

        // dil=1 -> altyazi, dil=0 -> dublaj
        // Gorunum sirasi: Dublaj Filemoon, Altyazi Filemoon, Dublaj Vidmoly, Altyazi Vidmoly
        // Filemoon (kapisiz) kaynaklar once hemen eklenir, kapinin arkasindaki Vidmoly'ler en sonda.
        val diller = listOf("0" to "Dublaj", "1" to "Altyazı")
        class Is(val etiket: String, val veri: KaynakData, val vidmoly: Boolean, val ref: String)
        val isler = ArrayList<Is>()
        for ((dil, etiket) in diller) {
            val ref = sayfalar[dil] ?: data
            val alts = alternatifGetir(bidler[dil] ?: bidYedek, dil, ref)
            note("dil=$dil bid=${bidler[dil] ?: bidYedek} alt=" + alts.joinToString(",") { it.baslik })
            // Her dilde yalnizca ilk Filemoon gosterilir (sitedeki ayni adli ek Filemoon'lar atlanir)
            var filemoonSayisi = 0
            for (veri in alts) {
                val vm = veri.baslik.contains("vidmoly", ignoreCase = true)
                val fm = veri.baslik.contains("filemoon", ignoreCase = true)
                if (fm && ++filemoonSayisi > 1) continue
                isler.add(Is(etiket, veri, vm, ref))
            }
        }

        var any = false
        var vidmolyVar = false
        val goruldu = HashSet<String>()
        for (i in isler.filter { !it.vidmoly } + isler.filter { it.vidmoly }) {
            val links = try { kaynakCoz(i.veri, i.etiket, i.ref, subtitleCallback) } catch (e: Exception) { emptyList() }
            for (l in links) {
                if (goruldu.add(l.name + "|" + l.url)) {
                    callback(l)
                    any = true
                    if (i.vidmoly) vidmolyVar = true
                }
            }
        }

        // Vidmoly cikmadiysa sebebi listeye DEBUG satiri olarak eklenir
        if (debug && !vidmolyVar) {
            val notlar = synchronized(dbgNotes) { ArrayList(dbgNotes) }
            notlar.chunked(2).take(4).forEachIndexed { n, grup ->
                callback(
                    newExtractorLink(name, "DEBUG ${n + 1} [Vidmoly yok] " + grup.joinToString(" | ").take(480), "https://debug.invalid/$n", ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return any
    }

    // /ajax/dataAlternatif22.asp (bid + dil) -> [{id, baslik, kalite}]
    private suspend fun alternatifGetir(bid: String, dil: String, ref: String): List<KaynakData> {
        return try {
            app.post(
                "${mainUrl}/ajax/dataAlternatif22.asp",
                headers = mapOf("User-Agent" to ua, "X-Requested-With" to "XMLHttpRequest", "Referer" to ref),
                cookies = sessionCookies,
                data = mapOf("bid" to bid, "dil" to dil)
            ).parsedSafe<Kaynak>()?.takeIf { it.status == "success" }?.data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // /ajax/dataEmbed22.asp (id) -> <iframe src="..."> ; tek bir alternatifi cozer ve linklerini dondurur
    private suspend fun kaynakCoz(
        veri: KaynakData,
        etiket: String,
        ref: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        gorunenAd: String = veri.baslik
    ): List<ExtractorLink> {
        val out = ArrayList<ExtractorLink>()
        try {
            val vm = veri.baslik.contains("vidmoly", ignoreCase = true)
            val kaynakAdi = "$etiket - $gorunenAd"
            var src = embedSrc(veri.id, ref)
            if (vm) note("$etiket vidmoly id=${veri.id} src=${src.take(90)}")

            // bazi alternatifler (Pixel) reCAPTCHA kapisi dondurur, atla; Vidmoly icin kapi asilmaya calisilir
            if (src.contains("reCAPTCHA", ignoreCase = true)) {
                if (!vm) return out
                src = resolveGate(src, ref, veri.id) ?: run { note("$etiket vidmoly kapi cozulemedi"); return out }
            }
            if (src.isEmpty()) return out
            val iframe = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("http") -> src
                else -> fixUrlNull(src) ?: return out
            }

            val ok = emitSource(iframe, kaynakAdi, subtitleCallback) { synchronized(out) { out.add(it) } }
            if (vm) note("$etiket vidmoly ${iframe.take(70)} cikarma=$ok")
        } catch (e: Exception) {
            lg("kaynakCoz hata ${veri.baslik}: ${e.message}")
        }
        // Bir kaynak icin TEK baglanti: Byse/Vidmoly kalite varyantlari ayni adla tekrar liste doldurmasin
        val tek = synchronized(out) {
            out.firstOrNull { it.url.contains("master.m3u8", true) } ?: out.maxByOrNull { it.quality } ?: out.firstOrNull()
        }
        return if (tek == null) emptyList() else listOf(tek)
    }

    data class Kaynak(val status: String = "", val data: List<KaynakData>? = null)
    data class KaynakData(val id: Int = 0, val baslik: String = "", val kalite: Int = 0)

    // /ajax/arama.asp cevabi: {status, results:{<kategori>:{name, results:[{title,url,image}]}}}
    data class Arama(val status: String = "", val results: Map<String, AramaGrup>? = null)
    data class AramaGrup(val name: String = "", val results: List<AramaSonuc>? = null)
    data class AramaSonuc(val title: String = "", val description: String = "", val url: String = "", val image: String = "")
    data class AspData(val alternatif: String, val embed: String)
}

// ---------------- TMDB JSON veri siniflari (alan adlari TMDB JSON'u ile birebir ayni) ----------------
data class TmdbTvResult(
    val id: Int? = null,
    val name: String? = null,
    val original_name: String? = null,
    val popularity: Double? = null
)

data class TmdbSearchResponse(
    val results: List<TmdbTvResult>? = null
)

data class TmdbSeasonInfo(
    val season_number: Int? = null,
    val episode_count: Int? = null
)

data class TmdbTvDetails(
    val id: Int? = null,
    val popularity: Double? = null,
    val seasons: List<TmdbSeasonInfo>? = null
)

data class TmdbEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val runtime: Int? = null,
    val air_date: String? = null
)

data class TmdbSeasonDetails(
    val episodes: List<TmdbEpisode>? = null
)
