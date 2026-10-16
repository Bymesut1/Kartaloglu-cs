package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SezonlukDizi : MainAPI() {
    override var mainUrl        = "https://sezonlukdizi.cc"
    override var name           = "SezonlukDizi"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // Her şey çalışınca false yap
    private val debugMode = true

    private val androidUA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=2&s="    to "Yerli Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=1&s="    to "Yabancı Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=3&s="    to "Asya Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=4&s="    to "Animasyonlar",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=5&s="    to "Animeler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=6&s="    to "Belgeseller",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.afis a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q        = java.net.URLEncoder.encode(query, "UTF-8")
        val url      = "${mainUrl}/diziler.asp?adi=$q"
        val document = app.get(url).document

        Log.d("SZD", "ARAMA url=$url len=${document.html().length} govde=${document.body().text().take(300)}")
        val results = document.select("div.afis a, a.column[href*='/diziler/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (results.isEmpty()) {
            val viaAjax = try { ajaxSearch(query) } catch (e: Exception) { Log.d("SZD", "ajaxSearch hata ${e.message}"); emptyList() }
            if (viaAjax.isNotEmpty()) return viaAjax
        }

        if (results.isEmpty() && debugMode) {
            // Boşsa nedenini görelim: her bilgi ayrı, kısa bir kart
            val infos = listOf(
                "DEBUG html=${document.html().length}",
                "DEBUG a=${document.select("a").size} afis=${document.select("div.afis").size}",
                "DEBUG baslik: ${document.title().take(40)}",
                "DEBUG govde: ${document.body().text().take(70)}",
                "DEBUG url: ${url.takeLast(40)}"
            )
            return infos.mapIndexed { i, t ->
                newTvSeriesSearchResponse(t, "$mainUrl/?debug=$i", TvType.TvSeries)
            }
        }
        return results
    }

    private val xhr = mapOf("X-Requested-With" to "XMLHttpRequest")

    private fun jsonCollect(node: Any?, out: MutableList<SearchResponse>) {
        when (node) {
            is org.json.JSONObject -> {
                val title = listOf("title", "baslik", "name", "ad", "adi")
                    .firstNotNullOfOrNull { k -> node.optString(k).takeIf { it.isNotBlank() } }
                val link  = listOf("url", "link", "href")
                    .firstNotNullOfOrNull { k -> node.optString(k).takeIf { it.isNotBlank() } }
                if (title != null && link != null) {
                    val img = listOf("image", "img", "poster", "resim")
                        .firstNotNullOfOrNull { k -> node.optString(k).takeIf { it.isNotBlank() } }
                    out.add(newTvSeriesSearchResponse(title, fixUrl(link), TvType.TvSeries) { this.posterUrl = fixUrlNull(img) })
                }
                val keys = node.keys()
                while (keys.hasNext()) jsonCollect(node.opt(keys.next()), out)
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) jsonCollect(node.opt(i), out)
        }
    }

    // Sitenin kendi canlı arama ucu (/ajax/arama.asp). Parametre adı bilinmediği için birkaç biçim denenir.
    private suspend fun ajaxSearch(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        try {
            val js = app.get("${mainUrl}/js/site.min.js").text
            val i  = js.indexOf("arama.asp")
            if (i >= 0) Log.d("SZD", "JS[arama] " + js.substring(maxOf(0, i - 300), minOf(js.length, i + 400)).replace(Regex("\\s+"), " "))
        } catch (e: Exception) { }

        val names = listOf("GET q", "POST q", "POST dizi", "POST adi")
        for ((idx, name) in names.withIndex()) {
            val txt = try {
                when (idx) {
                    0 -> app.get("${mainUrl}/ajax/arama.asp?q=$q", headers = xhr, referer = mainUrl).text
                    1 -> app.post("${mainUrl}/ajax/arama.asp", headers = xhr, data = mapOf("q" to query)).text
                    2 -> app.post("${mainUrl}/ajax/arama.asp", headers = xhr, data = mapOf("dizi" to query)).text
                    else -> app.post("${mainUrl}/ajax/arama.asp", headers = xhr, data = mapOf("adi" to query)).text
                }
            } catch (e: Exception) { Log.d("SZD", "ARAMA AJAX $name hata ${e.message}"); continue }
            Log.d("SZD", "ARAMA AJAX $name -> ${txt.take(300).replace(Regex("\\s+"), " ")}")
            val out = mutableListOf<SearchResponse>()
            val t = txt.trim()
            try {
                if (t.startsWith("{")) jsonCollect(org.json.JSONObject(t), out)
                else if (t.startsWith("[")) jsonCollect(org.json.JSONArray(t), out)
                else {
                    org.jsoup.Jsoup.parse(t).select("a[href]").forEach { a ->
                        val title = a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt") ?: "" }
                        val href  = fixUrlNull(a.attr("href"))
                        if (title.isNotBlank() && href != null) {
                            out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                this.posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
                            })
                        }
                    }
                }
            } catch (e: Exception) { Log.d("SZD", "ARAMA AJAX $name parse hata ${e.message}") }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.image img")?.attr("data-src")) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()

        val endpoint    = url.split("/").last()

        val actorsReq = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors    = actorsReq.select("div.doubling div.ui").mapNotNull {
            val actorName = it.selectFirst("div.header")?.text()?.trim() ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }

        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epName    = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epHref    = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("td:nth-of-type(3)")?.text()?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                val epSeason  = bolum.selectFirst("td:nth-of-type(2)")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            addActors(actors)
        }
    }

    // ---------------- Linkler ----------------
    //
    // Neden çalışıyor:
    //  1) Sitenin kendi AJAX uçları (dataAlternatif / dataEmbed) doğrudan iframe adresini veriyor.
    //  2) iframe, elle çözmek yerine CloudStream'in hazır extractor'larına (loadExtractor) veriliyor.
    //  3) Extractor'un döndürdüğü url / type / headers / referer AYNEN kullanılıyor.
    //  Olmazsa: WebView ile gerçek m3u8 isteği yakalanıyor ve #EXTM3U ile doğrulanıyor.

    // WebView yedeği şimdilik kapalı (takılmanın sebebi mi diye elemek için)
    private val useWebView = false

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dbg = java.util.Collections.synchronizedList(ArrayList<String>())
        val anyFound = AtomicBoolean(false)

        // Debug satırları listeye eklenir, en sonda topluca gösterilir
        // (anında göstermek oynatıcının ilk sahte linki seçip hata vermesine yol açıyordu)
        suspend fun d(msg: String) { dbg.add(msg); Log.d("SZD", msg) }

        d("basladi ${data.takeLast(40)}")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            try {
                val document = withTimeoutOrNull(15_000L) { app.get(data).document }
                if (document == null) { d("bolum sayfasi zaman asimi"); return@launch }
                d("bolum sayfasi ok")

                val aspData = withTimeoutOrNull(15_000L) { getAspData() }
                if (aspData == null) { d("site.min.js zaman asimi"); return@launch }

                val bid = document.selectFirst("div#dilsec")?.attr("data-id")
                d("asp alt=${aspData.alternatif} emb=${aspData.embed} bid=$bid")
                if (bid == null) return@launch

                val sem      = Semaphore(6)
                val embedSem = Semaphore(1)   // dataEmbed istekleri tek tek (Cloudflare 1015 hız sınırı)
                val wvSem = Semaphore(2)

                for ((dil, etiket) in listOf("1" to "AltYazı", "0" to "Dublaj")) {
                    launch {
                        val raw = try {
                            withTimeoutOrNull(15_000L) {
                                app.post(
                                    "${mainUrl}/ajax/dataAlternatif${aspData.alternatif}.asp",
                                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                    data    = mapOf("bid" to bid, "dil" to dil)
                                ).text
                            }
                        } catch (e: Exception) {
                            d("ALT hata $etiket ${e.message?.take(50)}"); return@launch
                        }
                        if (raw == null) { d("ALT zaman asimi $etiket"); return@launch }
                        d("RAW $etiket ${raw.take(120).replace(Regex("\\s+"), " ")}")

                        val resp = tryParseJson<Kaynak>(raw)
                        if (resp == null || resp.status != "success") {
                            d("$etiket yok (${resp?.status})"); return@launch
                        }
                        val list = resp.data
                        if (list == null) { d("$etiket data null"); return@launch }
                        d("$etiket kaynak ${list.size}")

                        for (veri in list) {
                            launch {
                                sem.withPermit {
                                    val label = "$etiket - ${veri.baslik}"
                                    val embedResp = try {
                                        embedSem.withPermit {
                                            var r = withTimeoutOrNull(15_000L) {
                                                app.post(
                                                    "${mainUrl}/ajax/dataEmbed${aspData.embed}.asp",
                                                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                                    data    = mapOf("id" to "${veri.id}")
                                                )
                                            }
                                            if (r != null && r.text.contains("1015")) {
                                                delay(3_000L)
                                                r = withTimeoutOrNull(15_000L) {
                                                    app.post(
                                                        "${mainUrl}/ajax/dataEmbed${aspData.embed}.asp",
                                                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                                                        data    = mapOf("id" to "${veri.id}")
                                                    )
                                                }
                                            }
                                            delay(700L)
                                            r
                                        }
                                    } catch (e: Exception) {
                                        d("EMB hata $label ${e.message?.take(50)}"); return@withPermit
                                    }
                                    if (embedResp == null) { d("EMB zaman asimi $label"); return@withPermit }
                                    val embedDoc = embedResp.document
                                    Log.d("SZD", "EMBRAW $label ${embedResp.text.take(500).replace(Regex("\\s+"), " ")}")

                                    val iframe = fixUrlNull(embedDoc.selectFirst("iframe")?.attr("src"))
                                    if (iframe == null) { d("$label iframe yok"); return@withPermit }
                                    if (iframe.contains("reCAPTCHA", true)) {
                                        d("$label CAPTCHA (Turnstile) - atlandi"); return@withPermit
                                    }

                                    val got = ConcurrentLinkedQueue<ExtractorLink>()
                                    try {
                                        withTimeoutOrNull(20_000L) {
                                            loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { got.add(it) }
                                        }
                                    } catch (e: Exception) {
                                        d("LE hata $label ${e.message?.take(50)}")
                                    }
                                    if (got.isEmpty() && iframe.contains("byse", true)) {
                                        try {
                                            withTimeoutOrNull(25_000L) {
                                                ByseExtractor().getUrl(iframe, "${mainUrl}/", subtitleCallback) { got.add(it) }
                                            }
                                        } catch (e: Exception) {
                                            d("BYSE hata $label ${e.message?.take(60)}")
                                        }
                                    }
                                    d("LE $label ${got.size} ${iframe.take(60)}")

                                    if (got.isNotEmpty()) {
                                        for (link in got) {
                                            callback(
                                                newExtractorLink(
                                                    source = label,
                                                    name   = label,
                                                    url    = link.url,
                                                    type   = link.type
                                                ) {
                                                    this.referer = link.referer
                                                    this.quality = link.quality
                                                    this.headers = link.headers
                                                }
                                            )
                                            anyFound.set(true)
                                        }
                                    } else if (useWebView) {
                                        val wv = wvSem.withPermit {
                                            try { webViewLink(iframe, data, label, dbg) } catch (e: Exception) {
                                                d("WV hata $label ${e.message?.take(50)}"); null
                                            }
                                        }
                                        if (wv != null) { callback(wv); anyFound.set(true) }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                d("GENEL HATA ${e.javaClass.simpleName} ${e.message?.take(60)}")
            }
        }

        // En fazla 35 sn bekle; sonra ne varsa onunla dön (sonsuz dönmesin)
        val finished = withTimeoutOrNull(35_000L) { job.join(); true }
        if (finished == null) d("ZAMAN ASIMI 35sn")
        scope.cancel()

        if (debugMode) {
            dbg.toList().take(40).forEach {
                callback(
                    newExtractorLink(name, "DEBUG $it", "https://debug.invalid/", ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }
        return anyFound.get()
    }

    // ---------------- WebView yedeği + doğrulama ----------------

    private val badExt =
        Regex("""\.(vtt|srt|jpg|jpeg|png|webp|gif|css|js|ico|svg)(\?|$)""", RegexOption.IGNORE_CASE)

    private fun originOf(u: String) = Regex("^https?://[^/]+").find(u)?.value ?: ""

    private suspend fun webViewLink(
        iframe: String, pageUrl: String, label: String, dbg: MutableList<String>
    ): ExtractorLink? {
        val resp = app.get(
            iframe,
            referer = pageUrl,
            headers = mapOf("User-Agent" to androidUA),
            interceptor = WebViewResolver(
                Regex("""\.m3u8|\.mp4(\?|$)|master\.txt|/hls/""", RegexOption.IGNORE_CASE)
            )
        )
        val u = resp.url
        dbg.add("WV $label ${u.take(90)}")
        if (u == iframe || badExt.containsMatchIn(u)) return null

        val origin = originOf(iframe)
        val isMp4  = Regex("""\.mp4(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(u)
        val variants = listOf(
            mapOf("User-Agent" to androidUA, "Referer" to "$origin/", "Origin" to origin),
            mapOf("User-Agent" to androidUA, "Referer" to "$mainUrl/", "Origin" to mainUrl),
            mapOf("User-Agent" to androidUA)
        )
        var headers = variants[0]
        if (!isMp4) {
            for ((i, h) in variants.withIndex()) {
                val r = try { app.get(u, headers = h) } catch (e: Exception) { null } ?: continue
                dbg.add("V $label h$i ${r.code} ${r.text.take(20).replace(Regex("\\s+"), " ")}")
                if (r.code == 200 && r.text.trimStart().startsWith("#EXTM3U")) { headers = h; break }
            }
        }
        return newExtractorLink(
            label, label, u,
            if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
        ) {
            this.referer = "$origin/"
            this.quality = Qualities.Unknown.value
            this.headers = headers
        }
    }

    // dataAlternatif / dataEmbed sonundaki sürüm numarasını site.min.js'ten alır
    private suspend fun getAspData(): AspData {
        val js  = app.get("${this.mainUrl}/js/site.min.js").text
        val alt = Regex("""dataAlternatif(.*?).asp""").find(js)?.groupValues?.get(1) ?: ""
        val emb = Regex("""dataEmbed(.*?).asp""").find(js)?.groupValues?.get(1) ?: ""
        // Teşhis: sitenin tüm ajax uçları ve captcha / embed çevresindeki kod logcat'e yazılır
        try {
            val eps = Regex("""/ajax/\w+\.asp""").findAll(js).map { it.value }.distinct().toList()
            Log.d("SZD", "AJAX UCLARI $eps")
            for (key in listOf("reCAPTCHADATA", "dataEmbed", "g-recaptcha", "grecaptcha")) {
                val i = js.indexOf(key)
                if (i >= 0) Log.d("SZD", "JS[$key] " + js.substring(maxOf(0, i - 350), minOf(js.length, i + 450)).replace(Regex("\\s+"), " "))
            }
        } catch (e: Exception) { Log.d("SZD", "JS teshis hata ${e.message}") }
        return AspData(alt, emb)
    }
}


// ---- Yeni Filemoon ("Byse") alan adları için elle yazılmış çözücü ----
data class ByseDetails(@JsonProperty("embed_frame_url") val embedFrameUrl: String?)
data class ByseRoot(@JsonProperty("playback") val playback: ByseEnc?)
data class ByseEnc(
    @JsonProperty("iv")        val iv: String?,
    @JsonProperty("payload")   val payload: String?,
    @JsonProperty("key_parts") val keyParts: List<String>?
)
data class ByseSources(@JsonProperty("sources") val sources: List<ByseSrc>?)
data class ByseSrc(
    @JsonProperty("url")       val url: String?,
    @JsonProperty("mime_type") val mimeType: String?
)

class ByseExtractor : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://bysejikuar.com"
    override val requiresReferer = false

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    private fun b64(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = Regex("^https?://[^/]+").find(url)?.value ?: return
        val code = url.substringAfterLast("/").substringBefore("?")
        var frameHost = host
        var frameUrl  = url

        try {
            val dTxt = app.get(
                "$host/api/videos/$code/embed/details",
                headers = mapOf("Referer" to url, "User-Agent" to ua)
            ).text
            Log.d("SZD", "BYSE details ${dTxt.take(300)}")
            val frame = tryParseJson<ByseDetails>(dTxt)?.embedFrameUrl
            if (!frame.isNullOrBlank()) {
                frameUrl  = frame
                frameHost = Regex("^https?://[^/]+").find(frame)?.value ?: host
            }
        } catch (e: Exception) { Log.d("SZD", "BYSE details hata ${e.message}") }

        val pTxt = app.get(
            "$frameHost/api/videos/$code/embed/playback",
            headers = mapOf(
                "Referer" to frameUrl,
                "X-Embed-Parent" to url,
                "Accept" to "*/*",
                "User-Agent" to ua
            )
        ).text
        Log.d("SZD", "BYSE playback ${pTxt.take(300)}")
        val pb = tryParseJson<ByseRoot>(pTxt)?.playback ?: return

        val iv      = b64(pb.iv ?: return)
        val payload = b64(pb.payload ?: return)
        val key     = pb.keyParts?.map { b64(it) }?.fold(ByteArray(0)) { acc, x -> acc + x } ?: return

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        val json = String(cipher.doFinal(payload), Charsets.UTF_8)
        Log.d("SZD", "BYSE cozuldu ${json.take(300)}")

        val srcs = tryParseJson<ByseSources>(json)?.sources ?: return
        for (src in srcs) {
            val link = src.url?.takeIf { it.isNotBlank() } ?: continue
            val isM3u8 = link.contains(".m3u8") || (src.mimeType?.contains("mpegurl", true) == true)
            callback(
                newExtractorLink(
                    name, name, link,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$frameHost/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to ua, "Referer" to "$frameHost/")
                }
            )
        }
    }
}
