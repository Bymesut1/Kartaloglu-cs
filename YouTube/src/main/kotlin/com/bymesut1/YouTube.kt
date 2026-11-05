package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class YouTube : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Others)

    private val apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    override val mainPage = mainPageOf(
        "türkçe müzik" to "Müzik",
        "film fragmanı" to "Fragmanlar",
        "belgesel türkçe" to "Belgeseller",
        "komik videolar" to "Komik",
        "oyun videoları" to "Oyun",
        "son dakika haberler" to "Haberler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        return newHomePageResponse(request.name, searchVideos(request.data), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchVideos(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = videoId(url) ?: throw ErrorLoadingException("Geçersiz video adresi")
        val watch = "$mainUrl/watch?v=$id"
        val info = try {
            val enc = URLEncoder.encode(watch, "UTF-8")
            JSONObject(app.get("$mainUrl/oembed?url=$enc&format=json").text)
        } catch (e: Exception) {
            null
        }
        val title = info?.optString("title").takeUnless { it.isNullOrBlank() } ?: "YouTube"
        val author = info?.optString("author_name")

        return newMovieLoadResponse(title, watch, TvType.Others, watch) {
            this.posterUrl = thumb(id)
            this.plot = author
        }
    }

    // Android VR istemcisi ile doğrudan, yavaşlatılmayan akış adresleri
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = videoId(data) ?: return false
        var found = false
        try {
            found = vrLinks(id, callback)
        } catch (e: Exception) {
            Log.e("YTVR", "hata: $e")
            found = false
        }
        // Hiçbir şey bulunamazsa CloudStream'ın kendi çözücüsüne dön
        if (!found) {
            found = loadExtractor(data, subtitleCallback, callback)
        }
        return found
    }

    private suspend fun vrLinks(id: String, callback: (ExtractorLink) -> Unit): Boolean {
        val ver = "1.62.27"
        val ua = "com.google.android.apps.youtube.vr.oculus/$ver " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

        val client = JSONObject()
            .put("clientName", "ANDROID_VR")
            .put("clientVersion", ver)
            .put("deviceMake", "Oculus")
            .put("deviceModel", "Quest 3")
            .put("androidSdkVersion", 32)
            .put("osName", "Android")
            .put("osVersion", "12L")
            .put("hl", "tr")
            .put("gl", "TR")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()

        val text = app.post(
            "$mainUrl/youtubei/v1/player?prettyPrint=false",
            headers = mapOf(
                "User-Agent" to ua,
                "X-YouTube-Client-Name" to "28",
                "X-YouTube-Client-Version" to ver,
                "Origin" to mainUrl
            ),
            requestBody = body.toRequestBody("application/json".toMediaType())
        ).text

        val root = JSONObject(text)
        val ps = root.optJSONObject("playabilityStatus")
        val sdLog = root.optJSONObject("streamingData")
        Log.d(
            "YTVR",
            "durum=${ps?.optString("status")} sebep=${ps?.optString("reason")} " +
                "birlesik=${sdLog?.optJSONArray("formats")?.length()} " +
                "ayri=${sdLog?.optJSONArray("adaptiveFormats")?.length()}"
        )
        val sd = root.optJSONObject("streamingData") ?: return false
        val dur = root.optJSONObject("videoDetails")
            ?.optString("lengthSeconds")?.toIntOrNull() ?: 0
        val hdr = mapOf("User-Agent" to ua)
        var found = false

        // 1) Hazır birleşik akışlar (varsa)
        val muxed = sd.optJSONArray("formats")
        if (muxed != null) {
            for (i in 0 until muxed.length()) {
                val f = muxed.getJSONObject(i)
                val url = f.optString("url")
                if (url.isBlank()) continue
                val h = f.optInt("height", 360)
                callback(
                    newExtractorLink(name, "$name ${h}p", url) {
                        this.quality = h
                        this.headers = hdr
                    }
                )
                found = true
            }
        }

        // 2) Ayrı video + ses akışlarından DASH üret (H264 + AAC)
        val adaptive = sd.optJSONArray("adaptiveFormats")
        if (adaptive != null && dur > 0) {
            val videos = ArrayList<JSONObject>()
            var audio: JSONObject? = null
            for (i in 0 until adaptive.length()) {
                val f = adaptive.getJSONObject(i)
                if (f.optString("url").isBlank()) continue
                val mime = f.optString("mimeType")
                if (mime.startsWith("video/mp4") && mime.contains("avc1")) {
                    videos.add(f)
                } else if (mime.startsWith("audio/mp4")) {
                    if (audio == null || f.optInt("bitrate") > audio.optInt("bitrate")) {
                        audio = f
                    }
                }
            }
            if (audio != null) {
                val byHeight = videos.groupBy { it.optInt("height") }
                for ((h, list) in byHeight) {
                    if (h <= 0) continue
                    val v = list.maxByOrNull { it.optInt("bitrate") } ?: continue
                    val mpd = buildMpd(dur, v, audio)
                    val b64 = Base64.encodeToString(mpd.toByteArray(), Base64.NO_WRAP)
                    callback(
                        newExtractorLink(
                            name,
                            "$name ${h}p (HD)",
                            "data:application/dash+xml;base64,$b64",
                            ExtractorLinkType.DASH
                        ) {
                            this.quality = h
                            this.headers = hdr
                        }
                    )
                    found = true
                }
            }
        }
        return found
    }

    private fun xml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun rep(f: JSONObject, video: Boolean): String {
        val mime = f.optString("mimeType")
        val codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1) ?: ""
        val initR = f.optJSONObject("initRange")
        val idxR = f.optJSONObject("indexRange")
        val sb = StringBuilder()
        sb.append("<Representation id=\"${f.optInt("itag")}\" codecs=\"$codecs\" ")
        sb.append("bandwidth=\"${f.optInt("bitrate")}\"")
        if (video) {
            sb.append(" width=\"${f.optInt("width")}\" height=\"${f.optInt("height")}\"")
            sb.append(" frameRate=\"${f.optInt("fps", 30)}\"")
        }
        sb.append(">")
        sb.append("<BaseURL>${xml(f.optString("url"))}</BaseURL>")
        sb.append("<SegmentBase indexRange=\"${idxR?.optString("start")}-${idxR?.optString("end")}\">")
        sb.append("<Initialization range=\"${initR?.optString("start")}-${initR?.optString("end")}\"/>")
        sb.append("</SegmentBase></Representation>")
        return sb.toString()
    }

    private fun buildMpd(durSec: Int, video: JSONObject, audio: JSONObject): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
            "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" " +
            "type=\"static\" mediaPresentationDuration=\"PT${durSec}S\" minBufferTime=\"PT2S\">" +
            "<Period>" +
            "<AdaptationSet mimeType=\"video/mp4\" subsegmentAlignment=\"true\">" +
            rep(video, true) + "</AdaptationSet>" +
            "<AdaptationSet mimeType=\"audio/mp4\" subsegmentAlignment=\"true\">" +
            rep(audio, false) + "</AdaptationSet>" +
            "</Period></MPD>"
    }

    // ---------- Yardımcılar ----------

    private fun thumb(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private fun videoId(url: String): String? {
        val r = Regex("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})")
        return r.find(url)?.groupValues?.get(1)
    }

    private suspend fun searchVideos(query: String): List<SearchResponse> {
        val client = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", "2.20250101.00.00")
            .put("hl", "tr")
            .put("gl", "TR")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", query)
            .put("params", "EgIQAQ==") // sadece videolar
            .toString()

        val text = app.post(
            "$mainUrl/youtubei/v1/search?key=$apiKey&prettyPrint=false",
            requestBody = body.toRequestBody("application/json".toMediaType())
        ).text

        val found = mutableListOf<JSONObject>()
        collectVideos(JSONObject(text), found)

        val seen = HashSet<String>()
        val result = ArrayList<SearchResponse>()
        for (v in found) {
            val id = v.optString("videoId")
            if (id.isBlank() || !seen.add(id)) continue
            val title = v.optJSONObject("title")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() } ?: continue
            result.add(
                newMovieSearchResponse(title, "$mainUrl/watch?v=$id", TvType.Others) {
                    this.posterUrl = thumb(id)
                }
            )
        }
        return result
    }

    private fun collectVideos(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "videoRenderer") collectVideos(node.opt(k), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectVideos(node.opt(i), out)
            }
        }
    }
}
