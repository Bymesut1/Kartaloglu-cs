package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AbiFilm0 : MainAPI() {
    override var mainUrl = "https://www.wfilmizle.cam"
    override var name = "AbiFilm0"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Son Eklenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page/", headers = mapOf("User-Agent" to ua)).document
        val items = doc.select(".listmovie").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun cleanTitle(t: String) =
        t.replace(Regex("\\s*izle\\s*$", RegexOption.IGNORE_CASE), "").trim()

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("div.film-ismi a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = cleanTitle(a.text())
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        )
        val year = Regex("""(\d{4})""").find(selectFirst(".fa-calendar-alt")?.parent()?.text() ?: "")
            ?.groupValues?.get(1)?.toIntOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}",
            headers = mapOf("User-Agent" to ua), referer = "$mainUrl/"
        ).document
        return doc.select(".listmovie").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/").document
        val title = cleanTitle(
            doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
        )
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("""(19|20)\d{2}""").find(
            doc.selectFirst(".fa-calendar-alt")?.parent()?.text() ?: ""
        )?.value?.toIntOrNull()
        val tags = doc.select("a[rel=category tag], .tur a").map { it.text() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/").document
        val frames = doc.select("iframe").mapNotNull { f ->
            val src = f.attr("data-src").ifBlank { f.attr("src") }
            if (src.isBlank() || src.startsWith("data:")) null else fixUrl(src)
        }.filter {
            Regex("player|embed|hdplayer|iframe", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }.distinct()

        var ok = false
        for (frame in frames) {
            if (resolvePlayer(frame, callback)) ok = true
            else if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) ok = true
        }
        return ok
    }

    // FirePlayer / hdplayersystem çözümleyici
    private suspend fun resolvePlayer(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val org = Regex("^(https?://[^/]+)").find(playerUrl)?.groupValues?.get(1) ?: return false
        val hash = Regex("[?&]data=([^&]+)").find(playerUrl)?.groupValues?.get(1)
            ?: Regex("/(?:video|v|e)/([A-Za-z0-9]+)").find(playerUrl)?.groupValues?.get(1)

        val links = linkedSetOf<String>()

        if (hash != null) {
            val txt = runCatching {
                app.post(
                    "$org/player/index.php?data=$hash&do=getVideo",
                    headers = mapOf(
                        "User-Agent" to ua,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to org
                    ),
                    referer = playerUrl,
                    data = mapOf("hash" to hash, "r" to "$mainUrl/")
                ).text
            }.getOrNull()
            if (txt != null) scan(txt, org, links)
        }

        if (links.isEmpty()) {
            val txt = runCatching {
                app.get(
                    playerUrl,
                    headers = mapOf("User-Agent" to ua, "Origin" to mainUrl),
                    referer = "$mainUrl/"
                ).text
            }.getOrNull()
            if (txt != null) scan(txt, org, links)
        }

        val url = links.firstOrNull() ?: return false
        val quality = Regex("(2160|1440|1080|720|480|360)").find(url)?.value?.toIntOrNull()
            ?: Qualities.Unknown.value

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = if (url.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            ) {
                this.referer = "$org/"
                this.quality = quality
                this.headers = mapOf("User-Agent" to ua, "Origin" to org)
            }
        )
        return true
    }

    private fun scan(raw: String, org: String, out: MutableSet<String>) {
        val txt = raw.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

        fun add(u: String) {
            val full = when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("http") -> u
                u.startsWith("/") -> org + u
                else -> return
            }
            out.add(full)
        }

        Regex(""""(?:securedLink|videoSource|hls|file)"\s*:\s*"([^"]+)"""").findAll(txt)
            .forEach { add(it.groupValues[1]) }
        Regex("""https?:[^"'\s\\<>]+\.(?:m3u8|mp4)[^"'\s\\<>]*""").findAll(txt)
            .forEach { add(it.value) }
        Regex("""https?://[^"'\s<>]+?/m3/[^"'\s\\<>]+""").findAll(txt)
            .forEach { add(it.value) }
        Regex("""["'](/m3/[^"'\s\\<>]+)["']""").findAll(txt)
            .forEach { add(it.groupValues[1]) }
        Regex("""atob\(["']([A-Za-z0-9+/=]{20,})["']\)""").findAll(txt).forEach {
            runCatching { scan(String(Base64.decode(it.groupValues[1], Base64.DEFAULT)), org, out) }
        }
    }
}
