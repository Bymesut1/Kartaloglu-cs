package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class VkVideo : MainAPI() {
    override var mainUrl = "https://vkvideo.ru"
    override var name = "VK Video"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val API = "https://api.vkvideo.ru/method"
        private const val V = "5.289"
        private const val CLIENT_ID = "52461373"

        // Bu süreden kısa videolar (klip vb.) listeden elenir (saniye)
        private const val MIN_DURATION = 1200

        // DevTools -> Ağ -> "?act=get_anonym_token" isteği -> Yük (Payload) / Başlıklar
        private const val LOGIN = "https://login.vk.ru/?act=get_anonym_token"
        private const val CLIENT_SECRET = ""
        private const val DEVICE_ID = ""

        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    }

    private val hdr = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
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
    ): Any? {
        val text = app.post(
            "$API/$method?v=$V&client_id=$CLIENT_ID",
            headers = hdr,
            data = params + ("access_token" to getToken())
        ).text
        val json = JSONObject(text)
        val err = json.optJSONObject("error")
        if (err != null) {
            val code = err.optInt("error_code")
            if (retry && code == 5) {
                token = null
                return api(method, params, false)
            }
            throw ErrorLoadingException("VK hata $code ($method): ${err.optString("error_msg")}")
        }
        return json.opt("response")
    }

    // ---------- Video listesi yardımcıları ----------

    private fun collectVideos(node: Any?, out: LinkedHashMap<String, JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (node.has("owner_id") && node.has("id") && node.has("title")) {
                    out.putIfAbsent(videoKey(node), node)
                }
                val ks = node.keys()
                while (ks.hasNext()) collectVideos(node.opt(ks.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectVideos(node.opt(i), out)
            }
        }
    }

    private fun videoKey(o: JSONObject): String {
        val k = "${o.optLong("owner_id")}_${o.optLong("id")}"
        val ak = o.optString("access_key")
        return if (ak.isNotBlank()) "${k}_$ak" else k
    }

    private fun poster(o: JSONObject): String? {
        val cands = ArrayList<Pair<String, Int>>()
        for (k in listOf("image", "first_frame")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val u = e.optString("url")
                if (u.isNotBlank()) cands.add(u to e.optInt("width"))
            }
        }
        for (k in listOf("photo_800", "photo_640", "photo_320", "photo_130")) {
            val u = o.optString(k)
            if (u.isNotBlank()) cands.add(u to k.removePrefix("photo_").toInt())
        }
        if (cands.isEmpty()) return null
        // okcdn önizlemeleri sona, ~640px genişliğe en yakın olan öne
        val best = cands.sortedWith(
            compareBy<Pair<String, Int>>(
                { it.first.contains("okcdn.ru") },
                { abs(it.second - 640) }
            )
        ).first()
        return best.first.replace("userapi.com", "vkuserphoto.ru")
    }

    private suspend fun searchVideos(q: String): List<SearchResponse> {
        val r = api(
            "catalog.getVideoSearchWeb2",
            mapOf("q" to q, "count" to "30", "extended" to "1")
        )
        val found = LinkedHashMap<String, JSONObject>()
        collectVideos(r, found)
        return found.values
            .filter { !it.has("duration") || it.optInt("duration") >= MIN_DURATION }
            .map { o ->
                newMovieSearchResponse(
                    o.optString("title"),
                    "$mainUrl/video${videoKey(o)}",
                    TvType.Movie
                ) {
                    this.posterUrl = poster(o)
                }
            }
    }

    private suspend fun fetchItem(key: String): JSONObject {
        val r = try {
            api("video.getByIds", mapOf("videos" to key))
        } catch (e: Exception) {
            api("video.get", mapOf("videos" to key))
        }
        val found = LinkedHashMap<String, JSONObject>()
        collectVideos(r, found)
        return found.values.firstOrNull()
            ?: throw ErrorLoadingException("Video bilgisi bulunamadı: $key")
    }

    // ---------- Link yardımcıları ----------

    private fun isImage(u: String): Boolean =
        u.contains(".jpg") || u.contains(".jpeg") || u.contains(".png") ||
            u.contains(".webp") || u.contains("getVideoPreview") ||
            u.contains("userapi.com") || u.contains("vkuserphoto")

    private fun isMedia(u: String): Boolean {
        if (isImage(u)) return false
        if (u.contains("video_ext.php") || u.contains("vkvideo.ru") ||
            u.contains("vk.com") || u.contains("vk.ru")
        ) return false
        return u.contains(".m3u8") || u.contains(".mpd") || u.contains(".mp4") ||
            u.contains("okcdn.ru")
    }

    // JSON içinde nerede olursa olsun http ile başlayan metinleri bulur
    private fun scanUrls(node: Any?, hint: String, out: MutableList<Pair<String, String>>) {
        when (node) {
            is JSONObject -> {
                val ks = node.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    if (k == "image" || k == "first_frame" || k.startsWith("photo_")) continue
                    scanUrls(node.opt(k), k, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) scanUrls(node.opt(i), hint, out)
            }
            is String -> {
                if (node.startsWith("http")) out.add(hint to node)
            }
        }
    }

    private fun qualityOf(hint: String, u: String): Int? {
        Regex("(\\d{3,4})").find(hint)?.value?.toIntOrNull()?.let { return it }
        val t = Regex("[?&]type=(\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull()
        return when (t) {
            0 -> 144
            1 -> 240
            2 -> 360
            3 -> 480
            4 -> 720
            5 -> 1080
            6 -> 1440
            7 -> 2160
            else -> null
        }
    }

    private suspend fun emitMedia(
        hint: String,
        rawUrl: String,
        seen: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val u = rawUrl.replace("\\/", "/")
        if (!seen.add(u)) return false
        val ref = "$mainUrl/"
        val hdrs = mapOf("User-Agent" to UA, "Origin" to mainUrl, "Referer" to ref)
        when {
            u.contains(".m3u8") || hint.contains("hls") -> {
                try {
                    M3u8Helper.generateM3u8(name, u, ref, headers = hdrs).forEach(callback)
                } catch (e: Exception) {
                    callback(
                        newExtractorLink(name, "$name HLS", u, ExtractorLinkType.M3U8) {
                            this.referer = ref
                            this.headers = hdrs
                        }
                    )
                }
            }
            u.contains(".mpd") || hint.contains("dash") -> {
                callback(
                    newExtractorLink(name, "$name DASH", u, ExtractorLinkType.DASH) {
                        this.referer = ref
                        this.headers = hdrs
                    }
                )
            }
            else -> {
                val q = qualityOf(hint, u)
                val label = if (q != null) "${q}p" else hint.ifBlank { "MP4" }
                callback(
                    newExtractorLink(name, "$name $label", u) {
                        this.referer = ref
                        this.headers = hdrs
                        this.quality = q ?: Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }

    // Yönlendirmeleri elle takip eder, çerezleri taşır (döngüyü kırar)
    private suspend fun fetchFollow(url: String): String? {
        var u = url
        var cookies = emptyMap<String, String>()
        repeat(10) {
            val r = app.get(u, headers = hdr, cookies = cookies, allowRedirects = false)
            cookies = cookies + r.cookies
            val loc = r.headers["Location"]
            if (r.code in 300..399 && !loc.isNullOrBlank()) {
                u = java.net.URI(u).resolve(loc).toString()
            } else {
                return r.text
            }
        }
        return null
    }

    // ---------- CloudStream ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = if (page == 1) searchVideos(request.data) else emptyList()
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchVideos(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfter("/video").substringBefore("?")
        val item = fetchItem(key)
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
        val item = fetchItem(data)
        val seen = HashSet<String>()
        val debug = ArrayList<String>()
        var found = false

        val parts = data.split("_")
        val oid = parts.getOrNull(0) ?: ""
        val vid = parts.getOrNull(1) ?: ""

        debug.add("keys=" + item.keys().asSequence().joinToString(","))

        // 1) getByIds / get yanıtı içindeki linkler
        val urls1 = ArrayList<Pair<String, String>>()
        scanUrls(item, "", urls1)
        for ((k, u) in urls1) {
            if (isMedia(u) && emitMedia(k, u, seen, callback)) found = true
        }

        // 2) Sitenin kendi oynatma metodları
        if (!found) {
            val playParams = mapOf(
                "videos" to data,
                "video" to data,
                "owner_id" to oid,
                "video_id" to vid,
                "id" to vid
            )
            for (m in listOf("video.getForPlay", "video.getPlayerConfig")) {
                try {
                    val r = api(m, playParams)
                    val list = ArrayList<Pair<String, String>>()
                    scanUrls(r, "", list)
                    var n = 0
                    for ((k, u) in list) {
                        if (isMedia(u) && emitMedia(k, u, seen, callback)) {
                            found = true
                            n++
                        }
                    }
                    debug.add("$m ok links=$n urls=${list.size}")
                } catch (e: Exception) {
                    debug.add("$m: ${e.message?.take(90)}")
                }
            }
        }

        // 3) Yedek: oynatıcı (embed) sayfası
        var player = item.optString("player")
        if (!found) {
            if (player.isBlank()) {
                val ak = item.optString("access_key")
                player = "$mainUrl/video_ext.php?oid=$oid&id=$vid" +
                    (if (ak.isNotBlank()) "&hash=$ak" else "")
            }
            try {
                val html = fetchFollow(player)
                if (html == null) {
                    debug.add("embed: yönlendirme döngüsü")
                } else {
                    val rx = Regex(
                        "\"(hls|dash_sep|dash_uni|url\\d+|mp4_\\d+)\"\\s*:\\s*\"(http[^\"]+)\""
                    )
                    var n = 0
                    for (m in rx.findAll(html)) {
                        if (emitMedia(m.groupValues[1], m.groupValues[2], seen, callback)) {
                            found = true
                            n++
                        }
                    }
                    debug.add("embed len=${html.length} links=$n")
                }
            } catch (e: Exception) {
                debug.add("embed: ${e.message?.take(90)}")
            }
        }

        // 4) Hiçbiri olmadıysa teşhis satırları göster
        if (!found) {
            debug.add("player=" + player.take(70))
            for (d in debug) {
                callback(
                    newExtractorLink(name, "DEBUG $d", "https://debug.invalid/") { }
                )
            }
            return true
        }
        return true
    }
}
