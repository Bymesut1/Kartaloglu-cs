package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AbiFilm3 : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "AbiFilm3"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val androidUA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val pageHeaders
        get() = mapOf(
            "User-Agent" to androidUA,
            "Accept-Language" to "tr-TR,tr;q=0.9",
            "Referer" to "$mainUrl/"
        )

    override val mainPage = mainPageOf("$mainUrl/" to "Ana Sayfa")

    // ---------------- Ana sayfa / Arama ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, headers = pageHeaders).document
        return newHomePageResponse(request.name, parseCards(doc), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/arama/?s=$q", headers = pageHeaders).document
        return parseCards(doc)
    }

    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("a.item[href*=/film/], a.item[href*=/dizi/]").mapNotNull { el ->
            val href = el.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val img = el.selectFirst("img")
            val title = el.attr("data-title").ifBlank { img?.attr("alt") ?: "" }.trim()
            if (title.isBlank()) return@mapNotNull null
            val poster = fixUrlNull(
                img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
            )
            val year = Regex("(19|20)\\d{2}")
                .find(el.select("div.info span").text())?.value?.toIntOrNull()
            val url = fixUrl(href)
            if (href.contains("/dizi/")) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    // ---------------- Detay sayfası ----------------

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = pageHeaders).document
        val html = doc.html()
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("(19|20)\\d{2}")
            .find(doc.select("div.info span").text())?.value?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val seen = HashSet<String>()
            val episodes = ArrayList<Episode>()
            Regex("href=\"([^\"]*?/sezon-(\\d+)/bolum-(\\d+)/?)\"")
                .findAll(html).forEach { m ->
                    val epUrl = fixUrl(m.groupValues[1])
                    val s = m.groupValues[2].toInt()
                    val e = m.groupValues[3].toInt()
                    if (seen.add("$s-$e")) {
                        episodes.add(newEpisode(epUrl) {
                            this.season = s
                            this.episode = e
                            this.name = "$s. Sezon $e. Bölüm"
                        })
                    }
                }
            episodes.sortWith(compareBy({ it.season }, { it.episode }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ---------------- Linkler ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = pageHeaders).document
        var any = false

        for ((label, embed) in extractParts(doc)) {
            val found = try { resolveEmbed(embed, data) } catch (e: Exception) { null }
            if (found != null) {
                val origin = originOf(embed)
                callback(
                    newExtractorLink(
                        name,
                        "$name - $label",
                        found.url,
                        if (found.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$origin/"
                        this.quality = Qualities.Unknown.value
                        this.headers = found.headers
                    }
                )
                any = true
            } else {
                // Kendi çözücümüz bulamazsa CloudStream'in hazır çıkarıcılarını dene
                try {
                    if (loadExtractor(embed, data, subtitleCallback, callback)) any = true
                } catch (_: Exception) {}
            }
        }
        return any
    }

    private fun extractParts(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val map = LinkedHashMap<String, String>()
        doc.select("a[data-video_url]").forEach { a ->
            val u = a.attr("data-video_url").trim()
            val label = a.text().replace(Regex("\\s+"), " ").trim().ifBlank { "Kaynak" }
            if (Regex("youtube\\.com|youtu\\.be").containsMatchIn(u) ||
                label.contains("fragman", true)
            ) return@forEach
            if (!u.startsWith("http") || map.containsKey(u)) return@forEach
            map[u] = label
        }
        if (map.isEmpty()) {
            doc.select("iframe[data-src]").forEach { f ->
                val u = f.attr("data-src").trim()
                if (!u.startsWith("http") || Regex("youtube|youtu\\.be").containsMatchIn(u)) return@forEach
                if (!map.containsKey(u)) map[u] = "Kaynak"
            }
        }
        return map.map { it.value to it.key }
    }

    // ---------------- Embed çözücü ----------------

    private data class Found(val url: String, val hls: Boolean, val headers: Map<String, String>)

    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String): Found? {
        val origin = originOf(embedUrl)
        for (ua in listOf(androidUA, desktopUA)) {
            val html = try {
                app.get(
                    embedUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Accept" to "text/html,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9",
                        "Referer" to pageUrl
                    )
                ).text
            } catch (e: Exception) { "" }
            if (html.isBlank()) continue
            val r = extractEmbed(html, origin) ?: continue
            return Found(
                r.first, r.second,
                mapOf("User-Agent" to ua, "Referer" to "$origin/")
            )
        }
        return null
    }

    private val badExt =
        Regex("""\.(vtt|srt|jpg|jpeg|png|webp|gif|css|js|ico|svg)(\?|$)""", RegexOption.IGNORE_CASE)
    private val cdnHost = Regex(
        """^https?://[^/]*(?:\.shop|pictabox\.[a-z]+|rapidrame\.[a-z]+|cdnimages?\d*\.[a-z]+|cdnimgs?\d*\.[a-z]+|static\d+\.[a-z]+)(?:[/:?]|$)""",
        RegexOption.IGNORE_CASE
    )

    private fun originOf(u: String) =
        Regex("^https?://[^/]+").find(u)?.value ?: ""

    private fun isMp4(u: String) =
        Regex("\\.mp4(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u)

    /** (url, hls?) döndürür */
    private fun extractEmbed(html: String, origin: String): Pair<String, Boolean>? {
        // Düz <video src> / <source src>
        Regex("<(?:source|video)[^>]*\\ssrc=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.let {
                val u = it.groupValues[1].replace("&amp;", "&")
                if (!badExt.containsMatchIn(u)) return u to !isMp4(u)
            }

        for (t in unpackAll(html)) {
            // JSON-LD contentUrl
            Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").find(t)?.let {
                val v = hexUnescape(it.groupValues[1])
                if (v.startsWith("http") && !badExt.containsMatchIn(v)) return v to !isMp4(v)
            }

            // RapidVid av('...')
            Regex("av\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").find(t)?.let {
                try {
                    val d = decodeSecret(it.groupValues[1])
                    findStreamUrl(d)?.let { f -> return f }
                    if (Regex("^https?://\\S+$").matches(d)) return d to true
                } catch (_: Exception) {}
            }

            // "file":"..."
            for (m in Regex("\"?file\"?\\s*:\\s*\"([^\"]+)\"").findAll(t)) {
                val v = hexUnescape(m.groupValues[1])
                if (v.startsWith("http") && !badExt.containsMatchIn(v)) return v to !isMp4(v)
            }

            findStreamUrl(t)?.let { return it }

            // Göreli m3u8
            Regex("[\"'](/[^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']").find(hexUnescape(t))?.let {
                return (origin + it.groupValues[1]) to true
            }
        }
        return null
    }

    private fun findStreamUrl(text0: String): Pair<String, Boolean>? {
        val text = hexUnescape(text0).replace("&amp;", "&")
        val urls = Regex("""https?://[^\s"'<>\\]+""").findAll(text).map { it.value }.toList()

        val m3u8 = urls.filter { it.contains(".m3u8", true) && !badExt.containsMatchIn(it) }
        if (m3u8.isNotEmpty()) {
            val best = m3u8.firstOrNull { it.contains("master", true) } ?: m3u8.first()
            return best to true
        }
        urls.firstOrNull { cdnHost.containsMatchIn(it) && !badExt.containsMatchIn(it) }
            ?.let { return it to true }

        Regex("""file\s*["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(text)?.let {
            return it.groupValues[1] to false
        }
        return null
    }

    // ---------------- Yardımcılar ----------------

    private fun b64(s: String): ByteArray {
        var x = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        if (x.length % 4 == 1) x = x.dropLast(1)
        while (x.length % 4 != 0) x += "="
        return try { Base64.decode(x, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
    }

    private fun latin1(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    // RapidVid: ters çevir -> b64 -> "K9L" anahtarı ile kaydır -> b64
    private fun decodeSecret(input: String): String {
        val bytes = b64(input.reversed())
        val key = "K9L"
        val out = ByteArray(bytes.size) { i ->
            val off = (key[i % key.length].code % 5) + 1
            (bytes[i] - off).toByte()
        }
        val inner = latin1(out)
        if (Regex("https?://").containsMatchIn(inner)) return inner
        return latin1(b64(inner))
    }

    private fun hexUnescape(s: String): String {
        return s
            .let { Regex("""\\x([0-9a-fA-F]{2})""").replace(it) { m -> m.groupValues[1].toInt(16).toChar().toString() } }
            .let { Regex("""\\u([0-9a-fA-F]{4})""").replace(it) { m -> m.groupValues[1].toInt(16).toChar().toString() } }
            .replace("\\/", "/")
    }

    // eval(function(p,a,c,k,e,d)...) paketlerini aç (en fazla 3 kat)
    private fun unpackAll(text: String): List<String> {
        val list = arrayListOf(text)
        var cur = text
        repeat(3) {
            val u = try { getAndUnpack(cur) } catch (e: Exception) { "" }
            if (u.isBlank() || u == cur) return list
            list.add(u)
            cur = u
        }
        return list
    }
}
