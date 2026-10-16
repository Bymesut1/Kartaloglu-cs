package com.Bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dizidad : MainAPI() {
    override var mainUrl        = "https://www.dizidad.com"
    override var name           = "Dizidad"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Ana sayfa satırları. Yavaşlatırsa istemediğin satırı sil.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/#populer" to "Popüler Diziler",

        "$mainUrl/netflix-dizileri-izle/" to "Netflix Dizileri",
        "$mainUrl/turkce-dublaj-diziler-hd/" to "Dublajlı Diziler",
        "$mainUrl/yerli-dizi-izle/" to "Yerli Diziler",
        "$mainUrl/anime-izle/" to "Animeler",
        "$mainUrl/yabanci-dizi-izle/" to "Yabancı Diziler",
        "$mainUrl/kore-dizileri-izle-hd/" to "Kore Dizileri",
        "$mainUrl/full-hd-hint-dizileri-izle/" to "Hint Dizileri",
        "$mainUrl/pakistan-dizileri-izle/" to "Pakistan Dizileri",
        "$mainUrl/tv-programlari-izle/" to "Tv Programları",

        "$mainUrl/film-tur/netflix-filmleri/" to "Netflix Filmleri",
        "$mainUrl/film-tur/2026-filmleri-izle/" to "2026 Filmleri",
        "$mainUrl/film-tur/2025-filmleri/" to "2025 Filmleri",
        "$mainUrl/yabanci-filmler-izle/" to "Yabancı Filmler",
        "$mainUrl/yerli-filmler/" to "Yerli Filmler",
        "$mainUrl/turkce-dublaj-filmler/" to "Türkçe Dublajlı Filmler",
        "$mainUrl/turkce-altyazili-filmler/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/tum-filmler/" to "Tüm Filmler",

        "$mainUrl/turkce-dublaj-bolumler-hd/" to "Dublajlı Bölümler",
        "$mainUrl/yerli-dizi-bolumleri/" to "Yerli Dizi Bölümleri",
        "$mainUrl/anime-dizilerin-bolumleri/" to "Anime Bölümler",
        "$mainUrl/yabanci-dizilerin-son-bolumleri/" to "Yabancı Bölümler",
        "$mainUrl/kore-dizilerinden-son-bolumler/" to "Kore Dizi Bölümleri",
        "$mainUrl/hint-dizilerinden-son-bolumler/" to "Hint Dizi Bölümleri",
        "$mainUrl/pakistan-dizilerinden-son-bolumler/" to "Pakistan Dizi Bölümleri",
        "$mainUrl/tv-program-bolumleri/" to "Tv Program Bölümleri",
        "$mainUrl/tum-bolumler/" to "Tüm Bölümler"
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun pageHeaders(ref: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent"      to ua,
        "Referer"         to ref,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // ---------------- Yardımcılar ----------------

    private val reItem = Regex(
        """^https?://(?:www\.)?dizidad\.com/(?:(?:diziler|filmler|film)/[^/?#]+|[a-z0-9-]+-izle[a-z0-9-]*)/?$""",
        RegexOption.IGNORE_CASE
    )
    private val reEpisode = Regex("""-(\d+)-sezon-(\d+)-bolum""", RegexOption.IGNORE_CASE)
    private val reMedia = Regex(
        """https?://[^\s"'<>\\)]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\)]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val reSub = Regex(
        """https?://[^\s"'<>\\)]+?\.(?:vtt|srt)(?:\?[^\s"'<>\\)]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val reYoutube = Regex(
        """(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
        RegexOption.IGNORE_CASE
    )

    // menü / kategori sayfaları: kart olarak listelenmesin
    private val skipSlugs = setOf(
        "netflix-dizileri-izle", "turkce-dublaj-diziler-hd", "yerli-dizi-izle", "anime-izle",
        "yabanci-dizi-izle", "kore-dizileri-izle-hd", "full-hd-hint-dizileri-izle",
        "pakistan-dizileri-izle", "tv-programlari-izle", "yabanci-filmler-izle",
        "turkce-dublaj-bolumler-hd", "yerli-dizi-bolumleri", "anime-dizilerin-bolumleri",
        "yabanci-dizilerin-son-bolumleri", "kore-dizilerinden-son-bolumler",
        "hint-dizilerinden-son-bolumler", "pakistan-dizilerinden-son-bolumler",
        "tv-program-bolumleri", "tum-bolumler", "tum-filmler", "tum-diziler", "tum-diziler-hd1",
        "yerli-filmler", "turkce-dublaj-filmler", "turkce-altyazili-filmler",
        "1-dizi-arsivi", "dizi-takvimi", "yardim", "iletisim", "uye-ol", "mobil-uygulama"
    )

    private fun unesc(s: String?): String = (s ?: "")
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun originOf(u: String): String =
        Regex("^https?://[^/]+").find(u)?.value.orEmpty()

    private fun cleanTitle(t: String): String = t
        .replace(Regex("\\s*[|\\-–]\\s*dizidad.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+izle\\b.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Video" }

    private fun qualityOf(u: String): Int {
        val m = Regex("(2160|1440|1080|720|480|360)").find(u)?.groupValues?.get(1)?.toIntOrNull()
        return m ?: Qualities.Unknown.value
    }

    private fun isJunk(u: String): Boolean =
        Regex("cloudflareinsights|google|facebook|\\.(js|css|png|jpg|gif|svg|ico|woff2?)(\\?|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(u)

    private fun findMedia(txt: String): List<String> =
        reMedia.findAll(unesc(txt)).map { it.value }.filter { !isJunk(it) }.distinct().toList()

    private fun findSubs(txt: String): List<String> =
        reSub.findAll(unesc(txt)).map { it.value }.distinct().toList()

    private fun youtubeIds(txt: String): List<String> =
        reYoutube.findAll(unesc(txt)).map { it.groupValues[1] }.distinct().toList()

    // ---------------- Liste / kart ayrıştırma ----------------

    private fun posterOf(img: Element?): String? {
        if (img == null) return null
        for (k in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val v: String = img.attr(k)
            if (v.isNotBlank() && !v.startsWith("data:")) return fixUrl(v)
        }
        return null
    }

    private fun parseCards(doc: Document, home: Boolean = false, popular: Boolean = false): List<SearchResponse> {
        val anchors: List<Element> = if (popular) {
            doc.select(".series_list a[href]")
        } else {
            if (home) doc.select(".series_list").remove()
            val inContent = doc.select("#content a[href]")
            if (inContent.isNotEmpty()) inContent else doc.select("a[href]")
        }

        val map = LinkedHashMap<String, Pair<String, String?>>()
        for (a in anchors) {
            val href: String = fixUrl(a.attr("href")).substringBefore("#")
            if (!reItem.matches(href) || reEpisode.containsMatchIn(href)) continue
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug in skipSlugs) continue

            val img: Element? = a.selectFirst("img")
            val alt: String = img?.attr("alt").orEmpty()
            val title: String = a.attr("title").ifBlank { alt }.ifBlank { a.text() }.trim()
            val poster: String? = posterOf(img)

            val prev = map[href]
            map[href] = title.ifBlank { prev?.first.orEmpty() } to (poster ?: prev?.second)
        }

        return map.filter { it.value.first.isNotBlank() }.map { (href, v) ->
            val raw = v.first
            var title = cleanTitle(raw)
            val dub = raw.contains("dublaj", true) || href.contains("dublaj", true)
            if (dub && !title.contains("dublaj", true)) title += " [Dublaj]"
            val poster = v.second
            if (href.contains("/diziler/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }
    }

    // ---------------- Ana sayfa ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val raw = request.data
        val popular = raw.endsWith("#populer")
        val base = raw.substringBefore("#")
        val isHome = base.trimEnd('/') == mainUrl

        if (popular && page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        }

        val url = if (page <= 1) base else base.trimEnd('/') + "/page/$page/"
        val list = try {
            parseCards(app.get(url, headers = pageHeaders()).document, home = isHome && !popular, popular = popular)
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty() && !popular)
    }

    // ---------------- Arama ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        return try {
            parseCards(app.get("$mainUrl/?s=$enc", headers = pageHeaders()).document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------- Dizi / film / bölüm sayfası ----------------

    override suspend fun load(url: String): LoadResponse? {
        val doc: Document = app.get(url, headers = pageHeaders()).document

        fun meta(p: String): String? {
            val el: Element? = doc.selectFirst("meta[property=$p]")
            val c: String = el?.attr("content").orEmpty()
            return c.ifBlank { null }
        }

        val h1: Element? = doc.selectFirst("h1")
        val h1Text: String? = h1?.text()

        val title  = cleanTitle(meta("og:title") ?: h1Text ?: doc.title())
        val poster = meta("og:image")?.let { fixUrl(it) }
        val plot   = meta("og:description")

        // fragman: sayfadaki YouTube bağlantısı (bölüm sayfalarında değil, çünkü orada asıl video olabilir)
        val trailer: String? = if (!url.contains("-bolum")) {
            youtubeIds(doc.html()).firstOrNull()?.let { "https://www.youtube.com/watch?v=$it" }
        } else null

        val isSeriesPage = url.contains("/diziler/")

        if (isSeriesPage) {
            val all = doc.select("a[href]").mapNotNull { a ->
                val href: String = fixUrl(a.attr("href")).substringBefore("#")
                if (!href.contains("dizidad.com", true)) return@mapNotNull null
                val m = reEpisode.find(href) ?: return@mapNotNull null
                Triple(href, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }.distinctBy { it.first }

            // başka dizilerin (yan menü vb.) bölümleri karışmasın
            val slug = url.substringAfter("/diziler/", "").trim('/')
            val own  = if (slug.isNotBlank()) all.filter { it.first.contains(slug, true) } else emptyList()
            val eps  = (if (own.isNotEmpty()) own else all)
                .sortedWith(compareBy({ it.second }, { it.third }))

            if (eps.isNotEmpty()) {
                val episodes = eps.map { (href, s, e) ->
                    newEpisode(href) {
                        this.name    = "$s. Sezon $e. Bölüm"
                        this.season  = s
                        this.episode = e
                    }
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot      = plot
                    addTrailer(trailer)
                }
            }
        }

        // film ya da tek bölüm sayfası
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
            addTrailer(trailer)
        }
    }

    // ---------------- Oynatma ----------------

    private fun extractEmbeds(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex("""<iframe[^>]+?(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach {
                var u = it.groupValues[1].replace("&amp;", "&")
                if (u.startsWith("//")) u = "https:$u"
                if (!u.startsWith("http")) return@forEach
                val isYt = reYoutube.containsMatchIn(u)
                if (!isYt && Regex("reklam|ads|facebook|google", RegexOption.IGNORE_CASE).containsMatchIn(u)) return@forEach
                out.add(u)
            }
        Regex("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?.let { out.add(unesc(it)) }
        return out.toList()
    }

    private suspend fun playYoutube(
        id: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = try {
        loadExtractor("https://www.youtube.com/watch?v=$id", referer, subtitleCallback, callback)
    } catch (e: Exception) {
        false
    }

    private suspend fun emit(
        url: String,
        referer: String,
        label: String,
        asM3u8: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(referer)
        val hdrs = mutableMapOf("User-Agent" to ua)
        if (origin.isNotBlank()) hdrs["Origin"] = origin
        callback.invoke(
            newExtractorLink(
                source = name,
                name   = "$name $label",
                url    = url,
                type   = if (asM3u8) ExtractorLinkType.M3U8 else null
            ) {
                this.referer = referer
                this.quality = qualityOf(url)
                this.headers = hdrs
            }
        )
    }

    // hdplayersystem / hdmomplayer gömülü oynatıcı -> akış
    private suspend fun resolveHdPlayer(
        embedUrl: String,
        pageUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(embedUrl)
        val id = embedUrl.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast("/")

        val html = app.get(
            embedUrl,
            headers = mapOf("User-Agent" to ua, "Referer" to pageUrl, "Origin" to mainUrl)
        ).text

        findSubs(html).forEachIndexed { i, s ->
            subtitleCallback.invoke(SubtitleFile("Altyazı ${i + 1}", s))
        }

        // sayfada doğrudan medya varsa
        val direct = findMedia(html)
        if (direct.isNotEmpty()) {
            direct.forEach { emit(it, embedUrl, label, false, callback) }
            return true
        }

        val base = Regex("""player_base_url\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1) ?: origin

        suspend fun attempt(r: String): Boolean {
            val text = try {
                app.post(
                    "$base/player/index.php?data=${URLEncoder.encode(id, "UTF-8")}&do=getVideo",
                    headers = mapOf(
                        "User-Agent"       to ua,
                        "Referer"          to embedUrl,
                        "Origin"           to base,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept"           to "application/json, text/javascript, */*; q=0.01"
                    ),
                    data = mapOf("hash" to id, "r" to r)
                ).text
            } catch (e: Exception) {
                return false
            }

            val sec = Regex("\"(?:securedLink|secured_link)\"\\s*:\\s*\"([^\"]+)\"")
                .find(text)?.groupValues?.get(1)
            val vs = Regex("\"(?:videoSource|video_source)\"\\s*:\\s*\"([^\"]+)\"")
                .find(text)?.groupValues?.get(1)

            // önce securedLink, yoksa videoSource
            val link = listOf(sec, vs).map { unesc(it) }
                .firstOrNull { it.startsWith("http", true) } ?: return false

            // playlist hangi Referer ile açılıyor
            var okRef: String? = null
            if (!link.contains(".mp4", true)) {
                for (ref in listOf(embedUrl, "$base/", pageUrl)) {
                    val t = try {
                        app.get(
                            link,
                            headers = mapOf(
                                "User-Agent" to ua,
                                "Referer"    to ref,
                                "Origin"     to originOf(ref)
                            )
                        ).text
                    } catch (e: Exception) {
                        ""
                    }
                    if (t.trim().startsWith("#EXTM3U")) { okRef = ref; break }
                }
            }

            emit(
                link,
                okRef ?: embedUrl,
                label,
                okRef != null || link.contains(".m3u8", true),
                callback
            )
            return true
        }

        if (attempt(pageUrl) || attempt("")) return true

        // oynatıcı sayfasında YouTube varsa onu dene
        for (yt in youtubeIds(html)) {
            if (playYoutube(yt, pageUrl, subtitleCallback, callback)) return true
        }
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = pageHeaders()).text
        val label = if (data.contains("dublaj", true)) "Türkçe Dublaj" else "Altyazılı"

        var any = false
        for (e in extractEmbeds(html)) {
            try {
                val yt = youtubeIds(e)
                if (yt.isNotEmpty()) {
                    // YouTube (yerli diziler / fragmanlar)
                    for (id in yt) {
                        if (playYoutube(id, data, subtitleCallback, callback)) any = true
                    }
                } else if (Regex("hdplayersystem|hdmomplayer", RegexOption.IGNORE_CASE).containsMatchIn(e)) {
                    if (resolveHdPlayer(e, data, label, subtitleCallback, callback)) any = true
                } else {
                    // CloudStream'in hazır çözücüleri (ok.ru, dailymotion vb.)
                    if (loadExtractor(e, data, subtitleCallback, callback)) any = true

                    val t = app.get(e, headers = pageHeaders(data)).text
                    val media = findMedia(t)
                    if (media.isNotEmpty()) {
                        findSubs(t).forEachIndexed { i, s ->
                            subtitleCallback.invoke(SubtitleFile("Altyazı ${i + 1}", s))
                        }
                        media.forEach { emit(it, e, label, false, callback) }
                        any = true
                    } else {
                        for (id in youtubeIds(t)) {
                            if (playYoutube(id, data, subtitleCallback, callback)) any = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // hiçbir şey çıkmadıysa sayfanın kendisinde YouTube ara
        if (!any) {
            for (id in youtubeIds(html)) {
                if (playYoutube(id, data, subtitleCallback, callback)) any = true
            }
        }
        return any
    }
}
