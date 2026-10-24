package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
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
        private const val MIN_DURATION = 0

        // true: açılan posteri olmayan kartlar listeden çıkarılır
        private const val REQUIRE_POSTER = true

        // Bir sayfa boş çıkarsa (süzgeçten geçen video yoksa) en fazla kaç ham istek daha atılsın
        private const val MAX_EMPTY_FETCHES = 4

        // Aramaya videoları dahil edilecek en fazla kanal/topluluk sayısı
        private const val MAX_CHANNELS = 6

        // Her kanaldan sayfa başına çekilecek video sayısı
        private const val CH_PAGE = 30

        // true: arama sonuçlarının başına teşhis kartı (API yanıt şekli, bulunan kanallar) eklenir
        private const val DEBUG_SEARCH = false

        // DevTools -> Ağ -> "?act=get_anonym_token" isteği -> Yük (Payload) / Başlıklar
        private const val LOGIN = "https://login.vk.ru/?act=get_anonym_token"
        private const val CLIENT_SECRET = ""
        private const val DEVICE_ID = ""

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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

    // Sadece gerçek videoları toplar (albüm/oynatma listesi gibi nesneler elenir)
    private fun collectVideos(node: Any?, out: LinkedHashMap<String, JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (node.has("owner_id") && node.has("id") && node.has("title") &&
                    node.has("duration") && node.optLong("id") != 0L &&
                    node.optLong("owner_id") != 0L
                ) {
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

    private fun shape(r: Any?): String = when (r) {
        is JSONObject -> "obj{" + r.keys().asSequence().joinToString(",").take(110) + "}"
        is JSONArray -> "arr[${r.length()}]"
        else -> r.toString().take(60)
    }

    // ---------- Poster ----------

    private fun posterCandidates(o: JSONObject): List<String> {
        val raw = ArrayList<Pair<String, Int>>()
        for (k in listOf("image", "first_frame")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val u = e.optString("url")
                if (u.isNotBlank()) raw.add(u to e.optInt("width"))
            }
        }
        for (k in listOf("photo_800", "photo_640", "photo_320", "photo_130")) {
            val u = o.optString(k)
            if (u.isNotBlank()) raw.add(u to k.removePrefix("photo_").toInt())
        }
        // okcdn önizlemeleri sona, ~640px genişliğe en yakın olan öne
        val sorted = raw.sortedWith(
            compareBy<Pair<String, Int>>(
                { it.first.contains("okcdn.ru") },
                { abs(it.second - 640) }
            )
        )
        val out = LinkedHashSet<String>()
        for ((u, _) in sorted) {
            if (u.contains("userapi.com")) out.add(u.replace("userapi.com", "vkuserphoto.ru"))
            out.add(u)
        }
        return out.toList()
    }

    private suspend fun imageOk(u: String): Boolean {
        return try {
            val r = app.get(
                u,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "image/*",
                    "Range" to "bytes=0-1023"
                ),
                timeout = 5L
            )
            val ct = r.headers["Content-Type"] ?: ""
            r.code in 200..206 && (ct.isEmpty() || ct.startsWith("image"))
        } catch (e: Exception) {
            false
        }
    }

    // Gerçekten açılan ilk posteri döndürür, hiçbiri açılmazsa null
    private suspend fun workingPoster(o: JSONObject): String? {
        for (u in posterCandidates(o).take(4)) {
            if (imageOk(u)) return u
        }
        return null
    }

    // ---------- Arama (sayfalı + kanallar) ----------

    private class Cur(val next: String, val section: String?)

    // "anahtar|sayfa" -> o sayfayı çekmek için gereken imleç
    private val cursors = java.util.concurrent.ConcurrentHashMap<String, Cur>()
    private val seenMap = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val chanMap = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<Long, String>>()

    private fun findSectionId(root: Any?): String? {
        val o = root as? JSONObject ?: return null
        val s = o.optJSONObject("catalog")?.optJSONArray("sections")
            ?.optJSONObject(0)?.optString("id")
        return if (s.isNullOrBlank()) null else s
    }

    // Yanıttaki "next_from" imleçlerinden en çok video içereni seçer
    private fun findCursor(root: Any?): Cur? {
        var best: Cur? = null
        var bestCount = -1
        val sec = findSectionId(root)
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val nf = n.optString("next_from")
                    if (nf.isNotBlank()) {
                        val tmp = LinkedHashMap<String, JSONObject>()
                        collectVideos(n, tmp)
                        if (tmp.size > bestCount) {
                            bestCount = tmp.size
                            best = Cur(nf, n.optString("section_id").ifBlank { sec })
                        }
                    }
                    val ks = n.keys()
                    while (ks.hasNext()) walk(n.opt(ks.next()))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        return best
    }

    // Yanıttaki kanal/topluluk listesini toplar (extended=1 ile "groups" dizisi gelir)
    private fun collectGroups(node: Any?, out: LinkedHashMap<Long, String>) {
        when (node) {
            is JSONObject -> {
                val g = node.optJSONArray("groups")
                if (g != null) {
                    for (i in 0 until g.length()) {
                        val o = g.optJSONObject(i) ?: continue
                        val id = abs(o.optLong("id"))
                        val nm = o.optString("name")
                        if (id != 0L && nm.isNotBlank()) out.putIfAbsent(id, nm)
                    }
                }
                val ks = node.keys()
                while (ks.hasNext()) collectGroups(node.opt(ks.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectGroups(node.opt(i), out)
        }
    }

    private fun fold(s: String): String = s.lowercase()
        .replace("\u0307", "")
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    // Kanal adı aramayla eşleşiyor mu? ("Filmizle" ~ "Film izle")
    private fun channelMatches(q: String, name: String): Boolean {
        val fq = fold(q)
        val fn = fold(name)
        if (fn.replace(" ", "").contains(fq.replace(" ", ""))) return true
        val tokens = fq.split(" ").filter { it.length >= 2 }
        return tokens.isNotEmpty() && tokens.all { fn.contains(it) }
    }

    private suspend fun channelVideos(gid: Long, offset: Int): Pair<List<JSONObject>, Boolean> {
        return try {
            val r = api(
                "video.get",
                mapOf(
                    "owner_id" to "-$gid",
                    "count" to "$CH_PAGE",
                    "offset" to "$offset",
                    "extended" to "1"
                )
            )
            val m = LinkedHashMap<String, JSONObject>()
            collectVideos(r, m)
            val total = (r as? JSONObject)?.optInt("count") ?: 0
            val more = if (total > 0) total > offset + CH_PAGE else m.size >= CH_PAGE
            m.values.toList() to more
        } catch (e: Exception) {
            emptyList<JSONObject>() to false
        }
    }

    private suspend fun toResponses(videos: Collection<JSONObject>): List<SearchResponse> {
        return videos.toList().amap { o ->
            val p = workingPoster(o)
            if (p == null && REQUIRE_POSTER) {
                null
            } else {
                newMovieSearchResponse(
                    o.optString("title"),
                    "$mainUrl/video${videoKey(o)}",
                    TvType.Movie
                ) {
                    this.posterUrl = p
                }
            }
        }.filterNotNull()
    }

    // Bir sayfa sonuç döndürür: (sonuçlar, devamı var mı)
    private suspend fun searchPaged(
        q: String,
        page: Int,
        withChannels: Boolean
    ): Pair<List<SearchResponse>, Boolean> {
        val ck = (if (withChannels) "S:" else "H:") + q
        val seen = seenMap.getOrPut(ck) { java.util.Collections.synchronizedSet(HashSet()) }
        if (page <= 1) {
            seen.clear()
            chanMap.remove(ck)
            cursors.keys.removeAll { it.startsWith("$ck|") }
        }
        val chans = chanMap.getOrPut(ck) { LinkedHashMap() }

        var cur: Cur? = if (page <= 1) null else cursors["$ck|$page"]
        var searchMore = page <= 1 || cur != null
        val fresh = LinkedHashMap<String, JSONObject>()
        var firstShape: String? = null
        var tries = 0

        while (searchMore && tries < MAX_EMPTY_FETCHES) {
            tries++
            val params = mutableMapOf("q" to q, "count" to "30", "extended" to "1")
            if (cur != null) params["start_from"] = cur.next
            var resp: Any? = api("catalog.getVideoSearchWeb2", params)
            var vids = LinkedHashMap<String, JSONObject>()
            collectVideos(resp, vids)

            // Arama imleci işe yaramadıysa bölüm (section) imleciyle dene
            if (cur != null && cur.section != null && vids.keys.none { it !in seen }) {
                try {
                    val r2 = api(
                        "catalog.getSection",
                        mapOf(
                            "section_id" to cur.section,
                            "start_from" to cur.next,
                            "count" to "30",
                            "extended" to "1"
                        )
                    )
                    val v2 = LinkedHashMap<String, JSONObject>()
                    collectVideos(r2, v2)
                    if (v2.keys.any { it !in seen }) {
                        resp = r2
                        vids = v2
                    }
                } catch (e: Exception) {
                }
            }

            if (firstShape == null) firstShape = shape(resp)
            if (withChannels) collectGroups(resp, chans)

            val nxt = findCursor(resp)
            cur = if (nxt != null && nxt.next != cur?.next) nxt else null

            for ((k, v) in vids) {
                if (v.optInt("duration") >= MIN_DURATION && seen.add(k)) fresh[k] = v
            }
            searchMore = cur != null
            if (fresh.isNotEmpty()) break
        }
        cur?.let { cursors["$ck|${page + 1}"] = it }

        // Kanallar / topluluklar: adı aramayla eşleşenlerin videoları
        var chMore = false
        var matched: List<Long> = emptyList()
        if (withChannels) {
            matched = chans.filter { channelMatches(q, it.value) }.keys.take(MAX_CHANNELS)
            val res = matched.amap { gid -> channelVideos(gid, (maxOf(page, 1) - 1) * CH_PAGE) }
            for ((list, more) in res) {
                if (more) chMore = true
                for (v in list) {
                    if (v.optInt("duration") >= MIN_DURATION && seen.add(videoKey(v))) {
                        fresh[videoKey(v)] = v
                    }
                }
            }
        }

        val items = ArrayList<SearchResponse>()
        if (DEBUG_SEARCH && page <= 1) {
            items.add(
                newMovieSearchResponse(
                    "DEBUG ${firstShape ?: "-"} groups=${chans.size} matched=${matched.size} " +
                        "names=${chans.values.take(6)}",
                    "$mainUrl/video0_0",
                    TvType.Movie
                )
            )
        }
        items.addAll(toResponses(fresh.values))
        return items to (searchMore || chMore)
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
        val u = rawUrl.replace("\\/", "/").replace("\\u0026", "&")
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

    // Sayfa yapısı/anahtar adı ne olursa olsun HTML içindeki medya adreslerini bulur
    private suspend fun scanHtml(html: String, seen: HashSet<String>, callback: (ExtractorLink) -> Unit): Int {
        val t = html.replace("\\/", "/").replace("\\u0026", "&").replace("&quot;", "\"").replace("&amp;", "&")
        var n = 0
        for (m in Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"(https?://[^\"\\s]+)\"").findAll(t)) {
            if (isMedia(m.groupValues[2]) && emitMedia(m.groupValues[1], m.groupValues[2], seen, callback)) n++
        }
        for (m in Regex("https?://[^\\s\"'<>\\\\]+").findAll(t)) {
            if (isMedia(m.value) && emitMedia("", m.value, seen, callback)) n++
        }
        return n
    }

    // Yönlendirmeleri elle takip eder, çerezleri taşır (döngüyü kırar)
    private suspend fun fetchFollow(url: String, h: Map<String, String> = hdr): String? {
        var u = url
        var cookies = emptyMap<String, String>()
        repeat(10) {
            val r = app.get(u, headers = h, cookies = cookies, allowRedirects = false)
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
        val (items, more) = searchPaged(request.data, page, false)
        return newHomePageResponse(request.name, items, more)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, more) = searchPaged(query, page, true)
        return newSearchResponseList(items, more)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchPaged(query, 1, true).first
    }

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfter("/video").substringBefore("?")
        val item = fetchItem(key)
        val title = item.optString("title")
        val desc = item.optString("description")
        val dur = item.optInt("duration") / 60
        val img = workingPoster(item)
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

        debug.add(
            "info type=${item.optString("type")} srv=${item.optString("server")} " +
                "ovp=${item.optString("ov_provider_id")} h=${item.optInt("height")}"
        )
        debug.add("download=" + item.opt("download").toString().take(80))
        debug.add("direct=" + item.optString("direct_url").take(80))

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
                    debug.add("$m links=$n urls=${list.size} ${shape(r)}")
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
                    val g = scanHtml(html, seen, callback)
                    if (g > 0) {
                        found = true
                        n += g
                    }
                    debug.add(
                        "ipucu=" + Regex("(?i)(m3u8|mpd|mp4|hls|dash)").findAll(html)
                            .groupingBy { it.value.lowercase() }.eachCount()
                    )
                    debug.add(
                        "embed len=${html.length} links=$n head=" +
                            html.take(80).replace("\n", " ")
                    )
                }
            } catch (e: Exception) {
                debug.add("embed: ${e.message?.take(90)}")
            }
        }

        // 3b) Farklı adresler / mobil sayfa (HTML'de oynatıcı verisi başka yerde olabilir)
        if (!found) {
            val hash = Regex("[?&]hash=([0-9a-zA-Z]+)").find(player)?.groupValues?.get(1)
                ?: item.optString("access_key")
            val mobileHdr = hdr + mapOf("User-Agent" to MOBILE_UA)
            val cands = listOf(
                player to mobileHdr,
                ("https://vk.com/video_ext.php?oid=$oid&id=$vid" + (if (hash.isNotBlank()) "&hash=$hash" else "")) to hdr,
                ("https://m.vk.com/video${oid}_$vid" + (if (hash.isNotBlank()) "?access_key=$hash" else "")) to mobileHdr
            )
            for ((cu, ch) in cands) {
                if (found) break
                try {
                    val html = fetchFollow(cu, ch) ?: continue
                    val n = scanHtml(html, seen, callback)
                    debug.add("alt ${cu.take(45)} len=${html.length} links=$n")
                    if (n > 0) found = true
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    debug.add("alt: ${e.message?.take(70)}")
                }
            }
        }

        // 3c) Son çare: sayfayı WebView'da açıp oynatıcının istediği medya adresini yakala
        if (!found) {
            for (wu in listOf(player, "$mainUrl/video$data")) {
                if (found) break
                try {
                    val r = app.get(
                        wu,
                        headers = hdr,
                        referer = "$mainUrl/",
                        interceptor = WebViewResolver(
                            Regex("""(?i)\.m3u8|\.mpd|(vkvd|okcdn|mycdn)[^/\s]*/[^\s?]*\.mp4"""),
                            userAgent = null,
                            useOkhttp = false,
                            timeout = 20_000L
                        ),
                        timeout = 30L
                    )
                    val cu = r.url
                    debug.add("webview ${cu.take(70)}")
                    if (cu != wu && isMedia(cu) && emitMedia("", cu, seen, callback)) found = true
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    debug.add("webview: ${e.message?.take(70)}")
                }
            }
        }

        // 4) Hiçbiri olmadıysa teşhis satırları göster (her satırın adresi benzersiz)
        if (!found) {
            debug.add("player=" + player.take(70))
            debug.forEachIndexed { i, d ->
                callback(
                    newExtractorLink(name, "DEBUG $d", "https://debug.invalid/$i") { }
                )
            }
            return true
        }
        return true
    }
}
