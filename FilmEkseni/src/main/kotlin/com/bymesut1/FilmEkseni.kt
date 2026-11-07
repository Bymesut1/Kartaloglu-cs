package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class FilmEkseni : MainAPI() {
    override var mainUrl = "https://filmekseni.vip"
    override var name = "FilmEkseni"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/kategori/tavsiye-filmler/" to "Tavsiye Filmler",
        "$mainUrl/kategori/netflix-yapimlari/" to "Netflix",
        "$mainUrl/kategori/marvel-yapimlari/" to "Marvel",
        "$mainUrl/kategori/dc-yapimlari/" to "DC",
        "$mainUrl/tur/aksiyon-filmleri/" to "Aksiyon",
        "$mainUrl/tur/komedi-filmleri/" to "Komedi",
        "$mainUrl/tur/korku-filmleri/" to "Korku",
        "$mainUrl/tur/gerilim-filmleri/" to "Gerilim",
        "$mainUrl/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "$mainUrl/tur/macera-filmleri/" to "Macera",
        "$mainUrl/tur/aile-filmleri/" to "Aile",
        "$mainUrl/ulke/yerli-film-izle/" to "Yerli Filmler"
    )

    private val skipPaths = listOf(
        "/tur/", "/kategori/", "/ulke/", "/diziler", "/api/", "/load-more", "/yil/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, referer = mainUrl).document
        val items = doc.select("a[href]:has(img)")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (skipPaths.any { href.contains(it) }) return null
        if (href.trimEnd('/') == mainUrl) return null

        val img = selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { attr("title") }.trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        )

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = app.get(
            "$mainUrl/api/search?q=$query",
            referer = mainUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val arr: JSONArray = try {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else {
                val o = JSONObject(trimmed)
                o.optJSONArray("results") ?: o.optJSONArray("data")
                ?: o.optJSONArray("items") ?: JSONArray()
            }
        } catch (e: Exception) {
            JSONArray()
        }

        val list = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { o.optString("name") }
            val link = o.optString("url").ifBlank { o.optString("link") }
                .ifBlank { o.optString("slug") }
            if (title.isBlank() || link.isBlank()) continue
            val href = if (link.startsWith("http")) link else fixUrl(link)
            val poster = listOf("posterUrl", "poster_url", "poster", "image", "img")
                .map { o.optString(it) }.firstOrNull { it.isNotBlank() }
            val isSeries = href.contains("/dizi/") ||
                    o.optString("type").contains("dizi", true)
            list += if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster)
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img")?.attr("src")
        )
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = doc.select("a[href*='/tur/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(doc.select("a[href*='/yil/'], span").text())?.value?.toIntOrNull()
        val actors = doc.select("span.text-accent span:last-child")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val epEls = doc.select("a[href*='/sezon-'][href*='/bolum-']")
        val isSeries = url.contains("/dizi/") || epEls.isNotEmpty()

        return if (isSeries) {
            val episodes = epEls.mapNotNull { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val season = Regex("/sezon-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                val ep = Regex("/bolum-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = el.text().trim().ifBlank { "Bölüm $ep" }
                    this.season = season
                    this.episode = ep
                }
            }.distinctBy { it.data }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                addActors(actors)
            }
        }
    }

    private fun unescape(input: String): String {
        var s = input
        s = Regex("""\\x([0-9a-fA-F]{2})""").replace(s) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        s = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, referer = mainUrl).text
        val raw = Regex("""videoPlayerData\(JSON\.parse\('([^']+)'\)""")
            .find(html)?.groupValues?.get(1)

        val urls = LinkedHashSet<String>()
        if (raw != null) {
            val json = unescape(raw)
            Regex(""""(?:iframeSrc|embedUrlWithLang|embedUrl|videoLink)"\s*:\s*"([^"]+)"""")
                .findAll(json).forEach { urls += fixUrl(it.groupValues[1].replace("\\/", "/")) }
        }
        if (urls.isEmpty()) {
            app.get(data, referer = mainUrl).document.select("iframe[src]")
                .forEach { urls += fixUrl(it.attr("src")) }
        }

        urls.forEach { loadExtractor(it, mainUrl, subtitleCallback, callback) }
        return urls.isNotEmpty()
    }
}
