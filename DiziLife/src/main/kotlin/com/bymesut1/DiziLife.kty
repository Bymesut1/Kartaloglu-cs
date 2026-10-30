package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziLife : MainAPI() {
    override var mainUrl = "https://dizi82.life"
    override var name = "DiziLife"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // true: bağlantı bulunamazsa kaynak listesine "DEBUG ..." satırı ekler
    private val debug = true

    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"

    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler"
    )

    private fun d(m: String) {
        Log.d("DiziLifeDbg", m)
    }

    private fun rethrow(e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
    }

    // ---------------- Yardımcılar ----------------

    private fun isCf(code: Int, t: String): Boolean =
        code == 403 || code == 503 ||
            (t.length < 30_000 && (t.contains("Just a moment", true) || t.contains("cf-chl", true)))

    private suspend fun fetchFull(url: String, referer: String? = null): Pair<Int, String> {
        val h = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9")
        try {
            val r = app.get(url, headers = h, referer = referer)
            if (!isCf(r.code, r.text)) return r.code to r.text
            d("cf engeli ${r.code} $url")
        } catch (e: Exception) {
            rethrow(e)
            d("fetch hata ${e.javaClass.simpleName} $url")
        }
        return try {
            val r2 = app.get(url, headers = h, referer = referer, interceptor = cf)
            r2.code to r2.text
        } catch (e: Exception) {
            rethrow(e)
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
        u?.substringBefore("#")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { abs(it) }

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"").replace("&amp;", "&")

    private fun humanize(slug: String): String =
        slug.split("-").filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }

    private fun cleanTitle(t: String): String =
        t.substringBefore(" | ")
            .replace(Regex("""(?i)\s*[-|–]\s*Dizi\s?Life.*$"""), "")
            .replace(Regex("""\s+[iİıI]zle\b.*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    // ---------------- Kartlar ----------------

    private val filmPathRe = Regex("""^/film/([a-z0-9-]+)""")
    private val diziPathRe = Regex("""^/dizi/([a-z0-9-]+)""")

    private class Card(val url: String, val title: String, val poster: String?, val movie: Boolean, val score: Int)

    private fun cardsOf(doc: Document): List<SearchResponse> {
        val best = LinkedHashMap<String, Card>()
        for (a in doc.select("a[href]")) {
            val path = a.attr("href").substringBefore("?").substringBefore("#")
                .replace(Regex("^https?://[^/]+"), "")
            val fm = filmPathRe.find(path)
            val dm = diziPathRe.find(path)
            val slug: String
            val url: String
            val movie: Boolean
            if (fm != null) {
                slug = fm.groupValues[1]
                url = "$mainUrl/film/$slug"
                movie = true
            } else if (dm != null) {
                slug = dm.groupValues[1]
                url = "$mainUrl/dizi/$slug"
                movie = false
            } else continue
            val img = a.selectFirst("img")
            val src = cleanImg(img?.attr("src")) ?: cleanImg(img?.attr("data-src"))
            var title = img?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) title = a.selectFirst("div.font-semibold")?.text()?.trim().orEmpty()
            if (title.isBlank()) title = a.attr("title").trim()
            val real = title.isNotBlank()
            if (!real) title = humanize(slug)
            val score = (if (src != null) 2 else 0) + (if (real) 1 else 0)
            val old = best[url]
            if (old == null || score > old.score) best[url] = Card(url, title, src ?: old?.poster, movie, score)
        }
        return best.values.map { c ->
            if (c.movie) newMovieSearchResponse(c.title, c.url, TvType.Movie) { this.posterUrl = c.poster }
            else newTvSeriesSearchResponse(c.title, c.url, TvType.TvSeries) { this.posterUrl = c.poster }
        }
    }

    private val firstPageKeys = ConcurrentHashMap<String, Set<String>>()
    private val pageCache = ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()

    private suspend fun cachedCards(url: String): List<SearchResponse> {
        val now = System.currentTimeMillis()
        val c = pageCache[url]
        if (c != null && now - c.first < 15 * 60_000L) return c.second
        val l = cardsOf(org.jsoup.Jsoup.parse(fetch(url), mainUrl))
        if (l.isNotEmpty()) pageCache[url] = now to l
        return l
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data
        val url = if (page <= 1) base else base + (if (base.contains("?")) "&" else "?") + "page=$page"
        val list = cachedCards(url)
        val keys = list.map { it.url }.toSet()
        if (page <= 1) {
            firstPageKeys[base] = keys
        } else if (list.isEmpty() || firstPageKeys[base]?.containsAll(keys) == true) {
            return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        }
        d("anasayfa ${request.name} sayfa=$page kart=${list.size}")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = base != "$mainUrl/"
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val list = cardsOf(org.jsoup.Jsoup.parse(fetch("$mainUrl/ara?q=$q"), mainUrl))
        d("arama '$query' sonuc=${list.size}")
        return list
    }

    // ---------------- TMDB ----------------

    private class Tm(
        val id: Int,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val genres: List<String>,
        val seasons: List<Int>,
        val runtime: Int?
    )

    private class TSeason(val eps: Map<Int, JSONObject>, val poster: String?)

    private fun jStr(o: JSONObject?, k: String): String? {
        if (o == null) return null
        val s = o.optString(k, "")
        return if (s.isBlank() || s == "null") null else s
    }

    private suspend fun tmdbGet(path: String, language: String = "tr-TR"): JSONObject? {
        if (tmdbKey.isBlank()) return null
        return try {
            val sep = if (path.contains("?")) "&" else "?"
            JSONObject(
                app.get(
                    "https://api.themoviedb.org/3$path${sep}api_key=$tmdbKey&language=$language",
                    headers = mapOf("User-Agent" to ua)
                ).text
            )
        } catch (e: Exception) {
            rethrow(e)
            null
        }
    }

    private fun normT(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9ğüşıöç]"), "")

    private suspend fun tmdbFind(title: String, movie: Boolean): Tm? {
        if (title.isBlank()) return null
        val kind = if (movie) "movie" else "tv"
        val nameKey = if (movie) "title" else "name"
        val origKey = if (movie) "original_title" else "original_name"
        val dateKey = if (movie) "release_date" else "first_air_date"
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val res = tmdbGet("/search/$kind?query=$q&include_adult=false")?.optJSONArray("results") ?: return null
        if (res.length() == 0) return null
        val nt = normT(title)
        var pick: JSONObject? = null
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            if (normT(o.optString(nameKey, "")) == nt || normT(o.optString(origKey, "")) == nt) {
                pick = o
                break
            }
        }
        if (pick == null) pick = res.optJSONObject(0)
        val p = pick ?: return null
        val id = p.optInt("id", 0)
        if (id == 0) return null
        val det = tmdbGet("/$kind/$id") ?: p
        var overview = jStr(det, "overview")
        if (overview == null) overview = jStr(tmdbGet("/$kind/$id", "en-US"), "overview")
        val genres = ArrayList<String>()
        det.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) jStr(g.optJSONObject(i), "name")?.let { genres.add(it) }
        }
        val seasons = ArrayList<Int>()
        det.optJSONArray("seasons")?.let { s ->
            for (i in 0 until s.length()) {
                val n = s.optJSONObject(i)?.optInt("season_number", 0) ?: 0
                if (n > 0) seasons.add(n)
            }
        }
        return Tm(
            id,
            overview,
            jStr(det, "poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            jStr(det, "backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            jStr(det, dateKey)?.take(4)?.toIntOrNull(),
            genres,
            seasons,
            det.optInt("runtime", 0).takeIf { it > 0 }
        )
    }

    private suspend fun tmdbSeason(id: Int, season: Int): TSeason? {
        val tr = tmdbGet("/tv/$id/season/$season") ?: return null
        val arr = tr.optJSONArray("episodes") ?: return null
        val map = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.optInt("episode_number", -1)] = o
        }
        if (map.values.any { jStr(it, "overview") == null }) {
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

    // ---------------- Film / Dizi sayfası ----------------

    override suspend fun load(url: String): LoadResponse? {
        val u = abs(url).substringBefore("?").substringBefore("#").trimEnd('/')
        if (u.contains("/film/")) return loadMovie(u)
        val series = Regex("""^(https?://[^/]+/dizi/[^/]+)""").find(u)?.groupValues?.get(1) ?: u
        return loadSeries(series)
    }

    private suspend fun loadMovie(pageUrl: String): LoadResponse? {
        val pg = fetchFull(pageUrl, "$mainUrl/")
        val raw = pg.second
        if (raw.isBlank() || pg.first == 404) return null
        val doc = org.jsoup.Jsoup.parse(raw, mainUrl)
        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()
        val title = cleanTitle(
            meta("og:title") ?: doc.selectFirst("title")?.text() ?: humanize(pageUrl.substringAfter("/film/"))
        )
        val sitePoster = cleanImg(meta("og:image"))
        val sitePlot = meta("og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tm = tmdbFind(title, true)
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = tm?.poster ?: sitePoster
            this.backgroundPosterUrl = tm?.backdrop
            this.plot = tm?.overview ?: sitePlot
            this.year = tm?.year
            if (tm != null && tm.genres.isNotEmpty()) this.tags = tm.genres
            this.duration = tm?.runtime
        }
    }

    private suspend fun loadSeries(pageUrl: String): LoadResponse? {
        val slug = pageUrl.substringAfter("/dizi/")
        val raw = fetch(pageUrl, "$mainUrl/")
        if (raw.isBlank()) return null
        val html = unescape(raw)
        val doc = org.jsoup.Jsoup.parse(raw, mainUrl)
        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()

        val title = cleanTitle(meta("og:title") ?: doc.selectFirst("title")?.text() ?: humanize(slug))
        val sitePoster = cleanImg(meta("og:image"))
        val sitePlot = meta("og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tm = tmdbFind(title, false)

        // Bölüm adresi: /dizi/<slug>/sezon/<s>/bolum/<e>
        val epRe = Regex("""/dizi/${Regex.escape(slug)}/sezon/(\d+)/bolum/(\d+)""")
        val found = LinkedHashSet<Pair<Int, Int>>()
        epRe.findAll(html).forEach { found.add(it.groupValues[1].toInt() to it.groupValues[2].toInt()) }
        d("dizi=$slug site bolum=${found.size} tmdb=${tm?.id}")

        val fromTmdb = found.isEmpty()
        val needed = (if (fromTmdb) (tm?.seasons ?: emptyList()) else found.map { it.first }.distinct()).sorted()
        val seasonMap = HashMap<Int, TSeason>()
        if (tm != null) {
            val got = needed.amap { sn -> sn to withTimeoutOrNull(6000L) { tmdbSeason(tm.id, sn) } }
            for ((sn, ts) in got) if (ts != null) seasonMap[sn] = ts
        }
        val pairs: List<Pair<Int, Int>> = if (!fromTmdb) {
            found.sortedWith(compareBy({ it.first }, { it.second }))
        } else {
            val l = ArrayList<Pair<Int, Int>>()
            for ((sn, ts) in seasonMap.toSortedMap()) for (en in ts.eps.keys.sorted()) if (en > 0) l.add(sn to en)
            l
        }

        val today = Date().time
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val episodes = ArrayList<Episode>()
        for ((s, e) in pairs) {
            val ts = seasonMap[s]
            val j = ts?.eps?.get(e)
            val air = jStr(j, "air_date")
            val airMs: Long? = air?.let {
                try {
                    df.parse(it)?.time
                } catch (ex: Exception) {
                    null
                }
            }
            if (fromTmdb && airMs != null && airMs > today) continue
            val still = jStr(j, "still_path")?.let { "https://image.tmdb.org/t/p/w300$it" }
            val epName = jStr(j, "name")
            val epDesc = jStr(j, "overview")
            val epRun = j?.optInt("runtime", 0)?.takeIf { it > 0 }
            episodes.add(
                newEpisode("$pageUrl/sezon/$s/bolum/$e") {
                    this.season = s
                    this.episode = e
                    if (epName != null) this.name = epName
                    if (epDesc != null) this.description = epDesc
                    this.posterUrl = still ?: ts?.poster
                    if (epRun != null) this.runTime = epRun
                    if (airMs != null) this.date = airMs
                }
            )
        }
        d("toplam bolum=${episodes.size}")

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = tm?.poster ?: sitePoster
            this.backgroundPosterUrl = tm?.backdrop
            this.plot = tm?.overview ?: sitePlot
            this.year = tm?.year
            if (tm != null && tm.genres.isNotEmpty()) this.tags = tm.genres
        }
    }

    // ---------------- Oynatma ----------------
    // Bölüm / film sayfası -> iframe -> oynatıcı sayfası. Oynatıcıdaki yapılandırma şifreli:
    // atob fonksiyonuna iki değer verilir (ters çevrilmiş base64): şifreli metin ve parola.
    // OpenSSL biçimi (Salted__ + 8 bayt tuz), EVP_BytesToKey (MD5) + AES-256-CBC.

    private fun b64(s: String): ByteArray {
        var c = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        while (c.length % 4 != 0) c += "="
        return Base64.decode(c, Base64.DEFAULT)
    }

    private fun revB64(s: String): ByteArray = b64(s.reversed())

    private fun evpKeyIv(pass: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        var prev = ByteArray(0)
        var total = ByteArray(0)
        while (total.size < 48) {
            md.reset()
            if (prev.isNotEmpty()) md.update(prev)
            md.update(pass)
            md.update(salt)
            prev = md.digest()
            total += prev
        }
        return total.copyOfRange(0, 32) to total.copyOfRange(32, 48)
    }

    private fun decryptConfig(playerHtml: String): String? {
        val fn = Regex("""var\s+([a-zA-Z0-9_$]+)\s*=\s*function\(s\)\{return atob""")
            .find(playerHtml)?.groupValues?.get(1) ?: return null
        val vals = Regex(Regex.escape(fn) + """\("([^"]+)"\)""").findAll(playerHtml)
            .map { it.groupValues[1] }.toList()
        if (vals.size < 2) return null
        val data = revB64(vals[0])
        val pass = revB64(vals[1])
        if (data.size < 17) return null
        val salt = data.copyOfRange(8, 16)
        val ct = data.copyOfRange(16, data.size)
        val (key, iv) = evpKeyIv(pass, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private suspend fun resolvePlayer(
        iframeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        info: StringBuilder
    ): Boolean {
        val ph = fetch(iframeUrl, referer)
        info.append("oynatici len=${ph.length} ")
        if (ph.isBlank()) return false

        var master: String? = null
        try {
            val dec = decryptConfig(ph)
            val fileMatch = dec?.let { Regex("""file:\s*["']([^"']+)["']""").find(it) }
            master = fileMatch?.groupValues?.get(1)?.substringBefore("?")
            info.append("sifre=${if (master != null) "ok" else "yok"} ")
        } catch (e: Exception) {
            rethrow(e)
            d("cozme hata ${e.javaClass.simpleName} ${e.message}")
            info.append("sifre hata ${e.javaClass.simpleName} ")
        }
        if (master == null) {
            master = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""").find(unescape(ph))?.value
        }
        if (master == null) return false

        callback(
            newExtractorLink(name, "$name 1080p Türkçe Dublaj / Altyazı", master, ExtractorLinkType.M3U8) {
                this.referer = iframeUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to ua, "Referer" to iframeUrl)
            }
        )
        return true
    }

    private suspend fun debugLink(callback: (ExtractorLink) -> Unit, text: String) {
        callback(
            newExtractorLink(name, "DEBUG $text".take(600), "https://debug.invalid/x.m3u8", ExtractorLinkType.M3U8) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = StringBuilder()
        val page = data.split("|").first().trim()
        val pg = fetchFull(page, "$mainUrl/")
        info.append("sayfa=${pg.first}/${pg.second.length} ")
        val html = unescape(pg.second)

        val iframes = Regex("""(?i)<iframe[^>]+(?:src|data-src)\s*=\s*["']((?:https?:)?//[^"']+)["']""")
            .findAll(html).map { m ->
                val u = m.groupValues[1]
                if (u.startsWith("//")) "https:$u" else u
            }.distinct().toList()
        d("iframe=${iframes.size} ${iframes.take(3).joinToString(" | ") { it.take(80) }}")
        info.append("iframe=${iframes.size} ")

        var ok = false
        for (f in iframes.take(4)) {
            val got = try {
                resolvePlayer(f, page, callback, info)
            } catch (e: Exception) {
                rethrow(e)
                d("oynatici hata ${e.javaClass.simpleName}")
                false
            }
            if (got) {
                ok = true
                continue
            }
            // şifre çözülemediyse CloudStream'in hazır çıkarıcılarına bırak
            try {
                loadExtractor(f, "$mainUrl/", subtitleCallback) { l ->
                    callback(l)
                    ok = true
                }
            } catch (e: Exception) {
                rethrow(e)
            }
        }

        if (!ok && debug) {
            debugLink(callback, info.toString())
            return true
        }
        return ok
    }
}
