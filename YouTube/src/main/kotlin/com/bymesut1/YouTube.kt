@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! https://github.com/recloudstream/extensions/blob/master/InvidiousProvider/src/main/kotlin/recloudstream/InvidiousProvider.kt

package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

class YouTube : MainAPI() {
    override var mainUrl              = "https://yt.chocolatemoo53.com"
    override var name                 = "YouTube"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val supportedTypes       = setOf(TvType.Others)

    // Invidious örnekleri API'yi zaman zaman kapatabiliyor (inv.nadeko.net gibi),
    // bu yüzden istekleri sırayla deneyerek çalışan instance'ı buluyoruz.
    // 2026-11: API'si acik kalan ornekler (f5.si 530, invidious.site 404, nadeko/nerdvpn API kapali).
    private val instances = listOf(
        "yt.chocolatemoo53.com",
        "invidious.materialio.us",
        "yewtu.be",
    )

    private fun isJson(body: String) = body.startsWith("[") || body.startsWith("{")

    // DASH manifest icindeki video kodekleri. Manifest'te ayni cozunurlukten birden fazla
    // (VP9 / AV1 / H264) var; hangisinin hizli aktigini kaynak listesinde ayri ayri gorebilmen icin
    // her kodek icin AYRI kaynak uretiyoruz. Siradaki ilk kaynak otomatik secilir.
    // Hangisi iyi akiyorsa bana soyle, digerlerini listeden cikaririm (ya da sirayi degistir).
    // Format: gorunen ad to kodek on ekleri
    private val codecGroups = listOf(
        "VP9"  to listOf("vp09", "vp9"),   // 720p / 1080p / 1440p / 4K
        "AV1"  to listOf("av01"),          // 720p / 1080p / 1440p / 4K
        "H264" to listOf("avc1"),          // en fazla 1080p
    )

    private suspend fun apiText(path: String): String? {
        for (host in instances) {
            try {
                val res  = app.get("https://$host$path")
                val body = res.text
                if (res.code == 200 && isJson(body)) return body
            } catch (_: Exception) { }
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
    // Gecerliyse manifest METNINI dondurur.
    private suspend fun mpdBody(host: String, url: String): String? = try {
        val res = app.get(url, headers = playHeaders(host))
        val t   = res.text
        if (res.code == 200 && t.contains("<MPD") && t.contains("<AdaptationSet") && !t.contains("NaN")) t else null
    } catch (_: Exception) { null }

    // TARAYICININ KULLANDIGI MANIFEST: video sayfasi parcalari yt-comp*.chocolatemoo53.com
    // uzerinden proxy'leyen "companion" manifest'ini ve her sayfa acilisinda degisen "check=" imzasini
    // kullaniyor:
    //   https://yt-comp3.../companion/api/manifest/dash/id/ID?local=true&unique_res=1&check=...
    // check imzasini hesaplayamayiz; o yuzden watch sayfasindan okuyoruz.
    private suspend fun companionDashUrl(host: String, videoId: String): String? = try {
        val res  = app.get("https://$host/watch?v=$videoId", headers = playHeaders(host))
        val html = res.text.replace("&amp;", "&").replace("\\u0026", "&").replace("\\/", "/")
        val rx   = Regex("""(https?://[A-Za-z0-9.\-]+)?(/companion/api/manifest/dash/id/$videoId\?[^"'<>\s\\]+)""")
        val m    = rx.find(html)
        if (m == null) null else m.groupValues[1].ifBlank { "https://$host" } + m.groupValues[2]
    } catch (_: Exception) { null }

    private val setRx   = Regex("<AdaptationSet.*?</AdaptationSet>", RegexOption.DOT_MATCHES_ALL)
    private val repRx   = Regex("<Representation.*?</Representation>", RegexOption.DOT_MATCHES_ALL)
    private val codecRx = Regex("codecs=\"([^\"]+)\"")
    private val baseRx  = Regex("<BaseURL>\\s*(?!https?://)([^<]+?)\\s*</BaseURL>")

    // Manifest'ten sadece istenen kodegin video varyantlarini birakir (ses parcalari aynen kalir).
    // Boylece oynaticida sadece o kaynagin 720p/1080p/1440p/4K varyantlari gorunur.
    // BaseURL'ler gocut olarak mutlak adrese cevrilir (manifest data: adresi olarak verilecek).
    private fun filterMpd(mpd: String, prefixes: List<String>, origin: String): String? {
        var kept = 0
        var out = setRx.replace(mpd) { sm ->
            val set     = sm.value
            val isVideo = set.contains("mimeType=\"video") || set.contains("contentType=\"video")
            if (!isVideo) {
                set
            } else {
                val newSet = repRx.replace(set) { rm ->
                    val c = codecRx.find(rm.value)?.groupValues?.get(1)
                        ?: codecRx.find(set)?.groupValues?.get(1)
                        ?: ""
                    if (prefixes.any { c.startsWith(it) }) { kept++; rm.value } else ""
                }
                if (repRx.containsMatchIn(newSet)) newSet else ""
            }
        }
        if (kept == 0) return null
        out = baseRx.replace(out) { m ->
            val u = m.groupValues[1]
            val abs = when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("/")  -> origin + u
                else               -> "$origin/$u"
            }
            "<BaseURL>$abs</BaseURL>"
        }
        return out
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
        ) ?: return null

        return tryParseJson<VideoEntry>(body)?.toLoadResponse(this)
    }

