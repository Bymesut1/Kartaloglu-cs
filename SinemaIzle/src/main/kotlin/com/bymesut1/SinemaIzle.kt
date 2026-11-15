package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

private fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}

class SinemaIzle : MainAPI() {
    override var mainUrl = "https://sinemaizle.org"
    override var name = "SinemaIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // DiziPod.kt icindeki TMDB anahtarini buraya yapistir
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val baseHeaders = mapOf("User-Agent" to ua)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Diziler",
        "$mainUrl/dil/turkce-dublaj-filmler-izle" to "Türkçe Dublaj Filmler",
        "$mainUrl/dil/turkce-altyazi-filmler-izle" to "Türkçe Altyazılı Filmler",
        "$mainUrl/dil/yerli-film-izle" to "Yerli Filmler",
        "$mainUrl/imdb-en-iyiler" to "IMDb En İyiler",
        "$mainUrl/tur/aksiyon-filmleri-izle" to "Aksiyon",
        "$mainUrl/tur/komedi-filmleri-izle-s1" to "Komedi",
        "$mainUrl/tur/korku-filmleri-izle" to "Korku",
        "$mainUrl/tur/gerilim-filmleri-izle" to "Gerilim",
        "$mainUrl/tur/dram-filmleri-izle" to "Dram",
        "$mainUrl/tur/fantastik-filmleri-izle" to "Fantastik",
        "$mainUrl/tur/macera-filmleri-izle" to "Macera",
        "$mainUrl/tur/romantik-filmleri-izle" to "Romantik",
        "$mainUrl/tur/suc-filmleri-izle" to "Suç"
    )

    // ------------------------------------------------------------------
    // ANA SAYFA
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, headers = baseHeaders, referer = "$mainUrl/").document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private val cardHref = Regex("""^https?://sinemaizle\.org/(film|dizi)/[^/?#]+/?$""")

    private fun cleanTitle(t: String): String {
        return t.replace(Regex("""\s*\(\d{4}\)"""), "")
            .replace(Regex("""\s*[-–|]\s*Sinemaizle.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(full\s*hd\s+)?izle\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private suspend fun toSearch(title: String, href: String, poster: String?): SearchResponse {
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private suspend fun parseCards(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (a in doc.select("a[href]")) {
            val href = fixUrl(a.attr("href")).substringBefore("#").substringBefore("?")
            if (!cardHref.matches(href)) continue
            val img = a.selectFirst("img") ?: continue
            if (!seen.add(href)) continue
            val title = cleanTitle(
                a.selectFirst("p")?.text()?.takeIf { it.isNotBlank() }
                    ?: img.attr("alt").ifBlank { a.attr("title") }
            )
            if (title.isBlank()) continue
            val poster = img.attr("data-src").ifBlank { img.attr("src") }
                .replace("/bg/", "/poster/")
                .ifBlank { null }
            out.add(toSearch(title, href, poster))
        }
        return out
    }

    // ------------------------------------------------------------------
    // ARAMA  (site ici arama: /arama/oneri?q=...)
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val results = ArrayList<SearchResponse>()
        try {
            val txt = app.get(
                "$mainUrl/arama/oneri?q=$q",
                headers = baseHeaders + mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "$mainUrl/film"
            ).text
            results.addAll(parseSuggest(txt))
        } catch (_: Exception) {
        }
        if (results.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/arama?q=$q", headers = baseHeaders, referer = "$mainUrl/").document
                results.addAll(parseCards(doc))
            } catch (_: Exception) {
            }
        }
        return results.distinctBy { it.url }
    }

    private suspend fun parseSuggest(txt: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val t = txt.trim()
        if (t.startsWith("{")) {
            collectItems(JSONObject(t), out)
        } else if (t.startsWith("[")) {
            collectItems(JSONArray(t), out)
        } else if (t.isNotEmpty()) {
            out.addAll(parseCards(Jsoup.parse(t, mainUrl)))
        }
        return out
    }

    private suspend fun collectItems(node: Any?, out: MutableList<SearchResponse>) {
        when (node) {
            is JSONObject -> {
                val url = node.str("url")
                val title = node.str("title")
                if (url != null && title != null) {
                    val href = fixUrl(url).substringBefore("#").substringBefore("?")
                    if (cardHref.matches(href)) {
                        val poster = node.str("poster")?.replace("/bg/", "/poster/")
                        out.add(toSearch(cleanTitle(title), href, poster))
                        return
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectItems(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectItems(node.opt(i), out)
            }
        }
    }

    // ------------------------------------------------------------------
    // TMDB (sadece icine girince)  -- org.json ile
    // ------------------------------------------------------------------
    private class EpInfo(
        val name: String?,
        val overview: String?,
        val still: String?,
        val air: String?
    )

    private suspend fun tmdbJson(url: String): JSONObject? {
        return try {
            JSONObject(app.get(url).text)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tmdbDetail(title: String, year: Int?, tv: Boolean): JSONObject? {
        if (tmdbKey.startsWith("BURAYA")) return null
        return try {
            val q = URLEncoder.encode(title, "UTF-8")
            val kind = if (tv) "tv" else "movie"
            val yearParam = if (year != null) {
                if (tv) "&first_air_date_year=$year" else "&year=$year"
            } else ""
            var id = tmdbJson("$tmdbApi/search/$kind?api_key=$tmdbKey&language=tr-TR&query=$q$yearParam")
                ?.optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            if (id == 0 && yearParam.isNotEmpty()) {
                id = tmdbJson("$tmdbApi/search/$kind?api_key=$tmdbKey&language=tr-TR&query=$q")
                    ?.optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            }
            if (id == 0) return null
            tmdbJson("$tmdbApi/$kind/$id?api_key=$tmdbKey&language=tr-TR&append_to_response=credits")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tmdbSeason(tvId: Int, season: Int): List<Pair<Int, EpInfo>> {
        val out = ArrayList<Pair<Int, EpInfo>>()
        try {
            val arr = tmdbJson("$tmdbApi/tv/$tvId/season/$season?api_key=$tmdbKey&language=tr-TR")
                ?.optJSONArray("episodes") ?: return out
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val n = e.optInt("episode_number", 0)
                if (n <= 0) continue
                out.add(n to EpInfo(e.str("name"), e.str("overview"), e.str("still_path"), e.str("air_date")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun LoadResponse.applyTmdb(d: JSONObject?) {
        if (d == null) return
        d.str("poster_path")?.let { this.posterUrl = "$tmdbImg/w500$it" }
        d.str("backdrop_path")?.let { this.backgroundPosterUrl = "$tmdbImg/w1280$it" }
        d.str("overview")?.let { this.plot = it }
        (d.str("release_date") ?: d.str("first_air_date"))?.take(4)?.toIntOrNull()?.let { this.year = it }

        val genres = ArrayList<String>()
        d.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.str("name")?.let { genres.add(it) }
        }
        if (genres.isNotEmpty()) this.tags = genres

        val actors = ArrayList<ActorData>()
        d.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 15)) {
                val c = arr.optJSONObject(i) ?: continue
                val n = c.str("name") ?: continue
                val img = c.str("profile_path")?.let { "$tmdbImg/w185$it" }
                actors.add(ActorData(Actor(n, img), roleString = c.str("character")))
            }
        }
        if (actors.isNotEmpty()) this.actors = actors
    }

    // ------------------------------------------------------------------
    // LOAD
    // ------------------------------------------------------------------
    private fun normalizeUrl(url: String): String {
        val u = url.substringBefore("#").substringBefore("?")
        val m = Regex("""^(https?://[^/]+/dizi/[^/]+)/sezon-.*$""").find(u)
        return (m?.groupValues?.get(1) ?: u).trimEnd('/')
    }

    private fun findYear(doc: Document, rawTitle: String): Int? {
        Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        Regex("""\((\d{4})\)""").find(og)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex(""""datePublished"\s*:\s*"(\d{4})""").find(doc.html())
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun collectEpisodes(text: String, slug: String, out: MutableSet<Pair<Int, Int>>) {
        val re = Regex("""/dizi/${Regex.escape(slug)}/sezon-(\d+)/bolum-(\d+)""")
        for (m in re.findAll(text.replace("\\/", "/"))) {
            val s = m.groupValues[1].toIntOrNull() ?: continue
            val e = m.groupValues[2].toIntOrNull() ?: continue
            out.add(s to e)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = normalizeUrl(url)
        val doc = app.get(base, headers = baseHeaders, referer = "$mainUrl/").document
        val isSeries = base.contains("/dizi/")

        val rawTitle = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val title = cleanTitle(rawTitle)
        val year = findYear(doc, rawTitle)
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val siteDesc = doc.selectFirst("meta[name=description]")?.attr("content")

        val tmdb = tmdbDetail(title, year, isSeries)

        if (!isSeries) {
            return newMovieLoadResponse(tmdb?.str("title") ?: title, base, TvType.Movie, base) {
                this.posterUrl = sitePoster
                this.plot = siteDesc
                this.year = year
                this.duration = tmdb?.optInt("runtime", 0)?.takeIf { it > 0 }
                applyTmdb(tmdb)
            }
        }

        val slug = base.substringAfter("/dizi/").trim('/')
        val found = linkedSetOf<Pair<Int, Int>>()
        collectEpisodes(doc.html(), slug, found)

        val tmdbSeasons = ArrayList<Int>()
        tmdb?.optJSONArray("seasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val sn = arr.optJSONObject(i)?.optInt("season_number", 0) ?: 0
                if (sn > 0) tmdbSeasons.add(sn)
            }
        }

        // sitede listelenmeyen sezonlari sezon sayfasindan dene
        val have = found.map { it.first }.toSet()
        for (sn in tmdbSeasons) {
            if (sn in have) continue
            try {
                val t = app.get("$mainUrl/dizi/$slug/sezon-$sn", headers = baseHeaders, referer = base).text
                collectEpisodes(t, slug, found)
            } catch (_: Exception) {
            }
        }

        val seasonNums = if (found.isNotEmpty()) found.map { it.first }.toSet() else tmdbSeasons.toSet()
        val epInfo = HashMap<Pair<Int, Int>, EpInfo>()
        val tvId = tmdb?.optInt("id", 0) ?: 0
        if (tvId != 0) {
            for (sn in seasonNums) {
                for ((en, info) in tmdbSeason(tvId, sn)) epInfo[sn to en] = info
            }
        }
        // sitede hic bolum bulunamazsa TMDB'den uret
        if (found.isEmpty()) epInfo.keys.forEach { found.add(it) }

        val episodes = found.sortedWith(compareBy({ it.first }, { it.second })).map { (s, e) ->
            val info = epInfo[s to e]
            newEpisode("$mainUrl/dizi/$slug/sezon-$s/bolum-$e") {
                this.name = info?.name ?: "$e. Bölüm"
                this.season = s
                this.episode = e
                this.description = info?.overview
                this.posterUrl = info?.still?.let { "$tmdbImg/w500$it" }
                addDate(info?.air)
            }
        }

        return newTvSeriesLoadResponse(tmdb?.str("name") ?: title, base, TvType.TvSeries, episodes) {
            this.posterUrl = sitePoster
            this.plot = siteDesc
            this.year = year
            applyTmdb(tmdb)
        }
    }

    // ------------------------------------------------------------------
    // LINKLER (vidmixi master m3u8 + Turkce altyazi)
    // ------------------------------------------------------------------
    private val processRegex = Regex("""https?://[^\s"'<>\\]+/process/[A-Za-z0-9+/=_\-%]+""")
    private val m3uRegex = Regex("""https?://[^\s"'<>\\]+(?:/m3u/[A-Za-z0-9+/=_\-]+|\.m3u8[^\s"'<>\\]*)""")
    private val vttRegex = Regex("""https?://[^\s"'<>\\]+?\.vtt""")

    private fun subLabel(url: String): String {
        val f = url.substringAfterLast('/').substringBefore('.').lowercase()
        return when {
            f.contains("forced") -> "Türkçe (Zorunlu)"
            f.startsWith("tur") || f.startsWith("tr") -> "Türkçe"
            f.startsWith("eng") || f.startsWith("en") -> "English"
            else -> f
        }
    }

    private suspend fun collectSubs(text: String, seen: MutableSet<String>, cb: (SubtitleFile) -> Unit) {
        for (m in vttRegex.findAll(text)) {
            val u = m.value
            if (seen.add(u)) cb(newSubtitleFile(subLabel(u), u))
        }
    }

    private fun origin(u: String): String {
        return try {
            val p = URI(u)
            "${p.scheme}://${p.host}/"
        } catch (_: Exception) {
            "$mainUrl/"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, headers = baseHeaders, referer = "$mainUrl/")
        val html = page.text.replace("\\/", "/")
        val subSeen = HashSet<String>()
        collectSubs(html, subSeen, subtitleCallback)

        val players = linkedSetOf<String>()
        processRegex.findAll(html).forEach { players.add(it.value) }
        for (f in page.document.select("iframe[src], iframe[data-src]")) {
            val s = fixUrl(f.attr("src").ifBlank { f.attr("data-src") })
            if (s.startsWith("http") && !s.contains("sinemaizle.org")) players.add(s)
        }

        var found = false

        suspend fun emit(url: String, referer: String) {
            callback(
                newExtractorLink(this.name, this.name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to ua, "Referer" to referer)
                }
            )
            found = true
        }

        for (p in players) {
            try {
                val resp = app.get(p, headers = baseHeaders, referer = "$mainUrl/")
                val body = resp.text.replace("\\/", "/")
                collectSubs(body, subSeen, subtitleCallback)
                if (body.trimStart().startsWith("#EXTM3U")) {
                    // process linki dogrudan master m3u8 donuyor (ses + goruntu birlikte)
                    emit(p, "$mainUrl/")
                } else {
                    val m = m3uRegex.find(body)?.value
                    if (m != null) emit(m, origin(p))
                }
            } catch (_: Exception) {
            }
        }

        if (!found) {
            m3uRegex.find(html)?.value?.let { emit(it, "$mainUrl/") }
        }
        return found
    }
}
