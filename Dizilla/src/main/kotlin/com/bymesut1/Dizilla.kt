package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    // TMDB (Türkçe bölüm adı, açıklama, bölüm/sezon resmi, süre, tarih). Boş/placeholder ise kapalı.
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"

    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/turkce-dublaj-dizi-izle" to "Türkçe Dublaj Diziler",
        "$mainUrl/yerli-dizi-izle" to "Yerli Diziler",
        "$mainUrl/anime-izle" to "Anime",
        "$mainUrl/kdrama-izle" to "Kdrama",
        "$mainUrl/asya-dizileri-izle" to "Asya Dizileri",
        "$mainUrl/imdb-top-100" to "IMDb Top 100"
    )

    private fun d(m: String) {
        Log.d("DizillaDbg", m)
    }

    // Uzun metinleri logcat'e parça parça yaz
    private fun dLong(tag: String, s: String) {
        var i = 0
        var n = 0
        while (i < s.length && n < 12) {
            d("$tag[$n] " + s.substring(i, minOf(s.length, i + 700)))
            i += 700
            n++
        }
    }

    // ---------------- Yardımcılar ----------------

    private suspend fun fetch(url: String, referer: String? = null): String {
        val h = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9")
        return try {
            val t = app.get(url, headers = h, referer = referer).text
            if (t.contains("Just a moment", true) && t.length < 20_000) {
                app.get(url, headers = h, referer = referer, interceptor = cf).text
            } else t
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("fetch hata ${e.javaClass.simpleName} $url")
            try {
                app.get(url, headers = h, referer = referer, interceptor = cf).text
            } catch (e2: Exception) {
                if (e2 is java.util.concurrent.CancellationException) throw e2
                ""
            }
        }
    }

    private fun abs(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> "$mainUrl/$u"
    }

    private fun cleanImg(u: String?): String? =
        u?.substringBefore("#")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"").replace("&amp;", "&")

    // ---------------- Kartlar (ana sayfa / kategori / arama) ----------------

    private fun cardsOf(doc: Document): List<SearchResponse> {
        val best = LinkedHashMap<String, Triple<String, String?, Int>>()
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href")
            val path = raw.substringBefore("?").substringBefore("#").removePrefix(mainUrl)
            if (!path.startsWith("/dizi/")) continue
            val slug = path.removePrefix("/dizi/").trim('/')
            if (slug.isBlank()) continue
            val title = a.attr("aria-label").substringBefore(",")
                .replace(Regex("""^\d+\.\s+"""), "").replace(Regex("""\.\s*Sayfasını aç$"""), "").trim()
                .ifBlank { a.text().trim() }
            if (title.isBlank()) continue
            val src = cleanImg(a.selectFirst("img")?.attr("src"))
            val score = if (src?.contains("384/576") == true) 2 else if (src != null) 1 else 0
            val old = best[slug]
            if (old == null || score > old.third) best[slug] = Triple(title, src, score)
        }
        return best.map { (slug, v) ->
            newTvSeriesSearchResponse(v.first, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                this.posterUrl = v.second
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Sayfalama adresi doğrulanana kadar yalnızca ilk sayfa alınır
        if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val doc = org.jsoup.Jsoup.parse(fetch(request.data), mainUrl)
        val list = cardsOf(doc)
        d("anasayfa ${request.name} kart=${list.size}")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = org.jsoup.Jsoup.parse(fetch("$mainUrl/ara?q=$q"), mainUrl)
        val list = cardsOf(doc)
        d("arama '$query' sonuç=${list.size}")
        return list
    }

    // ---------------- TMDB ----------------

    private fun tmdbOn(): Boolean = tmdbKey.isNotBlank() && !tmdbKey.startsWith("BURAYA")

    private class Tm(
        val id: Int,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val genres: List<String>
    )

    private class TSeason(val eps: Map<Int, JSONObject>, val poster: String?)

    private fun jStr(o: JSONObject?, k: String): String? {
        if (o == null) return null
        val s = o.optString(k, "")
        return if (s.isBlank() || s == "null") null else s
    }

    private suspend fun tmdbGet(path: String, language: String = "tr-TR"): JSONObject? {
        if (!tmdbOn()) return null
        return try {
            val sep = if (path.contains("?")) "&" else "?"
            val t = app.get(
                "https://api.themoviedb.org/3$path${sep}api_key=$tmdbKey&language=$language",
                headers = mapOf("User-Agent" to ua)
            ).text
            JSONObject(t)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }
    }

    private fun normT(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9ğüşıöç]"), "")

    private suspend fun tmdbFind(title: String, year: Int?): Tm? {
        if (!tmdbOn() || title.isBlank()) return null
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val res = tmdbGet("/search/tv?query=$q&include_adult=false")?.optJSONArray("results") ?: return null
        if (res.length() == 0) return null
        val nt = normT(title)
        var pick: JSONObject? = null
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            val same = normT(o.optString("name", "")) == nt || normT(o.optString("original_name", "")) == nt
            if (!same) continue
            val y = jStr(o, "first_air_date")?.take(4)?.toIntOrNull()
            if (year == null || y == year) {
                pick = o
                break
            }
            if (pick == null) pick = o
        }
        if (pick == null) pick = res.optJSONObject(0)
        val p = pick ?: return null
        val id = p.optInt("id", 0)
        if (id == 0) return null
        val det = tmdbGet("/tv/$id") ?: p
        var overview = jStr(det, "overview")
        if (overview == null) overview = jStr(tmdbGet("/tv/$id", "en-US"), "overview")
        val genres = ArrayList<String>()
        det.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) jStr(g.optJSONObject(i), "name")?.let { genres.add(it) }
        }
        return Tm(
            id,
            overview,
            jStr(det, "poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            jStr(det, "backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            jStr(det, "first_air_date")?.take(4)?.toIntOrNull(),
            genres
        )
    }

    // Bir sezonun tüm bölümleri (Türkçe; boş kalan ad/özet İngilizce ile tamamlanır)
    private suspend fun tmdbSeason(id: Int, season: Int): TSeason? {
        val tr = tmdbGet("/tv/$id/season/$season") ?: return null
        val arr = tr.optJSONArray("episodes") ?: return null
        val map = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.optInt("episode_number", -1)] = o
        }
        val needEn = map.values.any { jStr(it, "overview") == null }
        if (needEn) {
            val en = tmdbGet("/tv/$id/season/$season", "en-US")?.optJSONArray("episodes")
            if (en != null) {
                for (i in 0 until en.length()) {
                    val eo = en.optJSONObject(i) ?: continue
                    val tgt = map[eo.optInt("episode_number", -1)] ?: continue
                    if (jStr(tgt, "overview") == null) jStr(eo, "overview")?.let { tgt.put("overview", it) }
                    if (jStr(tgt, "still_path") == null) jStr(eo, "still_path")?.let { tgt.put("still_path", it) }
                }
            }
        }
        return TSeason(map, jStr(tr, "poster_path")?.let { "https://image.tmdb.org/t/p/w300$it" })
    }

    // ---------------- Dizi sayfası ----------------

    private val epRe = Regex("""/([a-z0-9][a-z0-9-]*)-(\d+)-sezon-(\d+)-bolum(-\d+)?(?![a-z0-9-])""")

    private data class Ep(val base: String, val s: Int, val e: Int, val suffix: String)

    private fun episodesIn(html: String): List<Ep> =
        epRe.findAll(html).map {
            Ep(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4])
        }.toList()

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = abs(url).substringBefore("?")
        val raw = fetch(pageUrl)
        if (raw.isBlank()) return null
        val html = unescape(raw)
        val doc = org.jsoup.Jsoup.parse(raw, mainUrl)

        // Bölüm adresleri dizi adresinden farklı bir kök kullanabilir (örn. /dizi/dreamland-2026-43 -> /dreamland-1-sezon-1-bolum-2)
        var eps = episodesIn(html)
        val base = eps.groupingBy { it.base }.eachCount().maxByOrNull { it.value }?.key
            ?: Regex("""/([a-z0-9][a-z0-9-]*)-\d+-sezon(?![a-z0-9-])""").find(html)?.groupValues?.get(1)
        d("kök=$base ilk sayfada bölüm=${eps.size}")

        if (base != null) {
            val seasons = (
                Regex("""/${Regex.escape(base)}-(\d+)-sezon(?![a-z0-9-])""").findAll(html)
                    .map { it.groupValues[1].toInt() }.toList() + eps.filter { it.base == base }.map { it.s } + 1
                ).distinct().sorted().take(40)
            val all = ArrayList(eps.filter { it.base == base })
            // Sezon sayfaları aynı anda çekilir
            val pages = seasons.amap { sn -> fetch("$mainUrl/$base-$sn-sezon") }
            for (t in pages) {
                if (t.isNotBlank()) all += episodesIn(unescape(t)).filter { it.base == base }
            }
            eps = all
        }

        val seen = HashSet<Pair<Int, Int>>()
        val list = eps.filter { base == null || it.base == base }
            .sortedWith(compareBy({ it.s }, { it.e }, { it.suffix.length }))
            .filter { seen.add(it.s to it.e) }

        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()
        // "Kyle XY izle | Türkçe Dublaj & Altyazılı" -> "Kyle XY"
        val title = (meta("og:title") ?: doc.selectFirst("title")?.text() ?: "Dizi")
            .replace(Regex("""(?i)\s*[-|–]\s*Dizilla.*$"""), "")
            .replace(Regex("""(?i)\s+izle\b.*$"""), "")
            .trim()
        val sitePoster = cleanImg(meta("og:image"))
        val sitePlot = meta("og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val isAnime = Regex("""(?i)"origin"\s*:\s*"anime"""").containsMatchIn(html)
        val siteYear = Regex("""izle\.\s*(\d{4})""").find(sitePlot ?: "")?.groupValues?.get(1)?.toIntOrNull()

        // TMDB: dizi bilgisi + sezonlar
        val tm = tmdbFind(title, siteYear)
        val seasonMap = HashMap<Int, TSeason>()
        if (tm != null) {
            val needed = list.map { it.s }.distinct()
            val got = needed.amap { sn -> sn to tmdbSeason(tm.id, sn) }
            for ((sn, ts) in got) if (ts != null) seasonMap[sn] = ts
        }
        d("tmdb=${tm?.id} sezon=${seasonMap.size}")

        val today = Date().time
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val episodes = ArrayList<Episode>()
        for (x in list) {
            val ts = seasonMap[x.s]
            val j = ts?.eps?.get(x.e)
            var airMs: Long? = null
            val air = jStr(j, "air_date")
            if (air != null) {
                airMs = try {
                    df.parse(air)?.time
                } catch (e: Exception) {
                    null
                }
                // Gelecek tarihli bölümler listeye eklenmez
                if (airMs != null && airMs > today) continue
            }
            val still = jStr(j, "still_path")?.let { "https://image.tmdb.org/t/p/w300$it" }
            val epName = jStr(j, "name")
            val epDesc = jStr(j, "overview")
            val epRun = j?.optInt("runtime", 0)?.takeIf { it > 0 }
            episodes.add(
                newEpisode("$mainUrl/${x.base}-${x.s}-sezon-${x.e}-bolum${x.suffix}") {
                    this.season = x.s
                    this.episode = x.e
                    if (epName != null) this.name = epName
                    if (epDesc != null) this.description = epDesc
                    // Bölüm görseli yoksa sezon posteri kullanılır
                    this.posterUrl = still ?: ts?.poster
                    if (epRun != null) this.runTime = epRun
                    if (airMs != null) this.date = airMs
                }
            )
        }
        d("toplam bölüm=${episodes.size}")

        return newTvSeriesLoadResponse(title, pageUrl, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            this.posterUrl = tm?.poster ?: sitePoster
            this.backgroundPosterUrl = tm?.backdrop
            this.plot = tm?.overview ?: sitePlot
            this.year = tm?.year ?: siteYear
            if (tm != null && tm.genres.isNotEmpty()) this.tags = tm.genres
        }
    }

    // ---------------- Oynatma ----------------

    private val iframeRe = Regex("""https?://[A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9_%.=-]+""")
    private val trailerRe =
        Regex("""(https?://[A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9_%.=-]+)"[^}]{0,300}?"trailer"\s*:\s*(true|false)""")
    private val m3u8Re = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")
    private val mp4Re = Regex("""https?://[^"'\s\\<>]+\.mp4[^"'\s\\<>]*""")
    private val subRe = Regex("""https?://[^"'\s\\<>]+\.(?:vtt|srt)[^"'\s\\<>]*""")
    private val assetRe = Regex("""(?i)\.(?:js|css|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|vtt|srt|html?|php)(?:\?|$)""")
    private val badHostRe = Regex("""(?i)google|gstatic|facebook|twitter|cloudflare|doubleclick|histats|yandex|jsdelivr|cdnjs|jquery|bootstrap|schema\.org|w3\.org""")
    private val keyRe = Regex("""(?i)m3u|master|playlist|index|hls|manifest|/f/|stream|video|cdn""")

    private fun originOf(u: String) = Regex("""^https?://[^/]+""").find(u)?.value ?: mainUrl

    private fun fixProto(u: String): String = if (u.startsWith("//")) "https:$u" else u

    private fun subLang(u: String): String {
        val m = Regex("""/([a-z]{2,3})\.(?:vtt|srt)""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)?.lowercase()
        return when (m) {
            "tr", "tur" -> "Türkçe"
            "en", "eng" -> "İngilizce"
            null -> "Altyazı"
            else -> m.uppercase()
        }
    }

    // HTML + packer çözümü + base64/hex/% çözümü: gizlenmiş adresleri de yakalar
    private fun allTexts(html: String): List<String> {
        val base = unescape(html)
        val out = ArrayList<String>()
        out.add(base)
        try {
            out.addAll(unpackAll(base))
        } catch (e: Exception) {
        }
        try {
            out.addAll(deepTexts(out.toList()))
        } catch (e: Exception) {
        }
        return out.map { unescape(it) }.distinct()
    }

    // Metinlerdeki olası video adresleri (öncelik sırasıyla)
    private fun mediaCandidates(texts: List<String>): List<String> {
        val set = LinkedHashSet<String>()
        val quoted = Regex("[\"'](https?://[^\"'\\s<>\\\\]+|//[^\"'\\s<>\\\\]+)[\"']")
        for (t in texts) {
            m3u8Re.findAll(t).forEach { set.add(it.value) }
            mp4Re.findAll(t).forEach { set.add(it.value) }
            quoted.findAll(t).forEach { set.add(fixProto(it.groupValues[1])) }
        }
        return set.filter { !badHostRe.containsMatchIn(it) && (!assetRe.containsMatchIn(it)) }
            .sortedBy {
                when {
                    it.contains(".m3u8", true) -> 0
                    it.contains(".mp4", true) -> 1
                    keyRe.containsMatchIn(it) -> 2
                    else -> 9
                }
            }
            .filter {
                it.contains(".m3u8", true) || it.contains(".mp4", true) || keyRe.containsMatchIn(it)
            }
    }

    private suspend fun emitLink(
        url: String,
        type: ExtractorLinkType,
        label: String,
        origin: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(name, label, url, type) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
            }
        )
    }

    // Aday adresleri sınıflandırıp yayınlar: .m3u8 -> HLS, .mp4 -> video, diğerleri #EXTM3U ise HLS
    private suspend fun emitCandidates(
        cands: List<String>,
        origin: String,
        label: String,
        sent: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        var verified = 0
        for (u in cands.take(14)) {
            if (u in sent) continue
            when {
                u.contains(".m3u8", true) -> {
                    sent.add(u)
                    emitLink(u, ExtractorLinkType.M3U8, label, origin, callback)
                    ok = true
                }
                u.contains(".mp4", true) -> {
                    sent.add(u)
                    emitLink(u, ExtractorLinkType.VIDEO, label, origin, callback)
                    ok = true
                }
                verified < 6 -> {
                    verified++
                    val body = try {
                        app.get(
                            u,
                            headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
                        ).text
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ""
                    }
                    if (body.trimStart().startsWith("#EXTM3U")) {
                        sent.add(u)
                        emitLink(u, ExtractorLinkType.M3U8, label, origin, callback)
                        ok = true
                    }
                }
            }
        }
        return ok
    }

    private var webTried = false

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        webTried = false
        val html = unescape(fetch(data, "$mainUrl/"))
        val trailers = trailerRe.findAll(html).filter { it.groupValues[2] == "true" }.map { it.groupValues[1] }.toSet()
        val frames = iframeRe.findAll(html).map { it.value }.distinct().filter { it !in trailers }.toList()
        d("iframe sayısı=${frames.size}")

        var ok = false
        val sent = HashSet<String>()
        val subSent = HashSet<String>()
        for ((i, f) in frames.withIndex()) {
            val origin = originOf(f)
            val label = "Kaynak ${i + 1} (${origin.removePrefix("https://").removePrefix("http://")})"

            // 1) iframe sayfasını al (referer = dizi sitesi)
            val rawIfr = fetch(f, "$mainUrl/")
            val texts = allTexts(rawIfr)
            val ifr = texts.firstOrNull() ?: ""
            val cfErr = ifr.contains("cf-error", true) || ifr.contains("cf.errors", true) ||
                ifr.contains("Attention Required", true)
            d("$label iframe len=${rawIfr.length} cfHata=$cfErr")

            // Altyazılar
            val subs = LinkedHashSet<String>()
            for (t in texts) subRe.findAll(t).forEach { subs.add(it.value) }
            for (m in subs) {
                if (subSent.add(m)) subtitleCallback(SubtitleFile(subLang(m), m))
            }

            // 2) açık / gizlenmiş video adresleri
            var cands = mediaCandidates(texts)
            d("$label aday=${cands.size} ${cands.take(4).joinToString(" | ") { it.take(90) }}")
            var got = emitCandidates(cands, origin, label, sent, callback)

            // 3) sayfadaki api / player uç noktalarını dene
            if (!got && rawIfr.isNotBlank() && !cfErr) {
                val ends = LinkedHashSet<String>()
                Regex("[\"'](/[A-Za-z0-9_\\-/]*(?:api|source|player|video|stream|ajax|embed|get)[A-Za-z0-9_\\-/.?=&%]*)[\"']", RegexOption.IGNORE_CASE)
                    .findAll(ifr).forEach { ends.add(it.groupValues[1]) }
                for (ep in ends.take(4)) {
                    val r = try {
                        app.get(
                            origin + ep,
                            headers = mapOf(
                                "User-Agent" to ua,
                                "Referer" to f,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "*/*"
                            )
                        ).text
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        ""
                    }
                    if (r.isBlank()) continue
                    val c2 = mediaCandidates(allTexts(r))
                    for (m in allTexts(r)) subRe.findAll(m).forEach { mm ->
                        if (subSent.add(mm.value)) subtitleCallback(SubtitleFile(subLang(mm.value), mm.value))
                    }
                    if (emitCandidates(c2, origin, label, sent, callback)) {
                        got = true
                        break
                    }
                }
            }

            // 4) altyazı CDN klasöründen ana liste tahmini: .../f/<id>/<hash>/ + master.m3u8 ...
            if (!got && subs.isNotEmpty()) {
                val first = subs.first()
                val folder = Regex("""^(https?://[^/]+/f/[^/]+/[^/]+/)""").find(first)?.groupValues?.get(1)
                val query = first.substringAfter("?", "")
                if (folder != null) {
                    val names = listOf("master.m3u8", "index.m3u8", "playlist.m3u8", "hls/master.m3u8", "hls/index.m3u8", "stream.m3u8")
                    val guesses = ArrayList<String>()
                    for (n in names) {
                        if (query.isNotBlank()) guesses.add("$folder$n?$query")
                        guesses.add("$folder$n")
                    }
                    val hit = guesses.take(12).amap { g ->
                        val b = try {
                            app.get(
                                g,
                                headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
                            ).text
                        } catch (e: Exception) {
                            if (e is java.util.concurrent.CancellationException) throw e
                            ""
                        }
                        if (b.trimStart().startsWith("#EXTM3U")) g else null
                    }.filterNotNull().firstOrNull()
                    d("$label cdn tahmin=${hit?.take(90)}")
                    if (hit != null && sent.add(hit)) {
                        emitLink(hit, ExtractorLinkType.M3U8, label, origin, callback)
                        got = true
                    }
                }
            }

            // 5) son çare: WebView ile ağdan video adresi yakala (bölüm başına bir kez)
            if (!got && !webTried) {
                webTried = true
                val w = try {
                    val r = app.get(
                        f,
                        referer = "$mainUrl/",
                        interceptor = WebViewResolver(
                            Regex("""(?i)\.(?:m3u8|mp4)(?:\?|$)|master\.txt|cf-master"""),
                            userAgent = ua,
                            useOkhttp = true,
                            timeout = 20_000L
                        ),
                        timeout = 30L
                    )
                    r.url.takeIf { it != f && Regex("""(?i)\.(?:m3u8|mp4|txt)""").containsMatchIn(it) }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    d("webview hata ${e.javaClass.simpleName}")
                    null
                }
                d("$label webview=${w?.take(90)}")
                if (w != null && sent.add(w)) {
                    emitLink(
                        w,
                        if (w.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                        label,
                        origin,
                        callback
                    )
                    got = true
                }
            }

            // Hâlâ bulunamadıysa: sonraki turda nedenini görebilmek için sayfa özetini logla
            if (!got) {
                val scripts = Regex("(?is)<script[^>]*>(.*?)</script>").findAll(ifr).map { it.groupValues[1].trim() }
                    .filter { it.length > 20 }.joinToString(" ¶ ")
                d("$label BULUNAMADI. iframe ozet:")
                dLong("ifr", (if (scripts.isNotBlank()) scripts else ifr).replace(Regex("\\s+"), " ").take(6000))
            }
            if (got) ok = true
        }
        return ok
    }

    private fun b64bin(s: String): ByteArray {
        var c = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        while (c.length % 4 != 0) c += "="
        return try {
            Base64.decode(c, Base64.DEFAULT)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun deepTexts(texts: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in texts) {
            if (Regex("\\\\x[0-9a-fA-F]{2}|\\\\u[0-9a-fA-F]{4}").containsMatchIn(t)) {
                var d = Regex("\\\\x([0-9a-fA-F]{2})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
                d = Regex("\\\\u([0-9a-fA-F]{4})").replace(d) { it.groupValues[1].toInt(16).toChar().toString() }
                out.add(d)
            }
            if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(t)) {
                try {
                    out.add(URLDecoder.decode(t.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"), "UTF-8"))
                } catch (e: Exception) {
                }
            }
            Regex("[A-Za-z0-9+/_\\-]{28,}={0,2}").findAll(t).take(400).forEach { m ->
                val bytes = b64bin(m.value)
                if (bytes.isNotEmpty()) {
                    val d = String(bytes, Charsets.ISO_8859_1)
                    if (d.length > 12 && Regex("https?://|\\.m3u8|\\.mp4|\"file\"|sources", RegexOption.IGNORE_CASE).containsMatchIn(d)) {
                        out.add(d)
                    }
                }
            }
        }
        return out.toList()
    }

    // Dean Edwards packer: eval(function(p,a,c,k,e,d){...}('...',62,300,'a|b'.split('|'),0,{}))
    private fun unpackAll(text: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("""\}\('((?:\\.|[^'\\])*)',\s*(\d+),\s*(\d+),\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)""")
        re.findAll(text).forEach { m ->
            try {
                var p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                val a = m.groupValues[2].toInt()
                var c = m.groupValues[3].toInt()
                val k = m.groupValues[4].split("|")
                fun enc(n: Int): String {
                    val hi = if (n < a) "" else enc(n / a)
                    val r = n % a
                    return hi + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
                }
                while (c-- > 0) {
                    if (c < k.size && k[c].isNotEmpty()) {
                        val word = k[c]
                        p = Regex("\\b" + Regex.escape(enc(c)) + "\\b").replace(p) { word }
                    }
                }
                out.add(p)
            } catch (e: Exception) {
            }
        }
        return out
    }
}
