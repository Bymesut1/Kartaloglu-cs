package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class DiziSol : MainAPI() {
    override var mainUrl = "https://dizisol.com"
    override var name = "DiziSol"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val apiHeaders = requestHeaders + (
        "Accept" to "application/json,text/plain,*/*;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/netflix-dizileri" to "Netflix Dizileri",
        "$mainUrl/disney-dizileri" to "Disney+ Dizileri",
        "$mainUrl/prime-dizileri" to "Prime Video Dizileri",
        "$mainUrl/hbomax-dizileri" to "HBO Max Dizileri",
        "$mainUrl/tabii-dizileri-izle" to "tabii Dizileri",
        "$mainUrl/tod-dizileri" to "TOD Dizileri"
    )

    private val seasonEpisodeRegex = Regex("""(?i)(\d+)-sezon-(\d+)-bolum""")
    private val yearRegex = Regex("""(?<!\d)(19\d{2}|20\d{2})(?!\d)""")

    // ---------------------------------------------------------------------
    // URL / metin yardımcıları
    // ---------------------------------------------------------------------

    private fun decode(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")

    private fun fixUrl(raw: String?, base: String = mainUrl): String? {
        val value = decode(raw.orEmpty()).trim().trim('"', '\'')
        if (value.isBlank() || value == "#" || value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) || value.startsWith("about:", true)
        ) return null

        return runCatching {
            val resolved = when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> URI(base).resolve(value).toString()
            }
            resolved.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }.getOrNull()
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")

    private fun isFilmUrl(url: String): Boolean =
        Regex("""(?i)^/film/[^/]+/?$""").matches(pathOf(url))

    private fun isSeriesUrl(url: String): Boolean =
        pathOf(url).startsWith("/dizi/", true)

    private fun isEpisodeUrl(url: String): Boolean =
        seasonEpisodeRegex.containsMatchIn(pathOf(url))

    private fun querySuffix(url: String): String =
        runCatching { URI(url).rawQuery?.let { "?$it" }.orEmpty() }.getOrDefault("")

    private fun queryParameter(url: String, key: String): String? {
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=')
            if (!name.equals(key, true)) return@firstNotNullOfOrNull null
            runCatching {
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }.getOrDefault(part.substringAfter('=', ""))
        }
    }

    private fun tmdbIdFromUrl(url: String): Int? =
        queryParameter(url, "tmdbId")?.toIntOrNull()?.takeIf { it > 0 }

    private fun addTmdbIdToUrl(url: String, parentUrl: String): String {
        val tmdbId = tmdbIdFromUrl(parentUrl) ?: return url
        if (tmdbIdFromUrl(url) != null) return url
        val separator = if (url.contains('?')) "&" else "?"
        return "$url${separator}tmdbId=$tmdbId"
    }

    private fun seriesUrlOf(rawUrl: String): String {
        val fixed = fixUrl(rawUrl) ?: return rawUrl
        if (!isSeriesUrl(fixed)) return fixed
        val parts = pathOf(fixed).trim('/').split('/')
        val query = querySuffix(fixed)
        if (parts.size >= 3 && seasonEpisodeRegex.containsMatchIn(parts.last())) {
            return mainUrl.trimEnd('/') + "/" + parts.take(2).joinToString("/") + query
        }
        return mainUrl.trimEnd('/') + "/" + pathOf(fixed).trim('/') + query
    }

    private fun seasonEpisode(url: String): Pair<Int, Int>? {
        val match = seasonEpisodeRegex.find(pathOf(url)) ?: return null
        val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (season < 1 || episode < 1) return null
        return season to episode
    }

    private fun cleanTitle(raw: String?): String =
        raw.orEmpty()
            .let(::decode)
            .replace(Regex("""(?i)\s*[\-|–]\s*DİZİSOL.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*DIZISOL.*$"""), "")
            .replace(Regex("""(?i)\s+izle\b.*$"""), "")
            .replace(Regex("""(?i)\s+\d+\.?\s*sezon\s+\d+\.?\s*bölüm.*$"""), "")
            .replace(Regex("""(?i)\s+\(\s*(?:film|dizi)\s*\)\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', '–', ':')

    private fun titleFromSlug(url: String): String {
        val parts = pathOf(url).trim('/').split('/')
        val slug = if (parts.size >= 2) parts[1] else parts.lastOrNull().orEmpty()
        return cleanTitle(
            slug.replace(Regex("""-[a-z0-9]{3,5}$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""[-_]+"""), " ")
                .replace(Regex("""\b[a-z]""")) { it.value.uppercase() }
        )
    }

    private suspend fun document(url: String): Document? {
        return runCatching {
            app.get(
                url,
                headers = requestHeaders + mapOf("Referer" to "$mainUrl/"),
                referer = "$mainUrl/",
                allowRedirects = true,
                timeout = 18000
            )
        }.onFailure { Log.w(name, "Sayfa isteği başarısız: $url") }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.document
    }

    private fun tmdbPosterFromAttributes(element: Element?, baseUrl: String): String? {
        if (element == null) return null
        val pathKeys = setOf(
            "poster_path", "posterPath", "data-poster-path", "data-poster_path",
            "data-tmdb-poster-path", "data-tmdb-poster", "data-tmdb-image"
        )
        for (key in pathKeys) {
            val raw = decode(element.attr(key)).trim().trim('"', '\'')
            if (raw.isBlank()) continue

            if (raw.contains("image.tmdb.org/t/p/", true)) {
                val direct = fixUrl(raw, baseUrl)
                if (direct != null && !isRejectedPoster(direct)) return direct
            }

            // TMDB poster_path alanı yalnızca dosya yolu döndürebilir.
            // Sadece adı açıkça TMDB/poster_path olan alanları TMDB tabanına ekle.
            val pathOnly = raw.removePrefix("/")
            if (!raw.startsWith("//") &&
                !raw.startsWith("http://", true) &&
                !raw.startsWith("https://", true) &&
                pathOnly.matches(Regex("""(?i)(?:[^/]+/)?[A-Za-z0-9_%.-]+\.(?:jpe?g|png|webp)"""))
            ) {
                val filename = pathOnly.substringAfterLast("/")
                return "https://image.tmdb.org/t/p/w500/$filename"
            }
        }
        return tmdbPosterFromRaw(element.outerHtml(), baseUrl)
    }

    private fun imageUrl(image: Element?, baseUrl: String = mainUrl): String? {
        if (image == null) return null
        tmdbPosterFromAttributes(image, baseUrl)?.let { return it }
        val keys = listOf(
            "data-src", "data-lazy-src", "data-original", "data-original-src",
            "data-src-original", "data-lazy", "data-image", "data-poster", "data-poster-url",
            "data-image-url", "data-tmdb-poster", "data-tmdb-image", "poster_path", "posterPath",
            "data-poster-path", "data-poster_path", "data-thumb", "data-thumbnail", "data-url",
            "data-echo", "data-srcset", "data-lazy-srcset", "srcset", "src", "poster", "content"
        )
        for (key in keys) {
            val raw = image.attr(key).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val candidate = if (key.contains("srcset", true)) {
                raw.split(",").maxByOrNull { part ->
                    Regex("""(\d+)(?:w|x)""").find(part)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                }?.trim()?.substringBefore(" ").orEmpty()
            } else raw
            val resolved = fixUrl(candidate, baseUrl) ?: continue
            if (isRejectedPoster(resolved)) continue
            return resolved
        }
        return backgroundImageUrl(image, baseUrl)
    }

    /**
     * DiziSol kartlarında TMDB görseli bazen HTML img alanında değil, HTML/JSON
     * içindeki doğrudan image.tmdb.org URL'si veya poster_path olarak bulunuyor.
     * Yalnızca kapsamdaki bütün TMDB adayları aynı dosyaya işaret ediyorsa döndür.
     */
    private fun tmdbPosterFromRaw(raw: String, baseUrl: String = mainUrl): String? {
        val normalized = decode(raw)
            .replace("\\u002e", ".", ignoreCase = true)
            .replace("\\x2e", ".", ignoreCase = true)
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\x2f", "/", ignoreCase = true)
            .replace("\\/", "/")

        val fileNames = LinkedHashSet<String>()
        val direct = Regex(
            """(?i)(?:https?:)?//image\.tmdb\.org/t/p/(?:w(?:92|154|185|342|500|780)|original)/([A-Za-z0-9_%.-]+\.(?:jpe?g|png|webp))"""
        )
        direct.findAll(normalized).forEach { match ->
            fileNames += match.groupValues[1]
        }

        val posterPath = Regex(
            """(?i)["']poster_path["']\s*:\s*["'](\/?[^"'\\\s,}]+\.(?:jpe?g|png|webp))["']"""
        )
        posterPath.findAll(normalized).forEach { match ->
            val file = match.groupValues[1].trimStart('/').substringAfterLast('/')
            if (file.matches(Regex("""(?i)[A-Za-z0-9_%.-]+\.(?:jpe?g|png|webp)"""))) {
                fileNames += file
            }
        }

        val posterPathAttribute = Regex(
            """(?i)(?:data-)?poster[-_]path\s*=\s*["']\/?([^"']+\.(?:jpe?g|png|webp))["']"""
        )
        posterPathAttribute.findAll(normalized).forEach { match ->
            val file = match.groupValues[1].trimStart('/').substringAfterLast('/')
            if (file.matches(Regex("""(?i)[A-Za-z0-9_%.-]+\.(?:jpe?g|png|webp)"""))) {
                fileNames += file
            }
        }

        if (fileNames.size != 1) return null
        val url = "https://image.tmdb.org/t/p/w500/${fileNames.first()}"
        return fixUrl(url.replace("__FILENAME__", fileNames.first()), baseUrl)
            ?.takeUnless(::isRejectedPoster)
    }

    private fun isRejectedPoster(url: String): Boolean =
        Regex("""(?i)\.(?:svg|gif|ico)(?:[?#]|$)""").containsMatchIn(url) ||
            listOf("logo", "avatar", "placeholder", "blank.", "no-image", "no_image").any {
                url.contains(it, true)
            }

    private fun backgroundImageUrl(element: Element?, baseUrl: String = mainUrl): String? {
        if (element == null) return null
        val dataKeys = listOf(
            "data-bg", "data-background", "data-background-image", "data-bg-image",
            "data-lazy-bg", "data-thumb", "data-thumbnail", "data-poster"
        )
        for (key in dataKeys) {
            val raw = element.attr(key).trim()
            if (raw.isBlank() || raw.startsWith("data:", true)) continue
            val resolved = fixUrl(raw, baseUrl) ?: continue
            if (!isRejectedPoster(resolved)) return resolved
        }

        val rawStyleUrl = Regex("""(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)""")
            .find(element.attr("style"))?.groupValues?.getOrNull(1)?.trim()
            ?: return null
        val resolved = fixUrl(rawStyleUrl, baseUrl) ?: return null
        return resolved.takeUnless(::isRejectedPoster)
    }

    /**
     * Afişi sadece eşleşen içerik kartından oku. Lazy-load ve CSS arka planlı görseller
     * da desteklenir; sayfanın rastgele ilk görseli hiçbir zaman karta atanmaz.
     */
    private fun structuredImageUrl(value: Any?, baseUrl: String): String? {
        when (value) {
            is String -> {
                val url = fixUrl(value, baseUrl) ?: return null
                return url.takeUnless(::isRejectedPoster)
            }
            is JSONObject -> {
                for (key in listOf("url", "contentUrl", "thumbnailUrl")) {
                    val result = structuredImageUrl(value.opt(key), baseUrl)
                    if (result != null) return result
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val result = structuredImageUrl(value.opt(index), baseUrl)
                    if (result != null) return result
                }
            }
        }
        return null
    }

    /**
     * Bazı sayfalarda başlık bağlantısı ile afiş bağlantısı farklı <a> öğelerindedir.
     * Bu durumda yalnızca aynı içerik URL'sine giden görsel bağlantıları eşleştir.
     * Aynı hedef için birden fazla farklı afiş varsa tahmin yürütme.
     */
    private fun posterFromMatchingAnchors(
        link: Element,
        baseUrl: String,
        sourceDoc: Document?,
        allowCanonicalFallback: Boolean
    ): String? {
        if (sourceDoc == null) return null
        val targetUrl = fixUrl(link.attr("href"), baseUrl) ?: return null
        val imageSelector =
            "img, source[srcset], [style*=background], [data-bg], [data-background], " +
                "[data-background-image], [data-src], [data-lazy-src], [data-original], " +
                "[data-image], [data-url], [data-echo], [data-poster], [data-poster-path], " +
                "[data-tmdb-poster], [data-tmdb-image], [data-thumb], [data-thumbnail]"

        fun collect(canonical: Boolean): Set<String> {
            val expected = if (canonical) canonicalResultUrl(targetUrl).trimEnd('/')
                else targetUrl.trimEnd('/')
            val posters = LinkedHashSet<String>()
            for (candidate in sourceDoc.select("a[href]")) {
                val candidateUrl = fixUrl(candidate.attr("href"), baseUrl) ?: continue
                val actual = if (canonical) canonicalResultUrl(candidateUrl).trimEnd('/')
                    else candidateUrl.trimEnd('/')
                if (actual != expected) continue

                val poster = tmdbPosterFromRaw(candidate.outerHtml(), baseUrl)
                    ?: imageUrl(candidate.selectFirst(imageSelector), baseUrl)
                    ?: backgroundImageUrl(candidate, baseUrl)
                if (poster != null && !isRejectedPoster(poster)) posters += poster
            }
            return posters
        }

        val exactPosters = collect(canonical = false)
        if (exactPosters.size == 1) return exactPosters.first()
        if (exactPosters.size > 1) return null

        if (allowCanonicalFallback) {
            val canonicalPosters = collect(canonical = true)
            if (canonicalPosters.size == 1) return canonicalPosters.first()
        }
        return null
    }

    private fun posterFromCard(
        link: Element,
        baseUrl: String = mainUrl,
        sourceDoc: Document? = null,
        allowCanonicalFallback: Boolean = true
    ): String? {
        tmdbPosterFromRaw(link.outerHtml(), baseUrl)?.let { return it }
        posterFromMatchingAnchors(link, baseUrl, sourceDoc, allowCanonicalFallback = false)
            ?.let { return it }

        val imageSelector =
            "img, source[srcset], [style*=background], [data-bg], [data-background], " +
                "[data-background-image], [data-src], [data-lazy-src], [data-original], " +
                "[data-image], [data-url], [data-echo], [data-poster], [data-thumb], [data-thumbnail]"

        imageUrl(link.selectFirst(imageSelector), baseUrl)?.let { return it }
        backgroundImageUrl(link, baseUrl)?.let { return it }

        val card: Element? = link.closest(
            "article, .movie-card, .film-card, .series-card, .content-card, " +
                ".poster-card, .media-card, .item-card, .movie-item, .film-item, .dizi-item, " +
                ".episode-item, .post-item, .swiper-slide, .grid-item, .film-box, .dizi-box, " +
                ".thumb, .thumbnail, .post, .item, .card, li"
        )
        card?.let { c: Element -> tmdbPosterFromRaw(c.outerHtml(), baseUrl) }?.let { return it }
        val cardImage: Element? = card?.selectFirst(imageSelector)
        imageUrl(cardImage, baseUrl)?.let { return it }
        backgroundImageUrl(cardImage, baseUrl)?.let { return it }
        backgroundImageUrl(card, baseUrl)?.let { return it }

        // Bazı temalarda başlık bağlantısı ile afiş bağlantısı aynı kartta ayrı <a>
        // öğeleridir ve kartın CSS sınıfı standart değildir. Yalnızca yakın bir üst
        // kapsayıcıda tek bir farklı içerik URL'si ve tek bir aday görsel varsa kullan.
        val linkUrl = fixUrl(link.attr("href"), baseUrl)?.let(::canonicalResultUrl)
        var parent = link.parent()
        var depth = 0
        while (parent != null && depth < 5) {
            val currentParent = parent
            val contentTargets = LinkedHashSet<String>()
            if (currentParent.tagName().equals("a", true)) {
                fixUrl(currentParent.attr("href"), baseUrl)?.let { href ->
                    if (isFilmUrl(href) || isSeriesUrl(href)) contentTargets += canonicalResultUrl(href)
                }
            }
            currentParent.select("a[href]").forEach { a ->
                val href = fixUrl(a.attr("href"), baseUrl) ?: return@forEach
                if (isFilmUrl(href) || isSeriesUrl(href)) contentTargets += canonicalResultUrl(href)
            }

            if (linkUrl != null && contentTargets.size == 1 && contentTargets.first() == linkUrl) {
                tmdbPosterFromRaw(currentParent.outerHtml(), baseUrl)?.let { return it }
                val urls = LinkedHashSet<String>()
                currentParent.select("img, [style*=background], [data-bg], [data-background], " +
                    "[data-background-image], [data-poster], [data-thumb], [data-thumbnail]").forEach { element ->
                    val url = imageUrl(element, baseUrl) ?: backgroundImageUrl(element, baseUrl)
                    if (url != null) urls += url
                }
                if (urls.size == 1) return urls.first()
            }
            parent = currentParent.parent()
            depth++
        }
        if (allowCanonicalFallback) {
            posterFromMatchingAnchors(link, baseUrl, sourceDoc, allowCanonicalFallback = true)
                ?.let { return it }
        }
        return null
    }

    private fun pagePoster(doc: Document): String? {
        val baseUrl = doc.location().ifBlank { mainUrl }

        // Önce sayfanın kendi paylaşım görseli metadata alanları.
        for (meta in doc.select(
            "meta[property=og:image], meta[property=og:image:url], " +
                "meta[property=og:image:secure_url], meta[name=twitter:image], " +
                "meta[name=twitter:image:src], link[rel=image_src]"
        )) {
            val raw = meta.attr("content").ifBlank { meta.attr("href") }.trim()
            val candidate = fixUrl(raw, baseUrl) ?: continue
            if (!isRejectedPoster(candidate)) return candidate
        }

        // JSON-LD içindeki dizi/film görseli, HTML sınıfları değişse bile güvenilir bir kaynaktır.
        val schemaNodes = jsonLdObjects(doc)
        val preferredNodes = schemaNodes.filter { node ->
            val type = node.opt("@type")?.toString().orEmpty()
            type.contains("TVSeries", true) || type.contains("Movie", true) ||
                type.contains("CreativeWork", true) || type.contains("Series", true)
        }
        for (node in preferredNodes) {
            for (key in listOf("image", "thumbnailUrl", "thumbnail", "poster", "posterUrl", "cover")) {
                structuredImageUrl(node.opt(key), baseUrl)?.let { return it }
            }
            tmdbPosterFromRaw(node.toString(), baseUrl)?.let { return it }
        }
        // Sayfa genelinde tek bir TMDB görsel dosyası varsa paylaşım/script alanları içinden yakala.
        tmdbPosterFromRaw(doc.html(), baseUrl)?.let { return it }

        val imageSelector =
            "img, [style*=background], [data-bg], [data-background], [data-background-image], " +
                "[data-poster], [data-thumb], [data-thumbnail]"
        val explicitSelectors = listOf(
            "main [itemprop=image]", "article [itemprop=image]", "[itemprop=image]",
            "main .detail-poster img", ".detail-poster img",
            "main .movie-detail [class*=poster] img", "main .series-detail [class*=poster] img",
            "main .movie-detail img", "main .series-detail img",
            "article .movie-detail img", "article .series-detail img",
            "article img[itemprop=image]"
        )
        for (selector in explicitSelectors) {
            val element = doc.selectFirst(selector) ?: continue
            val candidate = if (element.tagName().equals("img", true) ||
                element.tagName().equals("source", true)
            ) {
                imageUrl(element, baseUrl)
            } else {
                imageUrl(element.selectFirst("img, source[srcset]"), baseUrl)
                    ?: backgroundImageUrl(element, baseUrl)
            }
            if (candidate != null && !isRejectedPoster(candidate)) return candidate
        }

        // Bazı sayfalarda afiş, h1 başlığı ile aynı detay kapsayıcısındadır.
        val heading = doc.selectFirst("main h1, article h1, h1")
        var ancestor = heading?.parent()
        var headingDepth = 0
        while (ancestor != null && headingDepth < 5) {
            val currentAncestor = ancestor
            val candidates = LinkedHashSet<String>()
            currentAncestor.select(imageSelector).forEach { element ->
                val url = imageUrl(element, baseUrl) ?: backgroundImageUrl(element, baseUrl)
                if (url != null) candidates += url
            }
            if (candidates.size == 1) return candidates.first()
            ancestor = currentAncestor.parent()
            headingDepth++
        }

        // Genel fallback yalnızca ana içerikteki tekil görsel için uygulanır.
        // Önerilen içerik kartları çok sayıda görsel içeriyorsa rastgele afiş seçilmez.
        val mainImages = LinkedHashSet<String>()
        doc.select("main img, main [style*=background], main [data-bg], " +
            "article img, article [style*=background], article [data-bg]").forEach { element ->
            val url = imageUrl(element, baseUrl) ?: backgroundImageUrl(element, baseUrl)
            if (url != null) mainImages += url
        }
        if (mainImages.size == 1) return mainImages.first()

        Log.w(name, "Sayfada güvenilir afiş URL'si bulunamadı: $baseUrl")
        return null
    }

    private fun cardTitle(link: Element, url: String): String {
        val card: Element? = link.closest(
            "article, .movie-card, .film-card, .series-card, .content-card, " +
                ".poster-card, .media-card, .item-card, .movie-item, .film-item, .dizi-item, " +
                ".film-box, .dizi-box, .card, li"
        )
        val candidate = link.attr("title").ifBlank {
            card?.selectFirst("h1, h2, h3, h4, .title, .name, [itemprop=name]")?.text().orEmpty()
        }.ifBlank {
            link.selectFirst("img")?.let { img: Element -> img.attr("alt").ifBlank { img.attr("title") } }.orEmpty()
        }.ifBlank { link.text() }
        return cleanTitle(candidate).ifBlank { titleFromSlug(url) }
    }

    private fun canonicalResultUrl(url: String): String =
        if (isSeriesUrl(url)) seriesUrlOf(url) else url

    // ---------------------------------------------------------------------
    // DiziSol JSON API (the site is a client-rendered SPA; its HTML is only a shell)
    // ---------------------------------------------------------------------

    private suspend fun apiJson(url: String): Any? {
        return try {
            val response = app.get(
                url,
                headers = apiHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
                timeout = 18000
            )
            if (!response.isSuccessful) {
                Log.w(name, "API isteği başarısız: " + url)
                null
            } else {
                JSONTokener(response.text).nextValue()
            }
        } catch (e: Exception) {
            Log.w(name, "API yanıtı okunamadı: " + url, e)
            null
        }
    }

    private fun apiPosterUrl(raw: String?, size: String = "w500"): String? {
        val value = decode(raw.orEmpty()).trim()
        if (value.isBlank() || value.equals("null", true) || value == "false") return null
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            return value.takeUnless(::isRejectedPoster)
        }
        if (value.startsWith("//")) return ("https:" + value).takeUnless(::isRejectedPoster)

        val path = value.trimStart('/')
        if (path.isBlank() || path.contains(" ")) return null
        val resolved = "https://image.tmdb.org/t/p/" + size + "/" + path
        return resolved.takeUnless(::isRejectedPoster)
    }

    private fun apiTitle(item: JSONObject): String {
        val title = item.optString("title").trim()
        if (title.isNotBlank() && !title.equals("null", true)) return cleanTitle(title)
        val name = item.optString("name").trim()
        if (name.isNotBlank() && !name.equals("null", true)) return cleanTitle(name)
        return ""
    }

    private fun apiMediaType(item: JSONObject, fallbackType: String? = null): String {
        val raw = item.optString("media_type").ifBlank {
            item.optString("type")
        }.lowercase()
        if (raw == "tv" || raw.contains("series") || raw.contains("dizi")) return "tv"
        if (raw == "movie" || raw == "film") return "movie"
        if (!fallbackType.isNullOrBlank()) return fallbackType
        return if (item.optString("first_air_date").isNotBlank()) "tv" else "movie"
    }

    private fun contentIdFromUrl(url: String): Int? {
        val pathParts = pathOf(url).trim('/').split('/').filter { it.isNotBlank() }
        if (pathParts.isEmpty()) return null
        val slug = when (pathParts.firstOrNull()?.lowercase()) {
            "film", "dizi" -> pathParts.getOrNull(1).orEmpty()
            else -> pathParts.last()
        }.substringBefore('?')

        // The path suffix is the site's own internal record ID in base 36.
        // The TMDB ID is carried separately as the tmdbId query parameter.
        val shortCode = Regex("""-([a-z0-9]+)$""", RegexOption.IGNORE_CASE)
            .find(slug)?.groupValues?.getOrNull(1)
        if (!shortCode.isNullOrBlank()) {
            // Old generated URLs used the decimal TMDB ID directly (often 5-6 digits).
            if (shortCode.length >= 5 && shortCode.all { it.isDigit() }) {
                shortCode.toIntOrNull()?.let { return it }
            }
            shortCode.toIntOrNull(36)?.let { return it }
        }

        // Geriye dönük uyumluluk: eski, doğrudan sayısal kimlikli URL'ler.
        Regex("""(\d+)$""").find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return Regex("""\d+""").findAll(slug).mapNotNull { it.value.toIntOrNull() }.lastOrNull()
    }

    private fun slugifyTitle(raw: String): String {
        return cleanTitle(raw).lowercase()
            .replace("ç", "c")
            .replace("ğ", "g")
            .replace("ı", "i")
            .replace("ö", "o")
            .replace("ş", "s")
            .replace("ü", "u")
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }

    private fun contentUrl(
        mediaType: String,
        id: Int,
        title: String,
        tmdbId: Int? = null
    ): String {
        val route = if (mediaType == "tv") "dizi" else "film"
        val slug = slugifyTitle(title).ifBlank { "icerik" }
        // DiziSol's route suffix represents its internal record ID, not TMDB's ID.
        val shortCode = id.toString(36)
        val base = mainUrl.trimEnd('/') + "/" + route + "/" + slug + "-" + shortCode
        return if (tmdbId != null && tmdbId > 0) "$base?tmdbId=$tmdbId" else base
    }

    private fun collectApiContent(
        value: Any?,
        output: MutableList<JSONObject>,
        seen: MutableSet<String>,
        fallbackType: String? = null,
        depth: Int = 0
    ) {
        if (depth > 9 || output.size >= 100) return
        when (value) {
            is JSONObject -> {
                val id = value.optInt("id", value.optInt("tmdbId", 0))
                val title = apiTitle(value)
                val mediaType = apiMediaType(value, fallbackType)
                val rawPoster = value.optString("poster_path").ifBlank {
                    value.optString("poster")
                }
                val poster = apiPosterUrl(rawPoster)
                val rawType = value.optString("media_type").ifBlank {
                    value.optString("type")
                }.lowercase()
                val hasMediaType = rawType == "movie" || rawType == "tv" ||
                    rawType == "film" || rawType.contains("series") || rawType.contains("dizi") ||
                    fallbackType == "movie" || fallbackType == "tv" ||
                    value.optString("release_date").isNotBlank() ||
                    value.optString("first_air_date").isNotBlank()

                if (id > 0 && title.isNotBlank() && poster != null && hasMediaType &&
                    mediaType in setOf("movie", "tv")
                ) {
                    val key = mediaType + ":" + id
                    if (seen.add(key)) output += value
                    return
                }

                val keys = value.keys()
                while (keys.hasNext() && output.size < 100) {
                    val key = keys.next()
                    // Episode rows are handled within a selected series, not as movie/series cards.
                    if (key.equals("latestEpisodes", true) || key.equals("episodes", true) ||
                        key.equals("seasons", true)
                    ) continue
                    collectApiContent(value.opt(key), output, seen, fallbackType, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    if (output.size >= 100) break
                    collectApiContent(value.opt(index), output, seen, fallbackType, depth + 1)
                }
            }
        }
    }

    private fun searchResponseFromApi(item: JSONObject, fallbackType: String? = null): SearchResponse? {
        val id = item.optInt("id", item.optInt("tmdbId", 0))
        val title = apiTitle(item)
        if (id <= 0 || title.isBlank()) return null

        val mediaType = apiMediaType(item, fallbackType)
        if (mediaType != "movie" && mediaType != "tv") return null

        val posterRaw = item.optString("poster_path").ifBlank { item.optString("poster") }
        val poster = apiPosterUrl(posterRaw) ?: return null
        val date = item.optString("release_date").ifBlank { item.optString("first_air_date") }
        val year = yearRegex.find(date)?.value?.toIntOrNull()
        val tmdbId = item.optInt("tmdbId", 0).takeIf { it > 0 }
        val url = contentUrl(mediaType, id, title, tmdbId)
        val rating = apiScoreFromJson(item)

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                posterHeaders = requestHeaders
                this.year = year
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                posterHeaders = requestHeaders
                this.year = year
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun searchResponsesFromApi(value: Any?, fallbackType: String? = null): List<SearchResponse> {
        val objects = ArrayList<JSONObject>()
        collectApiContent(value, objects, LinkedHashSet(), fallbackType)
        return objects.mapNotNull { searchResponseFromApi(it, fallbackType) }
    }

    private fun apiPageUrl(requestData: String, page: Int): Pair<String, String?>? {
        val path = pathOf(requestData).ifBlank { "/" }
        val browse = mainUrl + "/api/library/browse?type=tv&platform="
        return when {
            path == "/" -> (mainUrl + "/api/library/home-feed") to null
            path.startsWith("/filmler", true) ->
                (mainUrl + "/api/library/browse?type=movie&page=" + page) to "movie"
            path.startsWith("/diziler", true) ->
                (mainUrl + "/api/library/browse?type=tv&page=" + page) to "tv"
            path.startsWith("/netflix-dizileri", true) ->
                (browse + "netflix&page=" + page) to "tv"
            path.startsWith("/disney-dizileri", true) ->
                (browse + "disney&page=" + page) to "tv"
            path.startsWith("/prime-dizileri", true) ->
                (browse + "prime&page=" + page) to "tv"
            path.startsWith("/hbomax-dizileri", true) ->
                (browse + "hbomax&page=" + page) to "tv"
            path.startsWith("/tabii-dizileri-izle", true) ||
                path.startsWith("/tabii-dizileri", true) ->
                (browse + "tabii&page=" + page) to "tv"
            path.startsWith("/tod-dizileri", true) ->
                (browse + "tod&page=" + page) to "tv"
            else -> null
        }
    }

    private fun parseCards(doc: Document, pageUrl: String): List<SearchResponse> {
        val found = LinkedHashMap<String, SearchResponse>()
        for (link in doc.select("a[href]")) {
            val href = fixUrl(link.attr("href"), pageUrl) ?: continue
            if (!isFilmUrl(href) && !isSeriesUrl(href)) continue
            val canonical = canonicalResultUrl(href)
            val title = cardTitle(link, href)
            if (title.isBlank() || title.length > 150 || title.equals("izle", true)) continue
            if (title.lowercase() in setOf("film izle", "dizi izle", "detaylar", "hemen izle")) continue

            val poster = posterFromCard(link, pageUrl, doc, allowCanonicalFallback = true)
            val cardHtml = link.closest(
                "article, .movie-card, .film-card, .series-card, .content-card, .poster-card, .media-card, .item-card, .card, li"
            )?.outerHtml().orEmpty() + " " + link.outerHtml()
            val nearbyText = link.parent()?.text().orEmpty()
            val cardYear = yearRegex.find(nearbyText)?.value?.toIntOrNull()
            val cardScore = extractImdbScore(cardHtml + " " + nearbyText)
            val response: SearchResponse = if (isSeriesUrl(href)) {
                newTvSeriesSearchResponse(title, canonical, TvType.TvSeries) {
                    posterUrl = poster
                    posterHeaders = requestHeaders
                    year = cardYear
                    cardScore?.let { score = Score.from10(it) }
                }
            } else {
                newMovieSearchResponse(title, canonical, TvType.Movie) {
                    posterUrl = poster
                    posterHeaders = requestHeaders
                    year = cardYear
                    cardScore?.let { score = Score.from10(it) }
                }
            }
            found.putIfAbsent(canonical, response)
        }
        return found.values.toList()
    }

    // ---------------------------------------------------------------------
    // Ana sayfa ve arama
    // ---------------------------------------------------------------------

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&page=$page"
        else base.trimEnd('/') + "/page/" + page + "/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiPage = apiPageUrl(request.data, page)
        if (apiPage != null) {
            val (apiUrl, fallbackType) = apiPage
            val json = apiJson(apiUrl)
            if (json != null) {
                val items = searchResponsesFromApi(json, fallbackType)
                if (items.isNotEmpty()) {
                    val response = json as? JSONObject
                    val totalPages = response?.optInt(
                        "total_pages",
                        response.optInt("totalPages", 1)
                    ) ?: 1
                    val hasMore = apiUrl.contains("/api/library/browse?") && page < totalPages
                    Log.d(name, "JSON API içerikleri yüklendi: " + apiUrl + "; kayıt=" + items.size)
                    return newHomePageResponse(request.name, items, hasMore)
                }
                Log.w(name, "JSON API yanıtı posterli içerik döndürmedi: " + apiUrl)
            }
        }

        // Keep a conservative HTML fallback for categories not exposed by the JSON API.
        val url = pageUrl(request.data, page)
        var doc = document(url)
        if ((doc == null || parseCards(doc, url).isEmpty()) && page > 1) {
            val alternate = if (url.contains("?")) url else request.data.trimEnd('/') + "?page=" + page
            doc = document(alternate) ?: doc
        }
        if (doc == null) return newHomePageResponse(request.name, emptyList(), false)
        val items = parseCards(doc, url)
        val more = doc.select("a[href]").any { link ->
            val label = link.text().trim().lowercase()
            label.contains("sonraki") || label == "next" ||
                (page + 1).toString() == link.text().trim() ||
                link.attr("href").contains("/page/" + (page + 1) + "/")
        }
        return newHomePageResponse(request.name, items, items.isNotEmpty() && more)
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
        if (q.length < 2) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val apiUrl = mainUrl + "/api/movies/search?q=" + encoded
        val apiResult = apiJson(apiUrl)
        if (apiResult != null) {
            val results = searchResponsesFromApi(apiResult).filter { matchesSearchQuery(q, it.name) }
            Log.d(name, "JSON API arama tamamlandı: " + apiUrl + "; eşleşen sonuç=" + results.size)
            if (results.isNotEmpty()) return results
            Log.d(name, "JSON API yanıtında başlık eşleşmesi yok; HTML arama yedeğine geçiliyor.")
        }

        // Try the HTML search as a fallback when the API fails or returns no title matches.
        val candidates = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?q=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/arama/?q=$encoded",
            "$mainUrl/arama/?s=$encoded",
            "$mainUrl/arama/$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/ara?q=$encoded"
        ).distinct()

        for (url in candidates) {
            val doc = document(url) ?: continue
            val results = parseCards(doc, url).filter { matchesSearchQuery(q, it.name) }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    // ---------------------------------------------------------------------
    // Detay ve metadata
    // ---------------------------------------------------------------------

    private fun ratingValue(raw: Any?): Double? {
        val value = when (raw) {
            null -> return null
            is Number -> raw.toString()
            is JSONObject -> {
                val nested = raw.opt("ratingValue") ?: raw.opt("value") ?: raw.opt("score")
                return ratingValue(nested)
            }
            else -> raw.toString()
        }
        val parsed = Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
            .find(value)?.value?.replace(',', '.')?.toDoubleOrNull()
        return parsed?.takeIf { it in 0.0..10.0 }
    }

    private fun imdbScoreFromJson(item: JSONObject?): Double? {
        if (item == null) return null
        val keys = listOf(
            "imdbRating", "imdb_rating", "imdbScore", "imdb_score",
            "imdbRate", "imdb_rate", "imdbVoteAverage", "imdb_vote_average"
        )
        for (key in keys) {
            ratingValue(item.opt(key))?.let { return it }
        }
        item.optJSONObject("ratings")?.let { ratings ->
            for (key in keys) ratingValue(ratings.opt(key))?.let { return it }
        }
        item.optJSONObject("data")?.let { data ->
            if (data !== item) imdbScoreFromJson(data)?.let { return it }
        }
        return null
    }

    private fun apiScoreFromJson(item: JSONObject?): Double? {
        if (item == null) return null
        imdbScoreFromJson(item)?.let { return it }
        for (key in listOf("vote_average", "voteAverage", "tmdbRating", "tmdb_rating", "score", "rating")) {
            ratingValue(item.opt(key))?.let { return it }
        }
        return null
    }

    private fun extractImdbScore(text: String): Double? =
        Regex("""(?i)(?:IMDb|IMDB)\s*(?:puanı|rating)?\s*[:★]?\s*([0-9]+(?:[.,][0-9]+)?)""")
            .find(decode(text))?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }

    private fun apiActors(details: JSONObject?): List<ActorData> {
        val cast = details?.optJSONObject("credits")?.optJSONArray("cast")
            ?: details?.optJSONObject("aggregate_credits")?.optJSONArray("cast")
            ?: return emptyList()
        val seen = LinkedHashSet<String>()
        return (0 until cast.length()).mapNotNull { index ->
            val item = cast.optJSONObject(index) ?: return@mapNotNull null
            val actorName = item.optString("name").trim()
                .takeUnless { it.isBlank() || it.equals("null", true) }
                ?: return@mapNotNull null
            if (!seen.add(actorName.lowercase())) return@mapNotNull null
            val profile = apiPosterUrl(item.optString("profile_path"), "w185")
            ActorData(Actor(actorName, profile))
        }.take(20)
    }

    private fun apiTrailer(details: JSONObject?): String? {
        val videos = details?.optJSONObject("videos")?.optJSONArray("results") ?: return null
        val candidates = (0 until videos.length())
            .mapNotNull { videos.optJSONObject(it) }
            .filter {
                it.optString("site").equals("YouTube", true) &&
                    it.optString("key").matches(Regex("""[A-Za-z0-9_-]{6,}"""))
            }
            .sortedWith(
                compareBy<JSONObject> {
                    if (it.optString("type").equals("Trailer", true)) 0 else 1
                }.thenBy {
                    if (it.optBoolean("official")) 0 else 1
                }.thenBy {
                    if (it.optString("iso_639_1").equals("tr", true)) 0 else 1
                }
            )
        val video = candidates.firstOrNull() ?: return null
        return "https://www.youtube.com/watch?v=" + video.optString("key")
    }

    private suspend fun fetchImdbScore(details: JSONObject?): Double? {
        imdbScoreFromJson(details)?.let { return it }
        val imdbId = details?.optJSONObject("external_ids")?.optString("imdb_id")
            ?.takeIf { it.matches(Regex("""tt\d{5,}""")) }
            ?: details?.optString("imdb_id")?.takeIf { it.matches(Regex("""tt\d{5,}""")) }
            ?: return null
        val imdbUrl = "https://www.imdb.com/title/$imdbId/"
        return try {
            val response = app.get(
                imdbUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9"
                ),
                allowRedirects = true,
                timeout = 6500
            )
            val imdbDoc = org.jsoup.Jsoup.parse(response.text, imdbUrl)
            val ldScore = jsonLdObjects(imdbDoc).firstNotNullOfOrNull { item ->
                ratingValue(item.optJSONObject("aggregateRating")?.opt("ratingValue"))
            }
            ldScore ?: ratingValue(
                imdbDoc.selectFirst("[itemprop=ratingValue], meta[property=og:rating]")?.let { el: Element ->
                    el.attr("content").ifBlank { el.attr("data-rating") }.ifBlank { el.text() }
                }
            ) ?: extractImdbScore(imdbDoc.text())
        } catch (e: Exception) {
            Log.d(name, "IMDb puanı alınamadı (" + imdbId + "): " + e.javaClass.simpleName)
            null
        }
    }

    private data class Meta(
        val year: Int?,
        val score: Double?,
        val genres: List<String>,
        val actors: List<String>,
        val trailer: String?,
        val plot: String?
    )

    private fun jsonLdObjects(doc: Document): List<JSONObject> {
        val objects = ArrayList<JSONObject>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    objects += value
                    walk(value.opt("@graph"))
                    walk(value.opt("itemListElement"))
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        doc.select("script[type=application/ld+json]").forEach { script ->
            runCatching { walk(JSONTokener(script.data().trim()).nextValue()) }
        }
        return objects
    }

    private fun jsonNames(value: Any?): List<String> = when (value) {
        is String -> value.split(",").map { it.trim() }.filter { it.isNotBlank() }
        is JSONObject -> listOfNotNull(value.optString("name").trim().takeIf { it.isNotBlank() })
        is JSONArray -> (0 until value.length()).flatMap { jsonNames(value.opt(it)) }
        else -> emptyList()
    }

    private fun meta(doc: Document, pageUrl: String): Meta {
        val nodes = jsonLdObjects(doc)
        val relevant = nodes.firstOrNull { node ->
            val type = node.opt("@type")?.toString().orEmpty()
            type.contains("Movie", true) || type.contains("TVSeries", true) ||
                type.contains("CreativeWork", true) || type.contains("Episode", true)
        }
        val date = relevant?.optString("datePublished").orEmpty()
            .ifBlank { relevant?.optString("dateCreated").orEmpty() }
        val year = yearRegex.find(date)?.value?.toIntOrNull()
            ?: yearRegex.find(doc.selectFirst("h1")?.text().orEmpty())?.value?.toIntOrNull()
            ?: yearRegex.find(doc.selectFirst("meta[name=description]")?.attr("content").orEmpty())?.value?.toIntOrNull()

        val ratingObj = relevant?.optJSONObject("aggregateRating")
            ?: nodes.firstNotNullOfOrNull { it.optJSONObject("aggregateRating") }
        val ratingRaw = ratingObj?.opt("ratingValue")?.toString().orEmpty()
        val score = ratingRaw.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
            ?: doc.selectFirst("[itemprop=ratingValue], meta[itemprop=ratingValue], .imdb-rating, .imdb-puan")
                ?.let { el: Element ->
                    el.attr("content").ifBlank { el.attr("data-rating") }
                        .ifBlank { el.attr("title") }.ifBlank { el.text() }
                }?.let { raw ->
                    Regex("""(?<!\d)(10(?:[.,]\d)?|[0-9](?:[.,]\d)?)(?!\d)""")
                        .find(raw)?.value?.replace(',', '.')?.toDoubleOrNull()
                }?.takeIf { it in 0.0..10.0 }

        val genres = LinkedHashSet<String>()
        nodes.forEach { node -> genres.addAll(jsonNames(node.opt("genre"))) }
        doc.select(
            "main .genres a, main .genre a, main [class*=category] a, " +
                "article .genres a, article .genre a, .movie-detail .tag, .series-detail .tag, " +
                "[itemprop=genre]"
        ).forEach { el ->
            val text = el.attr("content").ifBlank { el.text() }.trim()
            if (text.length in 2..40 && !text.contains("yetişkin", true) &&
                !text.equals("film", true) && !text.equals("dizi", true)
            ) genres += text
        }

        val actors = LinkedHashSet<String>()
        nodes.forEach { node ->
            actors.addAll(jsonNames(node.opt("actor")))
            actors.addAll(jsonNames(node.opt("actors")))
        }
        doc.select(
            "main [itemprop=actor] [itemprop=name], main .cast a, main .actors a, " +
                "article .cast a, article .actors a, .movie-detail .cast a, .series-detail .cast a"
        ).forEach { el ->
            val value = el.attr("content").ifBlank { el.attr("title") }.ifBlank { el.text() }.trim()
            if (value.length in 2..70 && value.split(' ').size <= 6) actors += value
        }

        val trailer = doc.select("iframe[src], meta[property=og:video], a[href]").firstOrNull { el ->
            val value = el.attr("src").ifBlank { el.attr("content") }.ifBlank { el.attr("href") }
            value.contains("youtube.com/embed", true) || value.contains("youtu.be/", true) ||
                value.contains("youtube.com/watch", true)
        }?.let { el: Element ->
            val raw = el.attr("src").ifBlank { el.attr("content") }.ifBlank { el.attr("href") }
            fixUrl(raw, pageUrl)
        }

        val plot = doc.selectFirst(
            "meta[property=og:description], meta[name=description], main [itemprop=description], " +
                "main .overview, main .plot, main .description, article .description"
        )?.let { el: Element ->
            el.attr("content").ifBlank { el.text() }.trim()
        }?.takeIf { it.length > 20 }

        return Meta(year, score, genres.toList(), actors.toList(), trailer, plot)
    }

    private fun pageTitle(doc: Document, url: String): String {
        val raw = doc.selectFirst("h1")?.text().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            .ifBlank { doc.title() }
        val clean = cleanTitle(raw)
        return clean.ifBlank { titleFromSlug(url) }
    }

    private suspend fun episodesFromApi(
        details: JSONObject,
        seriesUrl: String,
        fallbackPoster: String?
    ): List<Episode> {
        val seriesId = details.optInt("id", contentIdFromUrl(seriesUrl) ?: 0)
        if (seriesId <= 0) return emptyList()
        val seasons = details.optJSONArray("seasons") ?: return emptyList()
        val found = LinkedHashMap<String, Episode>()

        // The site uses TMDB's series/season endpoint for episode names and still images.
        for (seasonIndex in 0 until seasons.length()) {
            val seasonInfo = seasons.optJSONObject(seasonIndex) ?: continue
            val seasonNumber = seasonInfo.optInt("season_number", 0)
            if (seasonNumber <= 0) continue
            if (seasonInfo.optInt("episode_count", 1) <= 0) continue

            val seasonUrl = mainUrl + "/api/tmdb/tv/" + seriesId + "/season/" + seasonNumber
            val seasonData = apiJson(seasonUrl) as? JSONObject ?: continue
            val episodeList = seasonData.optJSONArray("episodes") ?: continue

            for (episodeIndex in 0 until episodeList.length()) {
                val episodeData = episodeList.optJSONObject(episodeIndex) ?: continue
                val episodeNumber = episodeData.optInt("episode_number", 0)
                if (episodeNumber <= 0) continue

                val episodeUrl = seriesUrl.substringBefore('?').trimEnd('/') + "/" +
                    seasonNumber + "-sezon-" + episodeNumber + "-bolum" + querySuffix(seriesUrl)
                val episodeName = episodeData.optString("name").trim()
                    .takeUnless { it.isBlank() || it.equals("null", true) }
                    ?: "$seasonNumber. Sezon $episodeNumber. Bölüm"
                val episodePoster = apiPosterUrl(
                    episodeData.optString("still_path"),
                    "w780"
                ) ?: fallbackPoster
                val key = "$seasonNumber:$episodeNumber"
                found.putIfAbsent(
                    key,
                    newEpisode(episodeUrl) {
                        name = episodeName
                        season = seasonNumber
                        episode = episodeNumber
                        posterUrl = episodePoster
                    }
                )
            }
        }
        return found.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )
    }

    private fun parseEpisodes(doc: Document, seriesUrl: String, fallbackPoster: String?): List<Episode> {
        val canonical = seriesUrlOf(seriesUrl).substringBefore('?').trimEnd('/')
        val found = LinkedHashMap<String, Episode>()
        for (link in doc.select("a[href]")) {
            val href = fixUrl(link.attr("href"), doc.location()) ?: continue
            if (!isSeriesUrl(href)) continue
            if (seriesUrlOf(href).substringBefore('?').trimEnd('/') != canonical) continue
            val episodeDataUrl = addTmdbIdToUrl(href, seriesUrl)
            val coordinates = seasonEpisode(href) ?: continue
            val (season, episodeNumber) = coordinates
            val text = link.text().trim()
            val name = if (Regex("""(?i)sezon.*bölüm""").containsMatchIn(text)) {
                cleanTitle(text).ifBlank { "$season. Sezon $episodeNumber. Bölüm" }
            } else {
                "$season. Sezon $episodeNumber. Bölüm"
            }
            found.putIfAbsent(
                episodeDataUrl,
                newEpisode(episodeDataUrl) {
                    this.name = name
                    this.season = season
                    this.episode = episodeNumber
                    // Bölüm afişi yalnızca aynı bölüm kartında açıkça bulunursa kullanılır.
                    posterUrl = posterFromCard(link, doc.location(), doc, allowCanonicalFallback = false) ?: fallbackPoster
                }
            )
        }
        val current = fixUrl(seriesUrl) ?: seriesUrl
        val coords = seasonEpisode(current)
        if (found.isEmpty() && coords != null) {
            val (season, episodeNumber) = coords
            found[current] = newEpisode(current) {
                name = "$season. Sezon $episodeNumber. Bölüm"
                this.season = season
                this.episode = episodeNumber
                posterUrl = fallbackPoster
            }
        }
        return found.values.sortedWith(
            compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = fixUrl(url) ?: return null
        if (!isFilmUrl(normalized) && !isSeriesUrl(normalized)) {
            Log.w(name, "Desteklenmeyen detay yolu: $normalized")
            return null
        }
        val doc = document(normalized) ?: return null
        val isMovie = isFilmUrl(normalized)
        val siteId = contentIdFromUrl(normalized)
        val id = tmdbIdFromUrl(normalized) ?: siteId
        val apiType = if (isMovie) "movie" else "tv"
        Log.d(
            name,
            "Detay kimlikleri: siteId=$siteId tmdbId=${tmdbIdFromUrl(normalized)} tür=$apiType"
        )
        val details = id?.let {
            apiJson(
                mainUrl + "/api/tmdb/" + apiType + "/" + it +
                    "?language=tr-TR&append_to_response=credits,videos,external_ids"
            ) as? JSONObject
        }

        val apiName = details?.let {
            it.optString("title").takeUnless { title -> title.isBlank() || title.equals("null", true) }
                ?: it.optString("name").takeUnless { title -> title.isBlank() || title.equals("null", true) }
        }.orEmpty()
        val title = cleanTitle(apiName).ifBlank { pageTitle(doc, normalized) }
        val poster = apiPosterUrl(details?.optString("poster_path"))
            ?: pagePoster(doc)
        val meta = meta(doc, normalized)

        val date = details?.optString("release_date").orEmpty()
            .ifBlank { details?.optString("first_air_date").orEmpty() }
        val apiYear = yearRegex.find(date)?.value?.toIntOrNull()
        val apiScore = details?.optDouble("vote_average", -1.0)?.takeIf { it >= 0.0 }
        val apiPlot = details?.optString("overview")
            ?.takeUnless { it.isBlank() || it.equals("null", true) }
        val apiGenres = details?.optJSONArray("genres")?.let { genres ->
            (0 until genres.length()).mapNotNull { index ->
                genres.optJSONObject(index)?.optString("name")
                    ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            }
        }.orEmpty()
        val finalPlot = apiPlot ?: meta.plot
        val finalYear = apiYear ?: meta.year
        val finalGenres = apiGenres.ifEmpty { meta.genres }
        val finalScore = fetchImdbScore(details) ?: meta.score ?: apiScore
        val cast = apiActors(details).ifEmpty { meta.actors.map { ActorData(Actor(it)) } }
        val trailer = apiTrailer(details) ?: meta.trailer

        Log.d(
            name,
            "Metadata: oyuncu=" + cast.size + ", fragman=" + (trailer != null) + ", puan=" + (finalScore ?: "yok")
        )

        return if (isMovie) {
            newMovieLoadResponse(
                name = title,
                url = normalized,
                type = TvType.Movie,
                dataUrl = normalized
            ) {
                posterUrl = poster
                posterHeaders = requestHeaders
                plot = finalPlot
                year = finalYear
                finalScore?.let { score = Score.from10(it) }
                tags = finalGenres
                actors = cast
                addTrailer(trailer)
            }
        } else {
            val canonicalSeries = seriesUrlOf(normalized)
            val fromApi = details?.let { episodesFromApi(it, canonicalSeries, poster) }.orEmpty()
            val episodes = fromApi.ifEmpty { parseEpisodes(doc, canonicalSeries, poster) }
            newTvSeriesLoadResponse(
                title,
                canonicalSeries,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                posterHeaders = requestHeaders
                plot = finalPlot
                year = finalYear
                finalScore?.let { score = Score.from10(it) }
                tags = finalGenres
                actors = cast
                addTrailer(trailer)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Video / altyazı çözümleme
    // ---------------------------------------------------------------------

    private fun directMediaUrls(raw: String): List<String> {
        val text = decode(raw)
            .replace("\\u002e", ".", ignoreCase = true)
            .replace("\\x2e", ".", ignoreCase = true)
        val found = LinkedHashSet<String>()
        Regex(
            """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { found += it.value.trimEnd(')', ']', '}', ';', ',') }
        Regex(
            """(?<![:\w])//[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { found += "https:" + it.value.trimEnd(')', ']', '}', ';', ',') }
        return found.filterNot {
            it.contains("thumbnail", true) || it.contains("preview", true) ||
                it.contains("/sample", true)
        }
    }

    private fun playerCandidates(doc: Document, pageUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        val selectors = listOf(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url]",
            "video[src], video source[src], source[src]",
            "[data-embed], [data-player], [data-video], [data-url], [data-src], [data-href]"
        )
        doc.select(selectors.joinToString(",")).forEach { el ->
            val value = listOf("src", "data-src", "data-lazy-src", "data-url",
                "data-embed", "data-player", "data-video", "data-href")
                .asSequence().map { el.attr(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            val candidate = fixUrl(decode(value), pageUrl)
            if (candidate != null && candidate != pageUrl && !isStaticAsset(candidate)) found += candidate
        }

        // Oynatıcı sekmeleri bazen iframe yerine sunucu düğmesi kullanıyor.
        doc.select("a[href]").forEach { link ->
            val href = fixUrl(link.attr("href"), pageUrl) ?: return@forEach
            val context = (link.text() + " " + link.className() + " " +
                link.id() + " " + link.parent()?.className().orEmpty()).lowercase()
            if (isMediaUrl(href) || ((context.contains("server") || context.contains("player") ||
                context.contains("kaynak") || context.contains("izle") || context.contains("embed")) &&
                (!isSameSite(href) || pathOf(href).contains("/embed", true) ||
                    pathOf(href).contains("/player", true)))
            ) {
                if (href != pageUrl && !isStaticAsset(href)) found += href
            }
        }
        return found.toList()
    }

    private fun isSameSite(url: String): Boolean = runCatching {
        URI(url).host.equals(URI(mainUrl).host, ignoreCase = true)
    }.getOrDefault(false)

    private fun isStaticAsset(url: String): Boolean =
        Regex("""(?i)\.(?:jpe?g|png|gif|webp|svg|ico|css|js|woff2?|ttf)(?:$|[?#])""")
            .containsMatchIn(url)

    private fun isMediaUrl(url: String): Boolean =
        Regex("""(?i)\.(?:m3u8|mp4|mpd|webm)(?:$|[?#])""").containsMatchIn(url) ||
            url.contains("/hls/", true)

    private fun quality(url: String): Int = when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1440", true) -> Qualities.P1440.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun host(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: "DiziSol"

    // Günlüklerde sorgu parametrelerini (olası imzalı bağlantıları) göstermeden URL'yi tanımlar.
    private fun safeLogUrl(url: String): String = runCatching {
        URI(url).let { "${it.host.orEmpty()}${it.path.orEmpty()}" }
    }.getOrDefault("<geçersiz-url>")

    private suspend fun emitMedia(
        url: String,
        sourcePage: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8", true) || url.contains("/hls/", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to sourcePage,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        callback(
            newExtractorLink(
                source = name,
                name = host(url),
                url = url,
                type = type
            ) {
                referer = sourcePage
                headers = mediaHeaders
                quality = quality(url)
            }
        )
    }

    private suspend fun collectSubtitles(doc: Document, pageUrl: String, callback: (SubtitleFile) -> Unit) {
        val seen = HashSet<String>()
        doc.select("track[src], track[data-src], track[data-url], a[href$='.vtt'], a[href$='.srt']")
            .forEach { el ->
                val raw = el.attr("src").ifBlank { el.attr("data-src") }
                    .ifBlank { el.attr("data-url") }.ifBlank { el.attr("href") }
                val subtitleUrl = fixUrl(raw, pageUrl) ?: return@forEach
                if (!seen.add(subtitleUrl)) return@forEach
                val label = el.attr("label").ifBlank { el.attr("srclang") }
                    .ifBlank { el.attr("data-lang") }.ifBlank { el.text() }
                    .ifBlank { "Altyazı" }
                callback(newSubtitleFile(label, subtitleUrl))
            }

        Regex(
            """(?i)["'](?:file|src|url)["']\s*:\s*["']([^"']+\.(?:vtt|srt)(?:\?[^"']*)?)["']"""
        ).findAll(doc.html()).forEach { match ->
            val subtitleUrl = fixUrl(decode(match.groupValues[1]), pageUrl) ?: return@forEach
            if (seen.add(subtitleUrl)) callback(newSubtitleFile("Altyazı", subtitleUrl))
        }
    }

    private suspend fun resolveWebView(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pattern = Regex("""(?i)(?:m3u8|mpd|\.mp4(?:\?|$)|\.webm(?:\?|$)|/hls/)""")
        val resolver = runCatching {
            WebViewResolver(
                interceptUrl = pattern,
                additionalUrls = listOf(pattern),
                useOkhttp = false,
                timeout = 22000L
            )
        }.getOrNull() ?: return false
        val response = runCatching {
            app.get(
                playerUrl,
                headers = requestHeaders + mapOf("Referer" to referer),
                referer = referer,
                interceptor = resolver,
                timeout = 25000
            )
        }.getOrNull() ?: return false
        val mediaUrl = response.url.orEmpty()
        if (!isMediaUrl(mediaUrl)) return false
        emitMedia(mediaUrl, playerUrl, callback)
        return true
    }

    private suspend fun byTmdbRecord(
        tmdbId: Int,
        mediaType: String,
        coordinates: Pair<Int, Int>?
    ): JSONObject? {
        val query = buildString {
            append("?type=").append(mediaType)
            if (mediaType == "tv" && coordinates != null) {
                append("&season=").append(coordinates.first)
                append("&episode=").append(coordinates.second)
            }
        }
        val apiUrl = "$mainUrl/api/movies/by-tmdb/$tmdbId$query"
        val json = apiJson(apiUrl) as? JSONObject
        if (json == null) {
            Log.w(name, "Video API yanıtı JSON nesnesi değil: ${safeLogUrl(apiUrl)}")
            return null
        }
        val record = json.optJSONObject("data")
            ?.takeIf { it.has("m3u8Url") || it.has("id") }
            ?: json
        val stream = record.optString("m3u8Url").trim()
        Log.i(
            name,
            "Video API yanıtı: anahtarlar=${record.keys().asSequence().toList()} m3u8Url=${stream.take(160)} kayıt=${record.toString().take(400)}"
        )
        // Kaynak jetonu sonradan (yedek yol için) kullanılabilsin diye kayda eklenir.
        if (stream.contains("::")) runCatching { record.put("sourceToken", stream) }
        // Platform içeriklerinde m3u8Url gerçek bağlantı değil, "setfilmizle::<base64 sayfa>::diller"
        // gibi bir kaynak jetonu olarak gelir. Bu durumda site bağlantıyı kendi sunucusunda çözer.
        val directStream = stream.isNotBlank() && !stream.equals("null", true) &&
            !stream.contains("::") &&
            (stream.startsWith("http", true) || stream.startsWith("/"))
        if (directStream) return record

        // Netflix / Disney+ / HBO vb. platform içeriklerinde kayıt m3u8Url olmadan gelir;
        // site bağlantıyı POST /api/admin/resolve-stream/{kayitId} ile üretir.
        Log.i(
            name,
            "Video API kaydında m3u8Url yok, resolve-stream deneniyor: tür=$mediaType tmdbId=$tmdbId kayıt=${record.toString().take(300)}"
        )
        return resolveStreamRecord(record, mediaType, coordinates)
    }

    private fun b64Text(raw: String): String? = runCatching {
        var t = raw.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private fun originOfUrl(u: String): String? = runCatching {
        val x = URI(u)
        "${x.scheme}://${x.host}"
    }.getOrNull()

    // Playlist gerçekten açılıyor mu? (#EXTM3U ile başlayan yanıt). Gövde ya da null döner.
    // Oynatıcı yanıt vermeyen sunucuda ~1 dk bekleyip hata verdiği için burada kısa sürede karar verilir.
    private suspend fun fetchPlaylist(url: String, headers: Map<String, String>): String? {
        return runCatching {
            val r = app.get(url, headers = headers, timeout = 10L)
            val t = r.text
            val ok = r.isSuccessful && t.trimStart().startsWith("#EXTM3U")
            if (!ok) {
                Log.w(
                    name,
                    "Playlist yoklaması: kod=${r.code} başı='${t.take(80).replace('\n', ' ')}' kaynak=${safeLogUrl(url)}"
                )
            }
            if (ok) t else null
        }.onFailure {
            Log.w(name, "Playlist yoklaması başarısız: ${safeLogUrl(url)} ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    // "setfilmizle::<base64 sayfa adresi>::diller" jetonundan kaynak sitenin bölüm sayfasını çıkarır
    private fun sourcePageFromToken(record: JSONObject): String? {
        val token = record.optString("sourceToken").trim()
        if (token.contains("::")) {
            val page = token.split("::").getOrNull(1)?.let { b64Text(it) }
            if (page != null && page.startsWith("http", true)) return page
        }
        val su = record.optString("sourceUrl").trim()
        return su.takeIf { it.startsWith("http", true) && !it.equals("null", true) }
    }

    private fun looksLikeStream(value: String): Boolean {
        val v = value.trim()
        return (v.startsWith("http", true) || v.startsWith("/")) &&
            (v.contains(".m3u8", true) || v.contains("/play?", true) ||
                v.contains("/playlist", true) || v.contains("setplay", true))
    }

    private fun findStreamUrl(node: Any?, depth: Int = 0): String? {
        if (node == null || depth > 6) return null
        when (node) {
            is JSONObject -> {
                val preferred = listOf(
                    "m3u8Url", "m3u8", "streamUrl", "stream_url", "playlistUrl",
                    "playlist", "videoUrl", "url", "src", "file", "source"
                )
                for (key in preferred) {
                    val v = node.opt(key)
                    if (v is String && looksLikeStream(v)) return v
                }
                for (key in preferred) {
                    val v = node.opt(key)
                    if (v is String && !v.contains("::") &&
                        (v.startsWith("http", true) || v.startsWith("/")) &&
                        !Regex("""(?i)\.(jpg|jpeg|png|webp|gif|vtt|srt)(\?|$)""").containsMatchIn(v)
                    ) return v
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findStreamUrl(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findStreamUrl(node.opt(i), depth + 1)?.let { return it }
                }
            }
            is String -> if (looksLikeStream(node)) return node
        }
        return null
    }

    private suspend fun resolveStreamRecord(
        record: JSONObject,
        mediaType: String,
        coordinates: Pair<Int, Int>?
    ): JSONObject? {
        var recordId: String? = null
        for (key in listOf("id", "recordId", "movieId", "episodeId", "streamId")) {
            val v = record.optString(key).trim()
            if (v.isNotBlank() && !v.equals("null", true) && v != "0") {
                recordId = v
                break
            }
        }
        if (recordId == null) {
            Log.w(name, "resolve-stream: kayıt kimliği bulunamadı: ${record.toString().take(200)}")
            return null
        }

        val resolveUrl = "$mainUrl/api/admin/resolve-stream/$recordId"
        val bodies = ArrayList<Map<String, Any>>()
        if (mediaType == "tv" && coordinates != null) {
            bodies += mapOf<String, Any>("season" to coordinates.first, "episode" to coordinates.second)
        }
        bodies += mapOf<String, Any>("force" to true)
        bodies += emptyMap<String, Any>()
        bodies += mapOf<String, Any>("retry" to true)

        for (body in bodies) {
            val text = runCatching {
                val response = app.post(
                    resolveUrl,
                    headers = apiHeaders + mapOf("Origin" to mainUrl, "Content-Type" to "application/json"),
                    referer = "$mainUrl/",
                    json = body,
                    timeout = 20000
                )
                Log.d(name, "resolve-stream: kod=${response.code} gövde=$body yanıt=${response.text.take(300)}")
                if (response.isSuccessful) response.text else null
            }.onFailure {
                Log.w(name, "resolve-stream isteği başarısız: ${safeLogUrl(resolveUrl)}", it)
            }.getOrNull() ?: continue

            val parsed = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            val found = findStreamUrl(parsed) ?: continue

            val result = JSONObject(record.toString())
            result.put("m3u8Url", found)
            val responseObj = parsed as? JSONObject
            val dataObj = responseObj?.optJSONObject("data")
            for (key in listOf("subtitleTr", "subtitleEn")) {
                val sub = (dataObj?.optString(key) ?: responseObj?.optString(key)).orEmpty()
                if (sub.isNotBlank() && !sub.equals("null", true)) result.put(key, sub)
            }
            Log.i(name, "resolve-stream başarılı: kaynak=${safeLogUrl(found)}")
            return result
        }
        Log.w(name, "resolve-stream hiçbir gövdeyle bağlantı üretmedi: kayıt=$recordId")
        return null
    }

    // Göreli (relative) playlist/segment adreslerini mutlak adrese çevirir
    private fun resolveRef(base: String, ref: String): String? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        if (r.startsWith("http", true)) return r
        if (r.startsWith("//")) return "https:$r"
        return runCatching { URI(base).resolve(r).toString() }.getOrElse {
            val origin = originOfUrl(base) ?: return null
            if (r.startsWith("/")) origin + r
            else base.substringBefore('?').substringBeforeLast('/') + "/" + r
        }
    }

    // Tek bir video parçasının (segment) kısa sürede gelip gelmediğine bakar
    private suspend fun probeOneSegment(url: String, headers: Map<String, String>): Boolean {
        val started = System.currentTimeMillis()
        return try {
            val r = app.get(url, headers = headers + mapOf("Range" to "bytes=0-4095"), timeout = 9L)
            val ok = r.isSuccessful
            Log.i(
                name,
                "Segment yoklaması: ${if (ok) "AÇILIYOR" else "AÇILMIYOR"} kod=${r.code} " +
                    "süre=${System.currentTimeMillis() - started}ms kaynak=${safeLogUrl(url)}"
            )
            ok
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                name,
                "Segment yoklaması başarısız: ${e.javaClass.simpleName} " +
                    "süre=${System.currentTimeMillis() - started}ms kaynak=${safeLogUrl(url)}"
            )
            false
        }
    }

    // Playlist açılıyor, peki video parçaları da açılıyor mu? (baş, %40 ve %80 noktalarındaki parçalar)
    private suspend fun probeSegments(
        playlist: String,
        playlistUrl: String,
        headers: Map<String, String>
    ): Boolean {
        var text = playlist
        var base = playlistUrl
        var hops = 0
        Log.d(name, "Playlist başı: " + text.lines().take(8).joinToString(" | ") { it.take(110) })
        while (hops < 2 && text.contains("#EXT-X-STREAM-INF", true)) {
            val lines = text.lines().map { it.trim() }
            val tagIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF", true) }
            val variant = lines.drop(tagIndex + 1)
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: break
            val variantUrl = resolveRef(base, variant) ?: break
            val variantText = fetchPlaylist(variantUrl, headers)
            if (variantText == null) {
                Log.w(name, "Alt playlist açılmadı: ${safeLogUrl(variantUrl)}")
                return false
            }
            base = variantUrl
            text = variantText
            hops++
        }
        val segs = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segs.isEmpty()) {
            Log.w(name, "Playlist içinde segment bulunamadı; doğrulanamadı kabul ediliyor")
            return true
        }
        val picks = listOf(0, segs.size * 2 / 5, segs.size * 4 / 5)
            .filter { it < segs.size }.distinct()
        val results = picks.amap { i ->
            val segUrl = resolveRef(base, segs[i])
            segUrl != null && probeOneSegment(segUrl, headers)
        }
        Log.i(name, "Segment sonucu: toplam=${segs.size} yoklanan=${picks.size} başarılı=${results.count { it }}")
        return results.all { it }
    }

    // Setplay oynatıcı sayfasını (stfplay.php) telefondan açıp telefonun IP'sine uygun m3u8 arar
    private suspend fun playerPageStream(
        pageUrl: String,
        referer: String
    ): Pair<String, Map<String, String>>? {
        val origin = originOfUrl(pageUrl) ?: return null
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        var body: String? = null
        try {
            val r = app.get(pageUrl, headers = pageHeaders, referer = referer, timeout = 12L)
            Log.i(name, "Oynatıcı sayfası: kod=${r.code} uzunluk=${r.text.length} kaynak=${safeLogUrl(pageUrl)}")
            if (r.isSuccessful) body = r.text
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "Oynatıcı sayfası alınamadı: ${e.javaClass.simpleName} ${safeLogUrl(pageUrl)}")
        }
        val html = body ?: return null

        val cands = LinkedHashSet<String>()
        directMediaUrls(html).forEach { cands += it }
        Regex("""https?://[^\s"'<>\\]+/manifest\.php[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(decode(html)).forEach { cands += it.value.trimEnd(')', ']', '}', ';', ',') }
        Log.i(name, "Oynatıcı sayfasında adaylar: ${cands.size}")

        for (cand in cands.take(4)) {
            val hdr = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to pageUrl,
                "Origin" to origin,
                "Accept" to "*/*",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )
            val pl = fetchPlaylist(cand, hdr) ?: continue
            if (probeSegments(pl, cand, hdr)) return cand to hdr
        }
        return null
    }

    private suspend fun emitApiRecord(
        record: JSONObject,
        sourcePage: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        state: IntArray? = null
    ): Boolean {
        val rawStream = record.optString("m3u8Url").trim()
        val streamUrl = fixUrl(rawStream, mainUrl) ?: when {
            rawStream.startsWith("//") -> "https:$rawStream"
            rawStream.startsWith("/") -> mainUrl.trimEnd('/') + rawStream
            rawStream.isNotBlank() && !rawStream.equals("null", true) &&
                !rawStream.contains(' ') && rawStream.contains(".m3u8", true) ->
                mainUrl.trimEnd('/') + "/" + rawStream.trimStart('/')
            else -> null
        }
        if (streamUrl == null) {
            Log.w(name, "emitApiRecord: m3u8Url kullanılamadı, değer='${rawStream.take(200)}'")
            return false
        }
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        // Setplay proxy'si bazı bölümlerde playlist'i veriyor ama video parçalarını (segment) vermeyip
        // oynatıcıyı ~1 dk bekletiyor (SocketTimeout). Bu yüzden playlist'e değil, PARÇALARA da bakılır.
        // Proxy sağlam değilse sırayla: doğrudan adres -> oynatıcı sayfası -> WebView -> kaynak sayfası.
        var verified = true
        val extras = ArrayList<Triple<String, String, Map<String, String>>>()
        var webViewGot = false
        if (streamUrl.contains("/api/proxy/setplay/", true)) {
            val uTxt = queryParameter(streamUrl, "u")?.let { b64Text(it) }
            val rTxt = queryParameter(streamUrl, "r")?.let { b64Text(it) }
            val dh = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (rTxt ?: "https://setplay.sbs/"),
                "Origin" to ((rTxt?.let { originOfUrl(it) }) ?: "https://setplay.sbs"),
                "Accept" to "*/*",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )
            val probes = listOf(0, 1).amap { i ->
                if (i == 0) {
                    val pl = fetchPlaylist(streamUrl, headers)
                    pl != null && probeSegments(pl, streamUrl, headers)
                } else if (uTxt != null && uTxt.startsWith("http", true)) {
                    val pl = fetchPlaylist(uTxt, dh)
                    pl != null && probeSegments(pl, uTxt, dh)
                } else {
                    false
                }
            }
            verified = probes[0]
            val directOk = probes[1]
            if (!verified && directOk && uTxt != null) {
                extras += Triple("DiziSol Doğrudan", uTxt, dh)
            }
            Log.i(
                name,
                "Setplay yoklaması (segmentler dahil): proxy=${if (verified) "açılıyor" else "AÇILMIYOR"} " +
                    "doğrudan=${if (directOk) "açılıyor" else "yok"}"
            )

            if (!verified && extras.isEmpty() && rTxt != null && rTxt.startsWith("http", true)) {
                val srcPage = sourcePageFromToken(record)
                val ref = srcPage?.let { originOfUrl(it) }?.let { "$it/" } ?: "https://www.setfilmizle.uk/"
                val alt = playerPageStream(rTxt, ref)
                if (alt != null) {
                    extras += Triple("DiziSol Oynatıcı", alt.first, alt.second)
                } else {
                    webViewGot = resolveWebView(rTxt, ref) { link ->
                        callback(link)
                    }
                    Log.i(name, "Setplay oynatıcı WebView denemesi: sonuç=$webViewGot")
                }
            }
        }

        for ((exLabel, exUrl, exHeaders) in extras) {
            callback(
                newExtractorLink(
                    source = name,
                    name = exLabel,
                    url = exUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = exHeaders["Referer"].orEmpty()
                    this.headers = exHeaders
                    quality = Qualities.P1080.value
                }
            )
        }

        callback(
            newExtractorLink(
                source = name,
                name = "DiziSol API",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$mainUrl/"
                this.headers = headers
                // Doğrulanamayan proxy bağlantısı listede sona düşsün
                quality = if (verified) quality(streamUrl) else Qualities.Unknown.value
            }
        )
        // 1 = bağlantı doğrulandı (proxy ya da alternatif), 2 = hiçbiri doğrulanamadı
        state?.set(0, if (verified || extras.isNotEmpty() || webViewGot) 1 else 2)

        record.optString("subtitleTr").takeIf { it.isNotBlank() && !it.equals("null", true) }?.let {
            fixUrl(it, mainUrl)?.let { url -> subtitleCallback(newSubtitleFile("Türkçe", url)) }
        }
        record.optString("subtitleEn").takeIf { it.isNotBlank() && !it.equals("null", true) }?.let {
            fixUrl(it, mainUrl)?.let { url -> subtitleCallback(newSubtitleFile("English", url)) }
        }
        Log.i(
            name,
            "Video API bağlantısı oluşturuldu: kaynak=${safeLogUrl(streamUrl)} tür=${record.optString("mediaType")} sezon=${record.optInt("season", 0)} bölüm=${record.optInt("episode", 0)}"
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrl(data) ?: run {
            Log.w(name, "Video teşhis: gelen içerik URL'si geçersiz.")
            return false
        }
        Log.i(name, "Video teşhis başladı: ${safeLogUrl(pageUrl)}")

        // API response has a distinct site ID and TMDB ID; never treat them as interchangeable.
        // Pages opened from HTML fallback may not have an explicit ?tmdbId= query.
        // DiziSol's slug suffix is base-36 encoded and the site itself uses it for /api/tmdb/... calls.
        val tmdbId = tmdbIdFromUrl(pageUrl) ?: contentIdFromUrl(pageUrl)
        val mediaType = if (isFilmUrl(pageUrl)) "movie" else "tv"
        val coordinates = if (mediaType == "tv") seasonEpisode(pageUrl) else null
        var extraPage: String? = null
        var apiEmitted = false
        if (tmdbId != null) {
            val record = byTmdbRecord(tmdbId, mediaType, coordinates)
            val apiState = IntArray(1)
            if (record != null && emitApiRecord(record, pageUrl, subtitleCallback, callback, apiState)) {
                if (apiState[0] != 2) {
                    Log.i(name, "Video API sonucu: bağlantı=1 tür=$mediaType tmdbId=$tmdbId")
                    return true
                }
                // Bağlantı verildi ama doğrulanamadı: oynamazsa diye kaynak sitenin sayfasını da tara
                apiEmitted = true
                extraPage = sourcePageFromToken(record)
                Log.w(name, "Video API bağlantısı doğrulanamadı; kaynak sayfası taranıyor: ${extraPage?.let { safeLogUrl(it) }}")
            } else {
                Log.w(
                    name,
                    "Video API fallback: tmdbId=$tmdbId tür=$mediaType koordinat=$coordinates kayıtVar=${record != null}; statik sayfa çözümlemesine geçiliyor"
                )
            }
        }

        val staticUrl = extraPage ?: pageUrl
        val first = document(staticUrl) ?: run {
            Log.w(name, "Video teşhis: içerik sayfası alınamadı: ${safeLogUrl(staticUrl)}")
            return apiEmitted
        }
        val visitedPages = HashSet<String>()
        val seenMedia = HashSet<String>()
        var linkCount = 0
        var webViewCount = 0
        val countedCallback: (ExtractorLink) -> Unit = {
            callback(it)
            linkCount++
        }

        suspend fun inspect(currentDoc: Document, currentUrl: String, depth: Int) {
            if (!visitedPages.add(currentUrl)) return
            collectSubtitles(currentDoc, currentUrl, subtitleCallback)

            val scriptsAndHtml = buildString {
                append(currentDoc.html()).append('\n')
                currentDoc.select("script").forEach { append(it.data()).append('\n') }
            }
            val directCandidates = directMediaUrls(scriptsAndHtml)
            val players = playerCandidates(currentDoc, currentUrl)
            Log.d(
                name,
                "Video teşhis: sayfa=${safeLogUrl(currentUrl)} derinlik=$depth htmlKarakter=${currentDoc.html().length} direktMedya=${directCandidates.size} oynatıcıAdayı=${players.size}"
            )
            for (media in directCandidates) {
                if (seenMedia.add(media)) emitMedia(media, currentUrl, countedCallback)
            }

            for (candidate in players.take(12)) {
                Log.d(name, "Video teşhis oynatıcı adayı: ${safeLogUrl(candidate)}")
                if (candidate in visitedPages) continue
                if (isMediaUrl(candidate)) {
                    if (seenMedia.add(candidate)) emitMedia(candidate, currentUrl, countedCallback)
                    continue
                }

                val before = linkCount
                runCatching { loadExtractor(candidate, currentUrl, subtitleCallback, countedCallback) }
                    .onFailure { Log.d(name, "Extractor çözemedi: $candidate") }

                // Embed sayfalarının içine yalnızca sınırlı derinlikte gir.
                if (depth < 2) {
                    val child = document(candidate)
                    if (child != null) {
                        inspect(child, candidate, depth + 1)
                    } else {
                        Log.d(name, "Video teşhis: aday sayfa okunamadı: ${safeLogUrl(candidate)}")
                    }
                }
                if (linkCount == before && webViewCount < 2 &&
                    (candidate.contains("player", true) || candidate.contains("embed", true) ||
                        candidate != pageUrl)
                ) {
                    webViewCount++
                    val resolved = resolveWebView(candidate, currentUrl, countedCallback)
                    Log.d(
                        name,
                        "Video teşhis WebView: aday=${safeLogUrl(candidate)} sonuç=$resolved bulunanBağlantı=$linkCount"
                    )
                }
            }
        }

        inspect(first, staticUrl, 0)

        // SPA pages may contain only a small HTML shell; their player is initialized
        // client-side and therefore no iframe/source appears in Jsoup's static DOM.
        // In that case, give the actual content page one WebView interception pass.
        if (linkCount == 0) {
            webViewCount++
            val resolved = resolveWebView(staticUrl, "$mainUrl/", countedCallback)
            Log.i(
                name,
                "Video teşhis ana sayfa WebView fallback: sonuç=$resolved bulunanBağlantı=$linkCount"
            )
        }

        Log.i(
            name,
            "Video çözümleme sonucu: sayfa=${safeLogUrl(pageUrl)} ziyaretEdilenSayfa=${visitedPages.size} adayMedya=${seenMedia.size} bağlantı=$linkCount webViewDenemesi=$webViewCount"
        )
        return linkCount > 0 || apiEmitted
    }
}
