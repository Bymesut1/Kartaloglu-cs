package com.Bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class MailRu : MainAPI() {
    override var mainUrl        = "https://my.mail.ru"
    private val mobileUrl       = "https://m.my.mail.ru"
    override var name           = "MailRu"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    // Yükleyen hesaplar. Yenisini eklemek için buraya ad yaz.
    private val accounts = listOf(
        "muhammedgngr25",
        "tr-bogdan",
        "zardozman",
        "filmakinesi5"
    )

    private val minSeconds  = 300
    private val searchPages = 6    // hesap listesi en fazla kaç sayfa taransın (1 sayfa = 20 video, 6 sayfa = 120 video)
    private val pageSize    = 20

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

    // ---------------- Yardımcılar ----------------

    private data class ListItem(
        val path: String,
        val id: String,
        val title: String,
        val dur: Int,
        val poster: String?
    )

    // arama/liste sonuçlarını sakla: load() ve loadLinks() buradan başlık, poster ve meta kimliğini alır
    private val cache = ConcurrentHashMap<String, ListItem>()

    private fun decodeHtml(s: String): String = s
        .replace("&quot;", "\"")
        .replace(Regex("&#0?39;"), "'")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

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

    private fun pathOf(url: String): String {
        val r = url.substringAfter("my.mail.ru", "")
        return if (r.startsWith("/")) r else ""
    }

    private val rePath   = Regex("my\\.mail\\.ru(/[^\"'#\\s:?]*?/video/[^\"'#\\s:?]*?/\\d+\\.html)")
    private val reId     = Regex("/\\+/video/(?:url|meta)/(?:[a-z0-9]+/)?(\\d{10,})")
    private val reDur    = Regex("list-item__duration\">\\s*([0-9:]+)")
    private val reTitle  = Regex("list-item__title[^\"]*\">\\s*([^<]+)")
    private val reMore   = Regex("show-more[^>]*\\shref=\"([^\"]+)\"")
    private val reAnchor = Regex("href=\"(?:https?://(?:m\\.)?my\\.mail\\.ru)?(/[^\"#?]*/video/[^\"#?]*/\\d+\\.html)[^\"]*\"[^>]*>\\s*([^<]{3,})<")
    private val reImgAny = Regex("<img[^>]+?(?:data-src|data-original|src)=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val reBg     = Regex("url\\(\\s*['\"]?([^'\")\\s]+)", RegexOption.IGNORE_CASE)

    private fun pickPoster(c: String): String? {
        val cands = reImgAny.findAll(c).map { it.groupValues[1] }.toList() +
                reBg.findAll(c).map { it.groupValues[1] }.toList()
        val u = cands.map { decodeHtml(it) }.firstOrNull { x ->
            !x.startsWith("data:") && !x.contains("sprite", true) && !x.contains("counter", true) &&
                    !x.endsWith(".svg", true) && !x.endsWith(".gif", true)
        } ?: return null
        return absolutize(u)
    }

    private fun parseItems(html: String): List<ListItem> {
        val out    = mutableListOf<ListItem>()
        val chunks = html.split("<li class=\"list-item")
        for (i in 1 until chunks.size) {
            val c     = chunks[i]
            val path  = rePath.find(c)?.groupValues?.get(1) ?: continue
            val id    = reId.find(c)?.groupValues?.get(1).orEmpty()
            val dur   = parseDuration(reDur.find(c)?.groupValues?.get(1))
            val raw   = reTitle.find(c)?.groupValues?.get(1) ?: ""
            val title = decodeHtml(decodeHtml(raw)).trim()
            out += ListItem(path, id, title, dur, pickPoster(c))
        }
        if (out.isNotEmpty()) return out
        // yedek: masaüstü yerleşim
        for (m in reAnchor.findAll(html)) {
            out += ListItem(m.groupValues[1], "", decodeHtml(m.groupValues[2]).trim(), 0, null)
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

    private fun accountPageUrl(acc: String, offset: Int): String =
        "$mobileUrl/mail/$acc/video/_myvideo?&offset=$offset&sameFromAjax=1"

    private suspend fun fetchPage(url: String): List<ListItem> {
        val html = try {
            app.get(url, headers = pageHeaders()).text
        } catch (e: Exception) {
            return emptyList()
        }
        return parseItems(html)
    }

    // hesabın videoları: 1. sayfa + offset=20, 40, ... (liste bitince ya da tekrar edince durur)
    private suspend fun fetchAccount(acc: String, pages: Int): List<ListItem> {
        var first = fetchPage("$mobileUrl/mail/$acc/video/")
        if (first.isEmpty()) first = fetchPage("$mainUrl/mail/$acc/video/_myvideo")
        val all = first.toMutableList()
        var p = 1
        while (p < pages && first.isNotEmpty()) {
            val more = fetchPage(accountPageUrl(acc, p * pageSize))
            if (more.isEmpty()) break
            if (more.all { m -> all.any { it.path == m.path } }) break
            all += more
            p++
        }
        return all
    }

    // "tt.57556.Örümcek Adam.tr" -> "Örümcek Adam tr"
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
        cache[path] = this
        val p = poster
        return newMovieSearchResponse(cleanTitle(title), "$mainUrl$path", TvType.Movie) {
            this.posterUrl = p
        }
    }

    private fun keep(x: ListItem) = x.dur == 0 || x.dur >= minSeconds

    // ---------------- Ana sayfa (hesabın tüm videoları, sayfa sayfa) ----------------

    private val seenHome = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val acc = request.name

        var items = if (page <= 1) fetchPage(request.data)
        else fetchPage(accountPageUrl(acc, (page - 1) * pageSize))
        if (items.isEmpty() && page <= 1) items = fetchPage("$mainUrl/mail/$acc/video/_myvideo")

        // aynı sayfa tekrar gelirse listeyi bitir
        val seen: MutableSet<String> =
            if (page <= 1) ConcurrentHashMap.newKeySet<String>().also { seenHome[acc] = it }
            else seenHome.getOrPut(acc) { ConcurrentHashMap.newKeySet<String>() }
        val fresh = items.filter { seen.add(it.path) }

        val list = fresh.map { it.toSearch() }
        return newHomePageResponse(request.name, list, hasNext = fresh.isNotEmpty() && page < 50)
    }

    // ---------------- Arama ----------------

    private var accountCache: List<ListItem>? = null

    private suspend fun accountItems(): List<ListItem> {
        accountCache?.let { return it }
        val items = accounts
            .amap { fetchAccount(it, searchPages) }
            .flatten()
            .distinctBy { it.path }
        accountCache = items
        return items
    }

    private suspend fun siteSearch(enc: String): List<ListItem> {
        var found = fetchList("$mobileUrl/video/search?st=search&q=$enc", 2)
        if (found.isEmpty()) found = fetchList("$mainUrl/video/search?st=search&q=$enc", 1)
        return found
    }

    private suspend fun accountMatches(q: String): List<ListItem> =
        if (q.length >= 3) accountItems().filter { norm(it.title).contains(q) } else emptyList()

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        val q   = norm(query)

        // site araması ve hesap listesi taraması AYNI ANDA çalışır
        val results = listOf(true, false).amap { isSite ->
            if (isSite) siteSearch(enc) else accountMatches(q)
        }
        val found        = results[0]
        val fromAccounts = results[1]

        return (found + fromAccounts)
            .distinctBy { it.path }
            .filter(::keep)
            .sortedByDescending { isTurkish(it.title) }
            .map { it.toSearch() }
    }

    // ---------------- Video sayfası ----------------

    override suspend fun load(url: String): LoadResponse? {
        val cached = cache[pathOf(url)]
        var title  = cached?.title?.takeIf { it.isNotBlank() }?.let { cleanTitle(it) }
        var poster = cached?.poster
        var plot: String? = null

        if (title == null || poster == null) {
            val html = try {
                app.get(url, headers = pageHeaders()).text
            } catch (e: Exception) {
                ""
            }
            val doc = Jsoup.parse(html)
            fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.takeIf { it.isNotBlank() }

            if (title == null) {
                val raw = meta("og:title") ?: doc.selectFirst("h1")?.text() ?: doc.title()
                title = cleanTitle(
                    decodeHtml(raw)
                        .replace(Regex("[\\u0400-\\u04FF]+"), " ")
                        .replace(Regex("\\s*[|\\-–]\\s*(my\\.)?mail\\.?ru.*$", RegexOption.IGNORE_CASE), "")
                )
            }
            if (poster == null) poster = meta("og:image")?.let { absolutize(it) }
            plot = meta("og:description")
        }

        return newMovieLoadResponse(title ?: "Video", url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
        }
    }

    // ---------------- Oynatma ----------------

    data class MailVideo(val url: String? = null, val key: String? = null)
    data class MailMeta(val videos: List<MailVideo>? = null)

    private data class MetaResult(val meta: MailMeta, val cookie: String?)

    private suspend fun fromMeta(url: String, pageUrl: String): MetaResult? {
        val res = try {
            app.get(
                url,
                headers = pageHeaders(
                    mapOf(
                        "Accept"           to "application/json, text/plain, */*",
                        "Referer"          to pageUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )
            )
        } catch (e: Exception) {
            return null
        }
        val meta = res.parsedSafe<MailMeta>() ?: return null
        if (meta.videos.isNullOrEmpty()) return null

        val cookie = res.cookies["video_key"]
            ?: res.headers.values("set-cookie")
                .firstNotNullOfOrNull { Regex("video_key=([^;,\\s]+)").find(it)?.groupValues?.get(1) }
        return MetaResult(meta, cookie)
    }

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
        val path    = pathOf(pageUrl)
        val id      = cache[path]?.id.orEmpty()

        val videoHeaders = mapOf(
            "User-Agent"      to androidUa,
            "Referer"         to "$mainUrl/",
            "Accept"          to "*/*",
            "Accept-Language" to "ru-RU,ru;q=0.9,tr;q=0.8"
        )

        // 1) listeden gelen kimlikle meta (önce masaüstü, sonra mobil alan adı)
        var mr: MetaResult? = null
        if (id.isNotBlank()) {
            mr = fromMeta("$mainUrl/+/video/meta/$id", pageUrl)
                ?: fromMeta("$mobileUrl/+/video/meta/$id", pageUrl)
        }

        // 2) yedek: video sayfasından meta adresi / doğrudan <video src>
        if (mr == null) {
            var html = try {
                app.get(pageUrl, headers = pageHeaders()).text
            } catch (e: Exception) {
                ""
            }
            if (html.length < 500 && path.isNotBlank()) {
                html = try {
                    app.get("$mobileUrl$path", headers = pageHeaders()).text
                } catch (e: Exception) {
                    html
                }
            }
            val metaUrl = findMetaUrl(html)
            if (metaUrl != null) mr = fromMeta(metaUrl, pageUrl)

            if (mr == null) {
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
        }

        val meta   = mr.meta
        val cookie = mr.cookie

        var any = false
        (meta.videos ?: emptyList())
            .mapNotNull { v ->
                val raw = v.url?.replace("\\/", "/") ?: return@mapNotNull null
                val u   = if (raw.startsWith("//")) "https:$raw" else raw
                if (!u.startsWith("http")) null else Triple(u, v.key.orEmpty(), heightOf(v.key))
            }
            .sortedByDescending { it.third }
            .forEach { (u, _, h) ->
                val hdrs = videoHeaders.toMutableMap()
                if (!cookie.isNullOrBlank() && !u.contains("video_key=")) hdrs["Cookie"] = "video_key=$cookie"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = if (cookie.isNullOrBlank()) "$name (çerez yok)" else name,
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
