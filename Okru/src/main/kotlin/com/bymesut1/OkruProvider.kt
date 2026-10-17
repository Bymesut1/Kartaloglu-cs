package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.parser.Parser
import java.net.URLEncoder

class OkruProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "Okru"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
    private val uaDesktop =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private data class OkItem(val id: String, val title: String, val poster: String?)

    private data class OkMeta(
        val id: String,
        val title: String,
        val poster: String?,
        val dur: Int,
        val desc: String,
        val videos: List<Pair<Int, String>>,
        val hls: String,
        val dash: String
    )

    // Elle eklenen garanti filmler (id = ok.ru video numarası). Buraya yenilerini ekleyebilirsin.
    private val secilmis = listOf(
        OkItem("1766021532169", "The Terminator (1984) TR 1080p", null),
        OkItem("1050987137588", "The Terminator (1984) BluRay TR", null),
        OkItem("1189269146174", "The Terminator (1984) Filmsarayi", null),
        OkItem("11402194193016", "Rambo İlk Kan (1982) Beyaz TV", null),
        OkItem("10001072654964", "İlk Kan (1982)", null),
        OkItem("357648699796", "Yeşil Dev Hulk (2008) TR Dublaj", null),
        OkItem("7225761139269", "Yeşil Dev Hulk 2 (2008)", null)
    )

    // Yükleyen hesaplar (video listesi sayfası)
    private val hesaplar = listOf("https://ok.ru/profile/590041294398/video")

    override val mainPage = mainPageOf(
        "secilmis" to "Seçilmiş Türkçe Filmler",
        "hesap" to "Yükleyen Hesap Videoları",
        "vitrin" to "ok.ru Vitrin"
    )

    // ---------------- Yardımcılar ----------------

    private fun dec(s: String): String = Parser.unescapeEntities(s, false)
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun hdr(desktop: Boolean = false): Map<String, String> = mapOf(
        "User-Agent" to (if (desktop) uaDesktop else ua),
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8,ru;q=0.5",
        "Referer" to "$mainUrl/video"
    )

    private fun fixPoster(p: String?): String? {
        val s = p?.trim() ?: return null
        if (s.isBlank()) return null
        return if (s.startsWith("//")) "https:$s" else s
    }

    private fun card(x: OkItem): SearchResponse =
        newMovieSearchResponse(x.title, "$mainUrl/video/${x.id}", TvType.Movie) {
            this.posterUrl = x.poster
        }

    private fun turkishTag(title: String): Boolean {
        val t = title.replace("İ", "i").lowercase()
        if (Regex("altyaz|\\bsub\\b|\\beng\\b|english").containsMatchIn(t)) return false
        return Regex("(^|[^a-z0-9])tr([^a-z0-9]|$)|dublaj|turkce|türkçe|\\bdual\\b|\\bizle\\b").containsMatchIn(t)
    }

    // ---------------- Ana sayfa ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val items: List<OkItem> = when (request.data) {
            "secilmis" -> secilmis
            "hesap" -> hesaplar.flatMap {
                try { parseProfile(app.get(it, headers = hdr(true)).text) } catch (e: Exception) { emptyList() }
            }
            else -> try {
                parseProps(app.get("$mainUrl/video/showcase", headers = hdr(true)).text)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val seen = HashSet<String>()
        val list = items.filter { seen.add(it.id) }.map { card(it) }
        return newHomePageResponse(request.name, list, false)
    }

    // Profil sayfası: her /video/<numara> için en yakın başlık
    private fun parseProfile(html: String): List<OkItem> {
        val ids = ArrayList<Pair<String, Int>>()
        val seen = HashSet<String>()
        Regex("/video/(\\d{8,})").findAll(html).forEach {
            if (seen.add(it.groupValues[1])) ids.add(it.groupValues[1] to it.range.first)
        }
        val skip = Regex("^(görüntüle|izle|video|videolar|kapat|logo|ok|oynat)$", RegexOption.IGNORE_CASE)
        val titles = ArrayList<Pair<Int, String>>()
        Regex("(?:alt|title|aria-label)=\"([^\"]{2,200})\"").findAll(html).forEach { m ->
            val t = dec(m.groupValues[1]).trim()
            if (t.isBlank() || t == "#" || t.startsWith("http") || skip.matches(t)) return@forEach
            titles.add(m.range.first to t)
        }
        return ids.mapNotNull { (id, at) ->
            val best = titles.minByOrNull { kotlin.math.abs(it.first - at) + (if (it.first > at) 1 else 0) }
            if (best == null || kotlin.math.abs(best.first - at) > 900) null else OkItem(id, best.second, null)
        }
    }

    // data-props / data-options JSON bloklarındaki film kayıtları (vitrin, arama)
    private fun parseProps(html: String): List<OkItem> {
        val out = ArrayList<OkItem>()
        val seen = HashSet<String>()
        fun add(o: JSONObject) {
            val id = o.optString("id")
            val title = dec(o.optString("title").ifBlank { o.optString("name") }).trim()
            if (id.isBlank() || title.isBlank() || !seen.add(id)) return
            val poster = listOf("thumbnailUrl", "thumbnail", "poster", "imageUrl")
                .map { o.optString(it) }.firstOrNull { it.isNotBlank() }
            out.add(OkItem(id, title, fixPoster(poster)))
        }
        fun walk(o: Any?, depth: Int) {
            if (o == null || depth > 9) return
            if (o is JSONArray) {
                for (i in 0 until o.length()) walk(o.opt(i), depth + 1)
                return
            }
            if (o !is JSONObject) return
            val mv = o.optJSONObject("movie")
            if (mv != null && mv.optString("id").isNotBlank()) {
                add(mv)
            } else if (Regex("^\\d{8,}$").matches(o.optString("id")) &&
                (o.has("title") || o.has("name")) &&
                (o.has("duration") || o.has("thumbnail") || o.has("poster") || o.has("imageUrl"))
            ) {
                add(o)
            }
            val keys = o.keys()
            while (keys.hasNext()) {
                val v = o.opt(keys.next())
                if (v is JSONObject || v is JSONArray) walk(v, depth + 1)
            }
        }
        Regex("data-(?:props|options|module|state)=\"([^\"]+)\"").findAll(html).forEach { m ->
            try {
                walk(JSONTokener(dec(m.groupValues[1])).nextValue(), 0)
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ---------------- Arama (ok.ru + arama motorları) ----------------

    private fun engineUrls(q: String): List<String> = listOf(
        "https://www.bing.com/search?q=${enc(q)}&setlang=tr&count=30",
        "https://www.google.com/search?q=${enc(q)}&hl=tr&num=30&gbv=1"
    )

    private fun engineHeaders(u: String): Map<String, String> {
        val h = hashMapOf(
            "User-Agent" to uaDesktop,
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to "https://www.google.com/"
        )
        if (u.contains("google.")) h["Cookie"] = "CONSENT=YES+cb; SOCS=CAI"
        return h
    }

    private fun engineIds(html0: String): List<String> {
        val html = html0.replace("%252F", "/").replace("%2F", "/").replace("%2f", "/")
            .replace("%3A", ":").replace("&amp;", "&").replace("\\/", "/")
        val out = LinkedHashSet<String>()
        Regex("ok\\.ru/video(?:embed)?/(\\d{8,})", RegexOption.IGNORE_CASE).findAll(html)
            .forEach { out.add(it.groupValues[1]) }
        return out.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val items = LinkedHashMap<String, OkItem>()

        try {
            val h = app.get(
                "$mainUrl/video/search?st.cmd=anonymVideo&st.m=SEARCH&st.v.sq=${enc(query)}",
                headers = hdr(true)
            ).text
            parseProps(h).forEach { if (!items.containsKey(it.id)) items[it.id] = it }
        } catch (e: Exception) {
        }

        val ids = LinkedHashSet<String>()
        val queries = listOf("$query tr ok.ru", "$query türkçe dublaj ok.ru", "$query izle ok.ru")
        for (q in queries) {
            for (u in engineUrls(q)) {
                val html = try { app.get(u, headers = engineHeaders(u)).text } catch (e: Exception) { "" }
                engineIds(html).forEach { ids.add(it) }
            }
            if (ids.size >= 14) break
        }

        // Bulunan numaraların gerçek başlığı video sayfasından doğrulanır
        for (id in ids.filter { !items.containsKey(it) }.take(12)) {
            val m = fetchMeta(id) ?: continue
            if (m.title.isBlank()) continue
            items[id] = OkItem(id, m.title, m.poster)
        }
        return items.values.sortedByDescending { turkishTag(it.title) }.map { card(it) }
    }

    // ---------------- Video bilgisi ----------------

    private val kalite = mapOf(
        "ultra" to 2160, "quad" to 1440, "full" to 1080, "hd" to 720,
        "sd" to 480, "low" to 360, "lowest" to 240, "mobile" to 144
    )

    private suspend fun fetchMeta(id: String): OkMeta? {
        for (path in listOf("/videoembed/", "/video/")) {
            val doc = try {
                app.get(mainUrl + path + id, headers = hdr(path == "/video/")).document
            } catch (e: Exception) {
                continue
            }
            for (el in doc.select("[data-options]")) {
                val raw = el.attr("data-options")
                if (!raw.contains("flashvars")) continue
                try {
                    val opts = JSONObject(raw)
                    val fv = opts.optJSONObject("flashvars") ?: continue
                    val md = fv.optJSONObject("metadata") ?: JSONObject(fv.optString("metadata", "{}"))
                    val mv = md.optJSONObject("movie") ?: JSONObject()

                    val vids = ArrayList<Pair<Int, String>>()
                    val arr = md.optJSONArray("videos")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val v = arr.optJSONObject(i) ?: continue
                            val u = v.optString("url")
                            if (u.isNotBlank()) vids.add((kalite[v.optString("name")] ?: 0) to u)
                        }
                    }
                    val hls = listOf("hlsManifestUrl", "hlsMasterPlaylistUrl", "ondemandHls")
                        .map { md.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
                    val dash = listOf("ondemandDash", "dashManifestUrl", "ondemandDashUrl", "dashUrl")
                        .map { md.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
                    if (vids.isEmpty() && hls.isBlank() && dash.isBlank()) continue

                    return OkMeta(
                        id,
                        mv.optString("title").trim(),
                        fixPoster(mv.optString("poster")),
                        mv.optString("duration").toIntOrNull() ?: 0,
                        mv.optString("description"),
                        vids, hls, dash
                    )
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    private fun idOf(url: String): String = Regex("(\\d{8,})").find(url)?.groupValues?.get(1) ?: ""

    override suspend fun load(url: String): LoadResponse? {
        val id = idOf(url)
        if (id.isBlank()) return null
        val m = fetchMeta(id) ?: return null
        val link = "$mainUrl/video/$id"
        val title = m.title.ifBlank { "Video $id" }
        val seed = secilmis.firstOrNull { it.id == id }
        return newMovieLoadResponse(seed?.title ?: title, link, TvType.Movie, link) {
            this.posterUrl = m.poster
            this.plot = m.desc.ifBlank { null }
            this.duration = if (m.dur > 0) m.dur / 60 else null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idOf(data)
        if (id.isBlank()) return false
        val m = fetchMeta(id) ?: return false
        val h = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl)
        var any = false

        for ((q, u0) in m.videos.sortedByDescending { it.first }) {
            var u = u0.replace("\\/", "/")
            if (u.startsWith("//")) u = "https:$u"
            if (!u.startsWith("http")) continue
            callback(
                newExtractorLink(name, "$name ${if (q > 0) "${q}p" else "Video"}", u, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = if (q > 0) q else Qualities.Unknown.value
                    this.headers = h
                }
            )
            any = true
        }
        if (m.hls.isNotBlank()) {
            callback(
                newExtractorLink(name, "$name HLS", m.hls, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = h
                }
            )
            any = true
        }
        if (m.dash.isNotBlank()) {
            callback(
                newExtractorLink(name, "$name DASH", m.dash, ExtractorLinkType.DASH) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = h
                }
            )
            any = true
        }
        return any
    }
}
