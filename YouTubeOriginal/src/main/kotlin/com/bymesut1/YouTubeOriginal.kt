package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.HttpURLConnection
import java.net.URL

/** NewPipeExtractor'ın ağ isteklerini yapan basit indirici (ek kütüphane gerektirmez). */
class YtDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.requestMethod = request.httpMethod()
            conn.setRequestProperty("User-Agent", WEB_UA)

            for ((key, values) in request.headers()) {
                if (key.equals("Accept-Encoding", ignoreCase = true)) continue
                values.forEachIndexed { i, v ->
                    if (i == 0) conn.setRequestProperty(key, v) else conn.addRequestProperty(key, v)
                }
            }

            val body = request.dataToSend()
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }

            val code = conn.responseCode
            if (code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }

            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            val headers = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null) headers[k] = v
            }

            return Response(code, conn.responseMessage, headers, text, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}

class YouTubeOriginal : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Original"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others, TvType.Live)

    companion object {
        @Volatile
        private var initialized = false

        private const val IOS_UA =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        private const val ANDROID_UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"

        fun ensureInit() {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        NewPipe.init(YtDownloader(), Localization("tr", "TR"), ContentCountry("TR"))
                        initialized = true
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun videoId(url: String): String? =
        Regex("""(?:v=|youtu\.be/|shorts/|live/)([\w-]{11})""").find(url)?.groupValues?.get(1)

    private fun thumb(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private fun heightOf(res: String?): Int =
        Regex("""(\d{3,4})p""").find(res ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun streamHeaders(url: String): Map<String, String> {
        val ua = when {
            url.contains("c=IOS") -> IOS_UA
            url.contains("c=ANDROID") -> ANDROID_UA
            else -> YtDownloader.WEB_UA
        }
        return mapOf("User-Agent" to ua)
    }

    private fun toSearch(item: StreamInfoItem): SearchResponse? {
        val id = videoId(item.url) ?: return null
        val live = item.streamType == StreamType.LIVE_STREAM ||
                item.streamType == StreamType.AUDIO_LIVE_STREAM
        val title = if (live) "🔴 ${item.name}" else item.name
        return newMovieSearchResponse(
            title,
            "https://www.youtube.com/watch?v=$id",
            if (live) TvType.Live else TvType.Others
        ) {
            this.posterUrl = thumb(id)
        }
    }

    private fun searchStreams(query: String): List<SearchResponse> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { toSearch(it) }
    }

    // ---------------------------------------------------------------- ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        ensureInit()
        val lists = ArrayList<HomePageList>()

        // NewPipe'taki "Canlı", "Trend" vb. sekmeler (kiosk'lar)
        val kioskList = ServiceList.YouTube.kioskList
        for (id in kioskList.availableKiosks.take(6)) {
            try {
                val extractor = kioskList.getExtractorById(id, null)
                extractor.fetchPage()
                val items = extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { toSearch(it) }
                if (items.isNotEmpty()) {
                    lists.add(HomePageList(extractor.name ?: id, items, true))
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        // Kiosk'lar boş dönerse arama ile doldur
        if (lists.isEmpty()) {
            for (q in listOf("canlı yayın", "haber", "müzik")) {
                try {
                    val items = searchStreams(q)
                    if (items.isNotEmpty()) lists.add(HomePageList(q, items, true))
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }

        return newHomePageResponse(lists, false)
    }

    // ---------------------------------------------------------------- arama

    override suspend fun search(query: String): List<SearchResponse>? {
        ensureInit()
        return searchStreams(query)
    }

    // ---------------------------------------------------------------- içerik sayfası

    override suspend fun load(url: String): LoadResponse? {
        ensureInit()
        val info = StreamInfo.getInfo(url)
        val id = info.id
        val live = info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM
        val watchUrl = "https://www.youtube.com/watch?v=$id"
        val title = (if (live) "🔴 " else "") + info.name

        return newMovieLoadResponse(
            title,
            watchUrl,
            if (live) TvType.Live else TvType.Others,
            watchUrl
        ) {
            this.posterUrl = thumb(id)
            this.plot = buildString {
                val uploader = info.uploaderName
                if (!uploader.isNullOrBlank()) appendLine("📺 $uploader\n")
                append(info.description?.content ?: "")
            }
            this.duration = if (info.duration > 0) (info.duration / 60).toInt() else null
        }
    }

    // ---------------------------------------------------------------- video bağlantıları

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()
        val info = StreamInfo.getInfo(data)
        val live = info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM
        var found = false

        // 1) HLS (canlı yayınlar + tüm kaliteler, ses dahil)
        val hls = info.hlsUrl
        if (!hls.isNullOrBlank()) {
            callback(
                newExtractorLink(this.name, "${this.name} HLS", hls, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.headers = if (live) mapOf("User-Agent" to YtDownloader.WEB_UA)
                    else mapOf("User-Agent" to IOS_UA)
                }
            )
            found = true
        }

        // 2) Ses+görüntü birleşik düz (progressive) akışlar
        info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedByDescending { heightOf(it.resolution) }
            .forEach { s ->
                val link = s.content
                if (!link.isNullOrBlank()) {
                    val h = heightOf(s.resolution)
                    callback(
                        newExtractorLink(
                            this.name,
                            "${this.name} ${s.resolution}",
                            link,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = if (h > 0) h else Qualities.Unknown.value
                            this.headers = streamHeaders(link)
                        }
                    )
                    found = true
                }
            }

        return found
    }
}
