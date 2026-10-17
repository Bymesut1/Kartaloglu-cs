package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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

    private val IC = RegexOption.IGNORE_CASE

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
        val t = s.replace("\\/", "/").replace("&amp;", "&")
        return if (t.startsWith("//")) "https:$t" else t
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

    private fun fold(s: String): String = s.replace("İ", "i").replace("I", "ı").lowercase()
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun normKey(s: String): String = fold(s).replace(" ", "")

    // ---------------- Video bilgisi önbelleği ----------------

    private val metaCache = ConcurrentHashMap<String, OkMeta>()

    private suspend fun metaOf(id: String): OkMeta? {
        metaCache[id]?.let { return it }
        val m = fetchMeta(id) ?: return null
        metaCache[id] = m
        return m
    }

    // ---------------- Afiş: her kartta mutlaka resim olsun ----------------

    private val finalPoster = ConcurrentHashMap<String, String>()

    // Resim adresi gerçekten açılıyor mu?
    private suspend fun imgOk(u: String): Boolean {
        return try {
            val r = app.get(
                u,
                headers = mapOf("User-Agent" to uaDesktop, "Referer" to "$mainUrl/", "Accept" to "image/*,*/*;q=0.8"),
                timeout = 10L
            )
            val ct = r.headers["Content-Type"].orEmpty()
            r.isSuccessful && (ct.isEmpty() || ct.startsWith("image", true))
        } catch (e: Exception) {
            false
        }
    }

    // Son çare: başlık yazılı düz bir afiş (her zaman açılır)
    private fun placeholder(title: String): String {
        val t = title.replace(Regex("""\s+"""), " ").trim().take(40).ifBlank { "Video" }
        return "https://placehold.co/500x750/1c1c1e/ffffff.png?text=" + enc(t)
    }

    // Sıra: video sayfası afişi -> ok.ru liste afişi -> (hiçbiri açılmazsa) başlıklı düz afiş
    private suspend fun bestPoster(id: String, title: String, cands: List<String?>): String {
        finalPoster[id]?.let { return it }
        var pick: String? = null
        for (c in cands.filterNotNull().filter { it.isNotBlank() }.distinct()) {
            if (imgOk(c)) {
                pick = c
                break
            }
        }
        val res = pick ?: placeholder(title)
        finalPoster[id] = res
        return res
    }

    // Kayıtların afişini (ve gerekirse başlığını) tamamlar. metaTitle=true: başlık video sayfasından
    private suspend fun withPosters(list: List<OkItem>, metaTitle: Boolean = false): List<OkItem> {
        val seedIds = secilmis.map { it.id }.toSet()
        return list.amap { x ->
            val m = try { metaOf(x.id) } catch (e: Exception) { null }
            val title = when {
                metaTitle && !seedIds.contains(x.id) && m != null && m.title.isNotBlank() -> m.title
                else -> x.title.ifBlank { m?.title.orEmpty() }
            }
            if (title.isBlank()) {
                x
            } else {
                OkItem(x.id, title, bestPoster(x.id, title, listOf(m?.poster, x.poster)))
            }
        }.filter { it.title.isNotBlank() }
    }

    // ---------------- Ana sayfa ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val items: List<OkItem> = when (request.data) {
            "secilmis" -> secilmis
            "hesap" -> hesaplar.flatMap { profileItems(it) }
            else -> vitrinItems()
        }
        val seen = HashSet<String>()
        val uniq = items.filter { seen.add(it.id) }.take(36)
        val list = withPosters(uniq).map { card(it) }
        return newHomePageResponse(request.name, list, false)
    }

    private suspend fun profileItems(url: String): List<OkItem> {
        val urls = listOf(url, url.replace("https://ok.ru", "https://m.ok.ru"))
        for (u in urls) {
            for (desk in listOf(true, false)) {
                val html = try { app.get(u, headers = hdr(desk), timeout = 15L).text } catch (e: Exception) { "" }
                if (html.isBlank()) continue
                val a = parseProfile(html)
                if (a.isNotEmpty()) return a
                val b = parseProps(html)
                if (b.isNotEmpty()) return b
                val ids = LinkedHashSet<String>()
                Regex("/video/(\\d{8,})").findAll(html).forEach { ids.add(it.groupValues[1]) }
                if (ids.isNotEmpty()) return ids.take(30).map { OkItem(it, "", null) }
            }
        }
        return emptyList()
    }

    private suspend fun vitrinItems(): List<OkItem> {
        val a = try {
            parseProps(app.get("$mainUrl/video/showcase", headers = hdr(true), timeout = 15L).text)
        } catch (e: Exception) {
            emptyList()
        }
        if (a.isNotEmpty()) return a
        // Vitrin boşsa ok.ru'nun kendi aramasından Türkçe filmler
        val r = okSearch("türkçe dublaj film")
        if (r.first.isNotEmpty()) return r.first
        return r.second.take(24).map { OkItem(it, "", null) }
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

    // ---------------- Arama (yalnızca ok.ru'nun kendi araması) ----------------

    // Döner: (arama sayfasındaki kayıtlar, sayfada geçen video numaraları)
    private suspend fun okSearch(q: String): Pair<List<OkItem>, List<String>> {
        val items = LinkedHashMap<String, OkItem>()
        val ids = LinkedHashSet<String>()
        val urls = listOf(
            "$mainUrl/video/search?st.cmd=anonymVideo&st.m=SEARCH&st.v.sq=${enc(q)}",
            "$mainUrl/video/search?st.v.sq=${enc(q)}",
            "https://m.ok.ru/video/search?st.v.sq=${enc(q)}"
        )
        for (u in urls) {
            val h = try {
                app.get(u, headers = hdr(!u.contains("m.ok.ru")), timeout = 15L).text
            } catch (e: Exception) {
                ""
            }
            if (h.isBlank()) continue
            parseProps(h).forEach { if (!items.containsKey(it.id)) items[it.id] = it }
            Regex("/video/(\\d{8,})").findAll(h).forEach { ids.add(it.groupValues[1]) }
            if (items.size >= 10) break
        }
        return items.values.toList() to ids.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val items = LinkedHashMap<String, OkItem>()

        // 1) Elle eklenen filmlerde eşleşme
        val words = fold(q).split(" ").filter { it.isNotBlank() }
        if (words.isNotEmpty()) {
            for (s in secilmis) {
                val t = fold(s.title)
                if (words.all { t.contains(it) } && !items.containsKey(s.id)) items[s.id] = s
            }
        }

        // 2) ok.ru kendi araması
        val (found, pageIds) = okSearch(q)
        found.forEach { if (!items.containsKey(it.id)) items[it.id] = it }

        // 3) Arama sayfasında geçen ama kaydı çıkarılamayan numaraların başlığı video sayfasından
        val extra = pageIds.filter { !items.containsKey(it) }.take(16)
        val metas = extra.amap { id -> id to (try { metaOf(id) } catch (e: Exception) { null }) }
        for ((id, m) in metas) {
            if (m == null || m.title.isBlank()) continue
            items[id] = OkItem(id, m.title, m.poster)
        }

        val sorted = items.values.sortedByDescending { turkishTag(it.title) }.take(40)
        return withPosters(sorted, metaTitle = true).map { card(it) }
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

                    val posterRaw = listOf(
                        mv.optString("poster"),
                        mv.optString("bigThumbnail"),
                        mv.optString("thumbnail"),
                        md.optString("poster")
                    ).firstOrNull { it.isNotBlank() }

                    return OkMeta(
                        id,
                        mv.optString("title").trim(),
                        fixPoster(posterRaw),
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
        val m = metaOf(id) ?: return null
        val link = "$mainUrl/video/$id"
        val title = m.title.ifBlank { "Video $id" }
        val seed = secilmis.firstOrNull { it.id == id }
        val shownTitle = seed?.title ?: title
        val poster = bestPoster(id, shownTitle, listOf(m.poster))
        return newMovieLoadResponse(shownTitle, link, TvType.Movie, link) {
            this.posterUrl = poster
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
        // Bağlantılar süreli olduğu için burada her zaman taze alınır
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
