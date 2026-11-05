package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class YouTube : MainAPI() {

    // Invidious sitesi. Site kapanırsa sadece bu satırı değiştir.
    override var mainUrl = "https://inv.nadeko.net"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Others)

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val hdr = mapOf("User-Agent" to ua)

    override val mainPage = mainPageOf(
        "/feed/popular" to "Popüler",
        "/feed/trending?region=TR" to "Trendler",
        "/feed/trending?type=Music&region=TR" to "Müzik",
        "/feed/trending?type=Gaming&region=TR" to "Oyun",
        "/feed/trending?type=Movies&region=TR" to "Filmler",
        "search:film fragmanı" to "Fragmanlar",
        "search:belgesel türkçe" to "Belgeseller"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = request.data
        val isSearch = data.startsWith("search:")
        if (!isSearch && page > 1) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        val url = if (isSearch) {
            "$mainUrl/search?q=${enc(data.removePrefix("search:"))}&page=$page"
        } else {
            "$mainUrl$data"
        }
        val items = parseVideos(app.get(url, headers = hdr).document)
        return newHomePageResponse(request.name, items, isSearch)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${enc(query)}"
        return parseVideos(app.get(url, headers = hdr).document)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = videoId(url) ?: throw ErrorLoadingException("Geçersiz video adresi")
        val watch = "$mainUrl/watch?v=$id"
        var title = "YouTube"
        var plot: String? = null
        try {
            val doc = app.get(watch, headers = hdr).document
            val t = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
            if (!t.isNullOrBlank()) title = t
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        } catch (e: Exception) {
            Log.e("YTINV", "load hata: $e")
        }
        return newMovieLoadResponse(title, watch, TvType.Others, id) {
            this.posterUrl = thumb(id)
            this.plot = plot
        }
    }

    // Sadece DASH: Invidious'un hazır DASH manifestini oynatıcıya verir (otomatik kalite)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = videoId(data) ?: return false
        var dash: String? = null
        try {
            val doc = app.get("$mainUrl/watch?v=$id&quality=dash", headers = hdr).document
            val src = doc.selectFirst("source[type=\"application/dash+xml\"]")?.attr("src")
            if (!src.isNullOrBlank()) {
                dash = when {
                    src.startsWith("http") -> src
                    src.startsWith("/") -> "$mainUrl$src"
                    else -> "$mainUrl/$src"
                }
            }
        } catch (e: Exception) {
            Log.e("YTINV", "sayfa hata: $e")
        }
        if (dash == null) {
            dash = "$mainUrl/api/manifest/dash/id/$id?local=true&unique_res=1"
        }
        Log.d("YTINV", "dash=$dash")

        callback(
            newExtractorLink(
                name,
                "$name DASH (otomatik kalite)",
                dash,
                ExtractorLinkType.DASH
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = hdr
            }
        )
        return true
    }

    // ---------- Yardımcılar ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun thumb(id: String) = "$mainUrl/vi/$id/mqdefault.jpg"

    private fun videoId(url: String): String? {
        val r = Regex("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})")
        r.find(url)?.groupValues?.get(1)?.let { return it }
        return if (Regex("^[A-Za-z0-9_-]{11}$").matches(url)) url else null
    }

    private fun parseVideos(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (a in doc.select("div.video-card-row a[href^=\"/watch?v=\"]")) {
            val id = videoId(a.attr("href")) ?: continue
            val title = a.text().trim()
            if (title.isBlank() || !seen.add(id)) continue
            out.add(
                newMovieSearchResponse(title, "$mainUrl/watch?v=$id", TvType.Others) {
                    this.posterUrl = thumb(id)
                }
            )
        }
        return out
    }
}
