package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Filmizletv : MainAPI() {
    override var mainUrl = "https://filmizletv.to"
    override var name = "Filmizletv"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "Filmizletv"
    private val TMDB_KEY = "000316508321ce461cf81e7c6815eec7"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/"
    private val VIDMIXI = "https://vidmixi.com/"

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Son Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Diziler",
        "$mainUrl/dil/turkce-dublaj" to "Türkçe Dublaj",
        "$mainUrl/dil/turkce-altyazi" to "Türkçe Altyazı",
        "$mainUrl/dil/yerli-filmler" to "Yerli Filmler",
        "$mainUrl/imdb-en-iyiler" to "IMDb Top 250"
    )

    // ---------------------------------------------------------------- ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, referer = mainUrl).document
        val items = doc.select("a.tv-afis, a.tv-dizi-kart")
            .mapNotNull { it.toSearch() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img") ?: return null
        val s = img.attr("data-src").ifBlank { img.attr("src") }
        return if (s.isBlank() || s.startsWith("data:")) null else fixUrlNull(s)
    }

    private fun Element.toSearch(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst(".ad")?.text()?.trim()?.ifBlank { null }
            ?: attr("title").removeSuffix(" izle").trim().ifBlank { null }
            ?: return null
        val poster = posterUrl()
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // ---------------------------------------------------------------- ARAMA
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<SearchResponse>()

        try {
            val txt = app.get(
                "$mainUrl/arama/oneri?q=$q",
                referer = mainUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text.trim()
            if (txt.startsWith("[")) {
                parseItems(JSONArray(txt), out)
            } else {
                val root = JSONObject(txt)
                parseItems(root.optJSONArray("films"), out)
                parseItems(root.optJSONArray("series"), out)
                parseItems(root.optJSONArray("results"), out)
                parseItems(root.optJSONArray("data"), out)
            }
        } catch (_: Exception) {
        }

        if (out.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/arama?q=$q", referer = mainUrl).document
                doc.select("a.tv-afis, a.tv-dizi-kart").mapNotNullTo(out) { it.toSearch() }
            } catch (_: Exception) {
            }
        }
        return out.distinctBy { it.url }
    }

    private fun parseItems(arr: JSONArray?, out: MutableList<SearchResponse>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = fixUrlNull(o.optString("url")) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val poster = fixUrlNull(o.optString("poster"))
            if (url.contains("/dizi/")) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
            }
        }
    }

    // ---------------------------------------------------------------- TMDB
    private suspend fun tmdbGet(path: String): JSONObject? = try {
        val sep = if (path.contains("?")) "&" else "?"
        JSONObject(
            app.get("https://api.themoviedb.org/3/$path${sep}api_key=$TMDB_KEY&language=tr-TR").text
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun tmdbFindId(isSeries: Boolean, title: String, orig: String?, year: Int?): Int? {
        val kind = if (isSeries) "tv" else "movie"
        val yearParam = if (year == null) "" else if (isSeries) "&first_air_date_year=$year" else "&year=$year"
        val names = listOfNotNull(orig, title).distinct()
        for (n in names) {
            val q = URLEncoder.encode(n, "UTF-8")
            for (yp in listOf(yearParam, "")) {
                val r = tmdbGet("search/$kind?query=$q$yp")?.optJSONArray("results")
                val id = r?.optJSONObject(0)?.optInt("id", 0) ?: 0
                if (id > 0) return id
            }
        }
        return null
    }

    // ---------------------------------------------------------------- LOAD
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document
        val isSeries = url.contains("/dizi/")

        val h1 = doc.selectFirst("h1")
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
        val title = h1?.ownText()?.trim()?.removeSuffix("izle")?.trim()?.ifBlank { null }
            ?: ogTitle?.substringBefore(" (")?.removeSuffix(" izle")?.trim()
            ?: return null
        val year = h1?.selectFirst(".yil")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(ogTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val orig = doc.selectFirst("h2.orj, .orj")?.text()?.trim()?.ifBlank { null }

        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val siteDesc = doc.selectFirst(".ozet")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val siteTags = doc.select(".tv-turler a").map { it.text().trim() }.filter { it.isNotBlank() }
        val trailer = Regex("""https:\\?/\\?/www\.youtube\.com\\?/embed\\?/[\w-]+""")
            .find(doc.html())?.value?.replace("\\/", "/")

        // --- TMDB (sadece içerik sayfasında)
        val tmdbId = tmdbFindId(isSeries, title, orig, year)
        val kind = if (isSeries) "tv" else "movie"
        val details = tmdbId?.let { tmdbGet("$kind/$it?append_to_response=credits") }

        val tPoster = details?.optString("poster_path")?.takeIf { it.isNotBlank() && it != "null" }?.let { TMDB_IMG + "w500" + it }
        val tBack = details?.optString("backdrop_path")?.takeIf { it.isNotBlank() && it != "null" }?.let { TMDB_IMG + "original" + it }
        val tDesc = details?.optString("overview")?.takeIf { it.isNotBlank() }
        val tVote = details?.optDouble("vote_average", 0.0)?.takeIf { it > 0 }
        val tGenres = details?.optJSONArray("genres")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }
        }?.takeIf { it.isNotEmpty() }
        val actors = details?.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 15)).mapNotNull { i ->
                val c = a.optJSONObject(i) ?: return@mapNotNull null
                val n = c.optString("name").ifBlank { return@mapNotNull null }
                val p = c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }?.let { TMDB_IMG + "w185" + it }
                Actor(n, p)
            }
        }

        val poster = tPoster ?: sitePoster
        val plot = tDesc ?: siteDesc
        val tags = tGenres ?: siteTags

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tBack ?: sitePoster
                this.year = year
                this.plot = plot
                this.tags = tags
                if (tVote != null) this.score = Score.from10(tVote)
                if (!actors.isNullOrEmpty()) addActors(actors)
                if (trailer != null) addTrailer(trailer)
            }
        }

        // --- Dizi bölümleri
        val epRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")
        val epMap = linkedMapOf<String, Triple<Int, Int, String?>>() // href -> (sezon, bölüm, site adı)

        fun collect(d: org.jsoup.nodes.Document) {
            d.select("a[href*=/sezon-][href*=/bolum-]").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                val m = epRegex.find(href) ?: return@forEach
                val s = m.groupValues[1].toInt()
                val e = m.groupValues[2].toInt()
                val nm = a.selectFirst(".ad")?.text()?.trim()?.ifBlank { null }
                if (!epMap.containsKey(href)) epMap[href] = Triple(s, e, nm)
            }
        }
        collect(doc)

        // Sayfada görünmeyen sezonlar varsa onların sayfalarını da çek
        val seen = epMap.values.map { it.first }.toSet()
        val seasonLinks = doc.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m = Regex("""/dizi/[^/]+/sezon-(\d+)/?$""").find(href) ?: return@mapNotNull null
            if (m.groupValues[1].toInt() in seen) null else href
        }.distinct()
        for (sl in seasonLinks) {
            try { collect(app.get(sl, referer = url).document) } catch (_: Exception) {}
        }

        // TMDB sezon detayları (Türkçe bölüm adı, açıklama, görsel)
        val tmdbEps = hashMapOf<Pair<Int, Int>, JSONObject>()
        if (tmdbId != null) {
            for (s in epMap.values.map { it.first }.distinct()) {
                val arr = tmdbGet("tv/$tmdbId/season/$s")?.optJSONArray("episodes") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    tmdbEps[s to o.optInt("episode_number")] = o
                }
            }
        }

        val episodes = epMap.entries
            .sortedWith(compareBy({ it.value.first }, { it.value.second }))
            .map { (href, v) ->
                val t = tmdbEps[v.first to v.second]
                newEpisode(href) {
                    this.name = t?.optString("name")?.takeIf { it.isNotBlank() } ?: v.third ?: "${v.second}. Bölüm"
                    this.season = v.first
                    this.episode = v.second
                    this.posterUrl = t?.optString("still_path")?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let { TMDB_IMG + "w500" + it }
                    this.description = t?.optString("overview")?.takeIf { it.isNotBlank() }
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tBack ?: sitePoster
            this.year = year
            this.plot = plot
            this.tags = tags
            if (tVote != null) this.score = Score.from10(tVote)
            if (!actors.isNullOrEmpty()) addActors(actors)
            if (trailer != null) addTrailer(trailer)
        }
    }

    // ---------------------------------------------------------------- LİNKLER
    private fun String.clean() = replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    private val vidmixiRegex = Regex("""https?://vidmixi\.com/(?:embed|list|m3u)/[^"'\s\\<>)]+""")
    private val vttRegex = Regex("""https?://vidmixi\.com/upload/videos/[^"'\s\\<>)]+?\.vtt""")
    private val m3u8Regex = Regex("""https?://[^"'\s\\<>)]+\.m3u8[^"'\s\\<>)]*""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1) Sayfayı al: bx([{i,s}]) verisi + csrf + çerezler
        val page = app.get(data, referer = mainUrl)
        val html = page.text
        val cookies = page.cookies
        val csrf = page.document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        val raw = Regex("""bx\(JSON\.parse\('(.*?)'\)\)""").find(html)?.groupValues?.get(1)
            ?.replace("\\u0022", "\"")?.replace("\\/", "/")
        val sources = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        Log.d(TAG, "bx kaynak sayısı=${sources.length()} csrf=${csrf.isNotBlank()} çerez=${cookies.size}")

        // 2) Her kaynak için /px -> {"u": "https://vidmixi.com/embed/..."}
        val embeds = linkedSetOf<String>()
        for (k in 0 until sources.length()) {
            val o = sources.optJSONObject(k) ?: continue
            try {
                val txt = app.post(
                    "$mainUrl/px",
                    data = mapOf("i" to o.optString("i"), "s" to o.optString("s")),
                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrf,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json",
                        "Origin" to mainUrl
                    ),
                    cookies = cookies,
                    referer = data
                ).text
                Log.d(TAG, "px cevap: ${txt.take(200)}")
                val u = JSONObject(txt).optString("u")
                if (u.isNotBlank()) embeds.add(u.clean())
            } catch (e: Exception) {
                Log.d(TAG, "px hata: ${e.message}")
            }
        }
        if (embeds.isEmpty()) return false

        // 3) Embed sayfalarını aç, m3u / list / altyazı çıkar
        var ok = false
        var idx = 0
        val vtts = linkedSetOf<String>()

        for (emb in embeds) {
            val found = linkedSetOf<String>()
            val direct = linkedSetOf<String>()

            fun scan(text: String) {
                val t = text.clean()
                vidmixiRegex.findAll(t).forEach { found.add(it.value) }
                m3u8Regex.findAll(t).forEach { found.add(it.value) }
                vttRegex.findAll(t).forEach { vtts.add(it.value) }
            }

            if (emb.contains("/m3u/") || emb.contains(".m3u8")) {
                found.add(emb)
            } else {
                val t = try {
                    app.get(emb, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).text
                } catch (e: Exception) {
                    Log.d(TAG, "embed hata: ${e.message}"); ""
                }
                Log.d(TAG, "embed $emb -> ${t.take(300)}")
                if (t.trimStart().startsWith("#EXTM3U")) direct.add(emb) else scan(t)

                for (u in found.filter { it.contains("/list/") }) {
                    try {
                        val lt = app.get(u, referer = VIDMIXI).text
                        if (lt.trimStart().startsWith("#EXTM3U")) direct.add(u) else scan(lt)
                    } catch (_: Exception) {
                    }
                }
            }

            val links = (found.filter { it.contains("/m3u/") || it.contains(".m3u8") } + direct).distinct()
            for (link in links) {
                idx++
                callback(
                    newExtractorLink(name, "$name $idx", link, ExtractorLinkType.M3U8) {
                        this.referer = VIDMIXI
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to VIDMIXI, "Origin" to VIDMIXI.trimEnd('/'))
                    }
                )
                ok = true
            }
        }

        // 4) Hiçbir şey çıkmadıysa kayıtlı extractor'lara bırak
        if (!ok) {
            for (emb in embeds) {
                try {
                    if (loadExtractor(emb, "$mainUrl/", subtitleCallback, callback)) ok = true
                } catch (_: Exception) {
                }
            }
        }

        vtts.forEach { v ->
            val lang = when {
                v.contains("forced", true) -> "Türkçe (Zorunlu)"
                v.contains("tur", true) -> "Türkçe"
                v.contains("eng", true) -> "English"
                else -> "Altyazı"
            }
            subtitleCallback(newSubtitleFile(lang, v) { this.headers = mapOf("Referer" to VIDMIXI) })
        }
        return ok
    }
}
