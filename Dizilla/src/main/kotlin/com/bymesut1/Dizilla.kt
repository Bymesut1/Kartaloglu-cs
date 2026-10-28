package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    // true: bağlantı bulunamazsa kaynak listesine, altyazı bulunamazsa altyazı listesine "DEBUG ..." satırı ekler
    private val debug = true

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

    private fun isCf(code: Int, t: String): Boolean =
        code == 403 || code == 503 ||
            (t.length < 30_000 && (t.contains("Just a moment", true) || t.contains("cf-chl", true) ||
                t.contains("challenge-platform", true)))

    // Kod + metin döndürür; Cloudflare engeli varsa CloudflareKiller ile yeniden dener
    private suspend fun fetchFull(url: String, referer: String? = null): Pair<Int, String> {
        val h = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9")
        try {
            val r = app.get(url, headers = h, referer = referer)
            if (!isCf(r.code, r.text)) return r.code to r.text
            d("cf engeli ${r.code} $url")
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("fetch hata ${e.javaClass.simpleName} $url")
        }
        return try {
            val r2 = app.get(url, headers = h, referer = referer, interceptor = cf)
            r2.code to r2.text
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("cf fetch hata ${e.javaClass.simpleName} $url")
            0 to ""
        }
    }

    private suspend fun fetch(url: String, referer: String? = null): String = fetchFull(url, referer).second

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

    // Bölüm adresi: /<dizi>-<sezon>-sezon-<bölüm>-bolum[-sonek]   (sonek: -2, -c01, -izle ...)
    private val epRe = Regex("""/([a-z0-9][a-z0-9-]*)-(\d+)-sezon-(\d+)-bolum(-[a-z0-9]{1,12})?(?![a-z0-9-])""")

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

        // Aynı bölümün birden fazla sayfası olabilir (örn. ...-bolum ve ...-bolum-c01): hepsi bir bölümde toplanır
        val grouped = LinkedHashMap<Pair<Int, Int>, MutableList<Ep>>()
        for (x in eps.filter { base == null || it.base == base }
            .sortedWith(compareBy({ it.s }, { it.e }, { it.suffix.length }))) {
            val g = grouped.getOrPut(x.s to x.e) { ArrayList() }
            if (g.none { it.base == x.base && it.suffix == x.suffix }) g.add(x)
        }
        val list = grouped.values.toList()

        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()
        // "Kyle XY izle | Türkçe Dublaj & Altyazılı" -> "Kyle XY"   ("İzle" büyük İ ile de temizlenir)
        val title = (meta("og:title") ?: doc.selectFirst("title")?.text() ?: "Dizi")
            .replace(Regex("""(?i)\s*[-|–]\s*Dizilla.*$"""), "")
            .replace(Regex("""\s+[iİıI]zle\b.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        val sitePoster = cleanImg(meta("og:image"))
        val sitePlot = meta("og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val isAnime = Regex("""(?i)"origin"\s*:\s*"anime"""").containsMatchIn(html)
        val siteYear = Regex("""[iİıI]zle\.\s*(\d{4})""", RegexOption.IGNORE_CASE).find(sitePlot ?: "")
            ?.groupValues?.get(1)?.toIntOrNull()

        // TMDB: dizi bilgisi + sezonlar
        val tm = tmdbFind(title, siteYear)
        val seasonMap = HashMap<Int, TSeason>()
        if (tm != null) {
            val needed = list.map { it.first().s }.distinct()
            val got = needed.amap { sn -> sn to tmdbSeason(tm.id, sn) }
            for ((sn, ts) in got) if (ts != null) seasonMap[sn] = ts
        }
        d("tmdb=${tm?.id} sezon=${seasonMap.size}")

        val today = Date().time
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val episodes = ArrayList<Episode>()
        for (g in list) {
            val x = g.first()
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
            // Bölümün tüm sayfa adresleri "|" ile birleştirilir (loadLinks hepsinden kaynak / altyazı toplar)
            val dataUrl = g.joinToString("|") { "$mainUrl/${it.base}-${it.s}-sezon-${it.e}-bolum${it.suffix}" }
            episodes.add(
                newEpisode(dataUrl) {
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
    private val ifrTagRe = Regex("""(?i)<iframe[^>]+?(?:src|data-src)\s*=\s*["']([^"']+)["']""")
    private val quotedUrlRe = Regex("[\"'](https?://[^\"'\\s<>\\\\]+|//[^\"'\\s<>\\\\]+)[\"']")
    private val playerKeyRe = Regex(
        """(?i)iframe|/embed|/player|/v/|/e/|/f/|vidmoly|filemoon|streamtape|dood|voe\.|mixdrop|sibnet|ok\.ru|rapidvid|vidmoxy|closeload|upstream|fembed|pixeldrain|uqload|streamwish|vidhide|hdplayer|peacemaker|cdn"""
    )
    private val m3u8Re = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")
    private val mp4Re = Regex("""https?://[^"'\s\\<>]+\.mp4[^"'\s\\<>]*""")
    private val subRe = Regex("""https?://[^"'\s\\<>]+\.(?:vtt|srt)[^"'\s\\<>]*""")
    private val assetRe = Regex("""(?i)\.(?:js|css|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|vtt|srt|html?|php)(?:\?|$)""")
    private val badHostRe = Regex("""(?i)google|gstatic|facebook|twitter|cloudflare|doubleclick|histats|yandex|jsdelivr|cdnjs|jquery|bootstrap|schema\.org|w3\.org""")
    private val keyRe = Regex("""(?i)m3u|master|playlist|index|hls|manifest|/f/|stream|video|cdn""")

    private fun originOf(u: String) = Regex("""^https?://[^/]+""").find(u)?.value ?: mainUrl

    private fun fixProto(u: String): String = if (u.startsWith("//")) "https:$u" else u

    // ---------------- Altyazı ----------------

    // Dil adı: önce dosya adından (.tr. / .en. / tr.vtt ...), yoksa etiketten
    private fun subLang(u: String, label: String = ""): String {
        val name = u.substringBefore("?").substringAfterLast("/").lowercase()
        val m = Regex("""(?:^|[._\-])(tr|tur|turkish|turkce|türkçe|en|eng|english)(?:[._\-]|$)""")
            .find(name)?.groupValues?.get(1)
        when (m) {
            "tr", "tur", "turkish", "turkce", "türkçe" -> return "Türkçe"
            "en", "eng", "english" -> return "İngilizce"
        }
        val l = label.lowercase()
        if (l.contains("türk") || l.contains("turk") || l == "tr" || l == "tur") return "Türkçe"
        if (l.contains("ingiliz") || l.contains("engl") || l == "en" || l == "eng") return "İngilizce"
        if (label.isNotBlank()) return label
        return "Altyazı"
    }

    private val labelRe = Regex("(?:label|lang|language|srclang)[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val trackTagRe = Regex("""(?is)<track\b[^>]*>""")
    private val kindRe = Regex("""(?i)kind["']?\s*[:=]\s*["'](?:captions|subtitles)["']""")

    // Metinlerdeki altyazı adresleri (+ yakındaki label / lang alanı):
    //  1) .vtt / .srt uzantılı adresler  2) <track src=...>  3) kind: captions/subtitles olan kayıtların file / src / url alanı
    private fun findSubs(texts: List<String>, origin: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        fun absU(s: String): String? = when {
            s.startsWith("http", true) -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> origin + s
            else -> null
        }
        for (t in texts) {
            for (m in subRe.findAll(t)) {
                val u = m.value
                if (Regex("thumb|sprite|chapter", RegexOption.IGNORE_CASE).containsMatchIn(u)) continue
                if (out.containsKey(u)) continue
                val ctx = t.substring(maxOf(0, m.range.first - 200), minOf(t.length, m.range.last + 200))
                out[u] = labelRe.find(ctx)?.groupValues?.get(1) ?: ""
            }
            for (m in trackTagRe.findAll(t)) {
                val tag = m.value
                val src = Regex("""(?i)src\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1) ?: continue
                val u = absU(src) ?: continue
                val lb = Regex("""(?i)(?:label|srclang)\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1) ?: ""
                if (!out.containsKey(u)) out[u] = lb
            }
            for (k in kindRe.findAll(t)) {
                val ctx = t.substring(maxOf(0, k.range.first - 300), minOf(t.length, k.range.last + 300))
                val fu = Regex("""(?i)(?:file|src|url)["']?\s*[:=]\s*["']([^"']+)["']""").find(ctx)?.groupValues?.get(1) ?: continue
                val u = absU(fu) ?: continue
                if (!out.containsKey(u)) out[u] = labelRe.find(ctx)?.groupValues?.get(1) ?: ""
            }
        }
        return out
    }

    // WEBVTT ise aynen, SRT ise VTT'ye çevirip döndürür; altyazı değilse null
    private fun toVtt(t0: String): String? {
        val t = t0.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (t.startsWith("WEBVTT")) return t
        if (Regex("""^\d+\s*\r?\n\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->""").containsMatchIn(t)) {
            return "WEBVTT\n\n" + t.replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
        }
        return null
    }

    // Altyazıyı farklı istek biçimleriyle çekmeyi dener (CDN tarayıcı tarzı isteği engelleyebiliyor)
    private suspend fun fetchSub(u: String, frameUrl: String): ByteArray? {
        val fo = originOf(frameUrl)
        val sets = listOf(
            mapOf("User-Agent" to ua, "Referer" to frameUrl, "Origin" to fo, "Accept" to "*/*"),
            mapOf("User-Agent" to ua, "Referer" to "$fo/", "Origin" to fo, "Accept" to "*/*"),
            mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Accept" to "*/*"),
            mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "*/*"),
            mapOf("Accept" to "*/*")
        )
        for ((i, h) in sets.withIndex()) {
            try {
                val r = app.get(u, headers = h)
                val v = if (r.code == 200) toVtt(r.text) else null
                d("altyazi deneme ${i + 1} kod=${r.code} vtt=${v != null}")
                if (v != null) return v.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("altyazi deneme ${i + 1} hata ${e.javaClass.simpleName}")
            }
        }
        // son çare: Cloudflare atlatıcı
        try {
            val r = app.get(u, headers = sets[0], interceptor = cf)
            val v = if (r.code == 200) toVtt(r.text) else null
            d("altyazi cf kod=${r.code} vtt=${v != null}")
            if (v != null) return v.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
        }
        return null
    }

    private val subCount = HashMap<String, Int>()

    // Oynatıcının altyazı bulunamadığında göstereceği tanı metni (Altyazılar listesinde "DEBUG ..." olarak çıkar)
    private val subDbg = StringBuilder()

    // Altyazıyı çeker, yerel sunucudan oynatıcıya verir. Çekilebildiyse true.
    // Uzantısı .vtt/.srt olmayan adresler yalnızca gerçekten altyazı olarak çekilebilirse eklenir.
    private suspend fun emitSub(
        u: String,
        label: String,
        frameUrl: String,
        subSent: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        if (!subSent.add(u)) return false
        val strict = !subRe.containsMatchIn(u)
        val bytes = fetchSub(u, frameUrl)
        if (bytes == null && strict) {
            d("altyazi atlandi (altyazi degil / cekilemedi) ${u.takeLast(70)}")
            return false
        }
        val lang = subLang(u, label)
        val n = subCount[lang] ?: 0
        subCount[lang] = n + 1
        val shown = if (n == 0) lang else "$lang ${n + 1}"
        val local = if (bytes != null) SubProxy.put(bytes) else null
        d("altyazi $shown ${if (local != null) "yerel sunucu" else "dogrudan (cekilemedi)"} ${u.takeLast(70)}")
        subtitleCallback(SubtitleFile(shown, local ?: u))
        return local != null
    }

    // ---------------- Video adresleri ----------------

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

    // Bölüm sayfasındaki olası oynatıcı (iframe / embed) adresleri
    private fun playerCandidates(texts: List<String>, trailers: Set<String>): List<String> {
        val strong = LinkedHashSet<String>()
        val weak = LinkedHashSet<String>()
        val selfHost = originOf(mainUrl)
        for (t in texts) {
            iframeRe.findAll(t).forEach { strong.add(it.value) }
            ifrTagRe.findAll(t).forEach { m ->
                val u = fixProto(m.groupValues[1])
                if (u.startsWith("http")) strong.add(u)
            }
            quotedUrlRe.findAll(t).forEach { m ->
                val u = fixProto(m.groupValues[1])
                if (weak.size < 40 && playerKeyRe.containsMatchIn(u)) weak.add(u)
            }
        }
        val out = ArrayList<String>()
        for (u in strong) if (u !in trailers && !badHostRe.containsMatchIn(u)) out.add(u)
        for (u in weak) {
            if (u in out || u in trailers) continue
            if (u.startsWith(selfHost)) continue
            if (badHostRe.containsMatchIn(u) || assetRe.containsMatchIn(u)) continue
            if (u.contains("themoviedb", true) || u.contains("image.tmdb", true)) continue
            out.add(u)
        }
        return out.distinct().take(12)
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
    private var srcCount = 0

    // Tek bir oynatıcı (iframe) adresinden video + altyazı çıkarır
    private suspend fun resolveFrame(
        f: String,
        label: String,
        sent: MutableSet<String>,
        subSent: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        info: StringBuilder
    ): Boolean {
        val origin = originOf(f)

        // 1) iframe sayfasını al (referer = dizi sitesi)
        val rawIfr = fetch(f, "$mainUrl/")
        val texts = allTexts(rawIfr)
        val ifr = texts.firstOrNull() ?: ""
        val cfErr = ifr.contains("cf-error", true) || ifr.contains("cf.errors", true) ||
            ifr.contains("Attention Required", true)
        d("$label iframe len=${rawIfr.length} cfHata=$cfErr")

        // Altyazılar: çek, yerel sunucudan ver
        val subMap = findSubs(texts, origin)
        var subOk = 0
        for ((su, sl) in subMap) {
            if (emitSub(su, sl, f, subSent, subtitleCallback)) subOk++
        }
        if (subMap.isEmpty() && subDbg.length < 400) {
            // altyazı çıkmadıysa nedenini görebilmek için sayfadaki ilgili kelimelerin özetini sakla
            val joined = texts.joinToString(" ")
            val kws = Regex("""(?i)vtt|srt|track|caption|subtitle|altyaz""").findAll(joined).toList()
            val sn = kws.firstOrNull()?.let {
                joined.substring(maxOf(0, it.range.first - 50), minOf(joined.length, it.range.last + 90))
                    .replace(Regex("\\s+"), " ")
            } ?: ""
            subDbg.append("[${origin.removePrefix("https://").removePrefix("http://").take(18)} len=${rawIfr.length} kw=${kws.size} $sn] ")
        }

        // 2) açık / gizlenmiş video adresleri
        val cands = mediaCandidates(texts)
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
                val rt = allTexts(r)
                val c2 = mediaCandidates(rt)
                for ((su, sl) in findSubs(rt, origin)) {
                    if (emitSub(su, sl, f, subSent, subtitleCallback)) subOk++
                }
                if (emitCandidates(c2, origin, label, sent, callback)) {
                    got = true
                    break
                }
            }
        }

        // 4) altyazı CDN klasöründen ana liste tahmini: .../f/<id>/<hash>/ + master.m3u8 ...
        if (!got && subMap.isNotEmpty()) {
            val first = subMap.keys.first()
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

        // 4b) CloudStream'in hazır çıkarıcıları (bilinen oynatıcı siteleri için)
        if (!got) {
            try {
                if (loadExtractor(f, "$mainUrl/", subtitleCallback, callback)) got = true
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
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
        info.append("[${origin.removePrefix("https://").removePrefix("http://").take(22)} len=${rawIfr.length} cf=$cfErr aday=${cands.size} altyazi=${subMap.size}/$subOk ok=$got] ")
        return got
    }

    private suspend fun debugLink(callback: (ExtractorLink) -> Unit, text: String) {
        callback(
            newExtractorLink(name, "DEBUG $text".take(600), "https://debug.invalid/x.m3u8", ExtractorLinkType.M3U8) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
            }
        )
    }

    // Bir bölüm sayfasından (tek adres) oynatıcıları bulup çözer
    private suspend fun processPage(
        pageUrl: String,
        sent: MutableSet<String>,
        subSent: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        info: StringBuilder
    ): Boolean {
        var ok = false
        val pg = fetchFull(pageUrl, "$mainUrl/")
        val html = unescape(pg.second)
        info.append("sayfa=${pg.first}/${pg.second.length} ")
        val pageTexts = allTexts(pg.second)
        val trailers = trailerRe.findAll(html).filter { it.groupValues[2] == "true" }.map { it.groupValues[1] }.toSet()
        val frames = playerCandidates(pageTexts, trailers)
        d("oynatıcı adayı=${frames.size} ${frames.take(5).joinToString(" | ") { it.take(80) }}")
        info.append("oynatici=${frames.size} ")

        // bölüm sayfasının kendisinde altyazı adresi varsa onu da ekle
        try {
            for ((su, sl) in findSubs(pageTexts, originOf(mainUrl))) emitSub(su, sl, pageUrl, subSent, subtitleCallback)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
        }

        // sayfa varyantı (örn. -c01) kaynak adında görünsün
        val vTag = Regex("""-bolum-([a-z0-9]{1,12})/?$""").find(pageUrl)?.groupValues?.get(1) ?: ""

        for (f in frames) {
            srcCount++
            val origin = originOf(f)
            val label = "Kaynak $srcCount (${origin.removePrefix("https://").removePrefix("http://")})" +
                (if (vTag.isNotBlank()) " [$vTag]" else "")
            val got = try {
                resolveFrame(f, label, sent, subSent, subtitleCallback, callback, info)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("$label hata ${e.javaClass.simpleName} ${e.message}")
                info.append("[hata ${e.javaClass.simpleName}] ")
                false
            }
            if (got) ok = true
        }

        if (!ok) {
            val scripts = Regex("(?is)<script[^>]*>(.*?)</script>").findAll(pg.second).map { it.groupValues[1].trim() }
                .filter { it.length > 20 }.joinToString(" ¶ ")
            d("BULUNAMADI sayfa=${pg.first} len=${pg.second.length}. sayfa ozet:")
            dLong("pg", (if (scripts.isNotBlank()) scripts else pg.second).replace(Regex("\\s+"), " ").take(6000))
        }
        return ok
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        webTried = false
        srcCount = 0
        subCount.clear()
        subDbg.setLength(0)
        val info = StringBuilder()
        val sent = HashSet<String>()
        val subSent = HashSet<String>()
        var ok = false

        // bölümün tüm sayfa adresleri ("|" ile ayrılı): hepsinden kaynak ve altyazı topla
        val urls = data.split("|").map { it.trim() }.filter { it.startsWith("http") }.ifEmpty { listOf(data) }
        for (u in urls) {
            val got = try {
                processPage(u, sent, subSent, subtitleCallback, callback, info)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("sayfa hata ${e.javaClass.simpleName} ${e.message}")
                false
            }
            if (got) ok = true
        }

        // hiçbiri çıkmadıysa: ilk bölüm sayfasını gerçek WebView'da aç, oynatıcı / video isteğini yakala
        if (!ok) {
            val first = urls.first()
            val w = try {
                val r = app.get(
                    first,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(
                        Regex("""(?i)iframe\.php\?v=|\.m3u8|\.mp4|/embed/|master\.txt"""),
                        userAgent = ua,
                        useOkhttp = false,
                        timeout = 25_000L
                    ),
                    timeout = 35L
                )
                r.url.takeIf { it != first }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("sayfa webview hata ${e.javaClass.simpleName}")
                null
            }
            d("sayfa webview=${w?.take(100)}")
            info.append("webview=${if (w != null) "var" else "yok"} ")
            if (w != null) {
                val origin = originOf(w)
                srcCount++
                val label = "Kaynak $srcCount (${origin.removePrefix("https://").removePrefix("http://")})"
                if (Regex("""(?i)\.(?:m3u8|mp4)(?:\?|$)""").containsMatchIn(w)) {
                    if (sent.add(w)) {
                        emitLink(
                            w,
                            if (w.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                            label,
                            origin,
                            callback
                        )
                        ok = true
                    }
                } else {
                    val got = try {
                        resolveFrame(w, label, sent, subSent, subtitleCallback, callback, info)
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        false
                    }
                    if (got) ok = true
                }
            }
        }

        // altyazı hiç bulunamadıysa: nedenini Altyazılar listesinde göster
        if (debug && subSent.isEmpty()) {
            subtitleCallback(SubtitleFile("DEBUG altyazi yok: $subDbg".take(500), "http://127.0.0.1:9/none.vtt"))
        }

        // hâlâ video yoksa: nedenini kaynak listesinde göster
        if (!ok && debug) {
            debugLink(callback, info.toString())
            return true
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

// ----------------------------------------------------------------------
// Yerel altyazı sunucusu: 127.0.0.1 üzerinde, bizim çektiğimiz altyazıyı oynatıcıya verir
// (CDN, oynatıcının kendi isteğini engelleyebildiği için)
// ----------------------------------------------------------------------
private object SubProxy {
    private val store = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port = 0

    @Synchronized
    fun ensure(): Boolean {
        val cur = server
        if (cur != null && !cur.isClosed) return true
        return try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            val t = Thread {
                while (!s.isClosed) {
                    try {
                        val c = s.accept()
                        val w = Thread { serve(c) }
                        w.isDaemon = true
                        w.start()
                    } catch (e: Exception) {
                        if (s.isClosed) break
                    }
                }
            }
            t.isDaemon = true
            t.name = "dizilla-sub-proxy"
            t.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    // Altyazı içeriğini saklar ve oynatıcıya verilecek yerel adresi döndürür
    fun put(bytes: ByteArray): String? {
        if (!ensure()) return null
        if (store.size > 80) store.clear()
        val id = java.lang.Long.toString(System.nanoTime(), 36)
        store[id] = bytes
        return "http://127.0.0.1:$port/s/$id.vtt"
    }

    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 15_000
            val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val reqLine = inp.readLine() ?: return
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
            }
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            val id = path.removePrefix("/s/").substringBefore("?").removeSuffix(".vtt")
            val os = sock.getOutputStream()
            val body = store[id]
            if (body == null) {
                os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                os.flush()
                return
            }
            val head = "HTTP/1.1 200 OK\r\nContent-Type: text/vtt; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            os.write(head.toByteArray())
            if (method != "HEAD") os.write(body)
            os.flush()
        } catch (ex: Exception) {
        } finally {
            try {
                sock.close()
            } catch (ex: Exception) {
            }
        }
    }
}
