package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class VkVideo : MainAPI() {
    override var mainUrl = "https://vkvideo.ru"
    override var name = "VK Video"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val API = "https://api.vk.ru/method"
        private const val V = "5.289"
        private const val CLIENT_ID = "52461373"

        // DevTools -> Ağ -> "?act=get_anonym_token" isteği -> Yük (Payload) / Başlıklar
        // Aşağıdaki 3 değeri o isteğe bakıp doldur:
        private const val LOGIN = "https://login.vk.ru/?act=get_anonym_token"
        private const val CLIENT_SECRET = ""
        private const val DEVICE_ID = ""

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val hdr = mapOf(
        "User-Agent" to UA,
        "Referer" to "https://vkvideo.ru/",
        "Origin" to "https://vkvideo.ru"
    )

    private var token: String? = null

    override val mainPage = mainPageOf(
        "türkçe dublaj film" to "Türkçe Dublaj Filmler",
        "türkçe altyazılı film" to "Türkçe Altyazılı Filmler",
        "yerli film" to "Yerli Filmler"
    )

    // ---------- API ----------

    private suspend fun getToken(): String {
        token?.let { return it }
        val text = app.post(
            LOGIN,
            headers = hdr,
            data = mapOf(
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
                "app_id" to CLIENT_ID,
                "version" to "1",
                "device_id" to DEVICE_ID
            )
        ).text
        val t = JSONObject(text).optJSONObject("data")?.optString("access_token")
        if (t.isNullOrBlank()) throw ErrorLoadingException("Token alınamadı: $text")
        token = t
        return t
    }

    private suspend fun api(
        method: String,
        params: Map<String, String>,
        retry: Boolean = true
    ): JSONObject {
        val text = app.post(
            "$API/$method?v=$V&client_id=$CLIENT_ID",
            headers = hdr,
            data = params + ("access_token" to getToken())
        ).text
        val json = JSONObject(text)
        val err = json.optJSONObject("error")
        if (err != null) {
            if (retry && err.optInt("error_code") == 5) {
                token = null
                return api(method, params, false)
            }
            throw ErrorLoadingException(err.optString("error_msg"))
        }
        return json.getJSONObject("response")
    }

    // ---------- Helpers ----------

    private fun videoKey(o: JSONObject): String {
        val k = "${o.optLong("owner_id")}_${o.optLong("id")}"
        val ak = o.optString("access_key")
        return if (ak.isNotBlank()) "${k}_$ak" else k
    }

    private fun poster(o: JSONObject): String? {
        val imgs = o.optJSONArray("image")
        if (imgs != null && imgs.length() > 0) {
            val u = imgs.getJSONObject(imgs.length() - 1).optString("url")
            if (u.isNotBlank()) return u
        }
        return o.optString("photo_800").ifBlank { o.optString("photo_320") }.ifBlank { null }
    }

    private suspend fun searchVideos(q: String, offset: Int): List<SearchResponse> {
        val r = api(
            "video.search",
            mapOf(
                "q" to q,
                "count" to "30",
                "offset" to offset.toString(),
                "longer" to "1200"
            )
        )
        val items = r.optJSONArray("items") ?: return emptyList()
        val list = ArrayList<SearchResponse>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val title = o.optString("title")
            if (title.isBlank()) continue
            val key = videoKey(o)
            list.add(
                newMovieSearchResponse(title, "$mainUrl/video$key", TvType.Movie) {
                    this.posterUrl = poster(o)
                }
            )
        }
        return list
    }

    // ---------- CloudStream ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = searchVideos(request.data, (page - 1) * 30)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchVideos(query, 0)
    }

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfter("/video").substringBefore("?")
        val item = api("video.get", mapOf("videos" to key))
            .getJSONArray("items").getJSONObject(0)
        val title = item.optString("title")
        val desc = item.optString("description")
        val dur = item.optInt("duration") / 60
        val img = poster(item)
        return newMovieLoadResponse(title, url, TvType.Movie, key) {
            this.posterUrl = img
            this.plot = desc.ifBlank { null }
            this.duration = dur
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = api("video.get", mapOf("videos" to data))
            .getJSONArray("items").getJSONObject(0)
        var found = false

        // 1) API "files" alanı (varsa)
        val files = item.optJSONObject("files")
        if (files != null) {
            val keys = files.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val u = files.optString(k)
                if (u.isBlank()) continue
                if (k.startsWith("mp4_")) {
                    val q = k.removePrefix("mp4_").toIntOrNull() ?: Qualities.Unknown.value
                    callback(
                        newExtractorLink(name, "$name ${q}p", u) {
                            this.referer = "$mainUrl/"
                            this.quality = q
                        }
                    )
                    found = true
                } else if (k == "hls" || k == "hls_ondemand") {
                    M3u8Helper.generateM3u8(name, u, "$mainUrl/").forEach(callback)
                    found = true
                }
            }
        }

        // 2) Yedek: player (embed) sayfasındaki playerParams
        if (!found) {
            val player = item.optString("player")
            if (player.isNotBlank()) {
                val html = app.get(player, headers = hdr).text
                val rx = Regex("\"(url\\d+|hls)\"\\s*:\\s*\"([^\"]+)\"")
                for (m in rx.findAll(html)) {
                    val k = m.groupValues[1]
                    val u = m.groupValues[2].replace("\\/", "/")
                    if (k == "hls") {
                        M3u8Helper.generateM3u8(name, u, "$mainUrl/").forEach(callback)
                        found = true
                    } else {
                        val q = k.removePrefix("url").toIntOrNull() ?: Qualities.Unknown.value
                        callback(
                            newExtractorLink(name, "$name ${q}p", u) {
                                this.referer = "$mainUrl/"
                                this.quality = q
                            }
                        )
                        found = true
                    }
                }
            }
        }
        return found
    }
}