    private data class SearchEntry(val title: String = "", val videoId: String = "") {
        fun toSearchResponse(provider: YouTube): SearchResponse {
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false

        for (host in instances) {
            // local=true -> URL'ler instance uzerinden proxy'lenir (googlevideo IP-kilidi 403/2004 hatasini onler)
            val body = try {
                val res = app.get(headers = playHeaders(host), url = "https://$host/api/v1/videos/$data?local=true&region=${lang.uppercase()}&fields=liveNow,hlsUrl,dashUrl,formatStreams")
                if (res.code == 200 && isJson(res.text)) res.text else null
            } catch (_: Exception) { null } ?: continue

            val info   = tryParseJson<LinkInfo>(body) ?: continue
            // DIKKAT: Invidious normal videolarda da hlsUrl dondurur (ama bu HLS 3001 hatasi verir).
            // Bu yuzden canli yayin tespiti sadece liveNow / yt_live_broadcast ile yapilir.
            val isLive = info.liveNow == true || info.hlsUrl?.contains("yt_live_broadcast") == true

            // --- CANLI YAYIN: HLS ---
            if (isLive && !info.hlsUrl.isNullOrBlank()) {
                callback(
                    ExtractorLink(
                        "YouTube",
                        "YouTube",
                        withLocal(absUrl(host, info.hlsUrl)),
                        "",
                        Qualities.Unknown.value,
                        true,
                        mapOf(),
                        null,
                        false
                    )
                )
                found = true
            }

            if (!isLive) {
                var dashOk = false

                // --- NORMAL VIDEO: DASH manifest (once tarayicidaki companion, olmazsa ana sunucu) ---
                var mpdUrl: String? = null
                var mpd: String?    = null

                val compUrl  = companionDashUrl(host, data)
                val compBody = compUrl?.let { mpdBody(host, it) }
                if (compUrl != null && compBody != null) {
                    mpdUrl = compUrl
                    mpd    = compBody
                } else {
                    val apiDash = info.dashUrl
                    val base    = if (!apiDash.isNullOrBlank()) absUrl(host, apiDash)
                                  else "https://$host/api/manifest/dash/id/$data"
                    val u       = dashWithLocal(base)
                    val b       = mpdBody(host, u)
                    if (b != null) {
                        mpdUrl = u
                        mpd    = b
                    }
                }

                if (mpd != null && mpdUrl != null) {
                    val origin = Regex("^(https?://[^/]+)").find(mpdUrl)?.groupValues?.get(1) ?: "https://$host"

                    // Her kodek icin ayri, sadece o kodegin varyantlarini iceren kaynak.
                    // Ilk siradaki en yuksek oncelikli oldugu icin video acilisinda o otomatik secilir.
                    for ((i, g) in codecGroups.withIndex()) {
                        val filtered = filterMpd(mpd, g.second, origin) ?: continue
                        val b64 = Base64.encodeToString(filtered.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        callback(
                            ExtractorLink(
                                "YouTube",
                                "YouTube ${g.first}",
                                "data:application/dash+xml;base64,$b64",
                                "https://$host/",
                                40000 - (i * 1000),
                                false,
                                playHeaders(host),
                                null,
                                true
                            )
                        )
                        dashOk = true
                        found  = true
                    }

                    // Filtre hicbir kodek bulamazsa: filtrelenmemis orijinal manifest (yedek)
                    if (!dashOk) {
                        callback(
                            ExtractorLink(
                                "YouTube",
                                "YouTube",
                                mpdUrl,
                                "https://$host/",
                                30000,
                                false,
                                playHeaders(host),
                                null,
                                true
                            )
                        )
                        dashOk = true
                        found  = true
                    }
                }

                // --- SON YEDEK: duz mp4 (video+ses birlikte, 360p/720p). Sadece DASH hic calismazsa eklenir ---
                if (!dashOk) {
                    info.formatStreams.orEmpty()
                        .filter { !it.url.isNullOrBlank() }
                        .forEach { f ->
                            val label = f.qualityLabel ?: f.quality ?: ""
                            val q     = Regex("(\\d{3,4})").find(label)?.groupValues?.get(1)?.toIntOrNull()
                                ?: Qualities.Unknown.value
                            callback(
                                ExtractorLink(
                                    "YouTube",
                                    "YouTube mp4",
                                    absUrl(host, f.url!!),
                                    "https://$host/",
                                    q,
                                    false,
                                    playHeaders(host),
                                    null,
                                    false
                                )
                            )
                            found = true
                        }
                }

                // DASH bulunan ilk instance yeter (yavas yedek kaynaklar listeyi kirletmesin)
                if (dashOk) break
            }

            // Canli yayinda ilk calisan instance yeter.
            if (found && isLive) break
        }

        return found
    }

}
