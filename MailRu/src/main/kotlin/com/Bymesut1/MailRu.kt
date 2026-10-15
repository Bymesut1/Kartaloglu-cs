package com.Bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.parser.Parser
import java.net.URLEncoder

class MailRu : MainAPI() {
    override var mainUrl            = "https://my.mail.ru"
    private val mobileUrl           = "https://m.my.mail.ru"
    override var name               = "MailRu"
    override val hasMainPage        = true
    override var lang               = "tr"
    override val hasQuickSearch     = false
    override val supportedTypes     = setOf(TvType.Movie)

    // Yükleyen hesaplar. Yenisini eklemek için buraya ad yaz.
    private val accounts = listOf(
        "muhammedgngr25",
        "tr-bogdan",
        "zardozman",
        "filmakinesi5"
    )

    private val minSeconds = 300
    private val maxPages   = 8

    override val mainPage = mainPageOf(
        *accounts.map { "$mobileUrl/mail/$it/video/" to it }.toTypedArray()
    )

    private val androidUa =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    private fun pageHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        "User-Agent"      to androidUa,
        "Accept"          to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ru-RU,ru;q=0.9,tr;q=0.8,en;q=0.7",
        "Referer"         to "$mainUrl/video"
    ) + extra

    private data class ListItem(
        val path: String,
        val title: String,
        val dur: Int,
        val poster: String?
    )

    private fun decodeHtml(s: String): String = Parser.unescapeEntities(s, false)

    private fun parseDuration(s: String?): Int {
        val p = (s ?: "").trim().split(":")
        if (p.size < 2 || p.size > 3) return 0
        var n = 0
        for (x in p) n = n * 60 + (x.toIntOrNull() ?: 0)
        return n
    }

    private fun absolutize(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/")  -> "$mobileUrl$u"
        else               -> u
    }

    private val rePath  = Regex("my\\.mail\\.ru(/[^\"'#\\s:?]*?/video/[^\"'#\\s:?]*?/\\d+\\.html)")
    private val reDur   = Regex("list-item__duration\">\\s*([0-9:]+)")
    private val reTitle = Regex("list-item__title[^\"]*\">\\s*([^<]+)")
    private val reImg   = Regex("(?:data-src|src)=\"((?:https?:)?//[^\"]+\\.(?:jpe?g|png|webp)[^\"]*)\"", RegexOption.IGNORE_CASE)
    private val reMore  = Regex("show-more[^>]*\\shref=\"([^\"]+)\"")

    private fun parseItems(html: String): List<ListItem> {
        val out    = mutableListOf<ListItem>()
        val chunks = html.split("<li class=\"list-item")
        for (i in 1 until chunks.size) {
            val c    = chunks[i]
            val path = rePath.find(c)?.groupValues?.get(1) ?: continue
            val dur  = parseDuration(reDur.find(c)?.groupValues?.get(1))
            val raw  = reTitle.find(c)?.groupValues?.get(1) ?: ""
            val title = decodeHtml(decodeHtml(raw)).trim()
            val img  = reImg.find(c)?.groupValues?.get(1)?.let { decodeHtml(it) }
            val poster = img?.let { if (it.startsWith("//")) "https:$it" else it }
            out += ListItem(path, title, dur, poster)
        }
        return out
    }

    private suspend fun fetchList(url: String, pages: Int): List<ListItem> {
        val all  = mutableListOf<ListItem>()
        var next: String? = url
        var left = pages
        while (next != null && left > 0) {
            val html = try {
                app.get(next, headers = pageHeaders()).text
            } catch (e: Exception) {
                break
            }
            val items = parseItems(html)
            if (items.isEmpty()) break
            all += items
            left--
            val href = reMore.find(html)?.groupValues?.get(1)
            next = href?.let { absolutize(decodeHtml(it)) }
        }
        return all
    }

    private fun cleanTitle(t: String): String {
        var s = t.replace(Regex("^\\s*tt+\\.?\\d{3,9}[.\\s_-]*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\.(mp4|mkv|avi|mpv)$", RegexOption.IGNORE_CASE), "")
        s = s.replace('.', ' ').replace('_', ' ')
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.ifBlank { t.ifBlank { "Video" } }
    }

    private fun isTurkish(t: String): Boolean =
        Regex("(^|[^a-z])(tr|dublaj|turkce|türkçe)([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(t)

    private fun norm(s: String): String = s.lowercase()
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9]"), "")

    private fun ListItem.toSearch(): SearchResponse {
        val p = poster
        return newMovieSearchResponse(cleanTitle(title), "$mainUrl$path", TvType.Movie) {
            this.posterUrl = p
        }
    }

    private fun keep(it: ListItem) = it.dur == 0 || it.dur >= minSeconds

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = fetchList(request.data, maxPages)
            .filter(::keep)
            .distinctBy { it.path }
            .map { it.toSearch() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private var accountCache: List<ListItem>? = null

    private suspend fun accountItems(): List<ListItem> {
        accountCache?.let { return it }
        val items = accounts
            .amap { fetchList("$mobileUrl/mail/$it/video/", maxPages) }
            .flatten()
            .distinctBy { it.path }
        accountCache = items
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        var found = fetchList("$mobileUrl/video/search?st=search&q=$enc", 2)
        if (found.isEmpty()) found = fetchList("$mainUrl/video/search?st=search&q=$enc", 1)

        val q = norm(query)
        val fromAccounts = if (q.length >= 3) {
            accountItems().filter { norm(it.title).contains(q) }
        } else emptyList()

        return (found + fromAccounts)
            .distinctBy { it.path }
            .filter(::keep)
            .sortedByDescending { isTurkish(it.title) }
            .map { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        var doc = app.get(url, headers = pageHeaders()).document
        var rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        if (rawTitle.isBlank()) {
            doc = app.get(url.replace(mainUrl, mobileUrl), headers = pageHeaders()).document
            rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        }
        val title = cleanTitle(
            decodeHtml(rawTitle)
                .replace(Regex("[\\u0400-\\u04FF]+"), " ")
                .replace(Regex("\\s*[|\\-–]\\s*(my\\.)?mail\\.?ru.*$", RegexOption.IGNORE_CASE), "")
        )
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    data class MailVideo(val url: String? = null, val key: String? = null)
    data class MailMeta(val videos: List<MailVideo>? = null)

    private fun findMetaUrl(html: String): String? {
        val direct = listOf(
            Regex("data-meta-url=\"([^\"]+)\""),
            Regex("[\"']metaUrl[\"']\\s*:\\s*[\"']([^\"']+)[\"']"),
            Regex("[\"']metadataUrl[\"']\\s*:\\s*[\"']([^\"']+)[\"']"),
            Regex("[\"']meta_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }

        if (direct != null) {
            val u = decodeHtml(direct).replace("\\/", "/")
            return when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("/")  -> "$mainUrl$u"
                else               -> u
            }
        }
        val id = Regex("/\\+/video/meta/(?:[a-z0-9]+/)?(\\d{8,})").find(html)?.groupValues?.get(1)
            ?: Regex("[\"']externalId[\"']\\s*:\\s*[\"']?(\\d{8,})").find(html)?.groupValues?.get(1)
        return id?.let { "$mainUrl/+/video/meta/$it" }
    }

    private fun heightOf(key: String?): Int =
        Regex("(\\d{3,4})").find(key ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data
        var html = app.get(pageUrl, headers = pageHeaders()).text
        if (html.length < 500) {
            html = app.get(pageUrl.replace(mainUrl, mobileUrl), headers = pageHeaders()).text
        }

        val videoHeaders = mutableMapOf(
            "User-Agent"      to androidUa,
            "Accept"          to "*/*",
            "Accept-Language" to "ru-RU,ru;q=0.9,tr;q=0.8"
        )

        val metaUrl = findMetaUrl(html)
        if (metaUrl == null) {
            val v = Regex("<video[^>]+\\ssrc=\"((?:https?:)?//[^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1) ?: return false
            val u = decodeHtml(v).let { if (it.startsWith("//")) "https:$it" else it }
            callback.invoke(
                newExtractorLink(name, name, u, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = videoHeaders
                }
            )
            return true
        }

        val res  = app.get(
            metaUrl,
            headers = pageHeaders(
                mapOf(
                    "Accept"           to "application/json, text/plain, */*",
                    "Referer"          to pageUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        )
        val meta = res.parsedSafe<MailMeta>() ?: return false

        val cookie = res.cookies["video_key"]
            ?: res.headers.values("set-cookie")
                .firstNotNullOfOrNull { Regex("video_key=([^;,\\s]+)").find(it)?.groupValues?.get(1) }

        var any = false
        (meta.videos ?: emptyList())
            .mapNotNull { v ->
                val raw = v.url?.let { decodeHtml(it).replace("\\/", "/") } ?: return@mapNotNull null
                val u   = if (raw.startsWith("//")) "https:$raw" else raw
                if (!u.startsWith("http")) null else Triple(u, v.key.orEmpty(), heightOf(v.key))
            }
            .sortedByDescending { it.third }
            .forEach { (u, key, h) ->
                val hdrs = videoHeaders.toMutableMap()
                if (!cookie.isNullOrBlank() && !u.contains("video_key=")) hdrs["Cookie"] = "video_key=$cookie"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = if (key.isNotBlank()) "$name $key" else name,
                        url    = u,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = if (h > 0) h else Qualities.Unknown.value
                        this.headers = hdrs
                    }
                )
                any = true
            }
        return any
    }
}
