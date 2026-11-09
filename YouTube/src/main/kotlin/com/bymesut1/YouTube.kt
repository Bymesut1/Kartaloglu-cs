@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! https://github.com/recloudstream/extensions/blob/master/InvidiousProvider/src/main/kotlin/recloudstream/InvidiousProvider.kt

package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class YouTube : MainAPI() {
    override var mainUrl              = "https://yt.chocolatemoo53.com"
    override var name                 = "YouTube"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val supportedTypes       = setOf(TvType.Others)

    // Invidious örnekleri API'yi zaman zaman kapatabiliyor (inv.nadeko.net gibi),
    // bu yüzden TUM instance'lar AYNI ANDA denenir, ilk cevap veren kazanir.
    // 2026-11: API'si acik kalan ornekler (f5.si 530, invidious.site 404, nadeko/nerdvpn API kapali).
    private val instances = listOf(
        "yt.chocolatemoo53.com",
        "invidious.materialio.us",
        "yewtu.be",
    )

    // KOK NEDEN (SocketTimeoutException): Instance, o videoyu YouTube'dan ILK kez isterken 10 sn'den
    // uzun surebiliyor. Uygulamanin varsayilan zaman asimi 10 sn oldugu icin ilk tikta
    // "Error loading" / "Baglanti bulunamadi" cikiyor, ikinci tikta instance onbellekten cevap veriyordu.
    // Cozum: uzun zaman asimi + tekrar deneme + instance'lari paralel yarıstirma + load() yedegi.
    private val apiTimeout = 25L   // saniye
    private val playTimeout = 20L  // saniye
    private val retryCount = 2     // her istek en fazla bu kadar denenir

    private fun isJson(body: String) = body.startsWith("[") || body.startsWith("{")

    // true  -> once DASH (en yuksek kalite) denenir, olmazsa duz mp4/HLS yedekleri.
    // false -> once duz mp4 (360p/720p, en guvenilir), DASH en sonda listelenir.
    private val preferDash = false

    // Arama/ana sayfadan gelen basliklari hatirlar; load() API'si cevap vermezse bile baslik gosterilir.
    private val titleCache = ConcurrentHashMap<String, String>()

    // Tum instance'larda ayni anda calisir, ilk BASARILI (null olmayan) sonucu dondurur.
    // Ayri bir scope kullanilir: yavas instance'in bitmesini beklemeyiz.
    private suspend fun <T : Any> firstSuccess(block: suspend (String) -> T?): T? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ch    = Channel<T?>(Channel.UNLIMITED)
        try {
            instances.forEach { host ->
                scope.launch {
                    val r = try {
                        block(host)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    ch.send(r)
                }
            }
            for (i in instances.indices) {
                val r = ch.receive()
                if (r != null) return r
            }
            return null
        } finally {
            scope.cancel()
        }
    }

    private suspend fun apiText(path: String): String? {
        for (attempt in 0 until retryCount) {
            val body = firstSuccess { host ->
                val res = app.get("https://$host$path", headers = playHeaders(host), timeout = apiTimeout)
                if (res.code == 200 && isJson(res.text)) res.text else null
            }
            if (body != null) return body
        }
        return null
    }

    // KOK NEDEN (403): /api/manifest/dash/id/ID adresi "local=true" olmadan parca adreslerini
    // dogrudan googlevideo.com'a verir (sunucu IP'sine kilitli -> telefondan 403).
    private fun dashWithLocal(u: String): String {
        var r = u
        if (!r.contains("local=true"))   r += (if (r.contains("?")) "&" else "?") + "local=true"
        if (!r.contains("unique_res=1")) r += "&unique_res=1"
        return r
    }

    // Manifest gecerli mi? (canli yayinda "PTNaNS" suresi uretiliyor -> ExoPlayer cokuyor)
    private suspend fun checkDash(host: String, url: String): String? = try {
        val res = app.get(url, headers = playHeaders(host), timeout = playTimeout)
        val t   = res.text
        if (res.code == 200 && t.contains("<MPD") && t.contains("<AdaptationSet") && !t.contains("NaN")) url else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }

    // TARAYICININ KULLANDIGI MANIFEST: video sayfasi parcalari yt-comp*.chocolatemoo53.com
    // uzerinden proxy'leyen "companion" manifest'ini ve her sayfa acilisinda degisen "check=" imzasini
    // kullaniyor:
    //   https://yt-comp3.../companion/api/manifest/dash/id/ID?local=true&unique_res=1&check=...
    // check imzasini hesaplayamayiz; o yuzden watch sayfasindan okuyoruz.
    private suspend fun companionDashUrl(host: String, videoId: String): String? = try {
        val res  = app.get("https://$host/watch?v=$videoId", headers = playHeaders(host), timeout = playTimeout)
        val html = res.text.replace("&amp;", "&").replace("\\u0026", "&").replace("\\/", "/")
        val rx   = Regex("""(https?://[A-Za-z0-9.\-]+)?(/companion/api/manifest/dash/id/$videoId\?[^"'<>\s\\]+)""")
        val m    = rx.find(html)
        if (m == null) null else m.groupValues[1].ifBlank { "https://$host" } + m.groupValues[2]
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }

    private suspend fun validDash(host: String, videoId: String, apiDashUrl: String?): String? {
        val base = if (!apiDashUrl.isNullOrBlank()) absUrl(host, apiDashUrl)
                   else "https://$host/api/manifest/dash/id/$videoId"
        return checkDash(host, dashWithLocal(base))
    }

    // Tarayicida (Chrome) calisan videoplayback istekleri uygulamada 403 veriyordu.
    // Tarayicinin gonderdigi UA / Referer / Origin basliklarini ExoPlayer'a da veriyoruz.
    private val browserUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // Sunucu videoplayback isteklerini "captcha_verified" cerezi olmadan 403 ile reddediyor
    // (tarayicidaki istekte bu cerez var, uygulamadakinde yoktu). Cerez ~1 haftada doluyor:
    // 1794810006 = 16 Kasim 2026. Dolunca tarayicida siteyi ac, DevTools > Ag > istek basliklari >
    // Cookie icindeki captcha_verified=... degerini buraya yapistir.
    private val captchaCookie =
        "captcha_verified=1794810006.full..b45f6030ddcb2c5d2dac0b1b41dbad2a926141a6f2d36125971c1c75edfb59a0"

    private fun playHeaders(host: String): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to browserUa,
            "Referer"    to "https://$host/",
            "Origin"     to "https://$host",
        )
        if (captchaCookie.isNotBlank() && host.endsWith("chocolatemoo53.com")) h["Cookie"] = captchaCookie
        return h
    }

    private fun absUrl(host: String, u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//")   -> "https:$u"
        u.startsWith("/")    -> "https://$host$u"
        else                 -> "https://$host/$u"
    }

    private fun withLocal(u: String): String =
        if (u.contains("local=true")) u else u + (if (u.contains("?")) "&" else "?") + "local=true"

    fun thumb(videoId: String, size: String) = "https://i.ytimg.com/vi/$videoId/$size.jpg"

    private suspend fun entries(path: String): List<SearchEntry> {
        val body = apiText(path) ?: return emptyList()
        return tryParseJson<List<SearchEntry>>(body) ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val region = lang.uppercase()
        val fields = "fields=videoId,title"

        // Bazi Invidious surumleri trending "type" parametresini yok sayip her kategoride
        // ayni listeyi donduruyor. Ayni liste gelirse o bolumu arama sonucuyla dolduruyoruz.
        val trend = entries("/api/v1/trending?region=$region&type=news&$fields")
        val lists = mutableListOf(HomePageList("Trend", trend.map { it.toSearchResponse(this) }, true))

        val extra = listOf(
            Triple("Müzik", "music", "müzik klip"),
            Triple("Film", "movies", "film fragman"),
            Triple("Oyun", "gaming", "oyun gameplay"),
        )
        for ((name, type, query) in extra) {
            var items = entries("/api/v1/trending?region=$region&type=$type&$fields")
            val same = items.take(5).map { it.videoId } == trend.take(5).map { it.videoId }
            if (items.isEmpty() || same) {
                items = entries("/api/v1/search?q=${query.encodeUri()}&region=$region&page=1&type=video&$fields")
            }
            lists.add(HomePageList(name, items.map { it.toSearchResponse(this) }, true))
        }

        return newHomePageResponse(lists, false)
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val body = apiText(
            "/api/v1/search?q=${query.encodeUri()}&region=${lang.uppercase()}&page=1&type=video&fields=videoId,title"
        ) ?: return emptyList()

        return tryParseJson<List<SearchEntry>>(body)?.map { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1) ?: return null
        val body    = apiText(
            "/api/v1/videos/$videoId?region=${lang.uppercase()}&fields=videoId,title,description,recommendedVideos,author,authorThumbnails,formatStreams"
        )

        val entry = body?.let { tryParseJson<VideoEntry>(it) }
        if (entry != null && entry.videoId.isNotBlank()) return entry.toLoadResponse(this)

        // YEDEK: API cevap vermese bile sayfa HER ZAMAN acilir ("Error loading" / "Tarayicida ac" ekrani
        // cikmaz). Oynatma loadLinks icinde kendi tekrar denemeleriyle yapilir.
        return newMovieLoadResponse(
            titleCache[videoId] ?: "YouTube Videosu",
            "$mainUrl/watch?v=$videoId",
            TvType.Others,
            videoId
        ) {
            posterUrl = thumb(videoId, "hqdefault")
        }
    }

    private data class SearchEntry(val title: String = "", val videoId: String = "") {
        fun toSearchResponse(provider: YouTube): SearchResponse {
            if (videoId.isNotBlank() && title.isNotBlank()) provider.titleCache[videoId] = title
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/watch?v=${videoId}",
                TvType.Others
            ) {
                this.posterUrl = provider.thumb(videoId, "mqdefault")
            }
        }
    }

    private data class VideoEntry(
        val title: String = "",
        val description: String? = null,
        val videoId: String = "",
        val recommendedVideos: List<SearchEntry>? = null,
        val author: String? = null,
        val authorThumbnails: List<Thumbnail>? = null
    ) {
        suspend fun toLoadResponse(provider: YouTube): LoadResponse {
            return provider.newMovieLoadResponse(
                title,
                "${provider.mainUrl}/watch?v=${videoId}",
                TvType.Others,
                videoId
            ) {
                plot            = description
                posterUrl       = provider.thumb(videoId, "hqdefault")
                recommendations = recommendedVideos?.map { it.toSearchResponse(provider) }
                actors          = listOf(ActorData(Actor(
                    author ?: "",
                    authorThumbnails?.lastOrNull()?.url ?: ""
                )))
            }
        }
    }

    private data class Thumbnail(val url: String = "")

    private data class FormatStream(
        val url: String? = null,
        val itag: String? = null,
        val qualityLabel: String? = null,
        val quality: String? = null
    )

    private data class LinkInfo(
        val liveNow: Boolean? = null,
        val hlsUrl: String? = null,
        val dashUrl: String? = null,
        val formatStreams: List<FormatStream>? = null
    )

    private data class HostLinks(val host: String, val live: Boolean, val links: List<ExtractorLink>)

    // Tek bir instance icin tum kaynaklari toplar (zaman asiminda tekrar dener).
    private suspend fun fetchHostLinks(host: String, data: String): HostLinks {
        val out = mutableListOf<ExtractorLink>()

        // local=true -> URL'ler instance uzerinden proxy'lenir (googlevideo IP-kilidi 403/2004 hatasini onler)
        var info: LinkInfo? = null
        for (attempt in 0 until retryCount) {
            info = try {
                val res = app.get(
                    "https://$host/api/v1/videos/$data?local=true&region=${lang.uppercase()}&fields=liveNow,hlsUrl,dashUrl,formatStreams",
                    headers = playHeaders(host),
                    timeout = apiTimeout
                )
                if (res.code == 200 && isJson(res.text)) tryParseJson<LinkInfo>(res.text) else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
            if (info != null) break
        }
        val i = info ?: return HostLinks(host, false, emptyList())

        // DIKKAT: Invidious normal videolarda da hlsUrl dondurur (ama bu HLS 3001 hatasi verir).
        // Bu yuzden canli yayin tespiti sadece liveNow / yt_live_broadcast ile yapilir.
        val isLive = i.liveNow == true || i.hlsUrl?.contains("yt_live_broadcast") == true

        // --- CANLI YAYIN: HLS ---
        if (isLive && !i.hlsUrl.isNullOrBlank()) {
            out.add(
                ExtractorLink(
                    "YouTube",
                    "YouTube",
                    withLocal(absUrl(host, i.hlsUrl)),
                    "",
                    Qualities.Unknown.value,
                    true,
                    mapOf(),
                    null,
                    false
                )
            )
        }

        // --- NORMAL VIDEO ---
        if (!isLive) {
            // duz mp4 (video+ses birlikte)
            i.formatStreams.orEmpty()
                .filter { !it.url.isNullOrBlank() }
                .forEach { f ->
                    val label = f.qualityLabel ?: f.quality ?: ""
                    val q     = Regex("(\\d{3,4})").find(label)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Qualities.Unknown.value
                    out.add(
                        ExtractorLink(
                            "YouTube",
                            "YouTube",
                            absUrl(host, f.url!!),
                            "https://$host/",
                            if (preferDash) q else q + 10000, // preferDash=false iken duz mp4 once denenir
                            false,
                            playHeaders(host),
                            null,
                            false
                        )
                    )
                }

            // tarayicidaki companion DASH (calisan yol)
            val comp = companionDashUrl(host, data)?.let { checkDash(host, it) }
            if (comp != null) {
                out.add(
                    ExtractorLink(
                        "YouTube",
                        "YouTube",
                        comp,
                        "https://$host/",
                        if (preferDash) 30000 else Qualities.Unknown.value,
                        false,
                        playHeaders(host),
                        null,
                        true
                    )
                )
            }

            // ana sunucu DASH (yedek), sadece gecerliyse
            val dash = if (comp == null) validDash(host, data, i.dashUrl) else null
            if (dash != null) {
                out.add(
                    ExtractorLink(
                        "YouTube",
                        "YouTube",
                        dash,
                        "https://$host/",
                        if (preferDash) 20000 else Qualities.Unknown.value,
                        false,
                        playHeaders(host),
                        null,
                        true
                    )
                )
            }
        }

        return HostLinks(host, isLive, out)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // Tum instance'lar AYNI ANDA calisir (eskisi sirayla deniyordu: biri 10 sn takilinca
        // digerlerine sira gelmeden "Baglanti bulunamadi" cikiyordu).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ch    = Channel<HostLinks>(Channel.UNLIMITED)
        val all   = mutableListOf<HostLinks>()

        try {
            instances.forEach { host ->
                scope.launch {
                    val r = try {
                        fetchHostLinks(host, data)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        HostLinks(host, false, emptyList())
                    }
                    ch.send(r)
                }
            }

            val start    = System.currentTimeMillis()
            var graceEnd = Long.MAX_VALUE
            while (all.size < instances.size) {
                val limit = minOf(start + 70_000L, graceEnd)
                val left  = limit - System.currentTimeMillis()
                if (left <= 0) break
                val r = withTimeoutOrNull(left) { ch.receive() } ?: break
                all.add(r)

                // Ana instance bitti ve elimizde kaynak var -> digerlerine kisa bir sure taninir, sonra oynat.
                val primaryDone = all.any { it.host == instances.first() }
                val anyLinks    = all.any { it.links.isNotEmpty() }
                if (primaryDone && anyLinks && graceEnd == Long.MAX_VALUE) {
                    graceEnd = System.currentTimeMillis() + 3_000L
                }
            }
        } finally {
            scope.cancel()
        }

        // Instance sirasina gore (ana instance once) sirala.
        val ordered = all.sortedBy { instances.indexOf(it.host) }

        // Canli yayinda ilk calisan instance yeter. Normal videoda tum instance'larin
        // kaynaklarini ekliyoruz: biri 403 verirse digerini secebilesin.
        val live  = ordered.firstOrNull { it.live && it.links.isNotEmpty() }
        val final = if (live != null) listOf(live) else ordered

        var found = false
        for (h in final) {
            for (link in h.links) {
                callback(link)
                found = true
            }
        }
        return found
    }

}
