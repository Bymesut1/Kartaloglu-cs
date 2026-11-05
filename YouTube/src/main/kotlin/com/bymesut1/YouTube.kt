package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tüm YouTube linklerini topla, sadece TEK bir H264 480p linkini ilet.
        val all = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        loadExtractor(data, subtitleCallback) { link -> all.add(link) }

        val h264 = synchronized(all) { all.toList() }
            .filter { it.name.contains("H264", ignoreCase = true) }

        // Önce tam 480p, 60fps olmayan tercih edilir.
        var best = h264
            .filter { heightOf(it) == 480 }
            .sortedBy { fps60Itags.contains(itagOf(it)) }
            .firstOrNull()

        // Videoda H264 480p yoksa en yakın H264 kaliteyi ver (bağlantı bulunamadı olmasın).
        if (best == null) {
            best = h264
                .sortedWith(
                    compareBy<ExtractorLink> { abs(heightOf(it) - 480) }
                        .thenBy { fps60Itags.contains(itagOf(it)) }
                )
                .firstOrNull()
        }

        if (best != null) {
            callback(best)
            return true
        }
        return false
    }

    // ---------- Yardımcılar ----------

    // H264 itag -> yükseklik (60fps dahil)
    private val h264Heights = mapOf(
        160 to 144, 133 to 240, 134 to 360, 135 to 480, 136 to 720, 137 to 1080,
        298 to 720, 299 to 1080
    )
    private val fps60Itags = setOf(298, 299)

    private fun itagOf(link: ExtractorLink): Int? =
        Regex("[?&]itag=(\\d+)").find(link.url)?.groupValues?.get(1)?.toIntOrNull()

    private fun heightOf(link: ExtractorLink): Int =
        itagOf(link)?.let { h264Heights[it] } ?: link.quality

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
