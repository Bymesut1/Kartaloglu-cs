package com.bymesut1

import com.lagradost.cloudstream3.*
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

        val imgPoster = doc.select("img")
            .firstOrNull { it.attr("alt").trim().equals(title, true) }
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val poster = fixUrlNull(
            imgPoster?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
        )

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = Regex("""yapımı\s+(.+?)\s+türündeki""")
            .find(plot ?: "")?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
        val year = Regex("""\((19|20)\d{2}\)""")
            .find(plot ?: "")?.value?.trim('(', ')')?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(doc.select("h1, h2, time").text())
                ?.value?.toIntOrNull()

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
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.plot = plot
                this.tags = tags
                this.year = year
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

    private fun isYoutube(u: String) =
        u.contains("youtube.com", true) || u.contains("youtu.be", true)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = unescape(app.get(data, referer = mainUrl).text)

        // eksenload player id'leri: https://eksenload.top/eplayer/<id>
        val eksenIds = LinkedHashSet<String>()
        Regex("""eksenload\.top/eplayer/([A-Za-z0-9]+)""")
            .findAll(text).forEach { eksenIds += it.groupValues[1] }

        // JSON içindeki diğer embed linkleri (YouTube hariç)
        val others = LinkedHashSet<String>()
        Regex(""""(?:iframeSrc|embedUrlWithLang|embedUrl|videoLink)"\s*:\s*"([^"]+)"""")
            .findAll(text).forEach {
                val u = fixUrl(it.groupValues[1])
                if (!isYoutube(u) && !u.contains("eksenload.top")) others += u
            }
        Regex("""https?://vidmoly\.[a-z]+/[^"'\s<>\\]+""")
            .findAll(text).forEach { others += it.value }

        var found = false

        eksenIds.forEach { id ->
            EksenLoadExtractor().getUrl(
                "https://eksenload.top/eplayer/$id", data, subtitleCallback, callback
            )
            found = true
        }

        others.forEach { u ->
            loadExtractor(u, mainUrl, subtitleCallback, callback)
            found = true
        }

        return found
    }
}
