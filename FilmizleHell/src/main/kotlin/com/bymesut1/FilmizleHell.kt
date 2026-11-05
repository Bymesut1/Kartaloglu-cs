package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class FilmizleHell : MainAPI() {
    override var mainUrl = "https://filmizlehell.net"
    override var name = "FilmizleHell"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/tur/1-aksiyon-14k" to "Aksiyon",
        "$mainUrl/tur/7-macera-1jy" to "Macera",
        "$mainUrl/tur/8-bilim-kurgu-pt1" to "Bilim-Kurgu",
        "$mainUrl/tur/9-savas-14f" to "Savaş",
        "$mainUrl/tur/10-dram-12w" to "Dram",
        "$mainUrl/tur/11-yerli-film-dv3" to "Yerli Filmler",
        "$mainUrl/tur/12-gerilim-1xr" to "Gerilim",
        "$mainUrl/tur/13-komedi-1wl" to "Komedi",
        "$mainUrl/tur/15-belgesel-170" to "Belgesel",
        "$mainUrl/tur/16-aile-1kf" to "Aile",
        "$mainUrl/tur/17-fantastik-zt1" to "Fantastik"
    )

    private val yearRegex = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")
    private val mediaRegex = Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'<>\\\s]*)?""")
    private val subtitleRegex = Regex("""(?i)(?:https?:)?//[^"'<>\\\s]+?\.(?:vtt|srt|ass)(?:\?[^"'<>\\\s]*)?""")
    private val quotedSubtitleRegex = Regex("""(?i)["']((?:(?:https?:)?//|/|\.\.?/)?[^"'<>\\\s]{1,260}\.(?:vtt|srt|ass)(?:\?[^"'<>\\\s]*)?)["']""")

    // Avoid repeating anonymous subtitle API queries for the same movie.
    // Values are cached briefly, including empty results, to prevent exhausting the daily guest quota.
    private val externalSubtitleCacheLock = Any()
    private val externalSubtitleCache = HashMap<String, Pair<Long, List<Pair<String, String>>>>()

    private fun decode(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\u003F", "?", ignoreCase = true)
        .replace("\\u0023", "#", ignoreCase = true)
        .replace("\\x23", "#", ignoreCase = true)
        .replace("%23", "#")
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'')
        if (value.isBlank() || value == "#" ||
            value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("about:", true)
        ) return null

        return runCatching {
            val resolved = when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:" + value
                else -> URI(base).resolve(value).toString()
            }
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")

    private fun isFilmUrl(url: String): Boolean =
        Regex("""(?i)^/film/\d+-[^/]+/?$""").matches(pathOf(url))

    private fun sameSite(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return host == "filmizlehell.net" || host == "www.filmizlehell.net"
    }

    private fun cleanText(value: String?): String =
        value.orEmpty().replace(Regex("""\s+"""), " ").trim()

    private fun cleanTitle(raw: String?): String = cleanText(raw)
        .replace(Regex("""(?i)\s*[-|–]\s*Film izle Hell.*$"""), "")
        .replace(Regex("""(?i)\s*\|\s*FilmizleHell.*$"""), "")
        .replace(Regex("""(?i)\s+Türkçe\s+(?:Dublaj|Altyazılı).*$"""), "")
        .replace(Regex("""(?i)\s+(?:Dublajlı|Altyazılı).*$"""), "")
        .replace(Regex("""(?i)\s+izle\s*$"""), "")
        .replace(Regex("""(?i)\s+IMDb\s*[:⭐★]?\s*\d+(?:[.,]\d+)?\s*$"""), "")
        .trim(' ', '-', '|', '–', ':', '★', '⭐')

    private suspend fun fetchDocument(url: String, referer: String = mainUrl): Document? =
        runCatching {
            app.get(
                url,
                headers = requestHeaders + ("Referer" to referer),
                referer = referer,
                allowRedirects = true,
                timeout = 18000
            )
        }.onFailure { Log.w(name, "Sayfa alınamadı: " + url, it) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.document

    private suspend fun fetchImdbScore(url: String, referer: String): Score? =
        runCatching {
            app.get(
                url,
                headers = requestHeaders + ("Referer" to referer),
                referer = referer,
                allowRedirects = true,
                timeout = 4500
            )
        }.onFailure {
            Log.d(name, "IMDb puanı alınamadı: " + safeLogUrl(url))
        }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.document
            ?.let { parseScore(it) }


    private fun imageUrl(element: Element?, base: String): String? {
        if (element == null) return null
        val attrs = listOf(
            "data-src", "data-lazy-src", "data-original", "data-image",
            "data-poster", "data-poster-url", "data-lazy", "srcset", "data-srcset", "src"
        )
        for (attr in attrs) {
            val raw = element.attr(attr).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val candidate = if (attr.contains("srcset", true)) {
                raw.split(",").maxByOrNull { part ->
                    Regex("""(\d+)(?:w|x)""").find(part)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                }?.trim()?.substringBefore(" ").orEmpty()
            } else raw
            val absolute = fixUrl(candidate, base) ?: continue
            val path = pathOf(absolute).lowercase(Locale.ROOT)
            if (listOf("/icons/", "/avatars/", "/favicon", "/logo", "/placeholder").any { path.contains(it) }) continue
            if (Regex("""(?i)\.(?:jpe?g|png|webp)(?:[?#].*)?$""").containsMatchIn(absolute)) return absolute
            if (path.contains("/storage/posters/")) return absolute
        }
        val style = element.attr("style")
        val rawBackground = Regex("""(?i)url\(['"]?([^)'"]+)""").find(style)?.groupValues?.getOrNull(1)
        return fixUrl(rawBackground, base)
    }

    private fun imageElementFor(link: Element, base: String): Element? {
        link.selectFirst("img")?.let { image ->
            if (imageUrl(image, base) != null) return image
        }

        // A parent image is safe only when that small wrapper points to one
        // content page. The old broad parent/parent lookup could borrow a poster
        // from a neighbouring section or from the "Son Yorumlar" widget.
        var parent: Element? = link.parent()
        repeat(3) {
            val container = parent ?: return null
            val filmTargets = container.select("a[href]").mapNotNull { anchor ->
                val url = fixUrl(anchor.attr("href"), base) ?: return@mapNotNull null
                url.takeIf { sameSite(it) && isFilmUrl(it) && !isCommentOrChrome(anchor) }
            }.map { it.trimEnd('/') }.distinct()

            if (filmTargets.size == 1) {
                container.select("img").firstOrNull { imageUrl(it, base) != null }?.let { return it }
            }
            parent = container.parent()
        }
        return null
    }

    private fun imageFor(link: Element, base: String): String? =
        imageUrl(imageElementFor(link, base), base)

    private fun normalizeSectionText(value: String?): String =
        cleanText(value).lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isCommentOrChrome(anchor: Element): Boolean {
        val blockedMarker = Regex(
            """(?i)(comment|comments|yorum|yorumlar|review|reviews|feedback|disqus|recent-comments|latest-comments)"""
        )
        var current: Element? = anchor
        repeat(9) {
            val node = current ?: return false
            val marker = node.id() + " " + node.className()
            if (blockedMarker.containsMatchIn(marker)) return true
            if (node.tagName().lowercase(Locale.ROOT) in setOf("footer", "aside", "nav")) return true
            current = node.parent()
        }
        return false
    }

    private fun isCommentSectionHeading(text: String): Boolean {
        val normalized = normalizeSectionText(text)
        return normalized.contains("son yorum") ||
            normalized == "yorumlar" ||
            normalized == "comments" ||
            normalized == "recent comments" ||
            normalized == "latest comments"
    }

    private fun isNewContentHeading(text: String): Boolean {
        val normalized = normalizeSectionText(text)
        return normalized.contains("yeni eklenen bolum") ||
            normalized.contains("son eklenen bolum") ||
            normalized.contains("yeni bolumler") ||
            normalized.contains("yeni eklenen film") ||
            normalized == "yeni eklenenler"
    }

    private fun isSectionBoundaryHeading(text: String): Boolean {
        val normalized = normalizeSectionText(text)
        if (isNewContentHeading(normalized)) return true
        return listOf(
            "son yorum", "yorumlar", "comments", "recent comments", "latest comments",
            "en cok izlenen", "trend filmler", "trend diziler", "yakinda gelecek",
            "2026 yili", "2025 yili", "kesfet", "film robotu", "kategoriler",
            "film turleri", "tum filmler", "seriler", "platformlar", "haftanin secme"
        ).any { normalized.contains(it) }
    }

    private fun newContentHeading(document: Document): Element? {
        val headings = document.select("h1,h2,h3,h4,h5,h6")
        // Episode-specific heading wins when the site exposes one; otherwise use
        // the site's new-film/new-content section.
        return headings.firstOrNull {
            val title = normalizeSectionText(it.text())
            title.contains("yeni eklenen bolum") ||
                title.contains("son eklenen bolum") ||
                title.contains("yeni bolumler")
        } ?: headings.firstOrNull { isNewContentHeading(it.text()) }
    }

    private fun linksAfterHeading(
        document: Document,
        heading: Element,
        base: String
    ): List<Element> {
        val elements = document.getAllElements()
        val headingIndex = elements.indexOfFirst { it === heading }
        if (headingIndex < 0) return emptyList()

        val links = ArrayList<Element>()
        for (index in (headingIndex + 1) until elements.size) {
            val element = elements[index]
            val tag = element.tagName().lowercase(Locale.ROOT)
            if (tag in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                val headingText = normalizeSectionText(element.ownText().ifBlank { element.text() })
                if (isSectionBoundaryHeading(headingText)) break
            }
            if (tag != "a" || !element.hasAttr("href") || isCommentOrChrome(element)) continue
            val absolute = fixUrl(element.attr("href"), base) ?: continue
            if (!sameSite(absolute)) continue
            links.add(element)
        }
        return links
    }

    private fun findNewContentMoreUrl(document: Document, base: String): String? {
        val heading = newContentHeading(document)
        if (heading != null) {
            val scopedLinks = linksAfterHeading(document, heading, base)
            val more = scopedLinks.firstOrNull { anchor ->
                val label = normalizeSectionText(
                    anchor.text() + " " + anchor.attr("aria-label") + " " + anchor.attr("title")
                )
                label.contains("tumunu gor") ||
                    label.contains("tum bolumleri gor") ||
                    label.contains("hepsini gor") ||
                    label.contains("tum bolumler")
            }
            if (more != null) {
                val url = fixUrl(more.attr("href"), base) ?: return null
                if (sameSite(url) && url.trimEnd('/') != base.trimEnd('/')) return url
            }

            // On templates where the "Tümünü Gör" control sits just outside the
            // heading's sibling range, inspect ancestors containing this section.
            var parent: Element? = heading.parent()
            repeat(6) {
                val container = parent ?: return@repeat
                val hasContentCards = container.select("a[href]").any { anchor ->
                    val url = fixUrl(anchor.attr("href"), base) ?: return@any false
                    sameSite(url) && isFilmUrl(url) && !isCommentOrChrome(anchor)
                }
                if (hasContentCards) {
                    val button = container.select("a[href]").firstOrNull { anchor ->
                        if (isCommentOrChrome(anchor)) return@firstOrNull false
                        val label = normalizeSectionText(
                            anchor.text() + " " + anchor.attr("aria-label") + " " + anchor.attr("title")
                        )
                        label.contains("tumunu gor") ||
                            label.contains("tum bolumleri gor") ||
                            label.contains("hepsini gor") ||
                            label.contains("tum bolumler")
                    }
                    val url = button?.let { fixUrl(it.attr("href"), base) }
                    if (url != null && sameSite(url) && url.trimEnd('/') != base.trimEnd('/')) return url
                }
                parent = container.parent()
            }
        }

        // Fallback: only accept a "Tümünü Gör" control near actual content cards.
        for (anchor in document.select("a[href]")) {
            if (isCommentOrChrome(anchor)) continue
            val label = normalizeSectionText(
                anchor.text() + " " + anchor.attr("aria-label") + " " + anchor.attr("title")
            )
            if (!(label.contains("tumunu gor") || label.contains("tum bolumleri gor") ||
                    label.contains("hepsini gor") || label.contains("tum bolumler"))
            ) continue
            val url = fixUrl(anchor.attr("href"), base) ?: continue
            if (!sameSite(url) || url.trimEnd('/') == base.trimEnd('/')) continue
            val nearContent = anchor.parents().take(6).any { ancestor ->
                ancestor.select("a[href]").count { sibling ->
                    val siblingUrl = fixUrl(sibling.attr("href"), base) ?: return@count false
                    isFilmUrl(siblingUrl) && !isCommentOrChrome(sibling)
                } >= 3
            }
            if (nearContent) return url
        }
        return null
    }

    private fun extractScore(text: String?): Score? {
        val value = text.orEmpty()
        val imdb = Regex("""(?is)\bIMDb\b[^\d]{0,50}(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)""")
            .find(value)?.groupValues?.getOrNull(1)
        val number = imdb ?: return null
        val score = number.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 } ?: return null
        return runCatching { Score.from10(score) }.getOrNull()
    }

    private fun scoreFromCard(element: Element): Score? {
        var current: Element? = element
        repeat(4) {
            val node = current ?: return@repeat
            val explicit = node.selectFirst(
                "[data-imdb], [itemprop=ratingValue], .imdb-rating, .imdb-score, [aria-label*=IMDb], [title*=IMDb]"
            )?.let { selected ->
                selected.attr("data-imdb")
                    .ifBlank { selected.attr("content") }
                    .ifBlank { selected.attr("aria-label") }
                    .ifBlank { selected.attr("title") }
                    .ifBlank { selected.text() }
            }
            if (!explicit.isNullOrBlank()) {
                val imdb = Regex("""(?is)\bIMDb\b[^\d]{0,50}(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)""")
                    .find(explicit)?.groupValues?.getOrNull(1)
                val numeric = (imdb ?: explicit).let {
                    Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                        .find(it)?.value
                }?.replace(',', '.')?.toDoubleOrNull()
                if (numeric != null && numeric in 0.0..10.0) {
                    return runCatching { Score.from10(numeric) }.getOrNull()
                }
            }
            extractScore(node.text())?.let { return it }
            current = node.parent()
        }

        // Fallback to the site's visible rating if the card has no explicit IMDb value.
        val generic = listOf(
            element.selectFirst("[data-rating]")?.attr("data-rating"),
            element.selectFirst(".rating, .score, .film-puan")?.text(),
            element.attr("aria-label"),
            element.attr("title")
        )
        for (raw in generic) {
            val number = Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                .find(raw.orEmpty())?.value?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (number in 0.0..10.0) return runCatching { Score.from10(number) }.getOrNull()
        }
        return null
    }
    private suspend fun parseCards(
        document: Document,
        base: String,
        query: String? = null,
        candidateAnchors: List<Element>? = null
    ): List<SearchResponse> {
        val output = LinkedHashMap<String, SearchResponse>()
        val detailLookupLimit = if (query != null) 12 else 8
        var detailLookups = 0

        // Even if the website's theme gives its comments widget no useful CSS
        // class, anything after the "Son Yorumlar" heading is not a content card.
        val allElements = document.getAllElements()
        val commentHeadingIndex = allElements.indexOfFirst { element ->
            element.tagName().lowercase(Locale.ROOT) in setOf("h1", "h2", "h3", "h4", "h5", "h6") &&
                isCommentSectionHeading(element.ownText().ifBlank { element.text() })
        }
        val afterCommentHeading = if (commentHeadingIndex >= 0) {
            allElements.drop(commentHeadingIndex + 1).toHashSet()
        } else {
            emptySet()
        }

        for (anchor in candidateAnchors ?: document.select("a[href]")) {
            if (isCommentOrChrome(anchor)) continue
            if (candidateAnchors == null && anchor in afterCommentHeading) continue
            val url = fixUrl(anchor.attr("href"), base) ?: continue
            if (!sameSite(url) || !isFilmUrl(url)) continue
            val key = url.trimEnd('/')
            if (output.containsKey(key)) continue

            val image = imageElementFor(anchor, base)
            val titleText = anchor.attr("title").takeIf { it.isNotBlank() }
                ?: image?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("h2,h3,h4,.title,.film-title,.movie-title")?.text()
                ?: anchor.text()
                .ifBlank { anchor.parent()?.text().orEmpty() }
            val title = cleanTitle(titleText)
            if (title.length < 2 || title.equals("Film izle Hell", true)) continue
            if (query != null && !matchesQuery(title, query)) continue

            val poster = imageFor(anchor, base)
            val cardContext = anchor.parent()?.parent() ?: anchor.parent() ?: anchor
            val cardScore = scoreFromCard(cardContext)
            val detailScore = if (detailLookups < detailLookupLimit) {
                detailLookups++
                fetchImdbScore(url, base)
            } else null
            val score = detailScore ?: cardScore
            Log.d(
                name,
                "Kart puanı: title=" + title.take(60) +
                    ", imdb=" + (detailScore != null) +
                    ", score=" + (score != null)
            )
            output[key] = newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.score = score
            }
        }
        return output.values.toList()
    }

    private fun mergeCardResults(
        preferred: List<SearchResponse>,
        additional: List<SearchResponse>
    ): List<SearchResponse> {
        val merged = LinkedHashMap<String, SearchResponse>()
        for (item in preferred + additional) {
            val key = item.url.trimEnd('/')
            val existing = merged[key]
            if (existing == null) {
                merged[key] = item
            } else {
                if (existing.posterUrl.isNullOrBlank() && !item.posterUrl.isNullOrBlank()) {
                    existing.posterUrl = item.posterUrl
                }
                if (existing.posterHeaders == null && item.posterHeaders != null) {
                    existing.posterHeaders = item.posterHeaders
                }
                if (existing.score == null && item.score != null) {
                    existing.score = item.score
                }
            }
        }
        return merged.values.toList()
    }

    private fun matchesQuery(title: String, query: String): Boolean {
        fun normalize(input: String): String = input.lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val t = normalize(title)
        val q = normalize(query)
        if (q.isBlank()) return false
        if (t.contains(q)) return true
        val parts = q.split(' ').filter { it.length > 1 }
        return parts.isNotEmpty() && parts.all { t.contains(it) }
    }

    private fun pageCandidates(base: String, page: Int): List<String> {
        if (page <= 1) return listOf(base)
        val separator = if (base.contains('?')) "&" else "?"
        return listOf(
            base.trimEnd('/') + "/sayfa/" + page,
            base + separator + "page=" + page,
            base + separator + "p=" + page,
            base + separator + "sayfa=" + page
        ).distinct()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homeRequest = pathOf(request.data).trimEnd('/').isBlank() ||
            request.data.trimEnd('/') == mainUrl.trimEnd('/')
        var contentListingUrl: String? = null

        // The "Tümünü Gör" target is the canonical source for older entries.
        // Rediscover it for page 2+ because HomePageRequest keeps the original homepage URL.
        if (homeRequest && page > 1) {
            val homeDocument = fetchDocument(mainUrl) ?: fetchDocument(mainUrl + "/")
            if (homeDocument != null) {
                contentListingUrl = findNewContentMoreUrl(homeDocument, mainUrl + "/")
            }
        }

        val pageBase = if (homeRequest && page > 1 && !contentListingUrl.isNullOrBlank()) {
            contentListingUrl!!
        } else {
            request.data
        }
        val urls = pageCandidates(pageBase, page)

        for (url in urls) {
            val doc = fetchDocument(url) ?: continue
            val baseSection = newContentHeading(doc)
            val scopedAnchors = if (homeRequest && page == 1 && baseSection != null) {
                linksAfterHeading(doc, baseSection, url).filter { anchor ->
                    val target = fixUrl(anchor.attr("href"), url)
                    target != null && sameSite(target) && isFilmUrl(target) && !isCommentOrChrome(anchor)
                }
            } else {
                null
            }

            var items = parseCards(
                doc,
                url,
                candidateAnchors = scopedAnchors?.takeIf { it.isNotEmpty() }
            )

            if (homeRequest && page == 1) {
                val moreUrl = findNewContentMoreUrl(doc, url)
                if (!moreUrl.isNullOrBlank() && moreUrl.trimEnd('/') != url.trimEnd('/')) {
                    val moreDocument = fetchDocument(moreUrl, url)
                    if (moreDocument != null) {
                        val moreHeading = newContentHeading(moreDocument)
                        val moreAnchors = if (moreHeading != null) {
                            linksAfterHeading(moreDocument, moreHeading, moreUrl).filter { anchor ->
                                val target = fixUrl(anchor.attr("href"), moreUrl)
                                target != null && sameSite(target) && isFilmUrl(target) &&
                                    !isCommentOrChrome(anchor)
                            }
                        } else {
                            null
                        }
                        val moreItems = parseCards(
                            moreDocument,
                            moreUrl,
                            candidateAnchors = moreAnchors?.takeIf { it.isNotEmpty() }
                        )
                        if (moreItems.isNotEmpty()) {
                            Log.d(
                                name,
                                "Tümünü Gör listesi: url=" + safeLogUrl(moreUrl) +
                                    ", section=" + (moreHeading?.text().orEmpty()) +
                                    ", count=" + moreItems.size
                            )
                            items = mergeCardResults(items, moreItems)
                        } else {
                            Log.d(name, "Tümünü Gör sayfasında içerik bulunamadı: " + safeLogUrl(moreUrl))
                        }
                    }
                }
            }

            Log.d(
                name,
                "Ana sayfa bölümü: request=" + request.name +
                    ", page=" + page +
                    ", section=" + baseSection?.text().orEmpty() +
                    ", items=" + items.size
            )
            if (items.isNotEmpty() || page == 1) {
                val hasMore = page < 30 && (items.isNotEmpty() || contentListingUrl != null)
                return newHomePageResponse(request.name, items, hasNext = hasMore)
            }
        }
        return null
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

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/arama?q=" + encoded,
            "$mainUrl/search?q=" + encoded,
            "$mainUrl/ara?q=" + encoded,
            "$mainUrl/?q=" + encoded,
            "$mainUrl/?s=" + encoded,
            "$mainUrl/ara/" + encoded
        ).distinct()

        for (url in urls) {
            val doc = fetchDocument(url) ?: continue
            val results = parseCards(doc, url, q).filter { matchesSearchQuery(q, it.name) }
            if (results.isNotEmpty()) return newSearchResponseList(results, false)
        }

        // Site şablonundaki arama formunun gerçek action/name alanlarını dene.
        val home = fetchDocument(mainUrl) ?: return newSearchResponseList(emptyList(), false)
        for (form in home.select("form")) {
            val input = form.select("input[name]").firstOrNull {
                val nameAttr = it.attr("name").lowercase(Locale.ROOT)
                val hint = (nameAttr + " " + it.attr("placeholder") + " " + it.attr("type")).lowercase(Locale.ROOT)
                hint.contains("search") || hint.contains("query") || hint.contains("ara") || hint.contains("keyword") || hint == "q text"
            } ?: continue
            val action = fixUrl(form.attr("action").ifBlank { mainUrl }, mainUrl) ?: continue
            val field = input.attr("name").ifBlank { "q" }
            val formUrl = action + (if (action.contains('?')) "&" else "?") +
                URLEncoder.encode(field, "UTF-8") + "=" + encoded
            val doc = fetchDocument(formUrl) ?: continue
            val results = parseCards(doc, formUrl, q)
            if (results.isNotEmpty()) return newSearchResponseList(results, false)
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    private fun detailContext(document: Document): Element {
        val heading = document.selectFirst("main h1, article h1, h1") ?: return document.body()
        var node: Element? = heading
        var fallback: Element = heading.parent() ?: document.body()
        for (i in 0..7) {
            val current = node ?: break
            val text = current.text()
            if (text.length in 50..7000) {
                fallback = current
                val hasMovieMetadata = Regex("""(?i)IMDb|oyuncu kadrosu|fragman|\b\d{4}\b""").containsMatchIn(text)
                if (hasMovieMetadata) return current
            }
            node = current.parent()
        }
        return fallback
    }

    private fun parseYear(document: Document, context: Element): Int? {
        val sources = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title(),
            document.selectFirst("time[datetime], [itemprop=datePublished]")?.let {
                it.attr("datetime").ifBlank { it.attr("content") }.ifBlank { it.text() }
            },
            context.text()
        )
        for (source in sources) {
            val year = yearRegex.find(source.orEmpty())?.value?.toIntOrNull()
            if (year != null && year in 1870..2100) return year
        }
        return null
    }

    private fun parseRuntime(document: Document, context: Element): Int? {
        val text = context.text() + " " + document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        val minutes = Regex("""(?i)(\d{1,3})\s*dk\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(\d{1,3})\s* dakika""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return minutes?.takeIf { it in 1..600 }
    }

    private fun parseScore(document: Document): Score? {
        val explicit = document.selectFirst(
            "#imdb-rating, .imdb-rating, .imdb-score, [data-imdb], [itemprop=ratingValue]"
        )?.let { element ->
            element.attr("data-imdb").ifBlank { element.attr("content") }
                .ifBlank { element.attr("title") }.ifBlank { element.text() }
        }
        if (!explicit.isNullOrBlank()) {
            val match = Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                .find(explicit)?.value?.replace(',', '.')?.toDoubleOrNull()
            if (match != null && match in 0.0..10.0) return runCatching { Score.from10(match) }.getOrNull()
        }
        val labelled = Regex("""(?is)\bIMDb\b[^\d]{0,60}(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)""")
            .find(document.text())?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
        if (labelled != null && labelled in 0.0..10.0) return runCatching { Score.from10(labelled) }.getOrNull()
        return null
    }

    private fun parsePoster(document: Document, context: Element, base: String): String? {
        val poster = context.selectFirst(
            ".poster img, .film-poster img, .movie-poster img, img[alt*=Poster], img[alt*=Afiş], img[itemprop=image]"
        ) ?: document.selectFirst(
            ".poster img, .film-poster img, .movie-poster img, img[alt*=Poster], img[alt*=Afiş], img[itemprop=image], img[src*=storage/posters], img[data-src*=storage/posters]"
        )
        imageUrl(poster, base)?.let { return it }
        val og = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
        imageUrlFromRaw(og, base)?.let { return it }
        return context.select("img").firstNotNullOfOrNull { imageUrl(it, base) }
    }

    private fun imageUrlFromRaw(raw: String?, base: String): String? {
        val fixed = fixUrl(raw, base) ?: return null
        val path = pathOf(fixed).lowercase(Locale.ROOT)
        if (listOf("/icons/", "/avatars/", "/favicon", "/logo").any { path.contains(it) }) return null
        return fixed
    }

    private fun parsePlot(document: Document, context: Element): String? {
        val candidate = context.selectFirst(
            ".film-description, .movie-description, .description, .overview, .plot, .summary, " +
                "[itemprop=description], #description, .film-plot"
        )?.text()?.trim()?.takeIf { it.length > 30 }
        if (candidate != null) return candidate
        return document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()?.takeIf { it.length > 20 }
    }

    private fun parseGenres(document: Document, context: Element): List<String> {
        val result = LinkedHashSet<String>()
        val genreSelectors =
            "a[href*=/tur/], a[href*=/kategori/], a[href*=/genre/], a[href*=/tag/], [itemprop=genre]"
        val detailSelectors =
            ".genres a, .genre a, .film-genres a, .movie-genres a, .film-meta a, " +
                ".movie-meta a, .detail-meta a, .movie-details a, .film-details a, " +
                ".film-info a, .movie-info a, .meta-info a, .tags a"
        val anchors = context.select(genreSelectors + ", " + detailSelectors) +
            document.select("main " + detailSelectors + ", article " + detailSelectors)
        fun addGenre(raw: String?) {
            val text = cleanText(raw)
                .replace(Regex("""(?i)\s+filmleri?$"""), "")
                .replace(Regex("""(?i)\s+filmi$"""), "")
                .trim(' ', ',', '|', '-', ':')
            if (text.length !in 2..35 || text.equals("Filmler", true) ||
                text.contains("tüm tür", true) || text.contains("kategori", true)
            ) return
            result += text
        }
        for (anchor in anchors) {
            val text = anchor.attr("content").ifBlank { anchor.attr("title") }.ifBlank { anchor.text() }
            if (anchor.parents().any {
                    it.tagName() in setOf("nav", "header", "footer") ||
                        it.className().contains("site-menu", true) ||
                        it.className().contains("footer-menu", true) ||
                        it.className().contains("main-menu", true)
                }) continue
            addGenre(text)
        }

        // Bazı tema sürümleri türleri görünür bağlantı yerine JSON-LD içinde tutuyor.
        for (script in document.select("script[type=application/ld+json]")) {
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            when (val genre = json.opt("genre")) {
                is String -> genre.split(",", "|", "•").forEach(::addGenre)
                is org.json.JSONArray -> for (i in 0 until genre.length()) {
                    addGenre(genre.optString(i))
                }
            }
        }
        return result.take(15)
    }

    private fun parseActors(document: Document, context: Element): List<ActorData> {
        val names = LinkedHashSet<String>()
        val anchors = context.select("a[href*=/oyuncu/], [itemprop=actor] a, .cast a, .actors a, .film-cast a") +
            document.select("main a[href*=/oyuncu/], article a[href*=/oyuncu/]")
        for (anchor in anchors) {
            val wholeText = cleanText(anchor.text())
            if (wholeText.isBlank() || wholeText.contains("yönetmen", true) || wholeText.contains("director", true)) continue
            val name = cleanText(
                anchor.selectFirst(".actor-name, .person-name, .name")?.text()
                    .takeUnless { it.isNullOrBlank() }
                    ?: anchor.ownText().takeUnless { it.isBlank() }
                    ?: anchor.attr("title").takeUnless { it.isBlank() }
                    ?: wholeText
            ).replace(Regex("""\s{2,}"""), " ")
            if (name.length in 2..70 && !name.contains("Film izle Hell", true)) names += name
        }
        if (names.isEmpty()) {
            val label = document.getAllElements().firstOrNull {
                it.ownText().trim().trimEnd(':').equals("Oyuncu Kadrosu", true) ||
                    it.ownText().trim().trimEnd(':').equals("Oyuncular", true)
            }
            var node: Element? = label
            repeat(3) {
                node = node?.parent() ?: return@repeat
                node?.select("a[href]").orEmpty().forEach { anchor ->
                    val name = cleanText(anchor.ownText().ifBlank { anchor.attr("title") }.ifBlank { anchor.text() })
                    if (name.length in 2..70 && !name.contains("yönetmen", true)) names += name
                }
            }
        }
        return names.take(35).map { ActorData(Actor(it)) }
    }

    private fun parseTrailer(document: Document, base: String): String? {
        val element = document.select("iframe[src], iframe[data-src], a[href], video[src]").firstOrNull { item ->
            val url = item.attr("src").ifBlank { item.attr("data-src") }.ifBlank { item.attr("href") }
            val label = (item.text() + " " + item.attr("title") + " " + item.className()).lowercase(Locale.ROOT)
            val isTrailer = label.contains("fragman") || label.contains("trailer") ||
                url.contains("youtube.com/embed", true) || url.contains("youtu.be/", true)
            isTrailer && (url.contains("youtube", true) || url.contains("youtu.be", true) || url.contains("vimeo", true))
        } ?: return null
        return fixUrl(element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }, base)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching {
            app.get(url, headers = requestHeaders, referer = mainUrl, allowRedirects = true, timeout = 18000)
        }.onFailure { Log.w(name, "Film detay sayfası açılamadı: " + url, it) }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val document = response.document
        val context = detailContext(document)
        val title = cleanTitle(
            document.selectFirst("main h1, article h1, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        )
        if (title.isBlank()) return null

        val poster = parsePoster(document, context, response.url)
        val plot = parsePlot(document, context)
        val year = parseYear(document, context)
        val runtime = parseRuntime(document, context)
        val score = parseScore(document)
        val genres = parseGenres(document, context)
        val actors = parseActors(document, context)
        val trailer = parseTrailer(document, response.url)

        Log.d(
            name,
            "Film bilgisi: title=" + title + ", year=" + year + ", genres=" + genres.size +
                ", actors=" + actors.size + ", score=" + (score != null) + ", poster=" + (poster != null)
        )

        return newMovieLoadResponse(title, response.url, TvType.Movie, response.url) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.duration = runtime
            this.score = score
            this.tags = genres
            this.actors = actors
            addTrailer(trailer)
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        val path = pathOf(url).lowercase(Locale.ROOT)
        if (Regex("""\.(?:m3u8|mp4|mpd|webm)$""").containsMatchIn(path)) return true
        return false
    }

    private fun isStaticAsset(url: String): Boolean {
        val path = pathOf(url).lowercase(Locale.ROOT)
        return listOf(
            "/storage/posters/", "/storage/backgrounds/", "/assets/", "/images/",
            "/icons/", "/avatars/", "/css/", "/js/", "/fonts/", "/favicon"
        ).any { path.contains(it) } ||
            Regex("""\.(?:jpe?g|png|gif|webp|svg|ico|css|js|json|map|xml|txt|woff2?|ttf)(?:$|\?)""").containsMatchIn(url.lowercase(Locale.ROOT))
    }

    private fun isAdOrPreviewUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull()
        val pathAndQuery = (uri?.rawPath.orEmpty() + "?" + uri?.rawQuery.orEmpty())
            .lowercase(Locale.ROOT)
        return Regex(
            """(?:^|[/_.?&=-])(ad|ads|advert|advertisement|preroll|pre-roll|preview|promo|trailer|teaser|sample)(?:$|[/_.?&=-])"""
        ).containsMatchIn(pathAndQuery)
    }

    private fun isRejectedPlayerCandidate(raw: String, fixed: String): Boolean {
        val original = decode(raw).trim().lowercase(Locale.ROOT)
        if (original.isBlank() || original.startsWith("javascript:") ||
            original.contains("getembedurl") || original.contains("activesource") ||
            original.contains("[object") || original.contains("undefined") ||
            original.contains("null") || original.contains("\${") ||
            original.contains("function(") || original.contains("function (")
        ) return true

        val host = runCatching { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val path = pathOf(fixed).lowercase(Locale.ROOT)
        // Trailer embeds must never become the movie playback source.
        // This site places its trailer YouTube iframe alongside the player source buttons.
        val blockedHosts = listOf(
            "googletagmanager.com", "google-analytics.com", "accounts.google.com",
            "static.cloudflareinsights.com", "github.com", "githubusercontent.com",
            "fonts.googleapis.com", "fonts.gstatic.com", "doubleclick.net",
            "googlesyndication.com", "googleadservices.com", "facebook.com",
            "connect.facebook.net", "t.me", "telegram.org", "twitter.com",
            "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com",
            "youtube.com", "youtube-nocookie.com", "youtu.be", "youtube.googleapis.com",
            "googlevideo.com", "ytimg.com", "gstatic.com"
        )
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        if (host == "filmizlehell.net" || host == "www.filmizlehell.net") {
            if (path.contains("/film/getembedurl") || path.contains("/film/get-embed-url")) return true
        }
        if (path == "/gtag/js" || path == "/gsi/client" ||
            path.endsWith("/beacon.min.js") || path.endsWith(".js") ||
            path.endsWith(".css") || path.endsWith(".map")
        ) return true
        return false
    }

    private fun addPlayerCandidate(raw: String?, base: String, direct: MutableSet<String>, embeds: MutableSet<String>) {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'').trimEnd(';', ',')
        if (value.isBlank() || value.length > 1800) return
        val fixed = fixUrl(value, base) ?: return
        if (isRejectedPlayerCandidate(value, fixed) || isStaticAsset(fixed)) return

        // Never expose an unsigned Playturka master directly. The raw path can
        // resolve to a short preview; only its per-video signed URL is a valid
        // full playback candidate.
        val fixedHost = runCatching { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        val playturkaId = Regex("""(?i)^/videos/([A-Za-z0-9_-]{5,64})/master\.m3u8$""")
            .find(pathOf(fixed))
            ?.groupValues?.getOrNull(1)
        if (
            fixedHost == "p.playturka.space" &&
            playturkaId != null &&
            !isSignedPlayturkaMaster(fixed, playturkaId)
        ) {
            Log.d(name, "İmzasız Playturka master atlandı: " + safeLogUrl(fixed))
            return
        }

        if (isDirectMedia(fixed)) {
            if (!fixed.contains("site.webm", true) && !fixed.contains("logo.webm", true)) direct += fixed
            return
        }
        val path = pathOf(fixed).lowercase(Locale.ROOT)
        val host = runCatching { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host.isBlank() || host.contains("doubleclick") || host.contains("googlesyndication") ||
            host.contains("google-analytics") || host.contains("facebook.com") || host.contains("t.me")
        ) return
        if (isFilmUrl(fixed)) return
        if (path.contains("embed") || path.contains("player") || path.contains("video") ||
            path.contains("stream") || path.endsWith(".php") || path.endsWith(".html") ||
            path.endsWith(".aspx") || path.endsWith(".asp")
        ) embeds += fixed
        else if (host != "filmizlehell.net" && host != "www.filmizlehell.net") embeds += fixed
    }

    private fun collectPlayerCandidates(document: Document, html: String, base: String): Pair<Set<String>, Set<String>> {
        val direct = LinkedHashSet<String>()
        val embeds = LinkedHashSet<String>()

        val nodes = document.select(
            "iframe[src], iframe[data-src], iframe[data-url], video[src], video[data-src], " +
                "source[src], source[data-src], embed[src], [data-embed], [data-player], [data-video], " +
                "[data-iframe], [data-source], [data-source-url], [data-source-id], [data-server], " +
                "[data-server-id], [data-stream], [data-stream-id], [data-video-id], [data-file], " +
                "button[onclick], [onclick]"
        )
        val attrs = listOf(
            "src", "data-src", "data-url", "data-embed", "data-player", "data-video",
            "data-iframe", "data-iframe-src", "data-href", "data-link", "data-file", "data-hls",
            "data-source", "data-source-url", "data-source-id", "data-server", "data-server-id",
            "data-stream", "data-stream-id", "data-video-id", "data-media-id", "data-video"
        )
        for (element in nodes) {
            for (attr in attrs) {
                if (element.hasAttr(attr)) addPlayerCandidate(element.attr(attr), base, direct, embeds)
            }
        }

        document.select("track[src], track[data-src]").forEach { track ->
            // Track URLs are handled as subtitles in loadLinks; never treat them as video embeds.
            if (track.hasAttr("src") || track.hasAttr("data-src")) Unit
        }

        val decoded = decode(html)
        mediaRegex.findAll(decoded).forEach { addPlayerCandidate(it.value, base, direct, embeds) }
        val baseHost = runCatching { URI(base).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (baseHost == "p.playturka.space" || decoded.contains("p.playturka.space", true)) {
            Regex("""(?i)(/videos/[A-Za-z0-9_-]{1,64}/(?:master|index)\.m3u8(?:\?[^"'<>\\\s]*)?)""")
                .findAll(decoded)
                .forEach { addPlayerCandidate(it.groupValues[1], base, direct, embeds) }
        }
        Regex("""(?is)(?:src|file|url|hls|dash|embed|iframe|player|video|source|stream)\s*["']?\s*[:=]\s*["']([^"'<>]{5,1200})["']""")
            .findAll(decoded).forEach { match ->
                addPlayerCandidate(match.groupValues[1], base, direct, embeds)
            }

        document.select("a[href]").forEach { anchor ->
            val label = (anchor.text() + " " + anchor.attr("title") + " " + anchor.className()).lowercase(Locale.ROOT)
            if (label.contains("kaynak") || label.contains("izle") || label.contains("source") ||
                label.contains("server") || label.contains("oynat") || label.contains("başlat")
            ) {
                addPlayerCandidate(anchor.attr("href"), base, direct, embeds)
                attrs.forEach { attr ->
                    if (anchor.hasAttr(attr)) addPlayerCandidate(anchor.attr(attr), base, direct, embeds)
                }
            }
        }
        return direct to embeds
    }

    private fun safeLogUrl(raw: String): String =
        runCatching {
            val uri = URI(raw)
            val keys = uri.rawQuery.orEmpty().split('&')
                .filter { it.isNotBlank() }
                .map { it.substringBefore('=').take(32) }
                .distinct()
                .take(8)
            val queryHint = if (keys.isEmpty()) "" else keys.joinToString(
                prefix = "?",
                separator = "&"
            ) { "$it=*" }
            (uri.host.orEmpty() + uri.path.orEmpty() + queryHint).take(260)
        }.getOrDefault(raw.substringBefore('?').take(240))

    private fun extractPlayturkaMasterUrls(
        embedUrl: String,
        document: Document,
        html: String
    ): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()
        val decoded = decode(html)
        val absolutePattern = Regex(
            """(?i)(?:https?:)?//p\.playturka\.space/videos/([A-Za-z0-9_-]{1,64})/master\.m3u8(?:\?[^\s"'<>]*)?"""
        )

        absolutePattern.findAll(decoded).forEach { match ->
            val rawUrl = match.value
            val id = match.groupValues.getOrNull(1).orEmpty()
            val fixed = fixUrl(rawUrl, embedUrl) ?: return@forEach
            if (id.isNotBlank() && isDirectMedia(fixed)) result.putIfAbsent(id, fixed)
        }

        val host = runCatching {
            URI(embedUrl).host.orEmpty().lowercase(Locale.ROOT)
        }.getOrDefault("")
        if (host == "p.playturka.space") {
            val relativePattern = Regex(
                """(?i)(/videos/([A-Za-z0-9_-]{1,64})/master\.m3u8(?:\?[^\s"'<>]*)?)"""
            )
            relativePattern.findAll(decoded).forEach { match ->
                val fixed = fixUrl(match.groupValues[1], embedUrl) ?: return@forEach
                val id = match.groupValues.getOrNull(2).orEmpty()
                if (id.isNotBlank() && isDirectMedia(fixed)) result.putIfAbsent(id, fixed)
            }

            document.select(
                "a[href], iframe[src], iframe[data-src], video[src], source[src], " +
                    "[data-src], [data-url], [data-hls], [data-file]"
            ).forEach { element ->
                val raw = listOf(
                    "href", "src", "data-src", "data-url", "data-hls", "data-file"
                ).asSequence()
                    .map { element.attr(it).trim() }
                    .firstOrNull {
                        it.contains("/videos/", true) &&
                            it.contains("/master.m3u8", true)
                    } ?: return@forEach
                val fixed = fixUrl(raw, embedUrl) ?: return@forEach
                val id = Regex("""(?i)/videos/([A-Za-z0-9_-]{5,64})/master\.m3u8""")
                    .find(fixed)?.groupValues?.getOrNull(1).orEmpty()
                if (id.isNotBlank()) result.putIfAbsent(id, fixed)
            }
        }

        return result.map { (id, url) -> id to url }
    }

    private fun extractPlayturkaIds(embedUrl: String, document: Document, html: String): Set<String> {
        val host = runCatching { URI(embedUrl).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host != "p.playturka.space" && !html.contains("p.playturka.space", true)) return emptySet()

        // Playturka video IDs are opaque alphanumeric keys (e.g. e9J4f52).
        // Short values such as activeSource=2 are source indexes, not video IDs.
        val ids = LinkedHashSet<String>()
        val acceptedId = Regex("""^[A-Za-z0-9_-]{5,64}$""")
        val rawFragment = runCatching { URI(embedUrl).rawFragment.orEmpty() }.getOrDefault("").trim()
        val decodedFragment = runCatching { java.net.URLDecoder.decode(rawFragment, "UTF-8") }
            .getOrDefault(rawFragment).trim().removePrefix("#")
        if (acceptedId.matches(decodedFragment)) ids.add(decodedFragment)
        Regex("""(?i)(?:video[_-]?id|source[_-]?id|stream[_-]?id)=([A-Za-z0-9_-]{5,64})""")
            .find(decodedFragment)?.groupValues?.getOrNull(1)?.let { if (acceptedId.matches(it)) ids.add(it) }

        // A token or cache-busting timestamp is never a video ID.
        val queryNames = setOf(
            "id", "vid", "video", "video_id", "videoid", "source", "source_id",
            "sourceid", "stream", "stream_id", "streamid", "media", "media_id"
        )
        runCatching { URI(embedUrl).rawQuery.orEmpty() }.getOrDefault("")
            .split('&')
            .forEach { pair ->
                val key = pair.substringBefore('=').lowercase(Locale.ROOT)
                if (key in queryNames) {
                    val rawValue = pair.substringAfter('=', "")
                    val value = runCatching { java.net.URLDecoder.decode(rawValue, "UTF-8") }
                        .getOrDefault(rawValue).trim()
                    if (acceptedId.matches(value)) ids += value
                }
            }

        val idAttributes = listOf(
            "data-video-id", "data-source-id", "data-stream-id", "data-media-id",
            "data-file-id", "data-id", "value",
            "data-video", "data-source", "data-stream", "data-file"
        )
        for (element in document.select(
            "[data-video-id], [data-source-id], [data-stream-id], [data-media-id], [data-file-id], " +
                "[data-id], [data-video], [data-source], [data-stream], [data-file], input[name*=video], input[name*=source]"
        )) {
            for (attr in idAttributes) {
                val raw = element.attr(attr).trim()
                if (acceptedId.matches(raw)) ids += raw
            }
        }

        val patterns = listOf(
            Regex("""(?i)(?:https?:)?//p\.playturka\.space/[^\s#]*#([A-Za-z0-9_-]{5,64})"""),
            Regex("""(?i)p\.playturka\.space[^\s]{0,180}?#([A-Za-z0-9_-]{5,64})"""),
            Regex("""(?i)(?:video[_-]?id|source[_-]?id|stream[_-]?id|media[_-]?id|file[_-]?id)\s*["']?\s*[:=]\s*["']([A-Za-z0-9_-]{5,64})["']"""),
            Regex("""(?i)/videos/([A-Za-z0-9_-]{5,64})/(?:master\.m3u8|index\.m3u8)""")
        )
        for (pattern in patterns) {
            pattern.findAll(decode(html)).forEach { match ->
                match.groupValues.getOrNull(1)?.takeIf { acceptedId.matches(it) }?.let(ids::add)
            }
        }
        return ids
    }

    private fun findImdbId(document: Document, expectedTitle: String): String? {
        val imdbPattern = Regex("""(?i)imdb\.com/title/(tt\d{7,10})""")
        val expected = normalizeSectionText(expectedTitle)
        val context = detailContext(document)

        // First choice: IMDb links belonging to the current movie detail block.
        // Do not scan the whole HTML with a loose regex: recommended film cards
        // can contain unrelated IMDb IDs and poison every subtitle search.
        for (anchor in context.select("a[href*=imdb.com/title/], a[href*=imdb.com/title]")) {
            imdbPattern.find(decode(anchor.attr("href")))?.groupValues?.getOrNull(1)?.let { return it }
        }

        fun titleMatches(candidate: String): Boolean {
            val normalized = normalizeSectionText(candidate)
            return normalized.length >= 3 && expected.length >= 3 &&
                (normalized == expected || normalized.contains(expected) || expected.contains(normalized))
        }

        fun idFromStructuredData(value: Any?): String? {
            when (value) {
                is JSONObject -> {
                    val objectName = value.optString("name")
                    if (titleMatches(objectName)) {
                        val references = value.optString("url") + " " + value.optString("sameAs")
                        imdbPattern.find(decode(references))?.groupValues?.getOrNull(1)?.let { return it }
                    }
                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        idFromStructuredData(value.opt(key))?.let { return it }
                    }
                }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        idFromStructuredData(value.opt(index))?.let { return it }
                    }
                }
            }
            return null
        }

        // JSON-LD is only accepted when its name belongs to the current movie.
        for (script in document.select("script[type=application/ld+json]")) {
            val json = runCatching { JSONObject(script.data()) }.getOrNull()
            idFromStructuredData(json)?.let { return it }
            val array = runCatching { org.json.JSONArray(script.data()) }.getOrNull()
            idFromStructuredData(array)?.let { return it }
        }

        // Keep page metadata as a final safe fallback, but never scrape arbitrary
        // unrelated page HTML for an IMDb ID.
        val metadata = document.select("meta[property=og:url], meta[name=twitter:url], link[rel=canonical]")
            .joinToString(" ") { it.attr("content").ifBlank { it.attr("href") } }
        return imdbPattern.find(decode(metadata))?.groupValues?.getOrNull(1)
    }

    private fun subtitleTitleMatches(expected: String, candidate: String): Boolean {
        fun normalizeSubtitleTitle(value: String): String =
            normalizeSectionText(value)
                .replace(yearRegex, " ")
                .replace(
                    Regex("""\b(izle|turkce|turkce dublaj|dublaj|altyazili|altyazi|film|movie|1080p|720p|480p|bluray|brrip|webrip|webdl|hdtv|x264|x265|yify|yts)\b"""),
                    " "
                )
                .replace(Regex("""\s+"""), " ")
                .trim()

        val expectedTitle = normalizeSubtitleTitle(expected)
        val candidateTitle = normalizeSubtitleTitle(candidate)
        if (expectedTitle.length < 3 || candidateTitle.length < 3) return false
        if (expectedTitle == candidateTitle ||
            expectedTitle.contains(candidateTitle) ||
            candidateTitle.contains(expectedTitle)
        ) return true

        val ignored = setOf("the", "and", "for", "with", "from", "into", "this", "that")
        val expectedTokens = expectedTitle.split(' ').filter { it.length >= 3 && it !in ignored }.toSet()
        val candidateTokens = candidateTitle.split(' ').filter { it.length >= 3 && it !in ignored }.toSet()
        if (expectedTokens.isEmpty() || candidateTokens.isEmpty()) return false
        val overlap = expectedTokens.intersect(candidateTokens).size.toDouble() / expectedTokens.size.toDouble()
        return overlap >= 0.6
    }

    private suspend fun publishFallbackTurkishSubtitles(
        moviePageUrl: String,
        seenSubtitles: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ): Int {
        val movieResponse = runCatching {
            app.get(
                moviePageUrl,
                headers = requestHeaders + ("Referer" to "$mainUrl/"),
                referer = "$mainUrl/",
                allowRedirects = true,
                timeout = 10000
            )
        }.onFailure {
            Log.d(name, "Harici altyazı film sayfası alınamadı: " + it.javaClass.simpleName)
        }.getOrNull() ?: return 0

        if (!movieResponse.isSuccessful) {
            Log.d(name, "Harici altyazı film sayfası HTTP=" + movieResponse.code)
            return 0
        }

        val document = movieResponse.document
        val context = detailContext(document)
        val title = cleanTitle(
            document.selectFirst("main h1, article h1, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        )
        if (title.length < 3) {
            Log.d(name, "Harici altyazı araması atlandı: film başlığı bulunamadı")
            return 0
        }

        val imdbId = findImdbId(document, title)
        val year = parseYear(document, context)
        val cacheKey = (imdbId ?: normalizeSectionText(title)) + ":" + (year ?: 0)
        val now = System.currentTimeMillis()
        val cached = synchronized(externalSubtitleCacheLock) {
            externalSubtitleCache[cacheKey]?.takeIf { it.first > now }?.second
        }
        if (cached != null) {
            var cachedEmitted = 0
            for ((cachedLanguage, cachedUrl) in cached) {
                if (!seenSubtitles.add(cachedUrl)) continue
                callback(newSubtitleFile(cachedLanguage, cachedUrl))
                cachedEmitted++
            }
            Log.d(name, "Harici altyazı önbelleği: title=" + title.take(55) +
                ", result=" + cached.size + ", emitted=" + cachedEmitted)
            return cachedEmitted
        }

        Log.d(name, "Harici altyazı araması: title=" + title.take(70) +
            ", imdb=" + (imdbId ?: "yok") + ", year=" + (year ?: 0))

        val foundSubtitles = LinkedHashMap<String, Pair<String, String>>()
        var successfulCatalogResponse = false
        var apiRateLimited = false

        fun addSubtitle(rawUrl: String, sourceTitle: String, language: String = "tr", formatHint: String = "") {
            if (foundSubtitles.size >= 3) return
            val trimmed = decode(rawUrl).trim()
            if (trimmed.isBlank()) return

            val url = when {
                trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true) -> trimmed
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/strapi/") -> "https://subtitles.website$trimmed"
                trimmed.startsWith("/uploads/") -> "https://subtitles.website/strapi$trimmed"
                else -> fixUrl(trimmed, "https://subtitles.website/") ?: return
            }

            val extMatch = Regex("""(?i)\.(srt|vtt|ass)(?:[?#].*)?$""").find(url)
            val urlFormat = extMatch?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT).orEmpty()
            val expectedFormat = formatHint.trim().lowercase(Locale.ROOT)
            val format = urlFormat.ifBlank { expectedFormat }
            if (format !in setOf("srt", "vtt", "ass")) {
                Log.d(name, "Harici altyazı URL'sinde desteklenen uzantı yok: " +
                    url.substringBefore('?').take(140))
                return
            }

            // Both catalog endpoints are explicitly filtered by language=tr;
            // when their schema omits lang, keep the requested Turkish language.
            val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
            val isTurkish = normalizedLanguage.isBlank() ||
                normalizedLanguage == "tr" || normalizedLanguage == "tur" ||
                normalizedLanguage == "turkish" || normalizedLanguage == "turkce" ||
                normalizedLanguage.startsWith("tr-") || normalizedLanguage.startsWith("tr_") ||
                normalizedLanguage.contains("turkish") || normalizedLanguage.contains("türkçe")
            if (!isTurkish) return

            if (!foundSubtitles.containsKey(url)) {
                foundSubtitles[url] = "Turkish" to url
                Log.d(name, "Harici Türkçe altyazı adayı: " + sourceTitle.take(50) +
                    ", url=" + url.substringBefore('?').take(140))
            }
        }

        // This is the language-filtered endpoint documented by the provider.
        // Use it first and avoid also calling the all-language Stremio endpoint;
        // duplicate requests can exhaust the anonymous daily API quota.
        if (!imdbId.isNullOrBlank()) {
            val endpoint = "https://subtitles.website/strapi/api/search/stremio/" +
                imdbId + "/tr"
            val response = runCatching {
                app.get(
                    endpoint,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json",
                        "Referer" to "https://subtitles.website/"
                    ),
                    referer = "https://subtitles.website/",
                    allowRedirects = true,
                    timeout = 9000
                )
            }.onFailure {
                Log.d(name, "Türkçe katalog bağlantısı başarısız: " + it.javaClass.simpleName)
            }.getOrNull()

            if (response != null) {
                if (response.code == 429) {
                    apiRateLimited = true
                    Log.w(name, "Harici altyazı API kotası dolu (HTTP 429)")
                } else if (response.isSuccessful) {
                    successfulCatalogResponse = true
                    val subtitles = runCatching {
                        JSONObject(response.text).optJSONArray("subtitles")
                    }.getOrNull()
                    if (subtitles != null) {
                        for (index in 0 until subtitles.length()) {
                            if (foundSubtitles.size >= 3) break
                            val item = subtitles.optJSONObject(index) ?: continue
                            addSubtitle(
                                rawUrl = item.optString("url"),
                                sourceTitle = item.optString("name").ifBlank { title },
                                language = item.optString("lang").ifBlank { "tr" }
                            )
                        }
                        Log.d(name, "Türkçe katalog: HTTP=" + response.code +
                            ", results=" + subtitles.length() + ", usable=" + foundSubtitles.size)
                    } else {
                        Log.d(name, "Türkçe katalog JSON içinde subtitles listesi yok; HTTP=" + response.code)
                    }
                } else {
                    Log.d(name, "Türkçe katalog HTTP=" + response.code)
                }
            }
        }

        // One secondary request only. If the IMDb route has no Turkish subtitle,
        // a title lookup can still find entries filed under a regional title.
        // If the IMDb metadata could not be determined, title search is the primary query.
        if (foundSubtitles.isEmpty() && !apiRateLimited) {
            val searchType = "title"
            val queryValue = title
            val query = URLEncoder.encode(queryValue, "UTF-8")
            val endpoint = "https://subtitles.website/strapi/api/search/subtitles?" +
                searchType + "=" + query + "&language=tr"

            val response = runCatching {
                app.get(
                    endpoint,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json",
                        "Referer" to "https://subtitles.website/"
                    ),
                    referer = "https://subtitles.website/",
                    allowRedirects = true,
                    timeout = 10000
                )
            }.onFailure {
                Log.d(name, "Başlıkla altyazı araması başarısız: " + it.javaClass.simpleName)
            }.getOrNull()

            if (response != null) {
                if (response.code == 429) {
                    apiRateLimited = true
                    Log.w(name, "Harici altyazı API kotası dolu (HTTP 429)")
                } else if (response.isSuccessful) {
                    successfulCatalogResponse = true
                    val data = runCatching {
                        JSONObject(response.text).optJSONArray("data")
                    }.getOrNull()

                    if (data != null) {
                        for (index in 0 until data.length()) {
                            if (foundSubtitles.size >= 3) break
                            val item = data.optJSONObject(index) ?: continue
                            val resultTitle = item.optString("film_title").ifBlank {
                                item.optString("title")
                            }.ifBlank {
                                item.optString("release")
                            }
                            if (!subtitleTitleMatches(title, resultTitle)) {
                                Log.d(name, "Harici altyazı başlık eşleşmedi: " + resultTitle.take(60))
                                continue
                            }

                            val listedYear = item.optInt("year", 0)
                            if (year != null && listedYear > 0 && listedYear != year) continue

                            val languageObject = item.optJSONObject("language")
                            val language = languageObject?.optString("code").orEmpty()
                                .ifBlank { languageObject?.optString("name").orEmpty() }
                                .ifBlank { item.optString("language_code") }
                                .ifBlank { item.optString("languageCode") }
                                .ifBlank { item.optString("lang") }
                                .ifBlank { "tr" }
                            val format = item.optString("format").lowercase(Locale.ROOT)
                            if (format.isNotBlank() && format !in setOf("srt", "vtt", "ass")) continue

                            var rawUrl = item.optString("download_url")
                            if (rawUrl.isBlank()) {
                                Log.d(name, "Harici altyazı sonucunda download_url yok; id=" +
                                    item.optString("id").take(48))
                                continue
                            }
                            if (rawUrl.startsWith("/uploads/")) {
                                rawUrl = "https://subtitles.website/strapi" + rawUrl
                            } else if (rawUrl.startsWith("/strapi/")) {
                                rawUrl = "https://subtitles.website" + rawUrl
                            }

                            addSubtitle(
                                rawUrl = rawUrl,
                                sourceTitle = resultTitle.ifBlank { title },
                                language = language,
                                formatHint = format
                            )
                        }
                        Log.d(name, "Başlık kataloğu: HTTP=" + response.code +
                            ", results=" + data.length() + ", usable=" + foundSubtitles.size)
                    } else {
                        Log.d(name, "Başlık kataloğu JSON içinde data listesi yok; HTTP=" + response.code)
                    }
                } else {
                    Log.d(name, "Başlık kataloğu HTTP=" + response.code)
                }
            }
        }

        val emitted = ArrayList<Pair<String, String>>()
        for ((url, pair) in foundSubtitles) {
            val (language, subtitleUrl) = pair
            emitted.add(language to subtitleUrl)
            if (!seenSubtitles.add(subtitleUrl)) continue
            callback(newSubtitleFile(language, subtitleUrl))
        }

        // Cache successful *and empty* responses so repeated play attempts don't burn
        // through the anonymous daily API limit. A short TTL lets a newly uploaded subtitle appear later.
        if (successfulCatalogResponse || apiRateLimited) {
            val ttl = if (apiRateLimited) 15 * 60 * 1000L else 60 * 60 * 1000L
            synchronized(externalSubtitleCacheLock) {
                externalSubtitleCache[cacheKey] = (System.currentTimeMillis() + ttl) to emitted
            }
        }

        Log.d(name, "Harici Türkçe altyazı sonucu: title=" + title.take(60) +
            ", imdb=" + (imdbId ?: "yok") + ", added=" + emitted.size +
            ", rateLimited=" + apiRateLimited)
        return emitted.size
    }

    private data class PlayturkaPlayerConfig(
        val subtitleCount: Int,
        val visitorId: String?,
        val siteKey: String?,
        val playbackCookie: String?
    )

    private fun playturkaCookieHeader(setCookie: String?): String? {
        val source = setCookie.orEmpty()
        val names = listOf("PHPSESSID", "am_visitor_id")
        val cookies = names.mapNotNull { cookieName ->
            val pattern = Regex(
                """(?i)(?:^|[,;]\s*)""" + Regex.escape(cookieName) + """=([^;,\s]+)"""
            )
            pattern.find(source)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { cookieName + "=" + it }
        }
        return cookies.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private suspend fun publishPlayturkaApiSubtitles(
        id: String,
        referer: String,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        scanScripts: Boolean = true
    ): PlayturkaPlayerConfig {
        // The visible Playturka player can expose subtitle tracks in its HTML/JS
        // player page even when its master.m3u8 contains no EXT-X-MEDIA entries.
        // Read only the public player page and its ordinary JS assets; don't try
        // to decode the opaque /api/video-url response.
        val playerUrl = "https://p.playturka.space/api/player?id=" + URLEncoder.encode(id, "UTF-8")
        val before = seenSubtitles.size
        val response = runCatching {
            app.get(
                playerUrl,
                headers = requestHeaders + mapOf("Accept" to "text/html,*/*", "Referer" to referer),
                referer = referer,
                allowRedirects = true,
                timeout = 8000
            )
        }.onFailure {
            Log.d(name, "Playturka player sayfası alınamadı: " + safeLogUrl(playerUrl))
        }.getOrNull() ?: return PlayturkaPlayerConfig(0, null, null, null)

        if (!response.isSuccessful || response.text.isBlank() || response.text.length > 4_000_000) {
            Log.d(
                name,
                "Playturka player sayfası atlandı: " + safeLogUrl(playerUrl) +
                    ", HTTP=" + response.code
            )
            return PlayturkaPlayerConfig(0, null, null, null)
        }

        val playerCookie = response.headers["Set-Cookie"]
        val playbackCookie = playturkaCookieHeader(playerCookie)
        val playerVisitor = playturkaVisitorId(playerCookie)
            ?: playturkaVisitorFromUrlOrHtml(response.url)
            ?: findPlayturkaJsonString(
                response.text, setOf("visitor_id", "visitorId", "AM_VISITOR_ID", "am_vid")
            )
        val playerSiteKey = playturkaSiteKey(response.url, response.text)
        val playerShape = runCatching {
            val normalized = response.text.trimStart('\uFEFF').trim().removePrefix(")]}',").trim()
            summarizePlayturkaApiValue(org.json.JSONTokener(normalized).nextValue())
        }.getOrElse { "non-json" }
        val shortPlayerBody = response.text.trim().replace(Regex("""\s+"""), " ")
            .takeIf { response.text.length <= 24 } ?: "<omitted>"
        Log.d(name, "Playturka player config: id=" + id +
            ", HTTP=" + response.code +
            ", type=" + response.headers["content-type"].orEmpty().take(60) +
            ", shape=" + playerShape +
            ", shortBody=" + shortPlayerBody +
            ", visitorIdPresent=" + !playerVisitor.isNullOrBlank() +
            ", siteKeyPresent=" + !playerSiteKey.isNullOrBlank() +
            ", cookieNames=" + playturkaCookieNames(playerCookie) +
            ", playbackCookiePresent=" + !playbackCookie.isNullOrBlank())

        publishSubtitles(
            response.document,
            response.text,
            response.url,
            seenSubtitles,
            subtitleCallback
        )

        // The page can register caption tracks inside player JS rather than
        // placing <track> elements in the initial HTML. Search the small set of
        // player-related scripts explicitly, because filenames such as bundle.js
        // may not contain the words "subtitle" or "player".
        if (scanScripts) {
            val scriptUrls = response.document.select("script[src]")
                .mapNotNull { fixUrl(it.attr("src"), response.url) }
                .distinct()
                .filter { pathOf(it).substringBefore('?').endsWith(".js", true) }
                .sortedByDescending { scriptUrl ->
                    val path = pathOf(scriptUrl).lowercase(Locale.ROOT)
                    listOf("subtitle", "caption", "track", "player", "video", "bundle", "fm", "source", "app")
                        .count { path.contains(it) }
                }
                .take(5)
    
            for (scriptUrl in scriptUrls) {
                val script = runCatching {
                    app.get(
                        scriptUrl,
                        headers = requestHeaders + ("Referer" to response.url),
                        referer = response.url,
                        allowRedirects = true,
                        timeout = 7000
                    )
                }.onFailure {
                    Log.d(name, "Playturka altyazı JS alınamadı: " + safeLogUrl(scriptUrl))
                }.getOrNull() ?: continue
    
                if (!script.isSuccessful || script.text.isBlank() || script.text.length > 2_000_000) continue
                publishSubtitles(
                    Jsoup.parse("", script.url),
                    script.text,
                    script.url,
                    seenSubtitles,
                    subtitleCallback
                )
            }
    
    
        } else {
            Log.d(name, "Playturka altyazı JS taraması atlandı: doğrudan Türkçe VTT mevcut")
        }

        val added = seenSubtitles.size - before
        Log.d(name, "Playturka player altyazı taraması: id=" + id + ", yeni=" + added)
        return PlayturkaPlayerConfig(added, playerVisitor, playerSiteKey, playbackCookie)
    }

    // Diagnostic-only JSON summary: reveal response structure without printing signed tokens.
    private fun summarizePlayturkaApiValue(value: Any?, depth: Int = 0): String {
        if (depth > 3) return "…"
        return when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted().take(16)
                keys.joinToString(prefix = "{", postfix = "}") { key ->
                    key + "=" + summarizePlayturkaApiValue(value.opt(key), depth + 1)
                }
            }
            is org.json.JSONArray -> {
                (0 until minOf(value.length(), 5)).joinToString(prefix = "[", postfix = "]") { index ->
                    summarizePlayturkaApiValue(value.opt(index), depth + 1)
                }
            }
            is String -> {
                val text = value.trim()
                when {
                    text.isEmpty() -> "string(empty)"
                    text.startsWith("http://", true) || text.startsWith("https://", true) ->
                        safeLogUrl(text)
                    else -> "string(len=" + text.length + ")"
                }
            }
            else -> if (value == JSONObject.NULL) "null" else value.toString().take(32)
        }
    }

    private fun findPlayturkaJsonString(raw: String, names: Set<String>): String? {
        val parsed = runCatching {
            org.json.JSONTokener(raw.trimStart('\uFEFF').trim()).nextValue()
        }.getOrNull() ?: return null
        val targetNames = names.map { it.lowercase(Locale.ROOT) }.toSet()

        fun walk(value: Any?, depth: Int): String? {
            if (depth > 5) return null
            when (value) {
                is JSONObject -> {
                    val keys = value.keys().asSequence().toList()
                    for (key in keys) {
                        val item = value.opt(key)
                        if (key.lowercase(Locale.ROOT) in targetNames) {
                            val text = item?.toString()?.trim().orEmpty()
                            if (text.isNotBlank() && text != "null" &&
                                !text.startsWith("{") && !text.startsWith("[")
                            ) return text
                        }
                        walk(item, depth + 1)?.let { return it }
                    }
                }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        walk(value.opt(index), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }
        return walk(parsed, 0)
    }

    private fun playturkaCookieNames(raw: String?): String {
        val excluded = setOf("path", "domain", "expires", "max-age", "samesite", "secure", "httponly", "priority")
        return Regex("""(?:^|[,;]\s*)([A-Za-z0-9_-]{2,50})=""")
            .findAll(raw.orEmpty())
            .map { it.groupValues[1] }
            .filterNot { it.lowercase(Locale.ROOT) in excluded }
            .distinct()
            .take(8)
            .joinToString(",")
    }

    private fun playturkaParam(raw: String, name: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull()
        val parts = listOf(uri?.rawQuery.orEmpty(), uri?.rawFragment.orEmpty(), raw)
        for (part in parts) {
            val normalized = part.removePrefix("#")
            val match = Regex(
                """(?i)(?:^|[?&#])""" + Regex.escape(name) + """=([^&#"'\s]+)"""
            ).find(normalized) ?: continue
            val value = match.groupValues.getOrNull(1).orEmpty()
            val decoded = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }
                .getOrDefault(value).trim()
            if (decoded.isNotBlank()) return decoded
        }
        return null
    }

    private fun playturkaSiteKey(referer: String, html: String): String? {
        listOf("site_key", "am_sk").forEach { key ->
            playturkaParam(referer, key)?.let { return it }
            playturkaParam(html, key)?.let { return it }
        }
        findPlayturkaJsonString(html, setOf("site_key", "siteKey", "AM_SITE_KEY", "am_sk"))
            ?.let { return it }
        val patterns = listOf(
            Regex("""(?i)["']?(?:site_key|siteKey|AM_SITE_KEY|am_sk)["']?\s*[:=]\s*["']([^"'\s]{4,300})["']"""),
            Regex("""(?i)(?:[?&#])(?:site_key|am_sk)=([^&#"'\s]{4,300})""")
        )
        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
                val value = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }
                    .getOrDefault(raw).trim()
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private fun playturkaVisitorId(setCookie: String?): String? =
        Regex("""(?i)(?:^|[,;]\s*)am_visitor_id=([^;,\s]+)""")
            .find(setCookie.orEmpty())?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun playturkaVisitorFromUrlOrHtml(value: String): String? {
        listOf("visitor_id", "am_vid").forEach { key ->
            playturkaParam(value, key)?.let { return it }
        }
        return findPlayturkaJsonString(
            value, setOf("visitor_id", "visitorId", "AM_VISITOR_ID", "am_vid")
        )
    }

    // Keep API diagnostics useful without leaking signed playback credentials.
    /**
     * Playturka's public player encrypts the /api/video-url JSON string by
     * substituting Base64 characters (charMapping/reverseMapping in bundle.js).
     * Reverse that exact mapping, restore Base64 padding, then parse the JSON.
     */
    private fun decodePlayturkaApiPayload(raw: String): JSONObject? {
        val normalized = raw.trimStart('﻿').trim()
        val encrypted = runCatching {
            when (val value = org.json.JSONTokener(normalized).nextValue()) {
                is String -> value
                is JSONObject -> {
                    listOf("data", "result", "payload").firstNotNullOfOrNull { key ->
                        value.optString(key).takeIf { it.isNotBlank() && it != "null" }
                    }
                }
                else -> null
            }
        }.getOrNull() ?: normalized.trim('"', '\'')

        if (encrypted.isBlank() || encrypted.length > 512_000) return null

        val decodedBase64 = buildString(encrypted.length + 4) {
            for (character in encrypted) {
                append(
                    when (character) {
                        in 'A'..'Z' -> ('Z' - (character - 'A'))
                        in 'a'..'z' -> ('z' - (character - 'a'))
                        in '0'..'9' -> ('0' + ((character - '0' - 5 + 10) % 10))
                        '+' -> '-'
                        '/' -> '_'
                        '-' -> '+'
                        '_' -> '/'
                        else -> character
                    }
                )
            }
        }
        if (decodedBase64.any { !(it in 'A'..'Z' || it in 'a'..'z' ||
                    it in '0'..'9' || it == '+' || it == '/' || it == '=')
            }) return null

        val padded = decodedBase64 + "=".repeat((4 - decodedBase64.length % 4) % 4)
        val jsonText = runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.trim().orEmpty()
        if (jsonText.isBlank()) return null

        return runCatching { JSONObject(jsonText) }.getOrNull()
    }

    private fun safePlayturkaApiPreview(raw: String): String {
        var preview = decode(raw).take(420)
        preview = Regex("""(?i)https?://[^\s"'<>]+""").replace(preview) { match ->
            safeLogUrl(match.value)
        }
        preview = Regex(
            """(?i)(["']?(?:access_token|refresh_token|token|signature|sig|authorization|auth|secret|password|api_key|apikey|key|exp|expires)["']?\s*[:=]\s*["']?)[^"'&,\s}]+"""
        ).replace(preview) { match ->
            match.groupValues[1] + "*"
        }
        preview = Regex(
            """(?i)([?&](?:access_token|token|signature|sig|auth|key|api_key|apikey|exp|expires)=)[^&#\s"'<>]+"""
        ).replace(preview) { match ->
            match.groupValues[1] + "*"
        }
        return preview.replace(Regex("""\s+"""), " ").trim()
    }

    private fun isSignedPlayturkaMaster(url: String, expectedId: String): Boolean {
        return runCatching {
            val uri = URI(url)
            if (!uri.host.equals("p.playturka.space", true)) return@runCatching false
            if (!uri.path.equals("/videos/" + expectedId + "/master.m3u8", true)) {
                return@runCatching false
            }
            val params = uri.rawQuery.orEmpty().split('&')
                .mapNotNull { part ->
                    val key = part.substringBefore('=').lowercase(Locale.ROOT)
                    val value = part.substringAfter('=', "")
                    key.takeIf { it.isNotBlank() }?.let { it to value }
                }.toMap()
            !params["token"].isNullOrBlank() && !params["exp"].isNullOrBlank()
        }.getOrDefault(false)
    }

    private suspend fun fetchPlayturkaSignedMaster(
        id: String,
        referer: String,
        playerVisitorId: String? = null,
        playerSiteKey: String? = null
    ): String? {
        // The public player bundle adds visitor_id and site_key when both are present.
        // Bootstrap the player origin to receive its am_visitor_id cookie first.
        val bootstrap = runCatching {
            app.get(
                referer.substringBefore("#").ifBlank { "https://p.playturka.space/" },
                headers = requestHeaders + ("Referer" to referer),
                referer = referer,
                allowRedirects = true,
                cacheTime = 0,
                timeout = 7000
            )
        }.getOrNull()
        val visitorId = playerVisitorId
            ?: playturkaVisitorId(bootstrap?.headers?.get("Set-Cookie"))
            ?: playturkaVisitorFromUrlOrHtml(referer)
        val siteKey = playerSiteKey
            ?: playturkaSiteKey(referer, bootstrap?.text.orEmpty())
        val endpoint = buildString {
            append("https://p.playturka.space/api/video-url?id=")
            append(URLEncoder.encode(id, "UTF-8"))
            if (!visitorId.isNullOrBlank() && !siteKey.isNullOrBlank()) {
                append("&visitor_id=").append(URLEncoder.encode(visitorId, "UTF-8"))
                append("&site_key=").append(URLEncoder.encode(siteKey, "UTF-8"))
            }
        }
        val apiHeaders = buildMap {
            putAll(requestHeaders)
            put("Accept", "application/json,text/plain,*/*")
            put("Referer", referer)
            if (!visitorId.isNullOrBlank()) put("Cookie", "am_visitor_id=$visitorId")
        }
        Log.d(name, "Playturka API kimlik bilgileri: id=" + id +
            ", visitorIdPresent=" + !visitorId.isNullOrBlank() +
            ", siteKeyPresent=" + !siteKey.isNullOrBlank() +
            ", bootstrapCookie=" + !bootstrap?.headers?.get("Set-Cookie").isNullOrBlank() +
            ", bootstrapCookieNames=" + playturkaCookieNames(bootstrap?.headers?.get("Set-Cookie")))
        val response = runCatching {
            app.get(
                endpoint,
                headers = apiHeaders,
                referer = referer,
                allowRedirects = true,
                cacheTime = 0,
                timeout = 9000
            )
        }.onFailure {
            Log.d(name, "Playturka imzalı URL API isteği başarısız: id=" + id +
                ", error=" + it.javaClass.simpleName)
        }.getOrNull() ?: return null

        if (!response.isSuccessful || response.text.isBlank() || response.text.length > 512_000) {
            Log.d(name, "Playturka imzalı URL API yanıtı uygun değil: id=" + id +
                ", HTTP=" + response.code + ", length=" + response.text.length)
            return null
        }

        val decodedBody = decode(response.text)
        val responseShape = runCatching {
            val normalizedBody = response.text.trimStart('\uFEFF').trim()
                .removePrefix(")]}',").trim()
            summarizePlayturkaApiValue(org.json.JSONTokener(normalizedBody).nextValue())
        }.getOrElse {
            "json-parse-failed"
        }
        val urlHints = Regex("""(?i)(?:https?:)?//[^"'<>\\\s]{10,1200}""")
            .findAll(decodedBody)
            .mapNotNull { match -> fixUrl(match.value, response.url)?.let { safeLogUrl(it) } }
            .distinct()
            .take(5)
            .toList()
        Log.d(name, "Playturka API yanıt yapısı: id=" + id +
            ", shape=" + responseShape +
            ", bodyLength=" + response.text.length +
            ", preview=" + safePlayturkaApiPreview(response.text) +
            ", urlHints=" + urlHints.joinToString("|"))

        val candidates = LinkedHashSet<String>()
        if (isDirectMedia(response.url)) candidates.add(response.url)
        extractPlayturkaMasterUrls(response.url, response.document, response.text)
            .map { it.second }
            .forEach(candidates::add)

        // /api/video-url returns a JSON string containing Playturka's custom
        // Base64 substitution payload. Decode it before searching for the signed
        // master; the public player does the same before reading files.masterUrl.
        val payload = decodePlayturkaApiPayload(response.text)
        val files = payload?.optJSONObject("files")
        val masterFields = listOf("masterUrl", "master_url", "master", "url")
        masterFields.forEach { field ->
            val rawMaster = files?.optString(field).orEmpty()
                .ifBlank { payload?.optString(field).orEmpty() }
            val absolute = fixUrl(rawMaster, response.url)
            if (!absolute.isNullOrBlank()) candidates.add(absolute)
        }
        val payloadStatus = payload?.optString("status").orEmpty()
        val payloadVideoId = payload?.optString("videoId").orEmpty()
        Log.d(name, "Playturka API çözümleme: id=" + id +
            ", decoded=" + (payload != null) +
            ", status=" + payloadStatus.take(24) +
            ", videoIdMatches=" + payloadVideoId.equals(id, true) +
            ", fileKeys=" + (files?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",") ?: "none") +
            ", masterCandidate=" + candidates.any { isSignedPlayturkaMaster(it, id) })

        val decoded = decode(response.text)
        Regex(
            """(?i)(?:https?:)?//p\.playturka\.space/videos/([A-Za-z0-9_-]{5,64})/master\.m3u8\?[^"'<>\s]+"""
        ).findAll(decoded)
            .mapNotNull { fixUrl(it.value, response.url) }
            .forEach(candidates::add)

        val selected = candidates.firstOrNull { isSignedPlayturkaMaster(it, id) }
        Log.d(name, "Playturka imzalı URL API: id=" + id +
            ", HTTP=" + response.code +
            ", signedMasterFound=" + (selected != null) +
            ", contentType=" + response.headers["content-type"].orEmpty().take(70))
        return selected
    }


    private suspend fun fetchPlayturkaHlsDurationSeconds(
        playlistUrl: String,
        referer: String,
        depth: Int = 0,
        visited: MutableSet<String> = LinkedHashSet(),
        initialBody: String? = null
    ): Double? {
        if (depth > 2 || !visited.add(playlistUrl)) return null

        val response = if (initialBody == null) runCatching {
            app.get(
                playlistUrl,
                headers = requestHeaders + mapOf("Accept" to "*/*", "Referer" to referer),
                referer = referer,
                allowRedirects = true,
                cacheTime = 0,
                timeout = 9000
            )
        }.onFailure {
            Log.d(name, "Playturka süre kontrolü isteği başarısız: " + safeLogUrl(playlistUrl))
        }.getOrNull() else null

        if (initialBody == null && response == null) return null
        val effectivePlaylistUrl = response?.url ?: playlistUrl
        val body = (initialBody ?: response?.text.orEmpty())
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if ((initialBody == null && response?.isSuccessful != true) || !body.startsWith("#EXTM3U")) {
            Log.d(
                name,
                "Playturka süre kontrolü playlist değil: " + safeLogUrl(playlistUrl) +
                    ", HTTP=" + (response?.code ?: 0)
            )
            return null
        }

        val mediaDuration = Regex("""(?im)^#EXTINF:([0-9]+(?:\.[0-9]+)?)(?:,|$)""")
            .findAll(body)
            .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
            .sum()
        if (mediaDuration > 0.0) return mediaDuration

        val lines = body.lines()
        val variants = LinkedHashSet<String>()
        for (index in lines.indices) {
            if (!lines[index].trim().startsWith("#EXT-X-STREAM-INF", true)) continue
            val uriLine = lines.drop(index + 1).firstOrNull {
                it.isNotBlank() && !it.trim().startsWith("#")
            } ?: continue
            fixUrl(uriLine.trim(), effectivePlaylistUrl)?.let(variants::add)
        }

        if (variants.isEmpty()) {
            val audioUris = Regex("""(?im)^#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*URI="([^"]+)"""")
                .findAll(body)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .toSet()
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#") ||
                    !line.contains(".m3u8", true) || line in audioUris
                ) return@forEach
                fixUrl(line, effectivePlaylistUrl)?.let(variants::add)
            }
        }

        // Every video rendition is still checked, but independent child playlists
        // are fetched concurrently. Each branch gets its own visited set.
        val selectedVariants = variants.toList()
        if (selectedVariants.isEmpty() || selectedVariants.size > 8) return null

        val variantDurations = ArrayList<Pair<String, Double>>()
        for (variant in selectedVariants) {
            val duration = fetchPlayturkaHlsDurationSeconds(
                variant,
                effectivePlaylistUrl,
                depth + 1,
                LinkedHashSet(visited)
            ) ?: continue
            variantDurations.add(variant to duration)
        }

        val allVariantsVerified = variantDurations.size == selectedVariants.size
        Log.d(
            name,
            "Playturka video varyant süreleri: checked=" + variantDurations.size +
                ", total=" + selectedVariants.size +
                ", details=" + variantDurations.joinToString(" | ") {
                    safeLogUrl(it.first) + "=" + it.second.toLong() + "s"
                }
        )
        if (!allVariantsVerified) return null
        return variantDurations.minOfOrNull { it.second }
    }

    private data class PlayturkaVideoPlaylistResolution(
        val isMasterPlaylist: Boolean,
        val preferredVariantUrl: String?
    )

    /**
     * Resolves the exact video rendition that the duration checker validated.
     * Some Playturka masters report a full-length child playlist but ExoPlayer
     * still receives a 10-second preview when the master itself is selected.
     * For masters without separate audio renditions, publish the verified child
     * playlist directly to avoid that mismatch.
     */
    private fun findPlayturkaPreferredVideoVariant(
        masterUrl: String,
        masterBody: String
    ): PlayturkaVideoPlaylistResolution? {
        val body = masterBody.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!body.startsWith("#EXTM3U")) return null

        val lines = body.lines()
        val variants = ArrayList<Pair<Long, String>>()
        for (index in lines.indices) {
            val info = lines[index].trim()
            if (!info.startsWith("#EXT-X-STREAM-INF", true)) continue
            val uriLine = lines.drop(index + 1).firstOrNull {
                it.isNotBlank() && !it.trim().startsWith("#")
            } ?: continue
            val url = fixUrl(uriLine.trim(), masterUrl) ?: continue
            val bandwidth = Regex("""(?i)\b(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\d+)""")
                .find(info)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            variants.add(bandwidth to url)
        }

        if (variants.isEmpty()) {
            return PlayturkaVideoPlaylistResolution(
                isMasterPlaylist = false,
                preferredVariantUrl = null
            )
        }

        return PlayturkaVideoPlaylistResolution(
            isMasterPlaylist = true,
            preferredVariantUrl = variants.maxByOrNull { it.first }?.second
        )
    }

    private suspend fun validatePlayturkaMovieStreamDuration(
        manifestUrl: String,
        referer: String,
        videoId: String,
        expectedRuntimeMinutes: Int?,
        initialBody: String? = null
    ): Double? {
        val duration = fetchPlayturkaHlsDurationSeconds(
            manifestUrl, referer, initialBody = initialBody
        )
        val minimumSeconds = expectedRuntimeMinutes
            ?.takeIf { it in 10..600 }
            ?.let { it * 60.0 * 0.55 }
            ?: 300.0

        val accepted = duration != null && duration >= minimumSeconds
        Log.d(
            name,
            "Playturka film süresi kontrolü: id=" + videoId +
                ", actualSeconds=" + (duration?.toLong()?.toString() ?: "unavailable") +
                ", expectedMinutes=" + (expectedRuntimeMinutes?.toString() ?: "unknown") +
                ", minimumSeconds=" + minimumSeconds.toLong() +
                ", accepted=" + accepted
        )
        return duration.takeIf { accepted }
    }

    private suspend fun publishPlayturkaManifestCandidates(
        embedUrl: String,
        document: Document,
        html: String,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        expectedRuntimeMinutes: Int? = null
    ): Int {
        val host = runCatching { URI(embedUrl).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host != "p.playturka.space" && !html.contains("p.playturka.space", true)) return 0

        val exactMasters = LinkedHashMap<String, String>()
        extractPlayturkaMasterUrls(embedUrl, document, html)
            .filter { (id, url) -> isSignedPlayturkaMaster(url, id) }
            .forEach { (id, url) ->
                exactMasters.putIfAbsent(id, url)
            }
        val ids = LinkedHashSet<String>()
        // Signed master URLs contain the real video ID and must be tried before
        // generic IDs found in buttons or unrelated page elements.
        ids.addAll(exactMasters.keys)
        ids.addAll(extractPlayturkaIds(embedUrl, document, html))
        if (exactMasters.isEmpty() && ids.isEmpty()) {
            val scriptUrls = document.select("script[src]")
                .mapNotNull { fixUrl(it.attr("src"), embedUrl) }
                .distinct()
                .filter { pathOf(it).substringBefore('?').endsWith(".js", true) }
                .filterNot {
                    listOf("google-analytics", "googletagmanager", "doubleclick", "adsystem",
                        "cloudflareinsights", "fonts.", "cdnjs", "jsdelivr", "unpkg")
                        .any { term -> it.contains(term, true) }
                }
                .sortedByDescending {
                    listOf("player", "video", "source", "main", "app").any { term ->
                        pathOf(it).contains(term, true)
                    }
                }
                .take(8)

            for (scriptUrl in scriptUrls) {
                val scriptResponse = runCatching {
                    app.get(
                        scriptUrl,
                        headers = requestHeaders + ("Referer" to embedUrl),
                        referer = embedUrl,
                        allowRedirects = true,
                        timeout = 8000
                    )
                }.getOrNull() ?: continue
                if (!scriptResponse.isSuccessful || scriptResponse.text.isBlank() ||
                    scriptResponse.text.length > 2_000_000
                ) continue
                val scriptText = decode(scriptResponse.text)
                val scriptHints = Regex(
                    """(?is).{0,100}(?:p\.playturka\.space|playturka|666film\.net|videoId|video_id|sourceId|source_id|embed.?url|playerUrl|sourceUrl|iframe|master\.m3u8|/videos/).{0,140}"""
                ).findAll(scriptText)
                    .take(7)
                    .map {
                        it.value.replace(Regex("""\s+"""), " ").trim()
                            .replace(Regex("""([?&][\w-]{1,40}=)[^&\s"'<>]+""")) { match ->
                                match.groupValues[1] + "*"
                            }
                    }
                    .joinToString(" | ")
                    .take(1500)
                if (scriptHints.isNotBlank()) {
                    Log.d(name, "Playturka kaynak JS: " + safeLogUrl(scriptUrl) + "; hints=" + scriptHints)
                }

                // Inspect only the public player script for the code path that
                // transforms the API's opaque JSON string into a signed URL.
                val decodeTerms = listOf(
                    "api/video-url", "video-url", "getVideoUrl", "decrypt", "charMapping",
                    "reverseMapping", "siteKey", "AM_SITE_KEY", "am_visitor_id", "visitor_id",
                    "site_key", "sk=", "CryptoJS", "crypto.subtle", "AES", "base64", "atob",
                    "btoa", "TextDecoder", "Uint8Array", "charCodeAt", "fromCharCode",
                    "fetch(", "XMLHttpRequest", "JSON.parse"
                )
                val decodeHints = decodeTerms.mapNotNull { term ->
                    val at = scriptText.indexOf(term, ignoreCase = true)
                    if (at < 0) return@mapNotNull null
                    val from = (at - 380).coerceAtLeast(0)
                    val until = (at + 1050).coerceAtMost(scriptText.length)
                    val snippet = scriptText.substring(from, until)
                        .replace(Regex("""\s+"""), " ")
                        .replace(Regex("""(?i)https?://[^\s"'<>]+""")) { match ->
                            safeLogUrl(match.value)
                        }
                    term + " => " + snippet
                }.joinToString(" || ").take(8500)
                val matchedDecodeTerms = decodeTerms.filter { term ->
                    scriptText.contains(term, ignoreCase = true)
                }
                Log.d(name, "Playturka çözümleme taraması: " + safeLogUrl(scriptUrl) +
                    ", scriptLength=" + scriptText.length +
                    ", matchedTerms=" + matchedDecodeTerms.joinToString(",") +
                    ", hints=" + decodeHints)

                // Print short, bounded slices around the mapping declarations/usages.
                // This identifies the real substitution table without logging signed URLs.
                for (mappingTerm in listOf("charMapping", "reverseMapping")) {
                    val occurrences = Regex(Regex.escape(mappingTerm), RegexOption.IGNORE_CASE)
                        .findAll(scriptText)
                        .map { it.range.first }
                        .take(20)
                        .toList()
                    val selectedPositions = if (occurrences.size <= 4) occurrences else
                        listOf(occurrences.first(), occurrences[1], occurrences[occurrences.size - 2], occurrences.last()).distinct()
                    Log.d(name, "Playturka JS eşleme konumları: term=" + mappingTerm +
                        ", positions=" + occurrences.joinToString(","))
                    selectedPositions.forEachIndexed { snippetIndex, position ->
                        val from = (position - 220).coerceAtLeast(0)
                        val until = (position + 1900).coerceAtMost(scriptText.length)
                        val mappingSnippet = scriptText.substring(from, until)
                            .replace(Regex("""\s+"""), " ")
                            .replace(Regex("""(?i)https?://[^\s"'<>]+""")) { match ->
                                safeLogUrl(match.value)
                            }
                            .replace(Regex("""([?&][\w-]{1,40}=)[^&\s"'<>]+""")) { match ->
                                match.groupValues[1] + "*"
                            }
                        Log.d(name, "Playturka JS eşleme ayrıntısı: term=" + mappingTerm +
                            ", part=" + snippetIndex + ", code=" + mappingSnippet.take(2100))
                    }
                }
                val scriptDocument = Jsoup.parse("", scriptResponse.url)
                extractPlayturkaMasterUrls(
                    scriptResponse.url,
                    scriptDocument,
                    scriptResponse.text
                ).filter { (id, signedMaster) ->
                    isSignedPlayturkaMaster(signedMaster, id)
                }.forEach { (id, signedMaster) ->
                    exactMasters.putIfAbsent(id, signedMaster)
                    ids.add(id)
                }
                ids.addAll(
                    extractPlayturkaIds(
                        scriptResponse.url,
                        scriptDocument,
                        scriptResponse.text
                    )
                )
                if (exactMasters.isNotEmpty()) {
                    Log.d(name, "Playturka imzalı master URL'si JS dosyasından bulundu: " + safeLogUrl(scriptUrl))
                    break
                }
            }
        }
        if (ids.isEmpty()) {
            val decodedHints = decode(html)
            val apiHints = Regex(
                """(?is).{0,70}(?:fetch\s*\(|XMLHttpRequest|axios|/api/|api/|master\.m3u8|/videos/|video[_-]?id|source[_-]?id|playlist|p\.playturka\.space|playturka|embed.?url|playerUrl|sourceUrl).{0,100}"""
            ).findAll(decodedHints)
                .take(8)
                .map {
                    it.value.replace(Regex("""\s+"""), " ").trim()
                        .replace(Regex("""([?&][\w-]{1,40}=)[^&\s"'<>]+""")) { match ->
                            match.groupValues[1] + "*"
                        }
                }
                .joinToString(" | ")
                .take(1200)
            val scriptHint = document.select("script[src]")
                .take(12)
                .mapNotNull { fixUrl(it.attr("src"), embedUrl) }
                .distinct()
                .joinToString(" | ") { safeLogUrl(it) }
            Log.d(name, "Playturka JS/API izleri: scripts=" + scriptHint + "; hints=" + apiHints)
            val sourceHints = document.select(
                "button, [onclick], [data-source], [data-source-id], [data-video-id], [data-embed], iframe[src], a[href]"
            ).filter { node ->
                val sample = (node.text() + " " + node.outerHtml()).lowercase(Locale.ROOT)
                listOf("playturka", "kaynak", "dublaj", "altyaz", "filmi başlat", "video", "source", "player", "server", "embed")
                    .any { sample.contains(it) }
            }.take(20).mapNotNull { node ->
                val label = cleanText(node.text()).take(36)
                val attrs = listOf(
                    "data-source-id", "data-video-id", "data-stream-id", "data-media-id",
                    "data-file-id", "data-source", "data-source-url", "data-embed", "data-player",
                    "data-id", "data-video", "data-src", "data-url", "src", "href", "onclick"
                ).mapNotNull { key ->
                    val value = node.attr(key).trim()
                    if (value.isBlank()) null
                    else {
                        val safeValue = if (key in setOf("data-src", "data-url", "src", "href", "data-source-url", "data-embed", "data-player")) {
                            safeLogUrl(fixUrl(value, embedUrl) ?: value)
                        } else value.take(70)
                        key + "=" + safeValue
                    }
                }.joinToString(",")
                if (label.isBlank() && attrs.isBlank()) null
                else listOf(label, attrs).filter { it.isNotBlank() }.joinToString(" [", postfix = if (attrs.isBlank()) "" else "]")
            }.joinToString(" | ")
            Log.d(name, "Playturka: ID bulunamadı; sayfa=" + safeLogUrl(embedUrl) +
                "; sourceHints=" + sourceHints.take(700))
            return 0
        }

        var found = 0
        val embeddedPlayturkaPage = document.select(
            "iframe[src], iframe[data-src], iframe[data-url], [data-src], [data-url]"
        ).mapNotNull { element ->
            fixUrl(
                element.attr("data-src").ifBlank {
                    element.attr("data-url").ifBlank { element.attr("src") }
                },
                embedUrl
            )
        }.firstOrNull { candidate ->
            runCatching { URI(candidate).host.orEmpty().equals("p.playturka.space", true) }
                .getOrDefault(false)
        }
        val orderedIds = LinkedHashSet<String>().apply {
            addAll(exactMasters.keys)
            addAll(ids)
        }
        for (id in orderedIds.take(3)) {
            val playerReferer = when {
                host == "p.playturka.space" -> embedUrl
                embeddedPlayturkaPage != null -> embeddedPlayturkaPage
                else -> "https://p.playturka.space/#" + id
            }

            // Playturka uses a direct VTT sidecar next to the HLS files:
            // /videos/{videoId}/subtitles/tr.vtt (sometimes cache-busted with ?v=...).
            // Validate the response before exposing it to CloudStream so HTML/404 pages
            // are not incorrectly listed as subtitles.
            val subtitleBase = "https://p.playturka.space/videos/" + id + "/subtitles/tr.vtt"
            val subtitleCandidates = listOf(
                subtitleBase,
                subtitleBase + "?v=" + (System.currentTimeMillis() / 1000L)
            )
            var directSubtitleAdded = false
            for (subtitleUrl in subtitleCandidates) {
                val subtitleResponse = runCatching {
                    app.get(
                        subtitleUrl,
                        headers = requestHeaders + mapOf(
                            "Accept" to "text/vtt,text/plain,*/*",
                            "Referer" to playerReferer
                        ),
                        referer = playerReferer,
                        allowRedirects = true,
                        timeout = 9000
                    )
                }.onFailure {
                    Log.d(name, "Playturka Türkçe VTT isteği başarısız: " +
                        safeLogUrl(subtitleUrl) + " (" + it.javaClass.simpleName + ")")
                }.getOrNull() ?: continue

                val body = subtitleResponse.text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                val contentType = subtitleResponse.headers["content-type"].orEmpty()
                val looksLikeVtt = body.startsWith("WEBVTT", true) ||
                    (contentType.contains("text/vtt", true) && body.contains("-->"))
                if (subtitleResponse.isSuccessful && looksLikeVtt) {
                    val resolvedSubtitleUrl = subtitleResponse.url.ifBlank { subtitleUrl }
                    if (seenSubtitles.add(resolvedSubtitleUrl)) {
                        subtitleCallback(newSubtitleFile("Turkish", resolvedSubtitleUrl))
                        Log.d(name, "Playturka Türkçe VTT bulundu: id=" + id +
                            ", url=" + safeLogUrl(resolvedSubtitleUrl) +
                            ", type=" + contentType.take(70))
                    }
                    directSubtitleAdded = true
                    break
                } else {
                    Log.d(name, "Playturka Türkçe VTT doğrulanmadı: id=" + id +
                        ", HTTP=" + subtitleResponse.code +
                        ", type=" + contentType.take(70) +
                        ", WEBVTT=" + body.startsWith("WEBVTT", true))
                }
            }
            if (!directSubtitleAdded) {
                Log.d(name, "Playturka doğrudan Türkçe VTT bulunamadı: id=" + id)
            }

            val playerConfig = publishPlayturkaApiSubtitles(
                id, playerReferer, seenSubtitles, subtitleCallback,
                scanScripts = !directSubtitleAdded
            )

            // The unsigned /videos/{id}/master.m3u8 path can return a short
            // 10-second preview. Obtain the signed URL from the same public endpoint
            // used by the site's player, unless the signed URL was already present
            // in the page or one of its player scripts.
            val signedMaster = exactMasters[id]
                ?.takeIf { isSignedPlayturkaMaster(it, id) }
                ?: fetchPlayturkaSignedMaster(id, playerReferer, playerConfig.visitorId, playerConfig.siteKey)

            val manifests = LinkedHashSet<String>()
            if (signedMaster != null) {
                manifests.add(signedMaster)
            } else {
                Log.w(name, "Playturka tam video URL'si bulunamadı; kısa/işaretsiz akış atlandı: id=" + id)
                continue
            }

            for (manifest in manifests) {
                val response = runCatching {
                    app.get(
                        manifest,
                        headers = requestHeaders + mapOf("Accept" to "*/*", "Referer" to playerReferer),
                        referer = playerReferer,
                        allowRedirects = true,
                        timeout = 10000
                    )
                }.onFailure {
                    Log.d(name, "Playturka manifest isteği başarısız: " + safeLogUrl(manifest))
                }.getOrNull() ?: continue
                val body = response.text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                val contentType = response.headers["content-type"].orEmpty()
                if (response.isSuccessful && body.startsWith("#EXTM3U")) {
                    val videoDurationSeconds = validatePlayturkaMovieStreamDuration(
                        manifest, playerReferer, id, expectedRuntimeMinutes, body
                    )
                    if (videoDurationSeconds == null) {
                        Log.w(
                            name,
                            "Kısa/reklam veya süresi doğrulanamayan Playturka akışı atlandı: id=" +
                                id + ", url=" + safeLogUrl(manifest)
                        )
                        continue
                    }

                    if (body.startsWith("#EXTM3U")) {
                        publishHlsSubtitleTracks(manifest, body, seenSubtitles, subtitleCallback)
                    }

                    // Master playlist must stay intact: audio can be a separate
                    // EXT-X-MEDIA rendition such as playlist_aud0.m3u8. Passing
                    // only the first video variant would silently drop that track.
                    val audioLines = Regex("""(?im)^#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*$""")
                        .findAll(body)
                        .map { it.value }
                        .toList()
                    val referencesAudioSidecar = body.contains("playlist_aud0.m3u8", true)
                    Log.d(
                        name,
                        "Playturka master doğrulandı: id=" + id +
                            ", signed=" + manifest.contains("token=", true) +
                            ", audioRenditions=" + audioLines.size +
                            ", aud0Sidecar=" + referencesAudioSidecar +
                            ", url=" + safeLogUrl(manifest)
                    )

                    // A full-length video rendition is not enough: the master may
                    // point to a 10-second audio/ad sidecar. Verify every external
                    // audio rendition against the already-validated video duration.
                    val resolution = findPlayturkaPreferredVideoVariant(manifest, body)
                    val videoPlaybackUrl = when {
                        resolution == null -> {
                            Log.w(
                                name,
                                "Playturka video listesi çözümlenemedi; akış oynatılmadı: id=" + id
                            )
                            continue
                        }
                        resolution.isMasterPlaylist &&
                            resolution.preferredVariantUrl.isNullOrBlank() -> {
                            Log.w(
                                name,
                                "Playturka master içinde oynatılabilir video varyantı yok: id=" + id
                            )
                            continue
                        }
                        resolution.isMasterPlaylist -> resolution.preferredVariantUrl!!
                        else -> manifest
                    }

                    val audioPlaylistUrls = audioLines.mapNotNull { audioLine ->
                        val rawUri = Regex("""(?i)\bURI\s*=\s*["]([^"]+)["]""")
                            .find(audioLine)?.groupValues?.getOrNull(1)
                            ?: return@mapNotNull null
                        fixUrl(rawUri, manifest)
                    }.distinct()
                    val hasExternalAudioSidecar = audioLines.any {
                        it.contains("URI=", true)
                    }
                    val audioDurations = ArrayList<Pair<String, Double?>>()
                    for (audioUrl in audioPlaylistUrls) {
                        val duration = fetchPlayturkaHlsDurationSeconds(
                            audioUrl, playerReferer
                        )
                        audioDurations.add(audioUrl to duration)
                    }
                    val minimumAudioSeconds = maxOf(300.0, videoDurationSeconds * 0.70)
                    val audioSidecarsVerified = !hasExternalAudioSidecar ||
                        (
                            audioPlaylistUrls.isNotEmpty() &&
                                audioDurations.size == audioPlaylistUrls.size &&
                                audioDurations.all { (_, duration) ->
                                    duration != null && duration >= minimumAudioSeconds
                                }
                            )
                    val keepMasterWithAudio = audioLines.isNotEmpty() && audioSidecarsVerified

                    Log.d(
                        name,
                        "Playturka ses varyant kontrolü: id=" + id +
                            ", videoSeconds=" + videoDurationSeconds.toLong() +
                            ", checked=" + audioDurations.count { it.second != null } +
                            ", total=" + audioPlaylistUrls.size +
                            ", minimumAudioSeconds=" + minimumAudioSeconds.toLong() +
                            ", details=" + audioDurations.joinToString(" | ") {
                                safeLogUrl(it.first) + "=" + (it.second?.toLong()?.toString() ?: "unavailable") + "s"
                            } +
                            ", masterAudioAccepted=" + audioSidecarsVerified
                    )

                    // Keep a master only when its external audio tracks are also
                    // full-length. Otherwise give ExoPlayer the exact long video
                    // rendition and avoid a short ad/audio sidecar determining the
                    // playback duration. If the playlist is already a media playlist,
                    // the original URL remains the correct source.
                    val playbackUrl = if (keepMasterWithAudio) manifest else videoPlaybackUrl
                    Log.d(
                        name,
                        "Playturka oynatma URL seçimi: id=" + id +
                            ", separateAudio=" + audioLines.isNotEmpty() +
                            ", directVideoVariant=" + (playbackUrl != manifest) +
                            ", url=" + safeLogUrl(playbackUrl)
                    )
                    // Match the headers used by the manifest validator. The
                    // generic site headers advertise HTML; some player/CDN paths
                    // may serve a short preview playlist when ExoPlayer inherits it.
                    val playbackHeaders = buildMap {
                        put("Accept", "*/*")
                        put("Cache-Control", "no-cache")
                        put("Pragma", "no-cache")
                        playerConfig.playbackCookie?.let { put("Cookie", it) }
                    }
                    Log.d(
                        name,
                        "Playturka oynatma HTTP başlıkları: accept=*/*, cacheBypass=true, cookiePresent=" +
                            playbackHeaders.containsKey("Cookie")
                    )
                    if (publishDirect(
                            playbackUrl,
                            playerReferer,
                            callback,
                            playbackHeaders,
                            expectedRuntimeMinutes = expectedRuntimeMinutes,
                            durationValidated = true
                        )
                    ) {
                        found++
                        break
                    }
                } else {
                    Log.d(name, "Playturka manifest doğrulanmadı: " + safeLogUrl(manifest) +
                        ", HTTP=" + response.code + ", type=" + contentType.take(80))
                }
            }
        }
        return found
    }

    private suspend fun collectExternalScriptCandidates(
        document: Document,
        base: String,
        referer: String,
        visitedScripts: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Pair<Set<String>, Set<String>> {
        val direct = LinkedHashSet<String>()
        val embeds = LinkedHashSet<String>()
        val blocked = listOf(
            "google-analytics", "googletagmanager", "doubleclick", "adsystem",
            "advert", "hotjar", "clarity", "facebook", "cookie", "share",
            "cloudflareinsights", "github.com", "githubusercontent", "google.com",
            "gstatic.com", "fonts.", "cdnjs", "jsdelivr", "unpkg"
        )
        val scriptUrls = document.select("script[src]")
            .mapNotNull { fixUrl(it.attr("src"), base) }
            .distinct()
            .filter { pathOf(it).substringBefore('?').endsWith(".js", true) }
            .filterNot { blocked.any { term -> it.contains(term, true) } }
            .sortedByDescending {
                listOf("player", "video", "source", "film", "main", "app").any { hint ->
                    pathOf(it).contains(hint, true)
                }
            }
            .take(8)

        var fetched = 0
        for (scriptUrl in scriptUrls) {
            if (!visitedScripts.add(scriptUrl)) continue
            val response = runCatching {
                app.get(
                    scriptUrl,
                    headers = requestHeaders + ("Referer" to referer),
                    referer = referer,
                    allowRedirects = true,
                    timeout = 10000
                )
            }.onFailure {
                Log.d(name, "JS isteği başarısız: " + safeLogUrl(scriptUrl) + " (" + it.javaClass.simpleName + ")")
            }.getOrNull() ?: continue
            if (!response.isSuccessful || response.text.isBlank() || response.text.length > 2_000_000) {
                Log.d(name, "JS atlandı: " + safeLogUrl(scriptUrl) + " HTTP=" + response.code)
                continue
            }
            fetched++
            publishSubtitles(
                Jsoup.parse("", response.url),
                response.text,
                response.url,
                seenSubtitles,
                subtitleCallback
            )
            val (scriptDirect, scriptEmbeds) =
                collectPlayerCandidates(Jsoup.parse("", response.url), response.text, response.url)
            direct.addAll(scriptDirect)
            embeds.addAll(scriptEmbeds)
        }
        Log.d(name, "JS taraması: fetched=" + fetched + ", direct=" + direct.size +
            ", embeds=" + embeds.size + ", page=" + safeLogUrl(base))
        return direct to embeds
    }

    private suspend fun publishDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        extraHeaders: Map<String, String> = emptyMap(),
        expectedRuntimeMinutes: Int? = null,
        durationValidated: Boolean = false
    ): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host.isBlank() || !isDirectMedia(url) || isRejectedPlayerCandidate(url, url) ||
            isStaticAsset(url) || isAdOrPreviewUrl(url)
        ) {
            Log.d(name, "Geçersiz/reklam/fragman medya bağlantısı atlandı: " + safeLogUrl(url))
            return false
        }

        val isHls = pathOf(url).endsWith(".m3u8", true)
        if (isHls && !durationValidated) {
            val duration = validatePlayturkaMovieStreamDuration(
                url, referer, "direct", expectedRuntimeMinutes
            )
            if (duration == null) {
                Log.w(
                    name,
                    "Kısa/reklam veya süresi doğrulanamayan doğrudan HLS kaynağı atlandı: " +
                        safeLogUrl(url)
                )
                return false
            }
        }

        val type = when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mp4", true) || url.contains(".webm", true) -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }
        callback(
            newExtractorLink(source = name, name = name, url = url, type = type) {
                this.referer = referer
                this.quality = Regex("""(?i)(2160|1440|1080|720|480|360)""")
                    .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                this.headers = requestHeaders + ("Referer" to referer) + extraHeaders
            }
        )
        return true
    }

    private suspend fun publishSubtitles(
        document: Document,
        html: String,
        base: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        for (track in document.select("track[src], track[data-src]")) {
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val url = fixUrl(raw, base) ?: continue
            if (!Regex("""(?i)\.(?:vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(url)) continue
            if (!seen.add(url)) continue
            val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
            callback(newSubtitleFile(label, url))
        }

        val decoded = decode(html)
        subtitleRegex.findAll(decoded).forEach { match ->
            val url = fixUrl(match.value, base) ?: return@forEach
            if (seen.add(url)) callback(newSubtitleFile("Altyazı", url))
        }
        quotedSubtitleRegex.findAll(decoded).forEach { match ->
            val url = fixUrl(match.groupValues[1], base) ?: return@forEach
            if (!Regex("""(?i)\.(?:vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(url)) return@forEach
            if (seen.add(url)) callback(newSubtitleFile("Altyazı", url))
        }
    }

    private suspend fun publishHlsSubtitleTracks(
        playlistUrl: String,
        playlist: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit
    ) {
        val trackLines = Regex("""(?im)^#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*$""")
            .findAll(playlist)
        for (match in trackLines) {
            val line = match.value
            val uri = Regex("""(?i)\bURI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1) ?: continue
            val url = fixUrl(uri, playlistUrl) ?: continue
            // CloudStream's external SubtitleFile accepts standalone text-subtitle files,
            // not an HLS rendition playlist itself.
            if (!Regex("""(?i)\.(?:vtt|srt|ass)(?:[?#].*)?$""").containsMatchIn(url)) continue
            val label = Regex("""(?i)\bNAME="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: Regex("""(?i)\bLANGUAGE="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                ?: "Altyazı"
            if (seen.add(url)) callback(newSubtitleFile(label, url))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = runCatching {
            app.get(data, headers = requestHeaders, referer = mainUrl, allowRedirects = true, timeout = 18000)
        }.onFailure { Log.w(name, "Oynatıcı sayfası alınamadı: " + data, it) }.getOrNull() ?: return false
        if (!page.isSuccessful) return false

        val seenSubtitles = HashSet<String>()
        val emittedSubtitleUrls = HashSet<String>()
        var emittedSubtitleCount = 0
        val emitSubtitle: (SubtitleFile) -> Unit = { subtitle ->
            if (emittedSubtitleUrls.add(subtitle.url)) {
                emittedSubtitleCount++
                subtitleCallback(subtitle)
            }
        }
        publishSubtitles(page.document, page.text, page.url, seenSubtitles, emitSubtitle)

        val visited = HashSet<String>()
        val visitedScripts = HashSet<String>()
        val (pageDirect, pageEmbeds) = collectPlayerCandidates(page.document, page.text, page.url)
        val hasPagePlayturkaEmbed = pageEmbeds.any { candidate ->
            runCatching { URI(candidate).host.orEmpty().equals("p.playturka.space", true) }
                .getOrDefault(false)
        }
        val (scriptDirect, scriptEmbeds) = if (hasPagePlayturkaEmbed) {
            Log.d(name, "Film sayfası JS taraması atlandı: Playturka iframe doğrudan bulundu")
            emptySet<String>() to emptySet<String>()
        } else {
            collectExternalScriptCandidates(
                page.document, page.url, page.url, visitedScripts, seenSubtitles, emitSubtitle
            )
        }
        val direct = LinkedHashSet<String>().apply {
            addAll(pageDirect)
            addAll(scriptDirect)
        }
        val embeds = LinkedHashSet<String>().apply {
            addAll(pageEmbeds)
            addAll(scriptEmbeds)
        }
        var found = false
        val expectedRuntimeMinutes = parseRuntime(page.document, detailContext(page.document))
        val pageHost = runCatching { URI(page.url).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        val hasDedicatedPlayturkaEmbed = hasPagePlayturkaEmbed ||
            embeds.any { candidate ->
                runCatching { URI(candidate).host.orEmpty().equals("p.playturka.space", true) }
                    .getOrDefault(false)
            }
        val pageHasSignedPlayturkaMaster = extractPlayturkaMasterUrls(
            page.url, page.document, page.text
        ).any { (id, url) -> isSignedPlayturkaMaster(url, id) }
        // When the movie page already points to a dedicated Playturka player,
        // resolve that player page only. Resolving the movie page first can emit
        // a second signed token for the same ID, and CloudStream may select that
        // first (incorrect/stale) link instead of the actual iframe result.
        val resolvePlayturkaOnMoviePage =
            pageHost.equals("p.playturka.space", true) ||
                !hasDedicatedPlayturkaEmbed || pageHasSignedPlayturkaMaster
        Log.d(
            name,
            "Playturka sayfa çözümleme: pageHost=" + pageHost +
                ", dedicatedEmbed=" + hasDedicatedPlayturkaEmbed +
                ", signedMasterInPage=" + pageHasSignedPlayturkaMaster +
                ", resolveMoviePage=" + resolvePlayturkaOnMoviePage
        )
        if (resolvePlayturkaOnMoviePage && publishPlayturkaManifestCandidates(
                page.url, page.document, page.text, seenSubtitles, emitSubtitle, callback,
                expectedRuntimeMinutes
            ) > 0
        ) {
            found = true
        }
        val allCandidates = LinkedHashSet<String>().apply {
            addAll(direct)
            addAll(embeds)
        }

        Log.d(name, "loadLinks: page=" + safeLogUrl(page.url) + ", direct=" + direct.size +
            ", embeds=" + embeds.size + ", candidates=" +
            allCandidates.take(20).joinToString(" | ") { safeLogUrl(it) })

        suspend fun resolve(candidate: String, referer: String, depth: Int) {
            if (depth > 2 || !visited.add(candidate)) return
            Log.d(name, "resolve[$depth]: " + safeLogUrl(candidate))
            if (isDirectMedia(candidate)) {
                if (publishDirect(
                        candidate,
                        referer,
                        callback,
                        expectedRuntimeMinutes = expectedRuntimeMinutes
                    )
                ) {
                    found = true
                }
                return
            }

            val candidateHost = runCatching {
                URI(candidate).host.orEmpty().lowercase(Locale.ROOT)
            }.getOrDefault("")
            // Playturka has a dedicated resolver below; probing generic extractors
            // first only adds requests and does not contribute a usable source.
            if (!candidateHost.equals("p.playturka.space", true)) {
                val extractedLinks = ArrayList<ExtractorLink>()
                runCatching {
                    loadExtractor(candidate, referer, emitSubtitle) {
                        extractedLinks.add(it)
                    }
                }.onFailure {
                    Log.d(name, "Extractor başarısız: " + safeLogUrl(candidate) + " (" + it.javaClass.simpleName + ")")
                }

                for (link in extractedLinks) {
                    if (isAdOrPreviewUrl(link.url)) {
                        Log.d(name, "Extractor reklam/fragman bağlantısı atlandı: " + safeLogUrl(link.url))
                        continue
                    }
                    if (pathOf(link.url).endsWith(".m3u8", true) &&
                        validatePlayturkaMovieStreamDuration(
                            link.url, referer, "extractor", expectedRuntimeMinutes
                        ) == null
                    ) {
                        Log.w(name, "Kısa/reklam veya süresi doğrulanamayan extractor HLS bağlantısı atlandı: " +
                            safeLogUrl(link.url))
                        continue
                    }
                    callback(link)
                    found = true
                }
            }

            if (depth >= 2) return
            val nested = runCatching {
                app.get(
                    candidate,
                    headers = requestHeaders + ("Referer" to referer),
                    referer = referer,
                    allowRedirects = true,
                    timeout = 12000
                )
            }.onFailure {
                Log.d(name, "Embed isteği başarısız: " + safeLogUrl(candidate) + " (" + it.javaClass.simpleName + ")")
            }.getOrNull() ?: return
            Log.d(name, "embed response: " + safeLogUrl(nested.url) + " HTTP=" + nested.code +
                ", length=" + nested.text.length + ", type=" + nested.headers["content-type"].orEmpty())
            if (!nested.isSuccessful || nested.text.isBlank() || nested.text.length > 4_000_000) return
            publishSubtitles(nested.document, nested.text, nested.url, seenSubtitles, emitSubtitle)
            val isPlayturkaPage = runCatching {
                URI(nested.url).host.orEmpty().equals("p.playturka.space", true)
            }.getOrDefault(false)
            var playturkaResolved = false
            if (isPlayturkaPage) {
                val playturkaFound = publishPlayturkaManifestCandidates(
                    nested.url, nested.document, nested.text, seenSubtitles, emitSubtitle, callback,
                    expectedRuntimeMinutes
                )
                if (playturkaFound > 0) {
                    found = true
                    playturkaResolved = true
                }
            }
            if (playturkaResolved) {
                Log.d(name, "Playturka tam film akışı doğrulandı; aynı sayfadaki diğer reklam/alternatif kaynaklar atlandı")
                return
            }

            val (nextDirect, nextEmbeds) = collectPlayerCandidates(nested.document, nested.text, nested.url)
            val (jsDirect, jsEmbeds) = collectExternalScriptCandidates(
                nested.document, nested.url, nested.url, visitedScripts, seenSubtitles, emitSubtitle
            )
            val nestedDirect = LinkedHashSet<String>().apply {
                addAll(nextDirect)
                addAll(jsDirect)
            }
            val nestedEmbeds = LinkedHashSet<String>().apply {
                addAll(nextEmbeds)
                addAll(jsEmbeds)
            }
            Log.d(name, "nested candidates: direct=" + nestedDirect.size + ", embeds=" +
                nestedEmbeds.size + ", page=" + safeLogUrl(nested.url))
            for (url in nestedDirect) {
                if (visited.contains(url)) continue
                if (publishDirect(
                        url,
                        nested.url,
                        callback,
                        expectedRuntimeMinutes = expectedRuntimeMinutes
                    )
                ) {
                    found = true
                    break
                }
            }
            if (!found) {
                for (next in nestedEmbeds.take(12)) {
                    resolve(next, nested.url, depth + 1)
                    if (found) break
                }
            }
        }

        // Prefer the actual Playturka master before generic media candidates,
        // which can include teaser/ad playlists embedded by the page or its scripts.
        val orderedCandidates = allCandidates.sortedBy { candidate ->
            val host = runCatching { URI(candidate).host.orEmpty() }.getOrDefault("")
            val path = pathOf(candidate)
            val playturkaId = Regex("""(?i)^/videos/([A-Za-z0-9_-]{1,64})/master\.m3u8$""")
                .find(path)?.groupValues?.getOrNull(1)
            when {
                host.equals("p.playturka.space", true) &&
                    playturkaId != null && isSignedPlayturkaMaster(candidate, playturkaId) -> 0
                host.equals("p.playturka.space", true) -> 1
                isDirectMedia(candidate) -> 2
                else -> 3
            }
        }
        for (candidate in orderedCandidates.take(20)) {
            if (found) break
            resolve(candidate, page.url, 0)
        }

        // If the iframe was present but the normal Playturka path failed, retain
        // the generic JavaScript-source fallback rather than dropping alternatives.
        if (!found && hasDedicatedPlayturkaEmbed) {
            val (fallbackDirect, fallbackEmbeds) = collectExternalScriptCandidates(
                page.document, page.url, page.url, visitedScripts, seenSubtitles, emitSubtitle
            )
            for (url in fallbackDirect) {
                if (isDirectMedia(url) && publishDirect(
                        url,
                        page.url,
                        callback,
                        expectedRuntimeMinutes = expectedRuntimeMinutes
                    )
                ) {
                    found = true
                    break
                }
            }
            if (!found) {
                for (candidate in fallbackEmbeds.take(12)) {
                    resolve(candidate, page.url, 0)
                    if (found) break
                }
            }
        }

        // Prefer native captions. The external catalogue is optional and only
        // searched when the page/player yielded no subtitle at all.
        if (emittedSubtitleCount == 0) {
            val fallbackCount = publishFallbackTurkishSubtitles(data, seenSubtitles, emitSubtitle)
            Log.d(name, "Harici Türkçe altyazı yedeği: count=" + fallbackCount)
        } else {
            Log.d(name, "Harici altyazı araması atlandı: gömülü altyazı mevcut")
        }

        Log.d(name, "loadLinks: found=" + found + ", visited=" + visited.size + ", candidates=" + allCandidates.size)
        return found
    }
}
