package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val WDT_TAG = "WebDramaTurkey2"

class WebDramaTurkey2 : MainAPI() {

    override var mainUrl = "https://webdramaturkey2.com"
    override var name = "WebDramaTurkey2"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.Anime,
        TvType.Others
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Bölümler",
        "$mainUrl/diziler?page=" to "Diziler",
        "$mainUrl/filmler?page=" to "Filmler",
        "$mainUrl/animeler?page=" to "Animeler",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%221%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Kore Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%222%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Çin Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%223%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Japon Dizileri",
        "$mainUrl/tur/romantik?page=" to "Romantik",
        "$mainUrl/tur/dram?page=" to "Dram",
        "$mainUrl/tur/fantastik?page=" to "Fantastik",
        "$mainUrl/tur/komedi?page=" to "Komedi",
        "$mainUrl/tur/gerilim?page=" to "Gerilim",
        "$mainUrl/tur/korku?page=" to "Korku",
        "$mainUrl/tur/aksiyon?page=" to "Aksiyon",
        "$mainUrl/tur/gizem?page=" to "Gizem",
        "$mainUrl/tur/bilim-kurgu?page=" to "BilimKurgu",
    )

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val url = if (data == "$mainUrl/") {
            data
        } else {
            "$data${page.coerceAtLeast(1)}"
        }

        Log.d(WDT_TAG, "getMainPage: $url")

        val document = runCatching {
            app.get(
                url,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return newHomePageResponse(
            request.name,
            emptyList(),
            false,
        )

        val items = if (data == "$mainUrl/") {
            document.select("div.col.sonyuklemeler")
                .mapNotNull { it.toLatestEpisodeResult() }
        } else {
            document.select("div.col")
                .mapNotNull { it.toMainPageResult() }
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = data != "$mainUrl/" && items.isNotEmpty() && page < 100,
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst(
            "a.list-title, a[href*='/dizi/'], a[href*='/film/'], " +
                "a[href*='/anime/'], a[href*='/program/']"
        ) ?: return null

        val title = selectFirst("a.list-title")?.text()?.trim()
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst("h2, h3, h4")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(link.attr("href").replace("&amp;", "&")) ?: return null
        if (!isContentUrl(href)) return null

        return makeSearchResponse(title, href, posterFromCard(this))
    }

    private fun Element.toLatestEpisodeResult(): SearchResponse? {
        val titleBase = selectFirst("div.list-title")?.text()?.trim()
            ?: selectFirst("h2, h3, h4")?.text()?.trim()
            ?: return null

        val episodeText = selectFirst("div.list-category")?.text()?.trim().orEmpty()
        val link = selectFirst("a[href]") ?: return null
        val originalHref = link.attr("href").trim()
        if (originalHref.isBlank()) return null

        val cleanHref = originalHref.replace(
            Regex("""/\d+-sezon/\d+-bolum/?$"""),
            "/",
        )
        val href = fixUrlNull(cleanHref) ?: return null
        if (!isContentUrl(href)) return null

        val title = if (episodeText.isNotBlank()) "$titleBase - $episodeText" else titleBase
        return makeSearchResponse(title, href, episodePoster(this))
    }

    private fun posterFromCard(element: Element): String? {
        val cover = element.selectFirst("div.media.media-cover")
        val raw = sequenceOf(
            cover?.attr("data-src"),
            cover?.attr("data-background"),
            cover?.attr("data-image"),
            cover?.selectFirst("img")?.attr("data-src"),
            cover?.selectFirst("img")?.attr("src"),
            element.selectFirst("img")?.attr("data-src"),
            element.selectFirst("img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        if (raw.isNotBlank()) return fixUrlNull(raw)

        val style = cover?.attr("style").orEmpty()
        return Regex(
            """url\((?:&quot;|["']?)([^)"']+)(?:&quot;|["']?)\)"""
        ).find(style)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)
    }

    private fun episodePoster(element: Element): String? {
        val ep = element.selectFirst("div.media.media-episode")
        val raw = sequenceOf(
            ep?.attr("data-src"),
            ep?.attr("data-background"),
            ep?.selectFirst("img")?.attr("data-src"),
            ep?.selectFirst("img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }

        if (!raw.isNullOrBlank()) return fixUrlNull(raw)

        val style = ep?.attr("style").orEmpty()
        return Regex(
            """url\((?:&quot;|["']?)([^)"']+)(?:&quot;|["']?)\)"""
        ).find(style)?.groupValues?.getOrNull(1)
            ?.replace("&quot;", "")
            ?.let(::fixUrlNull)
    }

    private fun makeSearchResponse(
        title: String,
        href: String,
        poster: String?,
    ): SearchResponse {
        val type = when {
            href.contains("/film/", true) -> TvType.Movie
            href.contains("/anime/", true) -> TvType.Anime
            href.contains("/program/", true) -> TvType.Others
            else -> TvType.AsianDrama
        }

        return newMovieSearchResponse(title, href, type) {
            posterUrl = poster
            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
            )
        }
    }

    private fun matchesSearchQuery(query: String, title: String): Boolean {
        fun normalize(value: String): String {
            val lowered = value.lowercase(java.util.Locale.ROOT).replace('ı', 'i')
            val decomposed = java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        }
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return false
        val ignored = setOf("the", "a", "an", "of", "and", "or", "ve")
        val year = Regex("(?:18|19|20|21)\\d{2}")
        val words = q.split(' ').filterNot { it in ignored || year.matches(it) }
        if (words.isEmpty()) return q == t
        val titleWords = t.split(' ').toSet()
        val numberWords = mapOf("0" to "zero", "1" to "one", "2" to "two", "3" to "three",
            "4" to "four", "5" to "five", "6" to "six", "7" to "seven", "8" to "eight",
            "9" to "nine", "10" to "ten")
        return words.all { word ->
            val mapped = numberWords[word]
            titleWords.any { candidate ->
                candidate == word || (mapped != null && candidate == mapped) ||
                    (candidate.length >= 4 && word.length >= 4 &&
                        kotlin.math.abs(candidate.length - word.length) <= 2 &&
                        (candidate.startsWith(word) || word.startsWith(candidate)))
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/arama/$encoded",
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
        ).distinct()

        val merged = LinkedHashMap<String, SearchResponse>()
        for (url in urls) {
            val document = runCatching {
                app.get(
                    url,
                    headers = pageHeaders,
                    referer = "$mainUrl/",
                    allowRedirects = true,
                ).document
            }.onFailure {
                Log.w(WDT_TAG, "Arama isteği başarısız: $url", it)
            }.getOrNull() ?: continue

            val items = document.select("div.tab-pane:not(#actors) div.col, div.col")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
                .filter { matchesSearchQuery(q, it.name) }
            Log.d(WDT_TAG, "Arama: $url -> ${items.size} sonuç")
            items.forEach { merged.putIfAbsent(it.url, it) }
            if (merged.isNotEmpty()) break
        }
        return merged.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst(
            "a[href*='/dizi/'], a[href*='/film/'], " +
                "a[href*='/anime/'], a[href*='/program/']"
        ) ?: return null

        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!isContentUrl(href)) return null

        val title = selectFirst("a.list-title")?.text()?.trim()
            ?: selectFirst("h2, h3, h4, span.title, .title")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        return makeSearchResponse(title, href, posterFromCard(this))
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrlNull(url) ?: return null
        val document = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return null

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("İzle").trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            listOf(
                document.selectFirst("meta[property='og:image']")?.attr("content"),
                document.selectFirst("div.app-detail-poster img")?.attr("data-src"),
                document.selectFirst("div.app-detail-poster img")?.attr("src"),
                document.selectFirst("div.media-cover img")?.attr("data-src"),
                document.selectFirst("div.media-cover img")?.attr("src"),
                document.selectFirst("div.cover img")?.attr("data-src"),
                document.selectFirst("div.cover img")?.attr("src"),
                document.selectFirst("div.poster img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }
        )

        val plot = document.select("div.detail-attr").firstOrNull { block ->
            block.selectFirst(".attr")?.text()?.trim()?.equals("Genel Bakış", true) == true
        }?.selectFirst(".text-content, .text")?.text()?.trim()
            ?: document.selectFirst(
                "div.app-detail-overview, div.desc, div.description, div.plot, p.desc"
            )?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val yearText = document.selectFirst(
            "div.app-detail-info, div.info, div.metadata, .detail-info"
        )?.text().orEmpty()

        val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(yearText)?.value?.toIntOrNull()
            ?: Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
                .find(document.text())?.value?.toIntOrNull()

        val statusText = document.selectFirst(
            "span[class*='status'], div[class*='status'], span[class*='durum']"
        )?.text()?.trim()

        val duration = document.selectFirst(
            "span[class*='time'], div[class*='duration'], span[class*='sure']"
        )?.text()?.trim()?.let { getDurationFromString(it) }

        val actors = document.select(
            "a[href*='/oyuncu/'], div.actor, div.oyuncu, .actors a[href]"
        ).mapNotNull { element ->
            val img = element.selectFirst("img, .media")
            val actorName = sequenceOf(
                element.selectFirst("div.list-title, div.name, span.name")?.text()?.trim(),
                img?.attr("alt")?.trim(),
                element.text().trim(),
            ).firstOrNull { !it.isNullOrBlank() } ?: return@mapNotNull null

            val imageUrl = img?.attr("data-src")
                ?.ifBlank { img.attr("src") }
                ?.ifBlank { null }
                ?: Regex("""url\((?:&quot;|["']?)([^)"']+)(?:&quot;|["']?)\)""")
                    .find(element.attr("style"))
                    ?.groupValues?.getOrNull(1)

            Actor(actorName, fixUrlNull(imageUrl))
        }.distinctBy { it.name }

        val trailer = document.select("iframe[src], iframe[data-src], a[href], button[data-remote]")
            .mapNotNull { element ->
                val raw = element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .ifBlank { element.attr("data-remote") }
                    .let { value ->
                        Regex("""(?:trailer=)(https?%3A%2F%2F[^&]+)""", RegexOption.IGNORE_CASE)
                            .find(value)?.groupValues?.getOrNull(1)
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            ?: value
                    }

                fixUrlNull(raw)?.takeIf {
                    it.contains("youtube.com", true) || it.contains("youtu.be", true)
                }
            }.firstOrNull()

        val isMovie = pageUrl.contains("/film/", true)
        val isAnime = pageUrl.contains("/anime/", true)
        val isProgram = pageUrl.contains("/program/", true)

        if (isMovie) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.contentRating = statusText
                addActors(actors)
                addTrailer(trailer)
            }
        }

        val episodeElements = document.select(
            "a[href*='-sezon/'][href*='-bolum'], a[href*='/sezon/'][href*='/bolum']"
        )

        val episodes = episodeElements.mapNotNull { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null

            val match = Regex("""/(\d+)-sezon/(\d+)-bolum""").find(href)
                ?: Regex("""/(\d+)/sezon/(\d+)/bolum""").find(href)

            val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

            val name = element.text().trim().ifBlank {
                if (season != null && episode != null) {
                    "$season. Sezon $episode. Bölüm"
                } else {
                    "Bölüm"
                }
            }

            newEpisode(href) {
                this.name = name
                this.season = season ?: 1
                this.episode = episode
            }
        }.distinctBy { it.data }

        if (isAnime) {
            return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
                posterUrl = poster
                posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
                this.tags = tags
                this.contentRating = statusText
                addEpisodes(DubStatus.Subbed, episodes)
                addActors(actors)
                addTrailer(trailer)
            }
        }

        if (isProgram) {
            return newTvSeriesLoadResponse(title, pageUrl, TvType.Others, episodes) {
                posterUrl = poster
                posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
                this.tags = tags
                this.contentRating = statusText
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, episodes) {
            posterUrl = poster
            posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
            this.plot = plot
            this.year = year
            this.tags = tags
            this.contentRating = statusText
            this.duration = duration
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(WDT_TAG, "loadLinks: $data")

        val document = runCatching {
            app.get(
                data,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return false

        // Aynı loadLinks çağrısında bütün alternatifleri tüketme.
        // İlk gerçekten medya linki üreten kaynak bulunduğunda dur.
        var found = false

        // Sayfada doğrudan bırakılmış gerçek player iframe/video adresleri.
        // Navigation/footer bağlantılarını özellikle taramıyoruz.
        val directPlayerCandidates = linkedSetOf<String>()

        document.select(
            "iframe[src], iframe[data-src], video[src], video[data-src], source[src], source[data-src]"
        ).forEach { element ->
            val raw = sequenceOf(
                element.attr("data-src"),
                element.attr("src"),
                element.attr("data-url")
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            if (
                raw.contains("vidmoly", true) ||
                raw.contains("filemoon", true) ||
                raw.contains("ok.ru", true) ||
                raw.contains("odnoklassniki", true) ||
                raw.contains("vk.com", true) ||
                raw.contains("vkvideo.ru", true) ||
                raw.contains("abstream", true) ||
                raw.contains("upn", true) ||
                raw.contains("abyss", true) ||
                raw.contains("moly", true) ||
                raw.contains("moon", true) ||
                raw.contains("p2p", true)
            ) {
                fixUrlNull(raw)?.let { directPlayerCandidates += it }
            }
        }

        for (playerUrl in directPlayerCandidates) {
            Log.d(WDT_TAG, "Doğrudan player adayı deneniyor: $playerUrl")
            if (
                resolveIframe(
                    playerUrl,
                    data,
                    "Doğrudan Kaynak",
                    subtitleCallback,
                    callback,
                )
            ) {
                found = true
                break
            }
        }

        // Asıl WDT kaynak akışı: data-embed -> AJAX -> video.php -> iframe/player.
        if (!found) {
            val buttons = document
                .select("button[data-embed], a[data-embed], .dropdown-source[data-embed], [data-embed]")
                .filter { it.attr("data-embed").trim().isNotBlank() }
                .distinctBy { it.attr("data-embed").trim() }

            for (button in buttons) {
                val embedId = button.attr("data-embed").trim()
                val sourceName = button.selectFirst("span.name")?.text()?.trim()
                    ?: button.text().trim().takeIf { it.isNotBlank() }
                    ?: "Alternatif"

                Log.d(WDT_TAG, "Kaynak deneniyor: " + sourceName + " | embedId=" + embedId)

                val ajaxResponse = runCatching {
                    app.post(
                        "$mainUrl/ajax/embed",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data,
                            "Accept" to "*/*",
                        ),
                        referer = data,
                        data = mapOf("id" to embedId),
                        cacheTime = 0,
                    )
                }.getOrNull() ?: continue

                Log.d(WDT_TAG, "AJAX HTTP=" + ajaxResponse.code + " | source=" + sourceName)
                if (!ajaxResponse.isSuccessful) continue

                val ajaxText = ajaxResponse.text

                val videoPhpUrl = Regex(
                    """src\s*=\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(ajaxText)?.groupValues?.getOrNull(1)
                    ?.replace("\\/", "/")
                    ?.replace("&amp;", "&")
                    ?.let(::fixUrlNull)
                    ?: Regex(
                        """(?:https?:)?//[^\s"'<>]*video\.php\?[^\s"'<>]+""",
                        RegexOption.IGNORE_CASE
                    ).find(ajaxText)?.value
                        ?.replace("\\/", "/")
                        ?.let(::fixUrlNull)

                Log.d(WDT_TAG, "video.php=" + videoPhpUrl + " | source=" + sourceName)

                if (videoPhpUrl.isNullOrBlank()) {
                    val fallbackIframe = Regex(
                        """(?:src|iframe)[^"']*["'](https?://[^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ).find(ajaxText)?.groupValues?.getOrNull(1)

                    if (!fallbackIframe.isNullOrBlank()) {
                        found = resolveIframe(
                            fallbackIframe,
                            data,
                            sourceName,
                            subtitleCallback,
                            callback,
                        ) || found
                    }
                    continue
                }

                val videoResponse = runCatching {
                    app.get(
                        videoPhpUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to data,
                        ),
                        referer = data,
                        allowRedirects = true,
                        cacheTime = 0,
                    )
                }.getOrNull() ?: continue

                if (!videoResponse.isSuccessful) continue

                val videoHtml = videoResponse.text

                val iframeUrl = Regex(
                    """<iframe[^>]+(?:id=["']main-iframe["'][^>]+)?src=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(videoHtml)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)
                    ?: Regex(
                        """src=["'](https?://[^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ).find(videoHtml)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)

                Log.d(WDT_TAG, "iframe=" + iframeUrl + " | source=" + sourceName)

                if (!iframeUrl.isNullOrBlank()) {
                    found = resolveIframe(
                        iframeUrl,
                        data,
                        sourceName,
                        subtitleCallback,
                        callback,
                    )
                }

                // video.php içinde medya adresi bulunuyorsa doğrudan kullan.
                if (!found) {
                    Regex(
                        """["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""",
                        RegexOption.IGNORE_CASE
                    ).findAll(videoHtml).forEach { match ->
                        val stream = match.groupValues[1]
                        val type = when {
                            stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                            stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        }
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - $sourceName",
                                url = stream,
                                type = type,
                            ) {
                                quality = Qualities.Unknown.value
                                referer = videoPhpUrl
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to videoPhpUrl,
                                )
                            }
                        )
                        found = true
                    }
                }

                Regex(
                    """["'](?:subtitle|subtitles|captions?)["']\s*[:=]\s*["']([^"']+(?:\.vtt|\.srt)[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                ).findAll(videoHtml).forEach { match ->
                    fixUrlNull(match.groupValues[1])?.let { subtitle ->
                        runCatching {
                            subtitleCallback(SubtitleFile("Türkçe", subtitle))
                        }
                    }
                }
            }
        }

        // Kaynak düğmesi bulunamazsa iframe fallback.
        if (!found) {
            for (iframe in document.select("iframe[src], iframe[data-src]")) {
                val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                val iframeUrl = fixUrlNull(raw) ?: continue

                if (resolveIframe(
                        iframeUrl,
                        data,
                        "Alternatif",
                        subtitleCallback,
                        callback,
                    )
                ) {
                    found = true
                    break
                }
            }
        }

        Log.d(WDT_TAG, "loadLinks sonucu found=" + found)
        return found
    }

    private suspend fun resolveIframe(
        iframeUrl: String,
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ): Boolean {
        if (depth > 2) return false

        val normalized = fixUrlNull(
            iframeUrl
                .trim()
                .replace("\\/", "/")
                .replace("&amp;", "&")
        ) ?: return false

        var emitted = false

        fun emitLink(link: ExtractorLink) {
            emitted = true
            callback(link)
        }

        // VK
        if (!emitted &&
            (normalized.contains("vkvideo.ru", true) || normalized.contains("vk.com", true))
        ) {
            runCatching {
                loadExtractor(
                    normalized,
                    referer,
                    subtitleCallback
                ) { link -> emitLink(link) }
            }.onFailure {
                Log.d(WDT_TAG, "VK extractor hatası: " + it.message)
            }
        }

        // Önce CloudStream yerleşik extractor'ları.
        // dtpasn player'ı aşağıdaki qualities çözümleyicisi ile açılır.
        if (!emitted &&
            !normalized.contains("dtpasn.asia", true) &&
            !normalized.contains("dtpasn.com", true)
        ) {
            runCatching {
                Log.d(WDT_TAG, "Yerleşik extractor deneniyor: " + normalized)
                loadExtractor(
                    normalized,
                    referer,
                    subtitleCallback
                ) { link ->
                    Log.d(WDT_TAG, "Yerleşik extractor link üretti: " + link.url)
                    emitLink(link)
                }
            }.onFailure {
                Log.d(WDT_TAG, "Yerleşik extractor hata: " + it.message)
            }
        }

        // WDT player: const qualities = [...] -> redirect -> gerçek medya URL.
        if (!emitted) {
            runCatching {
                emitted = resolveQualityPlayer(
                    normalized,
                    referer,
                    sourceName,
                    subtitleCallback,
                    callback
                ) || emitted
            }.onFailure {
                Log.d(WDT_TAG, "qualities çözümleme hatası: " + it.message)
            }
        }

        if (emitted) return true

        val response = runCatching {
            app.get(
                normalized,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
                referer = referer,
                allowRedirects = true,
            )
        }.getOrNull() ?: return false

        if (!response.isSuccessful) return false

        val html = response.text
        val directCandidates = linkedSetOf<String>()

        Regex(
            """https?://[^"'<>\s]+(?:\/[^"'<>\s]*)*\.(?:m3u8|mp4|mpd)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("\\/", "/").replace("\u0026", "&") }
            .forEach { directCandidates += it }

        Regex(
            """["'](?:file|src|url|source|videoSource|securedLink)["']?\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.groupValues[1].replace("\\/", "/").replace("\u0026", "&") }
            .filter {
                it.contains(".m3u8", true) ||
                it.contains(".mp4", true) ||
                it.contains(".mpd", true)
            }
            .forEach { raw ->
                fixUrlNull(raw)?.let { directCandidates += it }
            }

        response.document.select("video[src], source[src], source[data-src]").forEach { el ->
            val raw = el.attr("data-src").ifBlank { el.attr("src") }
            fixUrlNull(raw)?.let { candidate ->
                if (
                    candidate.contains(".m3u8", true) ||
                    candidate.contains(".mp4", true) ||
                    candidate.contains(".mpd", true)
                ) {
                    directCandidates += candidate
                }
            }
        }

        for (stream in directCandidates) {
            val type = when {
                stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            runCatching {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $sourceName",
                        url = stream,
                        type = type,
                    ) {
                        quality = Qualities.Unknown.value
                        this.referer = normalized
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to normalized,
                        )
                    }
                )
                emitted = true
            }
        }

        if (!emitted) {
            val nestedFrames = response.document
                .select("iframe[src], iframe[data-src]")
                .mapNotNull { frame ->
                    val raw = frame.attr("data-src").ifBlank { frame.attr("src") }
                    fixUrlNull(raw)
                }
                .filter { it.isNotBlank() }
                .distinct()
                .filterNot { it == normalized }

            for (nested in nestedFrames) {
                if (
                    resolveIframe(
                        nested,
                        normalized,
                        sourceName,
                        subtitleCallback,
                        callback,
                        depth + 1,
                    )
                ) {
                    emitted = true
                    break
                }
            }
        }

        Regex(
            """https?://[^"'<>\s]+(?:\/[^"'<>\s]*)*\.(?:vtt|srt)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("\\/", "/").replace("\u0026", "&") }
            .distinct()
            .forEach { sub ->
                runCatching {
                    subtitleCallback(SubtitleFile("Türkçe", sub))
                }
            }

        return emitted
    }

    private suspend fun resolveQualityPlayer(
        playerUrl: String,
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = app.get(
            playerUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ),
            referer = referer,
            allowRedirects = true,
            cacheTime = 0,
        )

        if (!response.isSuccessful) return false

        val html = response.text
        var emitted = false

        // Bazı player'larda gerçek medya adresleri:
        // const qualities = [{ "name": "720p", "url": "/redirect/..." }, ...];
        val qualityUrls = linkedSetOf<String>()

        val qualitiesBlock = Regex(
            """(?:const|let|var)\s+qualities\s*=\s*(\[.*?\])\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)

        val qualitySource = qualitiesBlock ?: html

        Regex(
            """["'](?:url|file|src)["']\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(qualitySource).forEach { match ->
            val raw = match.groupValues[1]
                .replace("\\/","/")
                .replace("\\u0026","&")
            absolutizePlayerUrl(raw, playerUrl)?.let { qualityUrls += it }
        }

        // En yüksek kalite genellikle dizinin sonunda geliyor.
        for (qualityUrl in qualityUrls.toList().asReversed()) {
            Log.d(WDT_TAG, "Quality URL: " + qualityUrl)

            val redirectResponse = runCatching {
                app.get(
                    qualityUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to playerUrl,
                    ),
                    referer = playerUrl,
                    allowRedirects = false,
                )
            }.getOrNull()

            val location = redirectResponse?.headers?.get("Location")
                ?.replace("\\/","/")
                ?.replace("\\u0026","&")

            val finalUrl = when {
                !location.isNullOrBlank() ->
                    absolutizePlayerUrl(location, playerUrl)
                qualityUrl.contains(".m3u8", true) ||
                    qualityUrl.contains(".mp4", true) ||
                    qualityUrl.contains(".mpd", true) ->
                    qualityUrl
                else -> null
            }

            if (finalUrl.isNullOrBlank()) continue

            val type = when {
                finalUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                finalUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $sourceName",
                    url = finalUrl,
                    type = type,
                ) {
                    quality = Qualities.Unknown.value
                    this.referer = playerUrl
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to playerUrl,
                    )
                }
            )

            Log.d(WDT_TAG, "Gerçek medya URL bulundu: " + finalUrl)
            emitted = true
            break
        }

        // Player sayfasında doğrudan m3u8/mp4/mpd bulunuyorsa ayrıca dene.
        if (!emitted) {
            Regex(
                """https?://[^"'<>\\s]+\.(?:m3u8|mp4|mpd)(?:\?[^"'<()>\s]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(html)
                .map { it.value.replace("\\/","/").replace("\u0026","&") }
                .distinct()
                .forEach { stream ->
                    val type = when {
                        stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                        stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - $sourceName",
                            url = stream,
                            type = type,
                        ) {
                            quality = Qualities.Unknown.value
                            this.referer = playerUrl
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to playerUrl,
                            )
                        }
                    )
                    emitted = true
                }
        }

        // Altyazı
        Regex(
            """https?://[^"'<>\s]+\\.(?:vtt|srt)(?:\\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("\\/","/").replace("\u0026","&") }
            .distinct()
            .forEach { subtitle ->
                runCatching { subtitleCallback(SubtitleFile("Türkçe", subtitle)) }
            }

        return emitted
    }

    private fun absolutizePlayerUrl(raw: String, base: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null

        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value
        }

        if (value.startsWith("//")) return "https:$value"

        return runCatching {
            java.net.URI(base).resolve(value).toString()
        }.getOrNull()
    }

    private fun isContentUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("$mainUrl/dizi/") ||
            value.contains("$mainUrl/film/") ||
            value.contains("$mainUrl/anime/") ||
            value.contains("$mainUrl/program/")
    }
}
