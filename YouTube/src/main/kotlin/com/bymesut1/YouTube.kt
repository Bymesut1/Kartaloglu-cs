package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class YouTube : MainAPI() {

    // Sadece bu Invidious sitesi kullanılır. Site değişirse sadece bu satırı değiştir.
    override var mainUrl = "https://inv.nadeko.net"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Others)

    private val browserUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val hdr = mapOf("User-Agent" to browserUa)

    override val mainPage = mainPageOf(
        "/feed/popular" to "Popüler",
        "/feed/trending?region=TR" to "Trendler",
        "/feed/trending?type=Music&region=TR" to "Müzik",
        "/feed/trending?type=Gaming&region=TR" to "Oyun",
        "/feed/trending?type=Movies&region=TR" to "Filmler",
        "search:film fragmanı" to "Fragmanlar",
        "search:belgesel türkçe" to "Belgeseller"
    )

    // ---------- Ana sayfa / arama ----------

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
        val items = parseVideos(app.get(url, headers = hdr, timeout = 20L).document)
        return newHomePageResponse(request.name, items, isSearch)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${enc(query)}"
        return parseVideos(app.get(url, headers = hdr, timeout = 20L).document)
    }

    // ---------- Detay ----------

    override suspend fun load(url: String): LoadResponse {
        val id = videoId(url) ?: throw ErrorLoadingException("Geçersiz video adresi")
        val watch = "$mainUrl/watch?v=$id"
        var title: String? = null
        var plot: String? = null

        // Başlık: YouTube oEmbed (bot koruması yok)
        try {
            val txt = app.get(
                "https://www.youtube.com/oembed?format=json&url=" +
                    enc("https://www.youtube.com/watch?v=$id"),
                headers = hdr, timeout = 10L
            ).text
            val m = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(txt)
            title = m?.groupValues?.get(1)?.let { unescape(it) }
        } catch (e: Exception) {
            Log.e("YTINV", "oembed hata: $e")
        }

        // Açıklama (bot sayfası gelirse yok sayılır)
        try {
            val doc = app.get(watch, headers = hdr, timeout = 15L).document
            if (!isBotPage(doc)) {
                if (title.isNullOrBlank()) {
                    title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                        ?: doc.selectFirst("h1")?.text()
                }
                plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            }
        } catch (e: Exception) {
            Log.e("YTINV", "load hata: $e")
        }

        if (title.isNullOrBlank() || isBotText(title)) title = "YouTube Videosu"
        return newMovieLoadResponse(title!!, watch, TvType.Others, id) {
            this.posterUrl = thumb(id)
            this.plot = plot
        }
    }

    // ---------- Linkler (sadece inv.nadeko.net) ----------
    // 1) Watch sayfasındaki <source> etiketleri (varsa)
    // 2) Hazır adresler: itag 22 (720p MP4), itag 18 (360p MP4), DASH (otomatik kalite)
    // Nadeko 500 verirse yedek olarak doğrudan YouTube çıkarıcısı kullanılır.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = videoId(data) ?: return false
        val added = HashSet<String>()
        var okCount = 0

        // Link nadeko'dan gerçekten açılıyor mu? (500 veren linkler listeye eklenmez)
        suspend fun works(url: String): Boolean {
            return try {
                val r = app.get(
                    url,
                    headers = hdr + mapOf("Range" to "bytes=0-1"),
                    referer = mainUrl,
                    timeout = 12L
                )
                Log.d("YTINV", "prob ${r.code} $url")
                r.code == 200 || r.code == 206
            } catch (e: Exception) {
                Log.e("YTINV", "prob hata $url: $e")
                false
            }
        }

        suspend fun add(label: String, url: String, type: ExtractorLinkType, q: Int) {
            if (!added.add(url)) return
            if (!works(url)) return
            callback(
                newExtractorLink(name, "$name $label", url, type) {
                    this.referer = mainUrl
                    this.quality = q
                    this.headers = hdr
                }
            )
            okCount++
        }

        // 1) Sayfadaki source etiketleri
        val uas = listOf(browserUa, "Mozilla/5.0 (compatible; CloudStream)", "curl/8.5.0")
        for (ua in uas) {
            try {
                val doc = app.get(
                    "$mainUrl/watch?v=$id&quality=dash&local=true",
                    headers = mapOf("User-Agent" to ua),
                    timeout = 15L
                ).document
                if (isBotPage(doc)) continue
                val srcs = doc.select("video source[src], source[src]")
                if (srcs.isEmpty()) continue
                for (s in srcs) {
                    val src = abs(s.attr("src")) ?: continue
                    val type = s.attr("type")
                    val label = s.attr("label").ifBlank { null }
                    when {
                        type.contains("dash") -> add("DASH (otomatik kalite)", src, ExtractorLinkType.DASH, Qualities.Unknown.value)
                        type.contains("mpegurl") || src.contains(".m3u8") -> add("HLS", src, ExtractorLinkType.M3U8, Qualities.Unknown.value)
                        else -> add("MP4 ${label ?: ""}".trim(), src, ExtractorLinkType.VIDEO, qualityOf(label))
                    }
                }
                break
            } catch (e: Exception) {
                Log.e("YTINV", "sayfa hata ($ua): $e")
            }
        }

        // 2) Hazır adresler
        add("MP4 720p", "$mainUrl/latest_version?id=$id&itag=22&local=true", ExtractorLinkType.VIDEO, 720)
        add("MP4 360p", "$mainUrl/latest_version?id=$id&itag=18&local=true", ExtractorLinkType.VIDEO, 360)
        add(
            "DASH (otomatik kalite)",
            "$mainUrl/api/manifest/dash/id/$id?local=true&unique_res=1",
            ExtractorLinkType.DASH,
            Qualities.Unknown.value
        )

        Log.d("YTINV", "nadeko çalışan link sayısı: $okCount")

        // Nadeko hiçbir link vermediyse (sunucu 500): CloudStream'in doğrudan YouTube çıkarıcısı, VP9 hariç
        if (okCount == 0) {
            try {
                loadExtractor(
                    "https://www.youtube.com/watch?v=$id",
                    null,
                    subtitleCallback
                ) { link ->
                    if (!link.name.contains("VP9")) {
                        callback(link)
                        okCount++
                    }
                }
            } catch (e: Exception) {
                Log.e("YTINV", "yedek çıkarıcı hata: $e")
            }
        }
        return okCount > 0
    }

    // ---------- Yardımcılar ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // Poster doğrudan YouTube'dan gelir
    private fun thumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    private fun unescape(s: String): String {
        return s.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .replace("\\n", " ")
            .replace(Regex("\\\\u([0-9a-fA-F]{4})")) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
    }

    private fun isBotText(s: String?): Boolean {
        if (s == null) return false
        val t = s.lowercase()
        return t.contains("not a bot") || t.contains("checking you") ||
            t.contains("making sure") || t.contains("anubis") ||
            t.contains("just a moment") || t.contains("attention required")
    }

    private fun isBotPage(doc: Document): Boolean {
        return isBotText(doc.title()) || isBotText(doc.selectFirst("h1")?.text())
    }

    private fun abs(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            else -> "$mainUrl/$u"
        }
    }

    private fun qualityOf(label: String?): Int {
        val n = label?.let { Regex("(\\d{3,4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return n ?: Qualities.Unknown.value
    }

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
            if (title.isBlank() || isBotText(title) || !seen.add(id)) continue
            out.add(
                newMovieSearchResponse(title, "$mainUrl/watch?v=$id", TvType.Others) {
                    this.posterUrl = thumb(id)
                }
            )
        }
        return out
    }
}
