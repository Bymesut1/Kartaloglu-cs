package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class YouTube2 : MainAPI() {
    override var mainUrl = "https://m.youtube.com"
    override var name = "YouTube 2"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; 21081111RG) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cookie" to "SOCS=CAI; CONSENT=YES+1"
    )

    // Ana sayfa satırları: soldaki arama sorgusu, sağdaki başlık. İstediğini değiştir.
    override val mainPage = mainPageOf(
        "gündem" to "Gündem",
        "türkçe film" to "Türkçe Film",
        "yerli dizi" to "Yerli Dizi",
        "belgesel" to "Belgesel",
        "müzik" to "Müzik",
        "komedi" to "Komedi"
    )

    // ---------------------------------------------------------------- yardımcılar

    private fun videoId(url: String): String? =
        Regex("""(?:[?&]v=|youtu\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})""")
            .find(url)?.groupValues?.get(1)

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun posterOf(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** JS string içindeki \xHH, \uHHHH ve \\ gibi kaçışları çöz. */
    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            val n = s[i + 1]
            when (n) {
                'x' -> {
                    val v = if (i + 3 < s.length) s.substring(i + 2, i + 4).toIntOrNull(16) else null
                    if (v != null) {
                        sb.append(v.toChar())
                        i += 4
                        continue
                    }
                    sb.append(n)
                }
                'u' -> {
                    val v = if (i + 5 < s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (v != null) {
                        sb.append(v.toChar())
                        i += 6
                        continue
                    }
                    sb.append(n)
                }
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    /** startIdx'teki '{' ile başlayan dengeli JSON nesnesini keser (string içindeki parantezleri sayma). */
    private fun balancedJson(text: String, startIdx: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        var i = startIdx
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(startIdx, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /** Sayfadaki ytInitialData'yı bulur: hem '\x7b...' (mobil) hem düz {...} biçimini dener. */
    private fun extractInitialData(html: String): JSONObject? {
        var from = 0
        while (true) {
            val key = html.indexOf("ytInitialData", from)
            if (key < 0) return null
            from = key + 13
            val eq = html.indexOf('=', from)
            if (eq < 0 || eq - from > 40) continue
            var p = eq + 1
            while (p < html.length && html[p].isWhitespace()) p++
            if (p >= html.length) return null

            if (html[p] == '\'') {
                // kaçışlı string
                var q = p + 1
                while (q < html.length) {
                    if (html[q] == '\\') {
                        q += 2
                        continue
                    }
                    if (html[q] == '\'') break
                    q++
                }
                if (q >= html.length) continue
                val raw = unescapeJs(html.substring(p + 1, q))
                runCatching { return JSONObject(raw) }
            } else if (html[p] == '{') {
                val json = balancedJson(html, p) ?: continue
                runCatching { return JSONObject(json) }
            }
        }
    }

    private fun textOf(node: Any?): String? {
        when (node) {
            is String -> return node.takeIf { it.isNotBlank() }
            is JSONObject -> {
                node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                val runs = node.optJSONArray("runs") ?: return null
                val sb = StringBuilder()
                for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
                return sb.toString().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun collectVideos(node: Any?, out: MutableList<SearchResponse>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val id = node.optString("videoId")
                if (id.length == 11 && !seen.contains(id)) {
                    val title = textOf(node.opt("title")) ?: textOf(node.opt("headline"))
                    if (title != null) {
                        seen.add(id)
                        out.add(
                            newMovieSearchResponse(title, watchUrl(id), TvType.Movie) {
                                this.posterUrl = posterOf(id)
                            }
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectVideos(node.opt(keys.next()), out, seen)
            }
            is JSONArray -> for (i in 0 until node.length()) collectVideos(node.opt(i), out, seen)
        }
    }

    private suspend fun searchVideos(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // sp=EgIQAQ%253D%253D -> sadece videolar
        val url = "$mainUrl/results?search_query=$q&sp=EgIQAQ%253D%253D"
        val html = runCatching { app.get(url, headers = pageHeaders, referer = "$mainUrl/").text }
            .getOrNull().orEmpty()
        val data = extractInitialData(html)
        if (data == null) {
            Log.d(name, "ytInitialData bulunamadı, html=${html.length}")
            return emptyList()
        }
        val out = ArrayList<SearchResponse>()
        collectVideos(data, out, HashSet())
        Log.d(name, "arama '$query' -> ${out.size} sonuç")
        return out
    }

    // ---------------------------------------------------------------- CloudStream API

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val items = searchVideos(request.data)
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank() || page > 1) return newSearchResponseList(emptyList(), false)
        return newSearchResponseList(searchVideos(query), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = videoId(url) ?: return null
        val watch = watchUrl(id)
        var title = id
        var plot: String? = null
        var poster = posterOf(id)

        runCatching {
            val doc = app.get("$mainUrl/watch?v=$id", headers = pageHeaders, referer = "$mainUrl/").document
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { plot = it }
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.startsWith("http") }?.let { poster = it }
        }

        return newMovieLoadResponse(title, watch, TvType.Movie, watch) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------------- Akış çıkarma
    // CloudStream'in YouTube çıkarıcısı kullanılmıyor: linkler doğrudan YouTube'un
    // youtubei/v1/player API'sinden alınıyor.

    private data class YtClient(
        val name: String,
        val id: Int,
        val version: String,
        val ua: String,
        val extra: Map<String, Any>
    )

    private val vrClient = YtClient(
        "ANDROID_VR", 28, "1.65.10",
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        mapOf(
            "deviceMake" to "Oculus",
            "deviceModel" to "Quest 3",
            "androidSdkVersion" to 32,
            "osName" to "Android",
            "osVersion" to "12L"
        )
    )

    private val iosClient = YtClient(
        "IOS", 5, "20.10.4",
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        mapOf(
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.3.2.22D82"
        )
    )

    private suspend fun playerResponse(id: String, c: YtClient): JSONObject? {
        val client = LinkedHashMap<String, Any>()
        client["clientName"] = c.name
        client["clientVersion"] = c.version
        client.putAll(c.extra)
        client["hl"] = "tr"
        client["gl"] = "TR"
        client["utcOffsetMinutes"] = 180

        val body = mapOf(
            "context" to mapOf("client" to client),
            "videoId" to id,
            "contentCheckOk" to true,
            "racyCheckOk" to true,
            "playbackContext" to mapOf(
                "contentPlaybackContext" to mapOf("html5Preference" to "HTML5_PREF_WANTS")
            )
        )
        val res = runCatching {
            app.post(
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                headers = mapOf(
                    "User-Agent" to c.ua,
                    "X-YouTube-Client-Name" to c.id.toString(),
                    "X-YouTube-Client-Version" to c.version,
                    "Origin" to "https://www.youtube.com"
                ),
                json = body
            )
        }.onFailure { Log.w(name, "${c.name} player isteği başarısız", it) }.getOrNull() ?: return null
        return runCatching { JSONObject(res.text) }.getOrNull()
    }

    private fun mimeOf(f: JSONObject): String = f.optString("mimeType").substringBefore(";").trim()

    private fun codecsOf(f: JSONObject): String =
        Regex("codecs=\"([^\"]+)\"").find(f.optString("mimeType"))?.groupValues?.get(1).orEmpty()

    private fun rangeOf(o: JSONObject?): String? {
        if (o == null) return null
        val a = o.optString("start")
        val b = o.optString("end")
        if (a.isBlank() || b.isBlank()) return null
        return "$a-$b"
    }

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun representation(f: JSONObject, video: Boolean): String? {
        val url = f.optString("url").takeIf { it.isNotBlank() } ?: return null
        val init = rangeOf(f.optJSONObject("initRange")) ?: return null
        val index = rangeOf(f.optJSONObject("indexRange")) ?: return null
        val bw = maxOf(f.optInt("bitrate", 0), f.optInt("averageBitrate", 0), 1)
        val sb = StringBuilder()
        sb.append("<Representation id=\"").append(f.optInt("itag")).append("\"")
        sb.append(" codecs=\"").append(xml(codecsOf(f))).append("\"")
        sb.append(" bandwidth=\"").append(bw).append("\"")
        if (video) {
            sb.append(" width=\"").append(f.optInt("width")).append("\"")
            sb.append(" height=\"").append(f.optInt("height")).append("\"")
            val fps = f.optInt("fps", 0)
            if (fps > 0) sb.append(" frameRate=\"").append(fps).append("\"")
        } else {
            val rate = f.optString("audioSampleRate").ifBlank { "44100" }
            sb.append(" audioSamplingRate=\"").append(rate).append("\"")
        }
        sb.append(">")
        if (!video) {
            sb.append("<AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"")
                .append(f.optInt("audioChannels", 2)).append("\"/>")
        }
        sb.append("<BaseURL>").append(xml(url)).append("</BaseURL>")
        sb.append("<SegmentBase indexRange=\"").append(index).append("\"><Initialization range=\"")
            .append(init).append("\"/></SegmentBase></Representation>")
        return sb.toString()
    }

    private fun buildMpd(durationSec: Int, v: JSONObject, a: JSONObject): String? {
        val vr = representation(v, true) ?: return null
        val ar = representation(a, false) ?: return null
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
            "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" " +
            "mediaPresentationDuration=\"PT${durationSec}S\" minBufferTime=\"PT2S\">" +
            "<Period>" +
            "<AdaptationSet id=\"0\" mimeType=\"${mimeOf(v)}\" subsegmentAlignment=\"true\">$vr</AdaptationSet>" +
            "<AdaptationSet id=\"1\" mimeType=\"${mimeOf(a)}\" subsegmentAlignment=\"true\">$ar</AdaptationSet>" +
            "</Period></MPD>"
    }

    private fun qualityLabel(height: Int): String = if (height >= 2160) "4K" else "${height}p"

    private suspend fun emitStreams(
        resp: JSONObject,
        c: YtClient,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sd = resp.optJSONObject("streamingData") ?: return false
        val linkHeaders = mapOf("User-Agent" to c.ua)
        var found = false

        val duration = resp.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toIntOrNull() ?: 0

        // 1) Ayrı video + ses akışları -> DASH manifest (4K / 1440p / 1080p ...)
        val adaptive = sd.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            val videos = ArrayList<JSONObject>()
            val audios = ArrayList<JSONObject>()
            for (i in 0 until adaptive.length()) {
                val f = adaptive.optJSONObject(i) ?: continue
                if (f.optString("url").isBlank()) continue
                val mime = mimeOf(f)
                if (mime.startsWith("video/")) {
                    videos.add(f)
                } else if (mime.startsWith("audio/")) {
                    val track = f.optJSONObject("audioTrack")
                    if (track == null || track.optBoolean("audioIsDefault", true)) audios.add(f)
                }
            }

            fun bestAudio(mime: String): JSONObject? =
                audios.filter { mimeOf(it) == mime }.maxByOrNull { it.optInt("bitrate") }
                    ?: audios.maxByOrNull { it.optInt("bitrate") }

            val heights = videos.map { it.optInt("height") }.filter { it >= 360 }.distinct().sortedDescending()
            val emitted = HashSet<String>()
            for (h in heights) {
                for (family in listOf("vp9", "avc")) {
                    val candidates = videos.filter { f ->
                        f.optInt("height") == h && when (family) {
                            "vp9" -> codecsOf(f).startsWith("vp09") || codecsOf(f).startsWith("vp9")
                            else -> codecsOf(f).startsWith("avc1")
                        }
                    }
                    val v = candidates.maxByOrNull { it.optInt("bitrate") } ?: continue
                    val audio = bestAudio(if (mimeOf(v) == "video/webm") "audio/webm" else "audio/mp4") ?: continue
                    val dur = if (duration > 0) duration else (v.optString("approxDurationMs").toLongOrNull()?.div(1000)?.toInt() ?: 0)
                    val mpd = buildMpd(dur, v, audio) ?: continue
                    val label = "YouTube " + (if (family == "vp9") "VP9" else "H264") + " " + qualityLabel(h)
                    if (!emitted.add(label)) continue
                    val dataUri = "data:application/dash+xml;base64," +
                        Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    callback(
                        newExtractorLink(source = name, name = label, url = dataUri, type = ExtractorLinkType.DASH) {
                            this.referer = "https://www.youtube.com/"
                            this.quality = h
                            this.headers = linkHeaders
                        }
                    )
                    found = true
                }
            }
        }

        // 2) Video + ses tek dosyada (genelde 360p) -> doğrudan MP4
        val muxed = sd.optJSONArray("formats")
        if (muxed != null) {
            for (i in 0 until muxed.length()) {
                val f = muxed.optJSONObject(i) ?: continue
                val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                val h = f.optInt("height", 0)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "YouTube ${if (h > 0) "${h}p" else "tek dosya"} (MP4)",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.youtube.com/"
                        this.quality = if (h > 0) h else Qualities.Unknown.value
                        this.headers = linkHeaders
                    }
                )
                found = true
            }
        }

        // 3) HLS (canlı yayınlar ve bazı istemciler)
        val hls = sd.optString("hlsManifestUrl")
        if (hls.isNotBlank()) {
            callback(
                newExtractorLink(source = name, name = "YouTube HLS", url = hls, type = ExtractorLinkType.M3U8) {
                    this.referer = "https://www.youtube.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = linkHeaders
                }
            )
            found = true
        }
        return found
    }

    private fun emitSubtitles(resp: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val tracks = resp.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val base = t.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
            val lang = textOf(t.opt("name")) ?: t.optString("languageCode").ifBlank { "Altyazı" }
            val url = if (base.contains("fmt=")) base else "$base&fmt=vtt"
            subtitleCallback(newSubtitleFile(lang, url))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = videoId(data) ?: return false
        var found = false
        for (c in listOf(vrClient, iosClient)) {
            val resp = playerResponse(id, c) ?: continue
            val status = resp.optJSONObject("playabilityStatus")
            Log.d(name, "${c.name}: durum=${status?.optString("status")} neden=${status?.optString("reason")}")
            if (status?.optString("status") != "OK") continue
            emitSubtitles(resp, subtitleCallback)
            if (emitStreams(resp, c, callback)) {
                found = true
                break
            }
        }
        if (!found) Log.d(name, "hiçbir istemciden link alınamadı: $id")
        return found
    }
}
