@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! https://github.com/recloudstream/extensions/blob/master/InvidiousProvider/src/main/kotlin/recloudstream/InvidiousProvider.kt

package com.bymesut1

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

    // true  -> once DASH (en yuksek kalite) denenir, olmazsa duz mp4/HLS yedekleri.
    // false -> once duz mp4 (360p/720p, en guvenilir), DASH en sonda listelenir.
    private val preferDash = false

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

    // Canli yayinlarda DASH manifest "PTNaNS" suresi uretiyor ve ExoPlayer coker
    // (ERROR_CODE_IO_UNSPECIFIED 2000). Bu yuzden gecersiz manifest'i eleriz.
    private suspend fun validDash(host: String, videoId: String): String? {
        val url = "https://$host/api/manifest/dash/id/$videoId"
        return try {
            val res = app.get(url)
            val t   = res.text
            if (res.code == 200 && t.contains("<MPD") && t.contains("<AdaptationSet") && !t.contains("NaN")) url else null
        } catch (_: Exception) { null }
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
        val formatStreams: List<FormatStream>? = null
    )

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false

        for (host in instances) {
            // local=true -> URL'ler instance uzerinden proxy'lenir (googlevideo IP-kilidi 403/2004 hatasini onler)
            val body = try {
                val res = app.get("https://$host/api/v1/videos/$data?local=true&region=${lang.uppercase()}&fields=liveNow,hlsUrl,formatStreams")
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
                        "YouTube Canlı (HLS)",
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

            // --- NORMAL VIDEO: duz mp4 (video+ses birlikte) ---
            if (!isLive) {
                info.formatStreams.orEmpty()
                    .filter { !it.url.isNullOrBlank() }
                    .forEach { f ->
                        val label = f.qualityLabel ?: f.quality ?: ""
                        val q     = Regex("(\\d{3,4})").find(label)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Qualities.Unknown.value
                        callback(
                            ExtractorLink(
                                "YouTube",
                                "YouTube $label".trim(),
                                absUrl(host, f.url!!),
                                "",
                                if (preferDash) q else q + 10000, // preferDash=false iken duz mp4 once denenir
                                false,
                                mapOf(),
                                null,
                                false
                            )
                        )
                        found = true
                    }

                // --- NORMAL VIDEO: DASH (yuksek kalite), sadece gecerliyse ---
                val dash = validDash(host, data)
                if (dash != null) {
                    callback(
                        ExtractorLink(
                            "YouTube",
                            "YouTube DASH (Yüksek Kalite)",
                            dash,
                            "",
                            if (preferDash) 20000 else Qualities.Unknown.value,
                            false,
                            mapOf(),
                            null,
                            true
                        )
                    )
                    found = true
                }
            }

            if (found) break // calisan instance bulundu
        }

        return found
    }

}
