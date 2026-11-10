package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class BuzFilmizle3 : MainAPI() {
    override var mainUrl = "https://www.buzfilmizle3.com"
    override var name = "BuzFilmizle3"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenen Filmler",
        "$mainUrl/aksiyon-filmleri/" to "Aksiyon",
        "$mainUrl/aile-filmleri/" to "Aile",
        "$mainUrl/animasyon-filmleri/" to "Animasyon",
        "$mainUrl/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "$mainUrl/dram-filmleri/" to "Dram",
        "$mainUrl/fantastik-filmler/" to "Fantastik",
        "$mainUrl/gerilim-filmleri/" to "Gerilim",
        "$mainUrl/komedi-filmleri/" to "Komedi",
        "$mainUrl/korku-filmleri/" to "Korku",
        "$mainUrl/macera-filmleri/" to "Macera",
        "$mainUrl/romantik-filmler/" to "Romantik",
        "$mainUrl/savas-filmleri/" to "Savaş",
        "$mainUrl/suc-filmleri/" to "Suç",
        "$mainUrl/cizgi-filmler/" to "Çizgi Filmler"
    )

    // ---------------------------------------------------------------- Yardımcılar

    private val mediaRegex =
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*""")

    private val yearRegex = Regex("""\b(?:19|20)\d{2}\b""")

    private val badFrameHosts = listOf(
        "google", "facebook", "doubleclick", "aarems", "wargamings",
        "disqus", "youtube", "twitter", "cloudflare"
    )

    private fun cleanTitle(t: String): String =
        t.replace(
            Regex("""(?i)\s*(Full\s*HD\s*Film|Full\s*Film\s*HD|Full\s*Film|Full\s*HD|Türkçe\s*Dublaj|Türkçe\s*Altyazılı)?\s*izle\s*$"""),
            ""
        ).trim()

    private fun originOf(url: String): String =
        runCatching {
            val u = URI(url)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("$mainUrl/")

    private fun sourceName(url: String, label: String): String {
        if (label.isNotBlank()) return label
        val u = url.lowercase()
        return when {
            "matrudas" in u || "vidmoly" in u -> "Vidmoly"
            "drewimplemnt" in u -> "Bronz"
            "97bf1" in u -> "FASTM"
            else -> runCatching { URI(url).host.removePrefix("www.") }.getOrDefault("BuzFilm")
        }
    }

    // ---------------------------------------------------------------- Ana sayfa / arama

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("div.movief a") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = cleanTitle(
            a.text().trim().ifBlank { this.selectFirst("img")?.attr("alt")?.trim() ?: "" }
        )
        if (title.isBlank()) return null

        val img = this.selectFirst("img[data-src]") ?: this.selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.startsWith("http") }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select("div.moviefilm").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/", params = mapOf("s" to query), referer = "$mainUrl/").document
        return doc.select("div.moviefilm").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------- Detay

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.filmcontent img[data-src]")?.attr("data-src")
            ?: doc.selectFirst("div.filmcontent img")?.attr("src")

        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val year = yearRegex.findAll(rawTitle).lastOrNull()?.value?.toIntOrNull()
        val tags = doc.select("a[rel=category tag]").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------- Kaynaklar

    private fun extractFrames(doc: Document): List<String> {
        val out = LinkedHashSet<String>()

        doc.select("iframe").forEach { f ->
            listOf("src", "data-src", "data-lazy-src", "data-fpo-src").forEach { attr ->
                val v = f.attr(attr).trim()
                if (v.isNotBlank() && !v.startsWith("data:") && !v.startsWith("about:")) {
                    val full = fixUrl(v.replace("&amp;", "&"))
                    if (badFrameHosts.none { full.contains(it, true) }) out.add(full)
                }
            }
        }

        // Script ile yazılan / gizli kalan oynatıcı adresleri
        val html = doc.html().replace("\\/", "/").replace("&amp;", "&")
        Regex("""https?://[^\s"'<>\\]*(?:matrudas|vidmoly|drewimplemnt)[^\s"'<>\\]*""")
            .findAll(html)
            .forEach { out.add(it.value) }

        return out.toList()
    }

    private suspend fun emitM3u8(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = runCatching {
            M3u8Helper.generateM3u8(source = name, streamUrl = url, referer = referer)
        }.getOrNull()

        if (!links.isNullOrEmpty()) {
            links.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private suspend fun emitVideo(
        name: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun handleEmbed(
        url: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ): Boolean {
        val name = sourceName(url, label)
        val lower = url.lowercase()

        // Doğrudan medya adresi (FASTM m3u8 / Bronz mp4)
        if (lower.contains(".m3u8")) {
            emitM3u8(name, url, referer, callback)
            return true
        }
        if (lower.contains(".mp4")) {
            emitVideo(name, url, referer, callback)
            return true
        }

        // Hazır çıkarıcılar (Vidmoly vb.)
        val extracted = runCatching {
            loadExtractor(url, referer, subtitleCallback, callback)
        }.getOrDefault(false)
        if (extracted) return true

        if (depth > 2) return false

        // matrudas gibi sarmalayıcılar: Referer ile sayfayı açıp içinden çıkar
        val res = runCatching {
            app.get(url, referer = referer, headers = mapOf("Accept" to "*/*"))
        }.getOrNull() ?: return false

        val finalUrl = res.url
        if (finalUrl.isNotBlank() && finalUrl != url) {
            if (handleEmbed(finalUrl, label, referer, subtitleCallback, callback, depth + 1)) {
                return true
            }
        }

        var text = res.text.replace("\\/", "/").replace("&amp;", "&")
        if (res.text.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching { getAndUnpack(res.text) }.getOrNull()?.let {
                text += "\n" + it.replace("\\/", "/")
            }
        }

        val embedReferer = originOf(url)
        val medias = mediaRegex.findAll(text).map { it.value }.distinct().toList()
        if (medias.isNotEmpty()) {
            medias.forEach { m ->
                if (m.contains(".m3u8", true)) emitM3u8(name, m, embedReferer, callback)
                else emitVideo(name, m, embedReferer, callback)
            }
            return true
        }

        // İç içe iframe
        var any = false
        res.document.select("iframe").forEach { f ->
            val src = (f.attr("src").ifBlank { f.attr("data-src") }).trim()
            if (src.isNotBlank() && !src.startsWith("data:") && !src.startsWith("about:")) {
                val next = runCatching { URI(url).resolve(src).toString() }.getOrDefault(src)
                if (badFrameHosts.none { next.contains(it, true) }) {
                    if (handleEmbed(next, label, url, subtitleCallback, callback, depth + 1)) any = true
                }
            }
        }
        return any
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = data.trimEnd('/')
        val firstDoc = app.get(data, referer = "$mainUrl/").document

        // Keremiya teması: kaynaklar (Vidmoly / Bronz / FASTM) ayrı "part" sayfalarında olabilir
        val pages = LinkedHashMap<String, String>()
        pages[data] = ""
        firstDoc.select(
            ".keremiya_part a, div.keremiya_part a, .pagination a, .post-page-numbers, .sources a, .part a"
        ).forEach { a ->
            val href = fixUrlNull(a.attr("href"))
            if (href != null) {
                val h = href.trimEnd('/')
                if (h.startsWith(base) && h != base) {
                    pages.putIfAbsent(href, a.text().trim())
                }
            }
        }

        var found = false
        val seen = HashSet<String>()

        for ((pageUrl, label) in pages) {
            val doc = if (pageUrl == data) {
                firstDoc
            } else {
                runCatching { app.get(pageUrl, referer = data).document }.getOrNull() ?: continue
            }

            for (frame in extractFrames(doc)) {
                if (!seen.add(frame)) continue
                if (handleEmbed(frame, label, pageUrl, subtitleCallback, callback)) found = true
            }
        }

        return found
    }
}
