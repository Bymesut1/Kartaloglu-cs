package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DiziMom : MainAPI() {
    override var mainUrl = "https://ddizimom.net"
    override var name = "DiziMom"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val vpnStatus = VPNStatus.None

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmler?sirala=yeni" to "Yeni Eklenen Filmler",
        "$mainUrl/diziler?sirala=yeni" to "Yeni Eklenen Diziler",
        "$mainUrl/filmler" to "Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val address = if (page <= 1) request.data else request.data.trimEnd('/') + "?page=$page"
        val document = getDocument(address)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val results = parseCatalog(document, address)
        val next = page < 20 && results.isNotEmpty()
        return newHomePageResponse(request.name, results, hasNext = next)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.length < 2) return emptyList()

        val encoded = URLEncoder.encode(value, "UTF-8")
        val apiUrl = "$mainUrl/ara/oneri?q=$encoded"

        val response = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/",
                ),
                referer = "$mainUrl/",
                timeout = 15000,
                allowRedirects = true,
            )
        }.getOrNull()

        if (response == null || !response.isSuccessful) {
            Log.e(name, "Arama API başarısız: ${response?.code}")
            return emptyList()
        }

        val json = runCatching { org.json.JSONObject(response.text) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        json.optJSONArray("movies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val poster = item.optString("poster").replace("\\/", "/").takeIf { it.isNotBlank() }
                results.add(newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster })
            }
        }

        json.optJSONArray("series")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val poster = item.optString("poster").replace("\\/", "/").takeIf { it.isNotBlank() }
                results.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster })
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url) ?: return null
        val title = pageTitle(document, url) ?: return null
        val poster = pagePoster(document, url)
        val plot = pagePlot(document)
        val year = pageYear(document, title)
        val genres = pageGenres(document)
        val rating = pageRating(document)
        val actors = pageActors(document)

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                rating?.let { score = Score.from10(it) }
                if (actors.isNotEmpty()) addActors(actors.map { Actor(it) })
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonLinks: List<Element> = document.select("div.flex.flex-wrap.gap-2 a[href*='?sezon=']").toList()
        if (seasonLinks.isNotEmpty()) {
            for (seasonLink: Element in seasonLinks) {
                val href: String = seasonLink.attr("href").trim()
                if (href.isBlank()) continue
                val seasonUrl = absolute(href, url).substringBefore("#")
                val seasonNum = Regex("""sezon=(\d+)""").find(seasonUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val seasonDoc = getDocument(seasonUrl) ?: continue
                episodes.addAll(parseEpisodes(seasonDoc, seasonUrl, seasonNum ?: 1))
            }
        } else {
            episodes.addAll(parseEpisodes(document, url, 1))
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            rating?.let { score = Score.from10(it) }
            if (actors.isNotEmpty()) addActors(actors.map { Actor(it) })
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String, defaultSeason: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val selectedEpisodeLinks: List<Element> =
            document.select("div.grid.grid-cols-2.gap-4 a[href*='/sezon-'][href*='/bolum-']").toList()
        val episodeLinks: List<Element> = if (selectedEpisodeLinks.isNotEmpty()) selectedEpisodeLinks
        else document.select("a[href*='/sezon-'][href*='/bolum-']").toList()

        for (link: Element in episodeLinks) {
            val href: String = link.attr("href").trim()
            if (href.isBlank()) continue
            val fullUrl = absolute(href, baseUrl)
            val match = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(fullUrl) ?: continue
            val seasonNum = match.groupValues[1].toIntOrNull() ?: defaultSeason
            val episodeNum = match.groupValues[2].toIntOrNull() ?: continue
            val episodeName: String = link.selectFirst("p.text-sm.font-semibold")?.text()?.trim()
                ?: link.text().trim().ifBlank { "Bölüm $episodeNum" }
            val episodePoster = imageFrom(link, baseUrl)
            episodes.add(newEpisode(fullUrl) {
                this.name = episodeName
                this.season = seasonNum
                this.episode = episodeNum
                this.posterUrl = episodePoster
            })
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sourceUrl = absolute(data, mainUrl)
        if (sourceUrl.isBlank()) return false
        Log.d(name, "loadLinks başladı: $sourceUrl")
        var found = false
        val addedSubs = mutableSetOf<String>()

        suspend fun emitLink(link: String, referer: String, label: String = "") {
            if (link.isBlank()) return
            val clean = link
                .replace("&amp;", "&").replace("\\u0026", "&")
                .replace("\\u003d", "=").replace("\\u003f", "?")
                .replace("\\/", "/").replace("\\u002F", "/")
            val isStreamPhp = clean.contains("/api/stream.php", true)
            val isDirectMedia = clean.contains(".m3u8", true) || clean.contains(".mp4", true) || clean.contains(".mpd", true)
            if (!isStreamPhp && !isDirectMedia) return
            val type = if (isStreamPhp || clean.contains(".m3u8", true)) ExtractorLinkType.M3U8
                       else if (clean.contains(".mpd", true)) ExtractorLinkType.DASH
                       else ExtractorLinkType.VIDEO
            callback.invoke(newExtractorLink(source = name, name = name, url = clean, type = type) {
                this.referer = referer
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Origin" to "https://ddizimom.net")
            })
            found = true
            Log.d(name, "★ Link eklendi ($label): $clean")
            if (isStreamPhp) {
                fetchSubtitlesFromStream(clean, referer, sourceUrl, subtitleCallback, addedSubs)
            }
        }

        val document = getDocument(sourceUrl) ?: return false
        val html = document.html().replace("\\/", "/")
        Regex("""https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""", RegexOption.IGNORE_CASE).findAll(html).forEach { emitLink(it.value, sourceUrl, "statik") }
        Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE).findAll(html).forEach { emitLink(it.value, sourceUrl, "statik-media") }

        if (!found) {
            val pvSlug: String? = document.selectFirst("[data-pv]")?.attr("data-pv")?.trim()
            val coreJsRaw: String? = document.select("script[src]").toList().firstOrNull { el: Element ->
                val src: String = el.attr("src")
                src.contains("/e/c.js") || src.contains("/assets/js/core.js")
            }?.attr("src")
            val base = when {
                !coreJsRaw.isNullOrBlank() -> absolute(coreJsRaw, sourceUrl).replace(Regex("/(?:e/c|assets/js/core)\\.js.*$"), "")
                else -> "https://pilavyerplay.top"
            }
            if (!pvSlug.isNullOrBlank()) {
                val sPhpUrl = "$base/assets/js/s.php?s=" + URLEncoder.encode(pvSlug, "UTF-8")
                val sPhpDoc = getDocument(sPhpUrl, sourceUrl)
                if (sPhpDoc != null) {
                    val sPhpHtml = sPhpDoc.html().replace("\\/", "/")
                    Regex("""https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""", RegexOption.IGNORE_CASE).findAll(sPhpHtml).forEach { emitLink(it.value, sPhpUrl, "s.php") }
                    Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE).findAll(sPhpHtml).forEach { emitLink(it.value, sPhpUrl, "s.php-media") }
                    val iframes: List<Element> = sPhpDoc.select("iframe[src], iframe[data-src]").toList()
                    for (iframe: Element in iframes) {
                        val src: String = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                        if (src.isBlank()) continue
                        val innerUrl = absolute(src, sPhpUrl)
                        if (innerUrl.contains("/api/stream.php", true)) emitLink(innerUrl, sPhpUrl, "s.php-iframe")
                    }
                    extractSubtitlesFromHtml(sPhpHtml, sPhpUrl, sourceUrl, subtitleCallback, addedSubs)
                }
            }
        }
        return found
    }

    private suspend fun fetchSubtitlesFromStream(
        streamUrl: String,
        referer: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        addedSubs: MutableSet<String>,
    ) {
        val resp = runCatching {
            app.get(streamUrl, headers = headers + mapOf("Referer" to referer), referer = referer, timeout = 10000)
        }.getOrNull() ?: return
        if (!resp.isSuccessful) return

        val m3u8 = resp.text.replace("\\/", "/")
        Regex("""#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*""", RegexOption.IGNORE_CASE).findAll(m3u8).forEach { match ->
            val line = match.value
            val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
            val label = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: Regex("""LANGUAGE="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
            if (!uri.isNullOrBlank()) {
                val fullSubUrl = absolute(uri, streamUrl)
                if (addedSubs.add(fullSubUrl)) {
                    runCatching {
                        subtitleCallback.invoke(SubtitleFile(url = fullSubUrl, lang = label?.let { mapLang(it) } ?: "Türkçe"))
                        Log.d(name, "★ Altyazı eklendi (HLS): $fullSubUrl")
                    }
                }
            }
        }
    }

    // __PLAYER__ config'inden altyazıları çıkar
    private suspend fun parsePlayerConfig(
        html: String,
        baseUrl: String,
        videoToken: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        addedSubs: MutableSet<String>,
    ) {
        val decoded = html.replace("\\/", "/").replace("\\\"", "\"")

        val configPatterns = listOf(
            Regex("""window\.__PLAYER__\s*=\s*(\{[\s\S]*?\})\s*;"""),
            Regex("""__PLAYER__\s*=\s*(\{[\s\S]*?\})\s*[,;]"""),
            Regex("""var\s+__PLAYER__\s*=\s*(\{[\s\S]*?\})\s*[,;]"""),
        )

        var configJson: String? = null
        for (p in configPatterns) {
            val m = p.find(decoded)
            if (m != null) {
                configJson = m.groupValues[1]
                break
            }
        }

        if (configJson.isNullOrBlank()) {
            Log.d(name, "parsePlayerConfig: __PLAYER__ bulunamadı")
            return
        }

        Log.d(name, "parsePlayerConfig: __PLAYER__ bulundu (${configJson.length} char): ${configJson.take(800)}")

        val json = runCatching { org.json.JSONObject(configJson) }.getOrNull() ?: return

        val streamUrl = json.optString("stream").replace("\\/", "/")
        val vParam = Regex("""[?&]v=([^&]+)""").find(streamUrl)?.groupValues?.getOrNull(1)
        val streamTokenFromConfig = Regex("""[?&]token=([^&]+)""").find(streamUrl)?.groupValues?.getOrNull(1)
        val effectiveToken = streamTokenFromConfig ?: videoToken

        Log.d(name, "parsePlayerConfig: v=$vParam, token=${effectiveToken?.take(30)}...")

        val subsArr = json.optJSONArray("subs") ?: run {
            Log.d(name, "parsePlayerConfig: subs dizisi yok")
            return
        }

        Log.d(name, "parsePlayerConfig: ${subsArr.length()} altyazı bulundu")

        for (i in 0 until subsArr.length()) {
            val sub = subsArr.optJSONObject(i) ?: continue
            val sid = sub.optString("sid")
            val label = sub.optString("label").ifBlank { "Altyazı ${i + 1}" }
            val lang = sub.optString("lang").ifBlank { "tr" }
            val src = sub.optString("src").replace("\\/", "/")

            Log.d(name, "  sub[$i]: sid=$sid, label=$label, lang=$lang, src=${src.take(300)}")

            if (src.isBlank()) continue

            val fullUrl = absolute(src, baseUrl)

            if (!addedSubs.add(fullUrl)) continue

            val resp = runCatching {
                app.get(
                    fullUrl,
                    headers = headers + mapOf(
                        "Referer" to baseUrl,
                        "Origin" to "https://pilavyerplay.top",
                        "Accept" to "text/vtt, application/x-subrip, text/plain, */*",
                    ),
                    referer = baseUrl,
                    timeout = 10000,
                )
            }.getOrNull()

            if (resp == null) {
                Log.d(name, "  sub[$i] bağlantı hatası")
                continue
            }
            if (!resp.isSuccessful) {
                Log.d(name, "  sub[$i] HTTP ${resp.code}")

                if (resp.code == 400) {
                    val retryUrls = mutableListOf<String>()
                    if (!fullUrl.contains("sid=", true) && sid.isNotBlank()) {
                        retryUrls.add("$fullUrl${if (fullUrl.contains("?")) "&" else "?"}sid=$sid")
                    }
                    if (!fullUrl.contains("v=", true) && !vParam.isNullOrBlank()) {
                        retryUrls.add("$fullUrl${if (fullUrl.contains("?")) "&" else "?"}v=" + URLEncoder.encode(vParam, "UTF-8"))
                    }
                    if (!fullUrl.contains("token=", true) && !effectiveToken.isNullOrBlank()) {
                        retryUrls.add("$fullUrl${if (fullUrl.contains("?")) "&" else "?"}token=" + URLEncoder.encode(effectiveToken, "UTF-8"))
                    }
                    if (sid.isNotBlank() && !vParam.isNullOrBlank() && !effectiveToken.isNullOrBlank()) {
                        retryUrls.add("$fullUrl${if (fullUrl.contains("?")) "&" else "?"}sid=$sid&v=" + URLEncoder.encode(vParam, "UTF-8") + "&token=" + URLEncoder.encode(effectiveToken, "UTF-8"))
                    }

                    for (retryUrl in retryUrls) {
                        Log.d(name, "  sub[$i] retry: $retryUrl")
                        val retryResp = runCatching {
                            app.get(retryUrl, headers = headers + mapOf("Referer" to baseUrl, "Origin" to "https://pilavyerplay.top"), referer = baseUrl, timeout = 10000)
                        }.getOrNull()
                        if (retryResp != null && retryResp.isSuccessful) {
                            val body = retryResp.text
                            if (body.contains("WEBVTT", true) || body.contains("-->")) {
                                if (addedSubs.add(retryUrl)) {
                                    subtitleCallback.invoke(SubtitleFile(url = retryUrl, lang = mapLang(lang)))
                                    Log.d(name, "★ Altyazı eklendi (config retry): $retryUrl ($label)")
                                }
                                break
                            }
                        }
                    }
                }
                continue
            }

            val body = resp.text
            Log.d(name, "  sub[$i] yanıt (${body.length} char): ${body.take(200)}")

            if (body.contains("WEBVTT", true) || body.contains("-->")) {
                subtitleCallback.invoke(SubtitleFile(url = fullUrl, lang = mapLang(lang)))
                Log.d(name, "★ Altyazı eklendi (config): $fullUrl ($label)")
            } else {
                Regex("""["']([^"']+\.(?:vtt|srt)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(body).forEach { m ->
                    val nestedUrl = absolute(m.groupValues[1].replace("\\/", "/"), fullUrl)
                    if (addedSubs.add(nestedUrl)) {
                        subtitleCallback.invoke(SubtitleFile(url = nestedUrl, lang = mapLang(lang)))
                        Log.d(name, "★ Altyazı eklendi (config nested): $nestedUrl ($label)")
                    }
                }
            }
        }
    }

    // önce __PLAYER__ config, sonra HTML fallback
    private suspend fun extractSubtitlesFromHtml(
        html: String,
        baseUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        addedSubs: MutableSet<String>,
    ) {
        val decoded = html.replace("\\/", "/").replace("\\\"", "\"").replace("&quot;", "\"").replace("&amp;", "&")
        Log.d(name, "extractSubtitlesFromHtml: HTML uzunluğu ${decoded.length}, base: $baseUrl")

        val streamTokenRegex = Regex("""stream\.php\?[^"'\s]*?token=([^"'&\s]+)""")
        val streamToken = streamTokenRegex.find(decoded)?.groupValues?.getOrNull(1)

        // ÖNCE: __PLAYER__ config'inden altyazıları çıkar
        parsePlayerConfig(decoded, baseUrl, streamToken, subtitleCallback, addedSubs)

        // Yedek: HTML'deki .vtt/.srt URL'leri
        fun tryAdd(rawUrl: String, label: String?) {
            if (rawUrl.isBlank()) return
            val clean = rawUrl.trim().replace("\\/", "/")
            if (!clean.contains(".vtt", true) && !clean.contains(".srt", true)) return
            val full = absolute(clean, baseUrl)
            if (full.isBlank() || !addedSubs.add(full)) return
            runCatching {
                subtitleCallback.invoke(SubtitleFile(url = full, lang = label?.let { mapLang(it) } ?: "Türkçe"))
                Log.d(name, "★ Altyazı eklendi (HTML .vtt): $full")
            }
        }

        Regex("""<track[^>]+>""", RegexOption.IGNORE_CASE).findAll(decoded).forEach { m ->
            val src = Regex("""src=["']([^"']+)["']""").find(m.value)?.groupValues?.getOrNull(1) ?: return@forEach
            val lang = Regex("""srclang=["']([^"']+)["']""").find(m.value)?.groupValues?.getOrNull(1)
                ?: Regex("""label=["']([^"']+)["']""").find(m.value)?.groupValues?.getOrNull(1)
            tryAdd(src, lang)
        }

        Regex("""["'`]([^"'`\s<>]+\.(?:vtt|srt)(?:\?[^"'`\s<>]*)?)["'`]""", RegexOption.IGNORE_CASE).findAll(decoded).forEach { m -> tryAdd(m.groupValues[1], null) }
    }

    private fun mapLang(code: String): String {
        val c = code.lowercase().take(2)
        return when (c) {
            "tr" -> "Türkçe"; "en" -> "English"; "de" -> "Deutsch"
            "fr" -> "Français"; "es" -> "Español"; "it" -> "Italiano"
            "ru" -> "Русский"; "ar" -> "العربية"; else -> code
        }
    }

    private suspend fun getDocument(url: String, referer: String = "$mainUrl/"): Document? {
        return try {
            val r = app.get(url, headers = headers + ("Referer" to referer), referer = referer, timeout = 15000, allowRedirects = true)
            if (r.isSuccessful) r.document else null
        } catch (e: Exception) { Log.e(name, "Sayfa hatası: $url; ${e.message}"); null }
    }

    private fun parseCatalog(document: Document, baseUrl: String): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        val links: List<Element> = document.select("a[href]").toList()
        for (link: Element in links) {
            val url = absolute(link.attr("href"), baseUrl).substringBefore("#")
            if (url.isBlank()) continue
            if (!url.contains("/film/") && !url.contains("/dizi/")) continue
            val card: Element = findCard(link)
            val poster: String? = imageFrom(link, baseUrl) ?: imageFrom(card, baseUrl)
            val title: String = listOf(
                link.attr("title"),
                link.attr("aria-label"),
                link.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
                link.selectFirst("img")?.attr("alt").orEmpty(),
                link.text(),
                card.selectFirst("h2,h3,h4,.title,.name")?.text().orEmpty(),
            ).firstOrNull { it.isNotBlank() }?.trim() ?: continue
            if (title.length < 2) continue
            val key = canonical(url)
            val item: SearchResponse = if (url.contains("/film/")) newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
                       else newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
            found.putIfAbsent(key, item)
        }
        return found.values.toList()
    }

    private fun findCard(link: Element): Element {
        var best: Element = link
        var current: Element? = link
        repeat(3) {
            val item: Element = current ?: return@repeat
            if (item.selectFirst("img") != null) best = item
            val parent: Element = item.parent() ?: return@repeat
            if (parent.select("a[href]").size > 10) return@repeat
            current = parent
            best = parent
        }
        return best
    }

    private fun imageFrom(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        val img: Element = if (element.tagName().equals("img", true)) element else (element.selectFirst("img") ?: return null)
        val src: String = img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        return if (src.isNotBlank()) absolute(src, baseUrl) else null
    }

    private fun pageTitle(document: Document, url: String): String? =
        document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().takeIf { it.isNotBlank() }
            ?: pathOf(url).substringAfterLast("/")

    private fun pagePoster(document: Document, baseUrl: String): String? {
        val og: String? = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (og != null) return absolute(og, baseUrl)
        val el: Element? = document.selectFirst(".poster img, .detail-poster img")
        return if (el != null) imageFrom(el, baseUrl) else null
    }

    private fun pagePlot(document: Document): String? =
        document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".description, .plot, .summary")?.text()?.trim()

    private fun detailValue(document: Document, label: String): String? {
        val rows: List<Element> = document.select("dl.divide-y.text-sm > div").toList()
        val row: Element = rows.firstOrNull { r: Element ->
            r.selectFirst("dt")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        } ?: return null
        return row.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun pageYear(document: Document, title: String): Int? {
        detailValue(document, "Yapım Yılı")?.let { Regex("""(19\d{2}|20\d{2})""").find(it)?.value?.toIntOrNull() }?.let { return it }
        val scripts: List<Element> = document.select("script[type=application/ld+json]").toList()
        for (script: Element in scripts) {
            Regex("\"datePublished\"\\s*:\\s*\"(\\d{4})").find(script.data())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun pageRating(document: Document): Double? {
        val raw: String = detailValue(document, "IMDB Puanı") ?: return null
        val rating = Regex("""(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)""").find(raw)?.value?.replace(',', '.')?.toDoubleOrNull()
        return rating?.takeIf { it in 0.0..10.0 }
    }

    private fun pageGenres(document: Document): List<String> {
        detailValue(document, "Tür")?.let { return it.split(',', '·').map { g -> g.trim() }.filter { it.isNotBlank() }.distinct().take(20) }
        val tags: List<Element> = document.select("a[href*='/tur/']").toList()
        return tags.map { e: Element -> e.text().trim() }.filter { it.isNotBlank() }.distinct().take(20)
    }

    private fun pageActors(document: Document): List<String> {
        val actors = LinkedHashSet<String>()
        val cards: List<Element> = document.select("#cast-grid a[href*='/oyuncu/']").toList()
        for (card: Element in cards) {
            val actorName: String? = card.selectFirst("p[title]")?.attr("title")?.trim()
                ?: card.selectFirst("p")?.text()?.trim()
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
            if (!actorName.isNullOrBlank()) actors.add(actorName)
        }
        return actors.take(50).toList()
    }

    private fun absolute(raw: String, base: String): String {
        val value = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (value.startsWith("//")) return "https:$value"
        return runCatching { URI(base).resolve(value).toString() }.getOrElse { mainUrl.trimEnd('/') + "/" + value.removePrefix("/") }
    }

    private fun canonical(url: String): String = absolute(url, mainUrl).substringBefore("#").substringBefore("?").trimEnd('/').lowercase()

    private fun pathOf(url: String): String = runCatching { URI(absolute(url, mainUrl)).path.orEmpty() }.getOrDefault("")
}
