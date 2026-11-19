package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

// TMDB API anahtarını buraya yaz (DiziPod.kt vb. dosyalarında kullandığın anahtarın aynısı olur)
private const val TMDB_API_KEY = "000316508321ce461cf81e7c6815eec7"
private const val TMDB = "https://api.themoviedb.org/3"
private const val TMDB_IMG = "https://image.tmdb.org/t/p"

class Dizirella : MainAPI() {
    override var mainUrl = "https://dizirella.one"
    override var name = "Dizirella"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val epRegex = Regex("-(\\d+)-sezon-(\\d+)-bolum")
    private val epSuffixRegex = Regex("\\s+\\d+\\.\\s*Sezon\\s+\\d+\\.\\s*Bölüm.*$", RegexOption.IGNORE_CASE)

    override val mainPage = mainPageOf(
        "home_eps" to "Son Eklenen Bölümler",
        "home_series" to "Son Eklenen Diziler",
        "home_asya" to "Son Eklenen Asya Dizileri",
        "home_asya_eps" to "Son Asya Bölümleri",
        "$mainUrl/tum-diziler/" to "Tüm Diziler",
        "$mainUrl/asya-dizileri/" to "Asya Dizileri",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/dram/" to "Dram",
        "$mainUrl/tur/suc/" to "Suç",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/tur/gerilim/" to "Gerilim",
        "$mainUrl/tur/gizem/" to "Gizem",
        "$mainUrl/tur/romantik/" to "Romantik"
    )

    // ---------------------------------------------------------------- ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data

        if (data.startsWith("home_")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val doc = app.get(mainUrl).document
            val secs = doc.select("section.r-sec")
            val sec = when (data) {
                "home_eps" -> secs.getOrNull(0)
                "home_series" -> secs.getOrNull(1)
                "home_asya" -> secs.getOrNull(2)
                else -> secs.getOrNull(3)
            }
            val items = if (sec != null) parseCards(sec) else emptyList()
            return newHomePageResponse(request.name, items, false)
        }

