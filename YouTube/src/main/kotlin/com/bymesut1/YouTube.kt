package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document
import java.net.URLEncoder

class YouTube : MainAPI() {

    // Ana Invidious sitesi (kart linkleri bunun üzerinden oluşur, video id'si her instance'ta aynı).
    override var mainUrl = "https://inv.nadeko.net"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Others)

    // Biri bot koruması / 500 verirse sıradakine geçilir. Ölen instance'ı çıkar, yenisini ekle.
    private val instances = listOf(
        "https://inv.nadeko.net",
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://inv.thepixora.com",
        "https://invidious.privacyredirect.com",
        "https://iv.melmac.space",
        "https://invidious.f5.si"
    )

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

    // ---------- JSON modelleri (Invidious API) ----------

    data class InvFormat(
        val url: String? = null,
        val qualityLabel: String? = null,
        val resolution: String? = null,
        val container: String? = null
    )

    data class InvVideo(
        val title: String? = null,
        val description: String? = null,
        val error: String? = null,
        val dashUrl: String? = null,
        val hlsUrl: String? = null,
        val formatStreams: List<InvFormat>? = null
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
        val path = if (isSearch) {
            "/search?q=${enc(data.removePrefix("search:"))}&page=$page"
        } else {
            data
        }
        val items = parseVideos(getListDoc(path))
        return newHomePageResponse(request.name, items, isSearch)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return parseVideos(getListDoc("/search?q=${enc(query)}"))
    }

    // ---------- Detay ----------

    override suspend fun load(url: String): LoadResponse {
        val id = videoId(url) ?: throw ErrorLoadingException("Geçersiz video adresi")
        val watch = "$mainUrl/watch?v=$id"
        var title: String? = null
        var plot: String? = null

        // 1) YouTube oEmbed: bot koruması yok, doğrudan telefondan çalışır
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

        // 2) Invidious API (başlık + açıklama)
        if (title.isNullOrBlank() || plot == null) {
            val v = fetchVideo(id)
            if (v != null) {
                if (title.isNullOrBlank() && !v.title.isNullOrBlank()) title = v.title
                plot = v.description
            }
        }

        if (title.isNullOrBlank() || isBotText(title)) title = "YouTube Videosu"
        return newMovieLoadResponse(title!!, watch, TvType.Others, id) {
            this.posterUrl = thumb(id)
            this.plot = plot
        }
    }

    // ---------- Linkler ----------
    // Sıra: 1) Invidious muxed MP4 (DASH'siz, ses+görüntü tek dosya)
    //       2) Invidious DASH (yüksek kalite)
    //       3) CloudStream'in kendi YouTube çıkarıcısı (NewPipe, doğrudan YouTube)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = videoId(data) ?: return false
        var found = false

        // --- Invidious: çalışan ilk instance'ı kullan ---
        for (base in instances) {
            val v = fetchVideoFrom(base, id) ?: continue
            var any = false

            // Muxed MP4 (DASH yok)
            v.formatStreams.orEmpty().forEach { f ->
                val u = abs(base, f.url) ?: return@forEach
                val q = qualityOf(f.qualityLabel ?: f.resolution)
                val label = f.qualityLabel ?: f.resolution ?: "MP4"
                callback(
                    newExtractorLink(
                        name,
                        "$name MP4 $label",
                        u,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = base
                        this.quality = q
                        this.headers = hdr
                    }
                )
                any = true
            }

            // HLS (canlı yayınlar için)
            val hls = abs(base, v.hlsUrl)
            if (hls != null) {
                callback(
                    newExtractorLink(
                        name,
                        "$name HLS",
                        hls,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = base
                        this.quality = Qualities.Unknown.value
                        this.headers = hdr
                    }
                )
                any = true
            }

            // DASH (otomatik kalite, 1080p+)
            val dash = abs(base, v.dashUrl)?.let {
                if (it.contains("local=")) it else it + (if (it.contains("?")) "&" else "?") + "local=true"
            } ?: "$base/api/manifest/dash/id/$id?local=true&unique_res=1"
            callback(
                newExtractorLink(
                    name,
                    "$name DASH (otomatik kalite)",
                    dash,
                    ExtractorLinkType.DASH
                ) {
                    this.referer = base
                    this.quality = Qualities.Unknown.value
                    this.headers = hdr
                }
            )
            any = true

            if (any) {
                found = true
                Log.d("YTINV", "kaynak instance: $base")
                break
            }
        }

        // --- CloudStream'in yerleşik YouTube çıkarıcısı (Invidious'tan bağımsız) ---
        try {
            val ok = loadExtractor(
                "https://www.youtube.com/watch?v=$id",
                null,
                subtitleCallback,
                callback
            )
            if (ok) found = true
        } catch (e: Exception) {
            Log.e("YTINV", "yerleşik youtube hata: $e")
        }

        return found
    }

    // ---------- Yardımcılar ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // Poster doğrudan YouTube'dan: instance'a bağlı değil
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

    private fun abs(base: String, u: String?): String? {
        if (u.isNullOrBlank()) return null
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$base$u"
            else -> "$base/$u"
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

    private suspend fun fetchVideoFrom(base: String, id: String): InvVideo? {
        return try {
            val txt = app.get(
                "$base/api/v1/videos/$id?local=true",
                headers = hdr, timeout = 15L
            ).text
            if (txt.trimStart().startsWith("<")) return null // bot sayfası / HTML
            val v = tryParseJson<InvVideo>(txt) ?: return null
            if (!v.error.isNullOrBlank()) null else v
        } catch (e: Exception) {
            Log.e("YTINV", "api hata ($base): $e")
            null
        }
    }

    private suspend fun fetchVideo(id: String): InvVideo? {
        for (base in instances) {
            fetchVideoFrom(base, id)?.let { return it }
        }
        return null
    }

    // Liste sayfaları için instance fallback'li HTML çekme
    private suspend fun getListDoc(path: String): Document? {
        var last: Document? = null
        for (base in instances) {
            try {
                val doc = app.get("$base$path", headers = hdr, timeout = 15L).document
                if (isBotPage(doc)) continue
                if (doc.select("div.video-card-row a[href^=\"/watch?v=\"]").isNotEmpty()) return doc
                last = doc
            } catch (e: Exception) {
                Log.e("YTINV", "liste hata ($base): $e")
            }
        }
        return last
    }

    private fun parseVideos(doc: Document?): List<SearchResponse> {
        if (doc == null) return emptyList()
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
