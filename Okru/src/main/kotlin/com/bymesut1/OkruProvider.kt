package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class OkruProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "Okru"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
    private val uaDesktop =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ok.ru'nun web istemcisinin kullandığı sabit istek kimliği (ByAyzen OKRU ile aynı)
    private val gwt = "9579ea2eT1774883610506"

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

    // ---- ok.ru arama JSON modelleri (video-search-result data-props ve web-api cevabı) ----
    data class SearchThumbnail(val small: String? = null, val big: String? = null)
    data class SearchMovie(val id: String? = null, val title: String? = null, val thumbnail: SearchThumbnail? = null)
    data class SearchItem(val name: String? = null, val imageUrl: String? = null, val movie: SearchMovie? = null)
    data class SearchVideos(val list: List<SearchItem>? = null, val hasMore: Boolean? = null)
    data class SearchData(val videos: SearchVideos? = null)
    data class ApiSearchResponse(val result: SearchData? = null)

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
        "turkce" to "Türkçe Dublaj Filmler",
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

    // Ağ isteği yok: resim yoksa anında başlıklı düz afiş verilir (kontrol/bekleme yok)
    private fun placeholder(title: String): String {
        val t = title.replace(Regex("""\s+"""), " ").trim().take(40).ifBlank { "Video" }
        return "https://placehold.co/500x750/1c1c1e/ffffff.png?text=" + enc(t)
    }

    private fun card(x: OkItem): SearchResponse =
        newMovieSearchResponse(x.title, "$mainUrl/video/${x.id}", TvType.Movie) {
            this.posterUrl = x.poster ?: placeholder(x.title)
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

    // ---------------- Video bilgisi önbelleği ----------------

    private val metaCache = ConcurrentHashMap<String, OkMeta>()

    private suspend fun metaOf(id: String): OkMeta? {
        metaCache[id]?.let { return it }
        val m = fetchMeta(id) ?: return null
        metaCache[id] = m
        return m
    }

    // ---------------- Hızlı arama (ByAyzen OKRU yöntemi) ----------------

    private fun SearchData.toItems(): List<OkItem> = videos?.list?.mapNotNull { item ->
        val id = item.movie?.id ?: return@mapNotNull null
        val title = dec(item.movie.title ?: item.name ?: return@mapNotNull null).trim()
        if (title.isBlank()) return@mapNotNull null
        val poster = fixPoster(item.movie.thumbnail?.big ?: item.movie.thumbnail?.small ?: item.imageUrl)
        OkItem(id, title, poster)
    } ?: emptyList()

    // parseJson (inline, JVM 11) yerine org.json ile elle okuma: JVM 1.8 hedefiyle uyumlu
    private fun parseSearchData(o: JSONObject?): SearchData? {
        val v = o?.optJSONObject("videos") ?: return null
        val arr = v.optJSONArray("list")
        val list = ArrayList<SearchItem>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val mv = row.optJSONObject("movie")
                val th = mv?.optJSONObject("thumbnail")
                val movie = if (mv == null) null else SearchMovie(
                    id = mv.optString("id").ifBlank { null },
                    title = mv.optString("title").ifBlank { null },
                    thumbnail = if (th == null) null else SearchThumbnail(
                        small = th.optString("small").ifBlank { null },
                        big = th.optString("big").ifBlank { null }
                    )
                )
                list.add(
                    SearchItem(
                        name = row.optString("name").ifBlank { null },
                        imageUrl = row.optString("imageUrl").ifBlank { null },
                        movie = movie
                    )
                )
            }
        }
        return SearchData(SearchVideos(list, if (v.has("hasMore")) v.optBoolean("hasMore") else null))
    }

    // Tek istek, tek JSON: sayfa 1 = HTML içindeki data-props, sonraki sayfalar = web-api
    private suspend fun searchData(q: String, page: Int): SearchData? {
        return try {
            if (page == 1) {
                val doc = app.post(
                    "$mainUrl/video/search?st.cmd=video&st.psft=showcase&st.m=SEARCH&st.ft=search&st.fuvh=on&st.furl=%2Fvideo%2Fshowcase&cmd=VideoContentBlock",
                    data = mapOf("st.v.sq" to q, "gwt.requested" to gwt),
                    headers = mapOf("User-Agent" to uaDesktop, "Accept-Language" to "tr-TR,tr;q=0.9"),
                    timeout = 10L
                ).document
                val js = doc.selectFirst("video-search-result")?.attr("data-props")
                if (!js.isNullOrEmpty()) parseSearchData(JSONObject(js)) else null
            } else {
                val offset = (page - 1) * 30
                val body = """{"id": $page,"parameters": {"displayMode": "Movie","videosOffset": $offset,"channelsOffset": 0,"searchQuery": ${JSONObject.quote(q)},"currentStateId": "video","durationType": "ANY","hd": false}}"""
                val txt = app.post(
                    "$mainUrl/web-api/v2/video/fetchSearchResult",
                    headers = mapOf(
                        "User-Agent" to uaDesktop,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "ok-screen" to "anonymVideo",
                        "x-client-flags" to "ms:0;dcss:0;mpv2:1;dz:0"
                    ),
                    requestBody = body.toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull()),
                    timeout = 10L
                ).text
                if (txt.isNotEmpty()) parseSearchData(JSONObject(txt).optJSONObject("result")) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Yedek: yeni yöntem boş dönerse eski GET arama sayfasından oku (sadece sayfa 1)
    private suspend fun fallbackSearch(q: String): List<OkItem> {
        val html = try {
            app.get(
                "$mainUrl/video/search?st.cmd=anonymVideo&st.m=SEARCH&st.v.sq=${enc(q)}",
                headers = hdr(true), timeout = 8L
            ).text
        } catch (e: Exception) {
            ""
        }
        return if (html.isBlank()) emptyList() else parseProps(html)
    }

    // ---------------- Yandex yedek arama ----------------

    // true: Yandex HER aramada ok.ru ile aynı anda çalışır, bulduklarını LİSTENİN BAŞINA koyar.
    // false: sadece ok.ru'da yeterli eşleşme yoksa yedek olarak çalışır.
    private val yandexHerZaman = true
    private val yandexBekleSn = 5L          // Yandex'i en fazla kaç saniye bekle
    private val yandexEsik = 3              // (yedek modunda) eşleşen sonuç bundan azsa Yandex'e bakılır
    private val yandexMaxVideo = 8      // Yandex'ten en fazla kaç video numarası değerlendirilsin

    private val reOkId =
        Regex("ok\\.ru(?:/|%2[Ff]|\\\\/)video(?:embed)?(?:/|%2[Ff]|\\\\/)(\\d{8,})")

    // Aramada anlam taşımayan ekler: "rambo 1 tr film izle" -> "rambo 1"
    private val gurultu = setOf(
        "tr", "film", "filmi", "filmini", "izle", "full", "hd", "fullhd", "turkce", "dublaj",
        "altyazili", "bluray", "1080p", "720p", "480p", "sinema", "yerli", "yabanci"
    )

    // Sonuçlardan kaçının başlığı aranan isimle gerçekten eşleşiyor?
    // Gürültü kelimeler sayılmaz; kalan kelimelerin HEPSİ başlıkta geçmeli.
    private fun eslesenSayisi(q: String, list: Collection<OkItem>): Int {
        val all = fold(q).split(" ").filter { it.isNotBlank() }
        val words = all.filter { !gurultu.contains(it) }.ifEmpty { all }
        if (words.isEmpty()) return list.size
        return list.count { x ->
            val t = " " + fold(x.title) + " "
            words.all { w ->
                // sayılar tam kelime olarak aranır ("1" -> "11" ile karışmasın)
                if (w.all { it.isDigit() }) t.contains(" $w ") else t.contains(w)
            }
        }
    }

    // "Rambo 1" -> "Rambo 1 türkçe dublaj okru" (Yandex "okru"yu ok.ru olarak anlıyor)
    private fun yandexQuery(q: String): String {
        val base = q.trim()
        val tag = if (turkishTag(base)) "" else " türkçe dublaj"
        return "$base$tag okru"
    }

    // Yandex sonuç sayfasından ok.ru video numaralarını alır, her birini ok.ru'dan doğrular
    // (başlık + afiş + oynatılabilir mi). Captcha gelirse boş döner.
    private suspend fun yandexItems(q: String, have: Set<String>): List<OkItem> {
        val url = "https://yandex.com.tr/search/?text=" + enc(yandexQuery(q))
        val html = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to uaDesktop,
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8,ru;q=0.5",
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
                ),
                timeout = 5L
            ).text
        } catch (e: Exception) {
            return emptyList()
        }
        if (html.isBlank() || html.contains("showcaptcha", true) || html.contains("smartcaptcha", true)) {
            return emptyList()
        }
        val ids = LinkedHashSet<String>()
        reOkId.findAll(html).forEach { ids.add(it.groupValues[1]) }
        val todo = ids.filter { !have.contains(it) }.take(yandexMaxVideo)
        if (todo.isEmpty()) return emptyList()

        // ok.ru'dan paralel doğrulama: oynatılamayan/silinmiş videolar elenir
        return todo.amap { id ->
            val m = try { metaOf(id) } catch (e: Exception) { null }
            if (m == null || m.title.isBlank()) null else OkItem(id, m.title, m.poster)
        }.filterNotNull()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)

        return coroutineScope {
            // Yandex, ok.ru aramasıyla AYNI ANDA başlar (sıralı bekleme yok)
            val yandexJob = if (page == 1 && yandexHerZaman) {
                async {
                    withTimeoutOrNull(yandexBekleSn * 1000L) {
                        try { yandexItems(q, emptySet()) } catch (e: Exception) { emptyList() }
                    } ?: emptyList()
                }
            } else null

            val own = LinkedHashMap<String, OkItem>()   // elle eklenen eşleşmeler
            val items = LinkedHashMap<String, OkItem>() // ok.ru sonuçları

            if (page == 1) {
                val words = fold(q).split(" ").filter { it.isNotBlank() }
                if (words.isNotEmpty()) {
                    for (s in secilmis) {
                        val t = fold(s.title)
                        if (words.all { t.contains(it) }) own[s.id] = s
                    }
                }
            }

            val data = searchData(q, page)
            var found = data?.toItems().orEmpty()
            if (found.isEmpty() && page == 1) found = fallbackSearch(q)
            found.forEach { if (!items.containsKey(it.id) && !own.containsKey(it.id)) items[it.id] = it }

            var yandex: List<OkItem> = yandexJob?.await().orEmpty()

            // Yedek modu: ok.ru'da isimle eşleşme azsa Yandex'e sonradan bak
            if (page == 1 && !yandexHerZaman && eslesenSayisi(q, own.values + items.values) < yandexEsik) {
                yandex = withTimeoutOrNull(yandexBekleSn * 1000L) {
                    try { yandexItems(q, items.keys) } catch (e: Exception) { emptyList() }
                } ?: emptyList()
            }

            // Sıra: elle eklenenler -> Yandex bulduklarını -> ok.ru (Türkçe etiketliler önde)
            val ordered = LinkedHashMap<String, OkItem>()
            own.values.forEach { ordered[it.id] = it }
            yandex.forEach { if (!ordered.containsKey(it.id)) ordered[it.id] = it }
            items.values.sortedByDescending { turkishTag(it.title) }
                .forEach { if (!ordered.containsKey(it.id)) ordered[it.id] = it }

            newSearchResponseList(ordered.values.map { card(it) }, data?.videos?.hasMore == true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1).items

    // ---------------- Ana sayfa ----------------

    private fun docCards(doc: Document): List<OkItem> = doc.select("div.ugrid_i").mapNotNull { item ->
        val c = item.selectFirst("div.video-card") ?: return@mapNotNull null
        val title = c.selectFirst("a.video-card_n")?.text()?.trim().orEmpty()
        val href = c.selectFirst("a.video-card_lk")?.attr("href").orEmpty()
        val id = Regex("/video/(\\d{8,})").find(href)?.groupValues?.get(1)
            ?: c.attr("data-id").takeIf { it.matches(Regex("\\d{8,}")) }
            ?: return@mapNotNull null
        if (title.isBlank()) return@mapNotNull null
        val img = c.selectFirst("img.video-card_img")
        val poster = fixPoster(img?.attr("src")?.ifBlank { img.attr("data-src") })
        OkItem(id, title, poster)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var hasNext = false
        val items: List<OkItem> = when (request.data) {
            "secilmis" -> {
                if (page > 1) emptyList()
                else secilmis.amap { x ->
                    // 7 kayıt paralel çekilir ve önbelleğe alınır
                    val m = try { metaOf(x.id) } catch (e: Exception) { null }
                    OkItem(x.id, x.title, m?.poster)
                }
            }
            "turkce" -> {
                val d = searchData("türkçe dublaj film", page)
                hasNext = d?.videos?.hasMore == true
                d?.toItems().orEmpty().sortedByDescending { turkishTag(it.title) }
            }
            "hesap" -> if (page > 1) emptyList() else hesaplar.flatMap { profileItems(it) }
            else -> {
                val r = vitrinPage(page)
                hasNext = r.isNotEmpty()
                r
            }
        }
        val seen = HashSet<String>()
        val list = items.filter { seen.add(it.id) }.map { card(it) }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = hasNext && list.isNotEmpty()
        )
    }

    private suspend fun vitrinPage(page: Int): List<OkItem> {
        return try {
            if (page == 1) {
                val doc = app.get("$mainUrl/video/showcase", headers = hdr(true), timeout = 10L).document
                val a = docCards(doc)
                if (a.isNotEmpty()) return a
                val b = parseProps(doc.html())
                if (b.isNotEmpty()) return b
                searchData("türkçe dublaj film", 1)?.toItems().orEmpty()
            } else {
                val first = app.get("$mainUrl/video/showcase", headers = hdr(true), timeout = 10L).document
                val last = first.selectFirst("[data-module=Loader]")?.attr("data-last-element") ?: "18"
                val doc = app.post(
                    "$mainUrl/video/showcase?st.cmd=anonymVideo&st.m=SHOWCASE&st.ft=showcase&st.furl=%2Fvideo%2Fshowcase&cmd=VideoUniversalContentBlock",
                    data = mapOf(
                        "fetch" to "false",
                        "st.page" to page.toString(),
                        "st.lastelem" to last,
                        "gwt.requested" to gwt
                    ),
                    headers = mapOf("ok-screen" to "anonymVideo", "X-Requested-With" to "XMLHttpRequest"),
                    timeout = 10L
                ).document
                docCards(doc)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun profileItems(url: String): List<OkItem> {
        val urls = listOf(url, url.replace("https://ok.ru", "https://m.ok.ru"))
        for (u in urls) {
            for (desk in listOf(true, false)) {
                val html = try { app.get(u, headers = hdr(desk), timeout = 12L).text } catch (e: Exception) { "" }
                if (html.isBlank()) continue
                val a = parseProfile(html)
                if (a.isNotEmpty()) return a
                val b = parseProps(html)
                if (b.isNotEmpty()) return b
                val ids = LinkedHashSet<String>()
                Regex("/video/(\\d{8,})").findAll(html).forEach { ids.add(it.groupValues[1]) }
                if (ids.isNotEmpty()) {
                    // Başlığı olmayanlar için video sayfasından bilgi alınır (paralel, önbellekli)
                    return ids.take(24).toList().amap { id ->
                        val m = try { metaOf(id) } catch (e: Exception) { null }
                        if (m == null || m.title.isBlank()) null else OkItem(id, m.title, m.poster)
                    }.filterNotNull()
                }
            }
        }
        return emptyList()
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

    // data-props / data-options JSON bloklarındaki film kayıtları (vitrin, yedek arama)
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

    // ---------------- Video bilgisi ----------------

    private val kalite = mapOf(
        "ultra" to 2160, "quad" to 1440, "full" to 1080, "hd" to 720,
        "sd" to 480, "low" to 360, "lowest" to 240, "mobile" to 144
    )

    private suspend fun fetchMeta(id: String): OkMeta? {
        for (path in listOf("/videoembed/", "/video/")) {
            val doc = try {
                app.get(mainUrl + path + id, headers = hdr(path == "/video/"), timeout = 8L).document
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
        val poster = m.poster ?: placeholder(shownTitle)
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