        val url = if (page <= 1) data else data + "page/$page/"
        val items = try {
            parseCards(app.get(url).document)
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private fun parseCards(root: Element): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        root.select("a.r-card").forEach { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@forEach
            val url = fixUrl(href)
            val title = a.selectFirst(".r-cap-t")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.trim()
                ?: return@forEach
            val poster = a.selectFirst("img")?.attr("src")
            if (seen.add(url)) {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }

        root.select("div.r-epcard").forEach { c ->
            val a = c.selectFirst("a.r-still") ?: return@forEach
            val href = a.attr("href")
            if (href.isBlank()) return@forEach
            val url = fixUrl(href)
            val t = c.selectFirst(".r-cap-t")?.text()?.trim() ?: return@forEach
            val ep = c.selectFirst(".r-cap-ep")?.text()?.trim().orEmpty()
            val poster = a.selectFirst("img")?.attr("src")
            if (seen.add(url)) {
                out.add(newTvSeriesSearchResponse("$t $ep".trim(), url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }
        return out
    }

    // ---------------------------------------------------------------- ARAMA (bölümleri tek diziye indirir)
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q").document

        var raw: List<SearchResponse> = parseCards(doc)
        if (raw.isEmpty()) {
            val tmp = ArrayList<SearchResponse>()
            val seenUrl = HashSet<String>()
            doc.select("a[href*=/dizi/]").forEach { a ->
                val url = fixUrl(a.attr("href"))
                val title = a.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.trim()
                    ?: a.text().trim()
                if (title.isBlank() || !seenUrl.add(url)) return@forEach
                val poster = a.selectFirst("img")?.attr("src")
                tmp.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
            raw = tmp
        }

        // "Gibi 6. Sezon 13. Bölüm" -> "Gibi" ; aynı diziden tek sonuç
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (r in raw) {
            val cleanName = r.name.replace(epSuffixRegex, "").trim()
            if (cleanName.isBlank() || !seen.add(cleanName.lowercase())) continue
            out.add(newTvSeriesSearchResponse(cleanName, r.url, TvType.TvSeries) {
                this.posterUrl = r.posterUrl
            })
        }
        return out
    }

    // ---------------------------------------------------------------- DETAY (TMDB burada devreye girer)
    override suspend fun load(url: String): LoadResponse? {
        var seriesUrl = fixUrl(url)
        var doc = app.get(seriesUrl).document
        var gridDoc: Document? = null

        // Bölüm linkine tıklanmışsa diziye yönlendir
        if (!seriesUrl.contains("/dizi/")) {
            val link = doc.select("nav.r-yol a[href*=/dizi/]").firstOrNull()?.attr("href") ?: return null
            gridDoc = doc
            seriesUrl = fixUrl(link)
            doc = app.get(seriesUrl).document
        }

        val rawTitle = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val title = cleanTitle(rawTitle)
        val siteYear = doc.selectFirst("a.r-chip-yil")?.text()?.trim()?.toIntOrNull()
        val sitePoster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val sitePlot = doc.selectFirst("meta[name=description]")?.attr("content")

        // Bölüm listesi (siteden)
        if (gridDoc == null) gridDoc = findGridDoc(doc, seriesUrl)
        val rawEps = ArrayList<Triple<String, Int, Int>>() // url, sezon, bölüm
        gridDoc?.select("a.r-epk")?.forEach { a ->
            val href = a.attr("href")
            val m = epRegex.find(href) ?: return@forEach
            val s = m.groupValues[1].toIntOrNull() ?: return@forEach
            val e = m.groupValues[2].toIntOrNull() ?: return@forEach
            rawEps.add(Triple(fixUrl(href), s, e))
        }

        // TMDB
        val tmdbId = try { tmdbSearchId(title, siteYear) } catch (e: Exception) { null }
        var detail: DzTmdbDetail? = null
        if (tmdbId != null) {
            detail = try {
                var d = app.get("$TMDB/tv/$tmdbId?api_key=$TMDB_API_KEY&language=tr-TR&append_to_response=credits")
                    .parsedSafe<DzTmdbDetail>()
                if (d != null && d.overview.isNullOrBlank()) {
                    val en = app.get("$TMDB/tv/$tmdbId?api_key=$TMDB_API_KEY&language=en-US")
                        .parsedSafe<DzTmdbDetail>()
                    d = d.copy(overview = en?.overview)
                }
                d
            } catch (e: Exception) {
                null
            }
        }

        val seasonMap = HashMap<Int, Map<Int, DzTmdbEpisode>>()
        if (tmdbId != null) {
            for (s in rawEps.map { it.second }.distinct()) {
                seasonMap[s] = try { fetchSeason(tmdbId, s) } catch (e: Exception) { emptyMap() }
            }
        }

        val episodes = rawEps
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { (href, s, e) ->
                val tm = seasonMap[s]?.get(e)
                newEpisode(href) {
                    this.season = s
                    this.episode = e
                    this.name = tm?.name?.takeIf { it.isNotBlank() } ?: "$e. Bölüm"
                    this.posterUrl = tm?.still_path?.let { "$TMDB_IMG/w780$it" }
                    this.description = tm?.overview?.takeIf { it.isNotBlank() }
                    this.addDate(tm?.air_date)
                }
            }

        val poster = detail?.poster_path?.let { "$TMDB_IMG/w500$it" } ?: sitePoster
        val backdrop = detail?.backdrop_path?.let { "$TMDB_IMG/w1280$it" }
        val plot = detail?.overview?.takeIf { it.isNotBlank() } ?: sitePlot
        val year = detail?.first_air_date?.take(4)?.toIntOrNull() ?: siteYear
        val tags = detail?.genres?.mapNotNull { it.name }
        val actorList = detail?.credits?.cast?.take(15)?.mapNotNull { c ->
            val n = c.name ?: return@mapNotNull null
            ActorData(Actor(n, c.profile_path?.let { "$TMDB_IMG/w185$it" }))
        }

        return newTvSeriesLoadResponse(
            detail?.name?.takeIf { it.isNotBlank() } ?: title,
            seriesUrl,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            if (!tags.isNullOrEmpty()) this.tags = tags
            if (!actorList.isNullOrEmpty()) this.actors = actorList
        }
    }

    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\s+-\\s+Dizirella.*$"), "")
            .replace(Regex("(?i)\\s+izle.*$"), "")
            .trim()

    private suspend fun findGridDoc(doc: Document, seriesUrl: String): Document? {
        if (doc.select("a.r-epk").isNotEmpty()) return doc
        val first = doc.select("a[href]").map { it.attr("href") }
            .firstOrNull { epRegex.containsMatchIn(it) }
        if (first != null) {
            return try { app.get(fixUrl(first)).document } catch (e: Exception) { null }
        }
        val slug = seriesUrl.trimEnd('/').substringAfterLast('/')
        return try { app.get("$mainUrl/$slug-1-sezon-1-bolum/").document } catch (e: Exception) { null }
    }

    // ---------------------------------------------------------------- TMDB yardımcıları
    private suspend fun tmdbSearchId(title: String, year: Int?): Int? {
        if (TMDB_API_KEY.startsWith("BURAYA")) return null
        val q = URLEncoder.encode(title, "UTF-8")
        if (year != null) {
            val r = app.get("$TMDB/search/tv?api_key=$TMDB_API_KEY&language=tr-TR&query=$q&first_air_date_year=$year")
                .parsedSafe<DzTmdbSearch>()
            r?.results?.firstOrNull()?.id?.let { return it }
        }
        return app.get("$TMDB/search/tv?api_key=$TMDB_API_KEY&language=tr-TR&query=$q")
            .parsedSafe<DzTmdbSearch>()?.results?.firstOrNull()?.id
    }

    private suspend fun fetchSeason(id: Int, season: Int): Map<Int, DzTmdbEpisode> {
        val tr = app.get("$TMDB/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=tr-TR")
            .parsedSafe<DzTmdbSeason>()?.episodes.orEmpty()
        val map = LinkedHashMap<Int, DzTmdbEpisode>()
        tr.forEach { e -> e.episode_number?.let { map[it] = e } }

        if (map.values.any { it.overview.isNullOrBlank() }) {
            val en = app.get("$TMDB/tv/$id/season/$season?api_key=$TMDB_API_KEY&language=en-US")
                .parsedSafe<DzTmdbSeason>()?.episodes.orEmpty()
            en.forEach { e ->
                val n = e.episode_number ?: return@forEach
                val cur = map[n]
                if (cur == null) {
                    map[n] = e
                } else if (cur.overview.isNullOrBlank()) {
                    map[n] = cur.copy(overview = e.overview, still_path = cur.still_path ?: e.still_path)
                }
            }
        }
        return map
    }

    // ---------------------------------------------------------------- ALTYAZI
    private fun subLabel(url: String): String {
        val m = Regex("[_/.\\-](tr|en|de|fr|es|it|ar|ru|pt|ja|ko)[_.\\-]", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase()
        return when (m) {
            "tr" -> "Türkçe"
            "en" -> "English"
            "de" -> "Deutsch"
            "fr" -> "Français"
            "es" -> "Español"
            "ar" -> "العربية"
            "ru" -> "Русский"
            null -> "Altyazı"
            else -> m.uppercase()
        }
    }

    private fun resolveUrl(u: String, base: String): String {
        val origin = Regex("^(https?://[^/]+)").find(base)?.value ?: base
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> origin + u
            else -> base.substringBeforeLast('/') + "/" + u
        }
    }

    // Embed sayfasında ve m3u8 master içinde .vtt/.srt arar
    private suspend fun findSubtitles(
        body: String,
        origin: String,
        m3u8Url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subs = LinkedHashSet<String>()
        val absSub = Regex("""https?://[^"'\s\\<>]+\.(?:vtt|srt)[^"'\s\\<>]*""")
        val relSub = Regex("""["'](/[^"'\s]+\.(?:vtt|srt)[^"']*)["']""")

        absSub.findAll(body).forEach { subs.add(it.value) }
        relSub.findAll(body).forEach { subs.add(origin + it.groupValues[1]) }

        // m3u8 master içinden
        try {
            val master = app.get(m3u8Url, referer = referer).text
            Regex("""[^\s"',]+\.(?:vtt|srt)[^\s"',]*""").findAll(master).forEach {
                subs.add(resolveUrl(it.value, m3u8Url))
            }
            master.lines().filter { it.contains("SUBTITLES") }.forEach { line ->
                val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: return@forEach
                val full = resolveUrl(uri, m3u8Url)
                if (uri.contains(".vtt") || uri.contains(".srt")) {
                    subs.add(full)
                } else if (uri.contains(".m3u8")) {
                    try {
                        val inner = app.get(full, referer = referer).text
                        inner.lines().firstOrNull { !it.startsWith("#") && it.contains(".vtt") }
                            ?.let { subs.add(resolveUrl(it.trim(), full)) }
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }

        for (s in subs) {
            subtitleCallback(
                newSubtitleFile(subLabel(s), s) {
                    this.headers = mapOf("Referer" to referer)
                }
            )
        }
    }

    // ---------------------------------------------------------------- LİNKLER (tek kaynak: Dizirella)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        val iframes = doc.select("iframe")
            .map { it.attr("src").ifBlank { it.attr("data-src") } }
            .filter { it.isNotBlank() && !it.contains("youtube") }
            .map { fixUrl(it) }
            .distinct()

        val absM3u8 = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")
        val relM3u8 = Regex("""["'](/[^"'\s]+\.m3u8[^"']*)["']""")
        val variantRegex = Regex("/pl/(avc1|mp4a)/")

        for (src in iframes) {
            try {
                val origin = Regex("^(https?://[^/]+)").find(src)?.value ?: src
                val body = app.get(src, referer = data).text.replace("\\/", "/")

                val urls = LinkedHashSet<String>()
                absM3u8.findAll(body).forEach { urls.add(it.value) }
                relM3u8.findAll(body).forEach { urls.add(origin + it.groupValues[1]) }
                if (urls.isEmpty()) continue

                // Tek link seç: önce "master", sonra varyant olmayan, yoksa ilk
                val chosen = urls.firstOrNull { it.contains("master") }
                    ?: urls.firstOrNull { !variantRegex.containsMatchIn(it) }
                    ?: urls.first()

                // video.twimg.com yabancı referer'a 403 veriyor -> referer boş
                val ref = if (chosen.contains("twimg.com")) "" else "$origin/"

                callback(
                    newExtractorLink(name, name, chosen, ExtractorLinkType.M3U8) {
                        this.referer = ref
                        this.quality = Qualities.Unknown.value
                    }
                )

                // Altyazılar (iki kaynakta da)
                try {
                    findSubtitles(body, origin, chosen, "$origin/", subtitleCallback)
                } catch (e: Exception) {
                }
                return true
            } catch (e: Exception) {
                // sıradaki iframe'e geç
            }
        }

        // m3u8 bulunamadıysa iframe'i extractor'lara bırak
        var found = false
        for (src in iframes) {
            try {
                if (loadExtractor(src, data, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
            }
        }
        return found
    }
}

// ---------------------------------------------------------------- TMDB veri sınıfları
// Alan adları TMDB JSON'u ile birebir aynı (annotation gerekmesin diye)
data class DzTmdbSearch(
    val results: List<DzTmdbItem>? = null
)

data class DzTmdbItem(
    val id: Int? = null,
    val name: String? = null
)

data class DzTmdbGenre(
    val name: String? = null
)

data class DzTmdbCast(
    val name: String? = null,
    val profile_path: String? = null
)

data class DzTmdbCredits(
    val cast: List<DzTmdbCast>? = null
)

data class DzTmdbDetail(
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val genres: List<DzTmdbGenre>? = null,
    val credits: DzTmdbCredits? = null
)

data class DzTmdbEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val air_date: String? = null
)

data class DzTmdbSeason(
    val episodes: List<DzTmdbEpisode>? = null
)
