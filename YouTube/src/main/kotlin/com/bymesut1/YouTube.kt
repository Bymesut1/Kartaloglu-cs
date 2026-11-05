package com.bymesut1

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tüm YouTube linklerini topla, sadece TEK bir VP9 1080p linkini ilet.
        val all = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        loadExtractor(data, subtitleCallback) { link -> all.add(link) }

        val best = synchronized(all) { all.toList() }
            .filter { it.name.contains("VP9", ignoreCase = true) }
            .filter { heightOf(it) == 1080 }
            .sortedBy { fps60Itags.contains(itagOf(it)) } // 1080p60 yerine normal 1080p
            .firstOrNull()

        if (best != null) {
            callback(best)
            return true
        }
        return false
    }

    // ---------- Yardımcılar ----------

    // VP9 itag -> yükseklik (HDR ve 60fps dahil)
    private val vp9Heights = mapOf(
        278 to 144, 242 to 240, 243 to 360, 244 to 480, 247 to 720, 248 to 1080,
        271 to 1440, 313 to 2160,
        302 to 720, 303 to 1080, 308 to 1440, 315 to 2160,
        330 to 144, 331 to 240, 332 to 360, 333 to 480, 334 to 720, 335 to 1080,
        336 to 1440, 337 to 2160
    )
    private val fps60Itags = setOf(302, 303, 308, 315, 334, 335, 336, 337)

    private fun itagOf(link: ExtractorLink): Int? =
        Regex("[?&]itag=(\\d+)").find(link.url)?.groupValues?.get(1)?.toIntOrNull()

    private fun heightOf(link: ExtractorLink): Int =
        itagOf(link)?.let { vp9Heights[it] } ?: link.quality

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
