package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DiziCehennemi : MainAPI() {
    override var mainUrl = "https://dizicehennemi.click"
    override var name = "DiziCehennemi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    // TMDB yalnızca sitede bulunan içerikler için özet, afiş, bölüm görseli ve oyuncu bilgisi verir
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val pilavHost = "https://play2.pilavyerplay.top"

    private val cf = CloudflareKiller()
    @Volatile private var needCf = false

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/diziler?sirala=yeni" to "Yeni Eklenen Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes" to "Animeler",
        "$mainUrl/trend" to "Trend",
        "$mainUrl/platform/netflix" to "Netflix",
        "$mainUrl/platform/disney" to "Disney+",
        "$mainUrl/platform/hbo-max" to "HBO Max",
        "$mainUrl/platform/amazon-prime" to "Amazon Prime",
        "$mainUrl/platform/apple-tv" to "Apple TV+",
        "$mainUrl/platform/exxen" to "Exxen",
        "$mainUrl/platform/blutv" to "BluTV",
        "$mainUrl/platform/tabii" to "tabii",
        "$mainUrl/tur/aksiyon-macera" to "Aksiyon & Macera",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu"
    )

    // ---------------- Yardımcılar ----------------

    private fun siteHdr(ref: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to ref
    )

    // Site Cloudflare kontrolü isterse sonraki istekler CloudflareKiller ile atılır
    private suspend fun get(url: String, ref: String = "$mainUrl/", t: Long = 15L) =
        try {
            if (needCf) throw Exception("cf")
            val r = app.get(url, headers = siteHdr(ref), timeout = t)
            if (r.code == 403 || r.code == 503 || r.text.contains("<title>Just a moment", true)) {
                needCf = true
                throw Exception("cf")
            }
            r
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            app.get(url, headers = siteHdr(ref), interceptor = cf, timeout = 20L)
        }

    private fun abs(u0: String): String {
        val u = Parser.unescapeEntities(u0, false).replace("\\/", "/").trim()
        return when {
            u.startsWith("http", true) ->
                u.replace(Regex("""^https?://(?:www\.)?dizicehennemi\.[a-z]+""", RegexOption.IGNORE_CASE), mainUrl)
            u.startsWith("//") -> "https:$u"
            else -> mainUrl + (if (u.startsWith("/")) "" else "/") + u
        }
    }

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1) ?: mainUrl

    private fun clean(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").replace("&#38;", "&").replace("&#x2F;", "/")

    private fun norm(s: String): String =
        s.replace('İ', 'i').replace('I', 'i').lowercase()
            .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o')
            .replace('ş', 's').replace('ü', 'u').replace('â', 'a').replace('î', 'i').replace('û', 'u')
            .replace(Regex("""[^a-z0-9]"""), "")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.str(k: String): String =
        optString(k).let { if (it == "null") "" else it.trim() }

    // ---------------- Kartlar, ana sayfa, arama ----------------

    private val reItem = Regex("""^(?:https?://[^/]*)?/(film|dizi|anime)/([^/?#]+)/?(?:[?#].*)?$""")

    // "Watch The Marvels Film" -> "The Marvels"
    private fun titleFromAlt(alt: String): String =
        alt.replace('\u00A0', ' ').trim()
            .removePrefix("Watch ")
            .replace(Regex("""(?i)\s+(Film|Dizi|Anime)(\s+izle)?\s*$"""), "")
            .replace(Regex("""(?i)\s+izle\s*$"""), "")
            .trim()

    private fun posterOf(img: Element): String? {
        val ds = img.attr("data-src").trim().ifBlank { img.attr("data-original").trim() }
        val s = img.attr("src").trim()
        val u = when {
            ds.isNotBlank() -> ds
            s.isNotBlank() && !s.startsWith("data:") -> s
            else -> return null
        }
        return abs(u)
    }

    private fun cards(root: Element?, anime: Boolean = false): List<SearchResponse> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in root.select("a[href]")) {
            val href0 = a.attr("href").trim()
            val m = reItem.find(href0) ?: continue
            val kind = m.groupValues[1]
            val key = kind + "/" + m.groupValues[2]
            if (out.containsKey(key)) continue
            var img: Element? = a.selectFirst("img")
            var p: Element? = a.parent()
            var up = 0
            while (img == null && p != null && up < 3) {
                img = p.selectFirst("img")
                p = p.parent()
                up++
            }
            val im: Element = img ?: continue
            val alt = im.attr("alt")
            val title = listOf(titleFromAlt(alt), titleFromAlt(a.attr("title")), titleFromAlt(a.text()))
                .firstOrNull { it.isNotBlank() } ?: continue
            val poster = posterOf(im)
            val href = abs(href0)
            out[key] = if (kind == "film") {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                val isAnime = kind == "anime" || anime || alt.trim().endsWith("Anime", true)
                newTvSeriesSearchResponse(title, href, if (isAnime) TvType.Anime else TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
        return out.values.toList()
    }

    // Sayfalama çubuğundaki "2" bağlantısından sayfa adresi kalıbı öğrenilir
    private val pageTpl = ConcurrentHashMap<String, String>()

    private fun learnTemplate(base: String, doc: Document) {
        val a2 = doc.select("a[href]").firstOrNull {
            it.text().trim() == "2" && Regex("""[?&=/]2/?(?:[?&].*)?$""").containsMatchIn(it.attr("href"))
        } ?: return
        val href = abs(a2.attr("href"))
        val tpl = href.replace(Regex("""(?<=[?&=/])2(?=/?(?:[?&].*)?$)"""), "{n}")
        if (tpl.contains("{n}")) pageTpl[base] = tpl
    }

    private fun pageUrl(base: String, page: Int): String =
        pageTpl[base]?.replace("{n}", page.toString())
            ?: if (base.contains("?")) "$base&page=$page" else "$base?page=$page"

    // Satırın adresi değişmişse (boş sayfa) bulunan gerçek adres burada saklanır
    private val altBase = ConcurrentHashMap<String, String>()

    // Boş gelen satır için: önce ana sayfadaki menü bağlantılarında satır adıyla eşleşen adres,
    // sonra yaygın yazım ayrımları (apple-tv-plus, appletv...) denenir. Kart çıkaran ilk adres seçilir.
    private suspend fun discoverBase(name: String, base: String): String? {
        altBase[base]?.let { return it }
        val want = norm(name)
        if (want.length < 4) return null
        val clean0 = base.substringBefore("?").trimEnd('/')
        val tried = HashSet<String>()
        tried.add(clean0)
        val cands = ArrayList<String>()
        try {
            val home: Document = get("$mainUrl/").document
            for (a in home.select("a[href]")) {
                val href = a.attr("href").trim()
                if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript", true)) continue
                val u = abs(href)
                val txt = norm(a.text())
                val hn = norm(u.substringAfter(mainUrl))
                if (txt == want || hn.endsWith(want) || (want.length >= 5 && txt.contains(want))) cands.add(u)
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
        }
        val parent = clean0.substringBeforeLast('/')
        val seg = clean0.substringAfterLast('/')
        cands.add("$parent/$seg-plus")
        cands.add("$parent/" + seg.replace("-", ""))
        cands.add("$parent/" + seg.replace("-", "_"))
        for (u in cands.distinct()) {
            if (tried.size > 8) break
            val key = u.substringBefore("?").trimEnd('/')
            if (!tried.add(key)) continue
            try {
                val d: Document = get(u).document
                if (cards(d, u.contains("/anime")).isNotEmpty()) {
                    altBase[base] = u
                    return u
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        var useBase = altBase[base] ?: base
        val url = if (page <= 1) useBase else pageUrl(useBase, page)
        var doc: Document = get(url).document
        var list: List<SearchResponse> = cards(doc, useBase.contains("/anime"))
        if (list.isEmpty() && page <= 1) {
            val alt = discoverBase(request.name, base)
            if (alt != null) {
                useBase = alt
                doc = get(alt).document
                list = cards(doc, alt.contains("/anime"))
            }
        }
        if (page <= 1) learnTemplate(useBase, doc)
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = list.isNotEmpty() && pageTpl.containsKey(useBase)
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.length < 2) return newSearchResponseList(emptyList(), false)
        val base = "$mainUrl/ara?q=${enc(q)}"
        val url = if (page <= 1) base else pageUrl(base, page)
        val doc = get(url).document
        if (page <= 1) learnTemplate(base, doc)
        // Önce diziler, sonra filmler
        val list = cards(doc).sortedBy { if (it.type == TvType.Movie) 1 else 0 }
        return newSearchResponseList(list, list.isNotEmpty() && pageTpl.containsKey(base))
    }

    // ---------------- TMDB ----------------

    private suspend fun tmdb(path: String, q: String = ""): JSONObject? = try {
        JSONObject(app.get("$tmdbApi$path?api_key=$tmdbKey$q", timeout = 15L).text)
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        null
    }

    private fun tmdbImage(p: String?, size: String): String? =
        if (p.isNullOrBlank() || p == "null") null else "$tmdbImg/$size$p"

    // Sitedeki başlıkla TMDB'de eşleşen kaydı bulur
    private suspend fun tmdbFind(isTv: Boolean, titles: List<String>): JSONObject? {
        val type = if (isTv) "tv" else "movie"
        for (t in titles) {
            if (t.isBlank()) continue
            val j = tmdb("/search/$type", "&language=tr-TR&include_adult=false&query=${enc(t)}") ?: continue
            val arr = j.optJSONArray("results") ?: continue
            var first: JSONObject? = null
            for (o in arr.objs()) {
                if (first == null) first = o
                val names = listOf(o.str("name"), o.str("original_name"), o.str("title"), o.str("original_title"))
                if (names.any { it.isNotBlank() && norm(it) == norm(t) }) return o
            }
            if (first != null) return first
        }
        return null
    }

    private fun dateMs(s: String?): Long? = try {
        if (s.isNullOrBlank()) null else SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    private fun castOf(d: JSONObject?): List<ActorData> =
        d?.optJSONObject("credits")?.optJSONArray("cast")?.objs().orEmpty().take(20).mapNotNull {
            val n = it.str("name")
            if (n.isBlank()) null
            else ActorData(Actor(n, tmdbImage(it.str("profile_path"), "w185")), roleString = it.str("character").ifBlank { null })
        }

    private fun trailerOf(d: JSONObject?): String? {
        val list = d?.optJSONObject("videos")?.optJSONArray("results")?.objs().orEmpty()
            .filter { it.str("site").equals("YouTube", true) }
        val v = list.firstOrNull { it.str("type").equals("Trailer", true) }
            ?: list.firstOrNull { it.str("type").equals("Teaser", true) }
            ?: return null
        return "https://www.youtube.com/watch?v=" + v.str("key")
    }

    // ---------------- Sayfa (dizi / film) ----------------

    private val reEp = Regex("""/(?:dizi|anime)/([^/?#]+)/sezon-(\d+)/bolum-(\d+)""")

    override suspend fun load(url: String): LoadResponse? {
        val m = Regex("""/(film|dizi|anime)/([^/?#]+)""").find(url) ?: return null
        val kind = m.groupValues[1]
        val slug = m.groupValues[2]
        val isTv = kind != "film"
        val pageUrl = "$mainUrl/$kind/$slug"

        val doc = get(pageUrl).document
        val siteTitle = listOf(
            doc.selectFirst("h1")?.text()?.trim(),
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" | ")?.replace(Regex("""(?i)\s+(full\s+)?izle.*$"""), "")?.trim()
        ).firstOrNull { !it.isNullOrBlank() } ?: slug.replace('-', ' ')

        // Tipler açıkça yazıldı: jsoup'un jspecify @Nullable ek açıklaması derleyicide çıkarım hatası veriyordu
        val posterEl: Element? = doc.selectFirst("img[src*=/storage/tmdb/posters/], img[data-src*=/storage/tmdb/posters/]")
        val sitePoster: String? = (if (posterEl != null) posterOf(posterEl) else null)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val sitePlot = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }

        val titles = listOf(siteTitle, slug.replace('-', ' ')).distinct()
        val found = tmdbFind(isTv, titles)
        val tid = found?.optInt("id") ?: 0
        val type = if (isTv) "tv" else "movie"
        val det = if (tid > 0) tmdb(
            "/$type/$tid",
            "&language=tr-TR&append_to_response=credits,videos&include_video_language=tr,en,null"
        ) else null

        var plot = det?.str("overview").orEmpty()
        if (plot.isBlank() && tid > 0) plot = tmdb("/$type/$tid", "&language=en-US")?.str("overview").orEmpty()
        if (plot.isBlank()) plot = sitePlot.orEmpty()

        val poster = tmdbImage(det?.str("poster_path"), "w500") ?: sitePoster
        val bg = tmdbImage(det?.str("backdrop_path"), "w1280")
        val tags = det?.optJSONArray("genres")?.objs().orEmpty().map { it.str("name") }.filter { it.isNotBlank() }
        val actors = castOf(det)
        val trailer = trailerOf(det)
        val year = (det?.str(if (isTv) "first_air_date" else "release_date").orEmpty()).take(4).toIntOrNull()

        if (!isTv) {
            val runtime = det?.optInt("runtime", 0)?.takeIf { it > 0 }
            return newMovieLoadResponse(siteTitle, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = plot.ifBlank { null }
                if (year != null) this.year = year
                if (tags.isNotEmpty()) this.tags = tags
                if (runtime != null) this.duration = runtime
                if (actors.isNotEmpty()) this.actors = actors
                if (!trailer.isNullOrBlank()) addTrailer(trailer)
            }
        }

        // Sitedeki bölüm bağlantıları (asıl liste)
        val siteEps = LinkedHashMap<Pair<Int, Int>, String>()
        for (a in doc.select("a[href]")) {
            val mm = reEp.find(a.attr("href")) ?: continue
            if (mm.groupValues[1] != slug) continue
            siteEps[(mm.groupValues[2].toInt()) to (mm.groupValues[3].toInt())] = abs(a.attr("href"))
        }
        val siteSeasons = siteEps.keys.map { it.first }.toSet()

        // TMDB bölüm bilgileri: ad, özet, görsel, süre, tarih
        val tmEps = HashMap<Pair<Int, Int>, JSONObject>()
        if (tid > 0) {
            val seasonNums = det?.optJSONArray("seasons")?.objs().orEmpty()
                .map { it.optInt("season_number") }
                .filter { it >= 1 || siteSeasons.contains(it) }
            val seasonJsons = seasonNums.amap { n -> n to tmdb("/tv/$tid/season/$n", "&language=tr-TR") }
            for ((n, sj) in seasonJsons) {
                for (e in sj?.optJSONArray("episodes")?.objs().orEmpty()) {
                    tmEps[n to e.optInt("episode_number")] = e
                }
            }
        }

        val cmp = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
        // Site sayfası çoğu zaman yalnızca ilk sezonun bölümlerini listeler; diğer sezonlar TMDB'den tamamlanır.
        // Sitede olmayan bölümün adresi sezon-N/bolum-M kalıbıyla kurulur. Yayın tarihi gelmemiş (ya da tarihi
        // olmayan) TMDB bölümleri eklenmez.
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val keys: List<Pair<Int, Int>> = run {
            val all = LinkedHashSet<Pair<Int, Int>>()
            all.addAll(siteEps.keys)
            for ((k, info) in tmEps) {
                if (siteEps.containsKey(k)) continue
                val air = info.str("air_date")
                if (air.isBlank() || air > today) continue
                all.add(k)
            }
            if (all.isEmpty()) listOf(1 to 1) else all.sortedWith(cmp)
        }
        val defRun = det?.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }

        val episodes = keys.map { (s, e) ->
            val info = tmEps[s to e]
            val epUrl = siteEps[s to e] ?: "$mainUrl/$kind/$slug/sezon-$s/bolum-$e"
            newEpisode(epUrl) {
                this.name = info?.str("name")?.takeIf { it.isNotBlank() } ?: "$e. Bölüm"
                this.season = s
                this.episode = e
                this.posterUrl = tmdbImage(info?.str("still_path"), "w300")
                this.description = info?.str("overview")?.takeIf { it.isNotBlank() }
                this.runTime = info?.optInt("runtime", 0)?.takeIf { it > 0 } ?: defRun
                this.date = dateMs(info?.str("air_date"))
            }
        }

        val isAnime = kind == "anime" ||
            (det?.optJSONArray("genres")?.objs().orEmpty().any { it.optInt("id") == 16 } &&
                det?.str("original_language") == "ja")
        return newTvSeriesLoadResponse(siteTitle, pageUrl, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            this.seasonNames = keys.map { it.first }.distinct().sorted().map { SeasonData(it, "$it. Sezon") }
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot.ifBlank { null }
            if (year != null) this.year = year
            if (tags.isNotEmpty()) this.tags = tags
            if (actors.isNotEmpty()) this.actors = actors
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
    }

    // ---------------- Oynatma ----------------

    private val asset = Regex("""\.(jpe?g|png|webp|gif|css|js|svg|ico|woff2?)(\?|$)""", RegexOption.IGNORE_CASE)

    private fun innerFrames(html: String): List<String> =
        Regex("""<iframe[^>]*?\s(?:data-src|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { abs(it.groupValues[1]) }.filter { !asset.containsMatchIn(it) }.toList().take(3)

    private fun playerPages(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("""<iframe[^>]*?\s(?:data-src|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(abs(it.groupValues[1])) }
        Regex("""\sdata-(?:url|embed|video|player|src)=["']((?:https?:)?//[^"']+|/[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(abs(it.groupValues[1])) }
        Regex("""https?://[^"'\s<>\\]*(?:player|embed|pilavyer)[^"'\s<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(clean(html)).forEach { out.add(it.value) }
        return out.filter { !asset.containsMatchIn(it) }.take(6)
    }

    private fun pctDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }

    // Sayfadaki data-pv belirteçleri ve s.php?s= bağlantıları
    private fun tokensIn(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("""\sdata-pv\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach {
            val v = Parser.unescapeEntities(it.groupValues[1], false).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        Regex("""s\.php\?s=([^"'&\s<>\\]+)""", RegexOption.IGNORE_CASE).findAll(clean(html)).forEach {
            val w = pctDecode(it.groupValues[1])
            if (w.isNotEmpty()) out.add(w)
        }
        return out.take(6)
    }

    private fun playerHosts(html: String): List<String> {
        val hs = LinkedHashSet<String>()
        hs.add(pilavHost)
        Regex("""https?://[a-z0-9.\-]*pilavyerplay[a-z0-9.\-]*""", RegexOption.IGNORE_CASE)
            .findAll(clean(html)).forEach { hs.add(it.value.replace(Regex("^http:", RegexOption.IGNORE_CASE), "https:")) }
        return hs.take(3)
    }

    // Tek bir (sunucu, belirteç) çifti için s.php'yi dener
    private suspend fun fetchToken(host: String, pv: String, pageUrl: String, blobs: MutableList<String>): Boolean {
        val urls = ArrayList<String>()
        urls.add("$host/assets/js/s.php?s=${URLEncoder.encode(pv, "UTF-8")}")
        if (Regex("%[0-9a-f]{2}", RegexOption.IGNORE_CASE).containsMatchIn(pv)) urls.add("$host/assets/js/s.php?s=$pv")
        val hdrs = listOf(
            siteHdr("$mainUrl/"),
            siteHdr(pageUrl) + mapOf("Origin" to mainUrl)
        )
        for (u in urls) for (h in hdrs) {
            val r = try {
                app.get(u, headers = h, timeout = 12L)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                continue
            }
            val raw = r.text
            if (r.code != 200 || raw.isBlank() ||
                Regex("""<title>[^<]*bulunamad""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
            ) continue
            blobs.add(clean(raw))
            for (iu in innerFrames(raw)) {
                try {
                    val r2 = app.get(iu, headers = mapOf("User-Agent" to ua, "Referer" to u, "Origin" to host), timeout = 12L)
                    blobs.add(clean(r2.text))
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                }
            }
            return true
        }
        return false
    }

    private fun streamsIn(blobs: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        val ok = Regex("""stream\.php|\.m3u8|\.mp4""", RegexOption.IGNORE_CASE)
        val sub = Regex("""sub\.php""", RegexOption.IGNORE_CASE)
        for (b in blobs) {
            Regex("""https?://[^"'\s<>\\)]+""").findAll(b).forEach {
                val u = it.value.trimEnd(',', ';')
                if (ok.containsMatchIn(u) && !sub.containsMatchIn(u)) out.add(u)
            }
        }
        return out.toList()
    }

    private fun subsIn(blobs: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (b in blobs) {
            Regex("""https?://[^"'\s<>\\)]+(?:sub\.php|\.vtt|\.srt)[^"'\s<>\\)]*""", RegexOption.IGNORE_CASE)
                .findAll(b).forEach { out.add(it.value.trimEnd(',', ';')) }
        }
        return out.toList()
    }

    // Bağlantı gerçekten oynatma listesi mi? Çalışan başlık takımını döndürür
    private suspend fun verify(u: String, sets: List<Map<String, String>>): Map<String, String>? {
        if (u.contains(".mp4", true)) return sets[0]
        for (h in sets) {
            val t = try {
                app.get(u, headers = h, timeout = 8L).text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ""
            }
            if (t.trimStart().startsWith("#EXTM3U")) return h
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        val html = get(pageUrl).text
        val blobs = java.util.Collections.synchronizedList(ArrayList<String>())
        blobs.add(clean(html))

        // 1) data-pv belirteçleri -> oynatıcı sunucusundaki s.php
        val tokens = tokensIn(html)
        var stateHost = ""
        if (tokens.isNotEmpty()) {
            for (h in playerHosts(html)) {
                val oks = tokens.amap { fetchToken(h, it, pageUrl, blobs) }
                if (oks.any { it }) {
                    stateHost = h
                    break
                }
            }
        }

        // 2) sayfadaki oynatıcı bağlantıları ve onların iç çerçeveleri
        val rs = playerPages(html).amap { u ->
            try {
                app.get(u, headers = siteHdr(pageUrl), timeout = 12L).text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ""
            }
        }
        val inner = LinkedHashSet<String>()
        for (t in rs) {
            if (t.isBlank()) continue
            blobs.add(clean(t))
            inner.addAll(innerFrames(t))
        }
        inner.take(4).toList().amap { u ->
            try {
                blobs.add(clean(app.get(u, headers = siteHdr(pageUrl), timeout = 12L).text))
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }

        val snap = synchronized(blobs) { ArrayList(blobs) }
        val streams = streamsIn(snap).take(6)
        val subUrls = subsIn(snap).take(2)
        if (streams.isEmpty()) return false

        val pb = stateHost.ifBlank { pilavHost }
        val sets = listOf(
            mapOf("User-Agent" to ua, "Referer" to "$pb/", "Origin" to pb),
            mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl),
            mapOf("User-Agent" to ua, "Referer" to pageUrl, "Origin" to mainUrl),
            mapOf("User-Agent" to ua)
        )

        val checked = streams.amap { it to verify(it, sets) }
        var good = checked.filter { it.second != null }
        if (good.isEmpty()) good = checked.take(1)

        good.forEachIndexed { i, pair ->
            val u = pair.first
            val h = pair.second ?: sets[0]
            val q = Regex("""stream_v(\d{3,4})""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)?.toIntOrNull()
            val isMp4 = u.contains(".mp4", true)
            val label = name + (if (q != null) " - ${q}p" else if (i > 0) " #${i + 1}" else "")
            callback(
                newExtractorLink(name, label, u, if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                    this.referer = h["Referer"] ?: "$mainUrl/"
                    this.quality = q ?: Qualities.Unknown.value
                    this.headers = h
                }
            )
        }

        subUrls.forEachIndexed { i, su ->
            subtitleCallback(SubtitleFile(if (i == 0) "Türkçe" else "Altyazı ${i + 1}", su))
        }
        return true
    }
}
