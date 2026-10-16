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

    // Kaynak listesinde "DEBUG" satırları çıkması için true. Her şey çalışınca false yap.
    private val DEBUG = false

    // Bölüm kategorileri kaldırıldı: bölümler artık dizinin içinde listeleniyor.
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
        "$mainUrl/tum-filmler/" to "Tüm Filmler"
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun pageHeaders(ref: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent"      to ua,
        "Referer"         to ref,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private data class EpRef(val href: String, val season: Int, val episode: Int, val hasSeason: Boolean)

    // ---------------- Yardımcılar ----------------

    private val reItem = Regex(
        """^https?://(?:www\.)?dizidad\.com/(?:(?:diziler|filmler|film)/[^/?#]+|[a-z0-9-]+-izle[a-z0-9-]*)/?$""",
        RegexOption.IGNORE_CASE
    )

    // "...-1-sezon-5-bolum..." ya da "...-30-bolum..."
    private val reEpAny = Regex("""-(?:(\d+)-sezon-)?(\d+)-bolum""", RegexOption.IGNORE_CASE)

    private val reMedia = Regex(
        """https?://[^\s"'<>\\)]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\)]*)?""",
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
    private val reTwimg = Regex("""https?://video\.twimg\.com/[^\s"'<>\\)]+""", RegexOption.IGNORE_CASE)

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
        Regex(
            "cloudflareinsights|google|facebook|\\.(js|css|png|jpg|gif|svg|ico|woff2?)(\\?|$)|[/._-](ads?|vast|banner|adserver)[/._?=-]",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(u)

    private fun findMedia(txt: String): List<String> =
        reMedia.findAll(unesc(txt)).map { it.value }.filter { !isJunk(it) }.distinct().toList()

    private fun findSubs(txt: String): List<String> =
        reSub.findAll(unesc(txt)).map { it.value }.distinct().toList()

    private fun youtubeIds(txt: String): List<String> =
        reYoutube.findAll(unesc(txt)).map { it.groupValues[1] }.distinct().toList()

    private fun typeOf(u: String): ExtractorLinkType? = when {
        u.contains(".m3u8", true) -> ExtractorLinkType.M3U8
        u.contains(".mpd", true)  -> ExtractorLinkType.DASH
        u.contains(".mp4", true) || u.contains(".m4s", true) -> ExtractorLinkType.VIDEO
        else -> null
    }

    private fun cookieHeader(c: Map<String, String>): String =
        c.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun hdrFor(ref: String, withOrigin: Boolean): Map<String, String> {
        val m = linkedMapOf(
            "User-Agent"      to ua,
            "Referer"         to ref,
            "Accept"          to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )
        if (withOrigin) {
            val o = originOf(ref)
            if (o.isNotBlank()) m["Origin"] = o
        }
        return m
    }

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

        // href -> (başlık, poster, bölümden türetildi mi)
        val map = LinkedHashMap<String, Triple<String, String?, Boolean>>()
        for (a in anchors) {
            var href: String = fixUrl(a.attr("href")).substringBefore("#")
            if (!reItem.matches(href)) continue
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug in skipSlugs) continue

            val img: Element? = a.selectFirst("img")
            val alt: String = img?.attr("alt").orEmpty()
            var title: String = a.attr("title").ifBlank { alt }.ifBlank { a.text() }.trim()
            val poster: String? = posterOf(img)

            // Bölüm kartı ise diziye çevir: "haysiyet-1-bolum-izle" -> /diziler/haysiyet-izle/
            var derived = false
            val em = reEpAny.find(slug)
            if (em != null && !href.contains("/diziler/")) {
                href = "$mainUrl/diziler/${slug.substring(0, em.range.first)}-izle/"
                title = title.replace(
                    Regex("""\s*(\d+\.\s*sezon\s*)?\d+\.?\s*bölüm.*$""", RegexOption.IGNORE_CASE), ""
                ).trim()
                derived = true
            }

            val prev = map[href]
            map[href] = when {
                prev == null -> Triple(title, poster, derived)
                derived      -> Triple(prev.first.ifBlank { title }, prev.second ?: poster, prev.third)
                else         -> Triple(title.ifBlank { prev.first }, poster ?: prev.second, false)
            }
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

    // ---------------- Dizi / film sayfası ----------------

    override suspend fun load(url: String): LoadResponse? {
        val doc: Document = app.get(url, headers = pageHeaders()).document

        // Bölüm sayfası açıldıysa dizinin sayfasına yönlendir
        val ownSlug = url.trimEnd('/').substringAfterLast('/')
        if (!url.contains("/diziler/") && reEpAny.containsMatchIn(ownSlug)) {
            val sHref: String? = doc.selectFirst("a[rel=category tag]")?.attr("href")?.let { fixUrl(it) }
            if (sHref != null && sHref.contains("/diziler/") && sHref != url) return load(sHref)
        }

        fun meta(p: String): String? {
            val el: Element? = doc.selectFirst("meta[property=$p]")
            val c: String = el?.attr("content").orEmpty()
            return c.ifBlank { null }
        }

        val h1Text: String? = doc.selectFirst("h1")?.text()
        val title  = cleanTitle(meta("og:title") ?: h1Text ?: doc.title())
        val poster = meta("og:image")?.let { fixUrl(it) }
        val plot   = meta("og:description")

        val trailer: String? = if (!url.contains("-bolum")) {
            youtubeIds(doc.html()).firstOrNull()?.let { "https://www.youtube.com/watch?v=$it" }
        } else null

        if (url.contains("/diziler/")) {
            val base = url.trimEnd('/').substringAfterLast('/').substringBefore("-izle")
                .removeSuffix("-turkce-dublaj").removeSuffix("-dublaj").removeSuffix("-turkce-altyazili")

            fun collect(d: Document, strict: Boolean = true): List<EpRef> = d.select("a[href]").mapNotNull { a ->
                val href: String = fixUrl(a.attr("href")).substringBefore("#")
                if (!href.contains("dizidad.com", true) || href.contains("/diziler/")) return@mapNotNull null
                val slug = href.trimEnd('/').substringAfterLast('/')
                if (strict && base.isNotBlank() && !slug.startsWith("$base-", true)) return@mapNotNull null
                val m = reEpAny.find(slug) ?: return@mapNotNull null
                val ep = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val sg = m.groupValues[1]
                EpRef(href, sg.toIntOrNull() ?: 1, ep, sg.isNotEmpty())
            }.distinctBy { it.href }

            fun collectAny(d: Document): List<EpRef> {
                val strict = collect(d)
                return if (strict.isNotEmpty()) strict else collect(d, false)
            }

            var all: List<EpRef> = collectAny(doc)

            // Bölüm sayfasındaki "Dizinin Diğer Bölümleri" listesi tüm bölümleri verir (özellikle Kore/Hint dizileri).
            // Seri sayfasında bölüm yoksa ilk bölüm adresini tahmin et.
            val seeds = ArrayList<String>()
            all.lastOrNull()?.let { seeds.add(it.href) }
            if (base.isNotBlank()) {
                seeds.add("$mainUrl/$base-1-sezon-1-bolum-izle/")
                seeds.add("$mainUrl/$base-1-bolum-izle/")
                seeds.add("$mainUrl/$base-1-sezon-1-bolum-turkce-dublaj-izle/")
                seeds.add("$mainUrl/$base-1-bolum-turkce-dublaj-izle/")
            }
            for (seed in seeds.distinct()) {
                try {
                    val extra = collectAny(app.get(seed, headers = pageHeaders(url)).document)
                    if (extra.isNotEmpty()) {
                        all = (all + extra).distinctBy { it.href }
                        break
                    }
                } catch (_: Exception) {
                }
            }

            val list = all

            if (list.isNotEmpty()) {
                val hasSeason = list.any { it.hasSeason }
                // aynı sezon/bölümün dublaj + altyazılı sayfaları tek bölümde birleşir
                val grouped = list.groupBy { Pair(it.season, it.episode) }
                    .toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))

                val episodes = grouped.map { (key, refs) ->
                    newEpisode(refs.joinToString("|") { it.href }) {
                        this.name    = null
                        this.season  = if (hasSeason) key.first else null
                        this.episode = key.second
                    }
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot      = plot
                    addTrailer(trailer)
                }
            }
        }

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

    // YouTube: sadece H264 1080p (yoksa en iyi H264, o da yoksa en iyi kalite). Diğerleri atılır.
    private fun pickYoutube(list: List<ExtractorLink>): ExtractorLink? {
        if (list.isEmpty()) return null
        fun is264(l: ExtractorLink) = l.name.contains("H264", true) || l.name.contains("avc", true)
        fun is1080(l: ExtractorLink) = l.quality == 1080 || l.name.contains("1080")
        val h264 = list.filter { is264(it) }
        return h264.firstOrNull { is1080(it) }
            ?: h264.maxByOrNull { it.quality }
            ?: list.firstOrNull { is1080(it) }
            ?: list.maxByOrNull { it.quality }
    }

    private suspend fun emit(
        url: String,
        referer: String,
        label: String,
        type: ExtractorLinkType?,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name   = "$name $label",
                url    = url,
                type   = type
            ) {
                this.referer = referer
                this.quality = qualityOf(url)
                this.headers = headers
            }
        )
    }

    private fun absUrl(base: String, ref: String): String = when {
        ref.startsWith("http", true) -> ref
        ref.startsWith("//")         -> "https:$ref"
        ref.startsWith("/")          -> originOf(base) + ref
        else -> base.substringBefore("?").substringBeforeLast("/") + "/" + ref
    }

    // Playlist + ilk parça (segment) gerçekten açılıyor mu? Açılan başlık setini döndürür.
    private suspend fun probeHls(
        url: String,
        variants: List<Map<String, String>>,
        dbg: MutableList<String>
    ): Map<String, String>? {
        for ((i, h) in variants.withIndex()) {
            try {
                val t = app.get(url, headers = h).text
                if (!t.trim().startsWith("#EXTM3U")) {
                    dbg.add("HLS#$i liste -> ${t.trim().take(30)}")
                    continue
                }
                var plUrl = url
                var plText = t
                if (t.contains("#EXT-X-STREAM-INF")) {
                    val v = t.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                    plUrl = absUrl(url, v)
                    plText = app.get(plUrl, headers = h).text
                    if (!plText.trim().startsWith("#EXTM3U")) {
                        dbg.add("HLS#$i alt liste -> ${plText.trim().take(30)}")
                        continue
                    }
                }
                val mapUri = Regex("""#EXT-X-MAP:[^\n]*URI="([^"]+)"""").find(plText)?.groupValues?.get(1)
                val seg = plText.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                val first = seg ?: mapUri ?: return h
                val code = app.get(absUrl(plUrl, first), headers = h + ("Range" to "bytes=0-1")).code
                dbg.add("HLS#$i parça $code ${absUrl(plUrl, first).removePrefix("https://").take(50)}")
                if (code in 200..299) return h
            } catch (e: Exception) {
                dbg.add("HLS#$i ERR ${e.message?.take(50)}")
            }
        }
        return null
    }

    private fun variantsFor(l: ExtractorLink, page: String): List<Map<String, String>> {
        val own = LinkedHashMap<String, String>()
        own.putAll(l.headers)
        if (l.referer.isNotBlank()) own["Referer"] = l.referer
        if (!own.containsKey("User-Agent")) own["User-Agent"] = ua
        return listOf(
            own as Map<String, String>,
            mapOf("User-Agent" to ua),
            hdrFor(page, false),
            hdrFor(page, true),
            hdrFor(originOf(l.url) + "/", false)
        ).distinct()
    }

    // true = akış doğrulandı (ya da HLS değil); orijinal çalışıyorsa aynen verilir, değilse çalışan başlıklarla
    private suspend fun emitValidated(
        l: ExtractorLink,
        page: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
        dbg: MutableList<String>
    ): Boolean {
        if (!seen.add(l.url)) return true
        val isHls = l.type == ExtractorLinkType.M3U8 || l.url.contains(".m3u8", true) || l.url.contains("/m3/")
        if (!isHls) {
            callback.invoke(l)
            return true
        }
        val vs = variantsFor(l, page)
        val ok = probeHls(l.url, vs, dbg)
        if (ok == null) {
            dbg.add("HLS doğrulanamadı: ${l.url.removePrefix("https://").take(60)}")
            callback.invoke(l)
            return false
        }
        if (ok == vs.first()) {
            callback.invoke(l)
            return true
        }
        callback.invoke(
            newExtractorLink(source = l.source, name = l.name, url = l.url, type = ExtractorLinkType.M3U8) {
                this.referer = ok["Referer"] ?: ""
                this.quality = l.quality
                this.headers = ok
            }
        )
        return true
    }

    // playlist hangi başlık kombinasyonuyla açılıyor? (security error çözümü: Referer / Origin / Cookie denemeleri)
    private suspend fun probePlaylist(
        link: String,
        refs: List<Pair<String, Boolean>>,
        cookies: Map<String, String>,
        dbg: MutableList<String>
    ): Map<String, String>? {
        for ((ref, withOrigin) in refs) {
            val h = hdrFor(ref, withOrigin)
            val t = try {
                app.get(link, headers = h, cookies = cookies).text
            } catch (e: Exception) {
                "ERR ${e.message}"
            }
            dbg.add("GET m3 ref=${ref.removePrefix("https://").take(30)} o=$withOrigin -> ${t.trim().take(40)}")
            if (t.trim().startsWith("#EXTM3U")) return h
        }
        return null
    }

    // Gömülü oynatıcı (hdplayersystem, peacemakerst vb.) -> akış.
    // Dönüş: 2 = sağlam akış, 1 = doğrulanamayan akış, 0 = yok
    private suspend fun resolvePlayer(
        embedUrl: String,
        pageUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        yt: MutableList<ExtractorLink>,
        dbg: MutableList<String>
    ): Int {
        val origin = originOf(embedUrl)
        val clean = embedUrl.substringBefore("?").substringBefore("#")
        val id = clean.trimEnd('/').substringAfterLast("/")

        val resp = app.get(
            embedUrl,
            headers = mapOf("User-Agent" to ua, "Referer" to pageUrl, "Origin" to mainUrl)
        )
        val html = resp.text
        val cookies: Map<String, String> = resp.cookies
        val ck = cookieHeader(cookies)
        fun withCookie(h: Map<String, String>): Map<String, String> =
            if (ck.isBlank()) h else h + ("Cookie" to ck)

        dbg.add("embed ${origin.removePrefix("https://")} code=${resp.code} len=${html.length} cookies=${cookies.size}")

        findSubs(html).forEachIndexed { i, s ->
            subtitleCallback.invoke(SubtitleFile("Altyazı ${i + 1}", s))
        }

        val tw = reTwimg.findAll(unesc(html)).map { it.value }.distinct().toList()
        if (tw.isNotEmpty()) dbg.add("twimg x${tw.size}: ${tw.first().removePrefix("https://").take(90)}")

        // 1) sayfada doğrudan medya varsa
        val hasApi = html.contains("getVideo") || html.contains("player_base_url")
        val direct = findMedia(html)
        val m3u8s = direct.filter { it.contains(".m3u8", true) }
        val pick = if (m3u8s.isNotEmpty()) m3u8s else direct
        if (pick.isNotEmpty() && (m3u8s.isNotEmpty() || !hasApi)) {
            for (u in pick) emit(u, embedUrl, label, typeOf(u), withCookie(hdrFor(embedUrl, true)), callback)
            return 2
        }

        // 2) getVideo POST
        val base = Regex("""player_base_url\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)?.trimEnd('/') ?: origin
        val vi = clean.indexOf("/video/")
        val dirPrefix = if (vi > 0) clean.substring(0, vi) else origin
        val endpoints = listOf("$base/player/index.php", "$origin/player/index.php", "$dirPrefix/player/index.php").distinct()

        var apiText = ""
        suspend fun callApi(postRef: String, r: String): List<String> {
            for (ep in endpoints) {
                val text = try {
                    app.post(
                        "$ep?data=${URLEncoder.encode(id, "UTF-8")}&do=getVideo",
                        headers = mapOf(
                            "User-Agent"       to ua,
                            "Referer"          to postRef,
                            "Origin"           to originOf(ep),
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                            "Accept"           to "application/json, text/javascript, */*; q=0.01"
                        ),
                        cookies = cookies,
                        data = mapOf("hash" to id, "r" to r)
                    ).text
                } catch (e: Exception) {
                    "ERR ${e.message}"
                }
                dbg.add("POST ${ep.removePrefix("https://").take(40)} ref=${postRef.removePrefix("https://").take(25)} r=${r.length} -> ${text.trim().take(70)}")
                apiText += " " + text

                val keyed = listOf("securedLink", "secured_link", "videoSource", "video_source", "hls", "file").mapNotNull { k ->
                    Regex("\"$k\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                }
                val m3tok = Regex("""https?://[^"'\s<>\\]+?/m3/[^"'\s<>\\]+""").findAll(unesc(text)).map { it.value }.toList()
                val found = (keyed.map { unesc(it) } + m3tok + findMedia(text))
                    .map { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) originOf(ep) + it else it }
                    .filter { it.startsWith("http", true) }
                    .distinct()
                if (found.isNotEmpty()) return found
            }
            return emptyList()
        }

        // token, POST'taki Referer / r değerine bağlı olabilir: hepsini sırayla dene, playlist açılanı al
        val combos = listOf(
            embedUrl to "$mainUrl/",
            pageUrl to pageUrl,
            pageUrl to "",
            embedUrl to pageUrl,
            "$mainUrl/" to "$mainUrl/",
            embedUrl to ""
        )
        var result = 0
        var weak: String? = null
        for ((postRef, r) in combos) {
            val links = callApi(postRef, r)
            if (links.isEmpty()) continue
            val refs = listOf(
                "$origin/" to true,
                postRef to false,
                postRef to true,
                embedUrl to false,
                embedUrl to true,
                "$base/" to true,
                pageUrl to true,
                "$mainUrl/" to true
            ).distinct()
            for (link in links) {
                if (link.contains(".mp4", true)) {
                    emit(link, embedUrl, label, ExtractorLinkType.VIDEO, withCookie(hdrFor(embedUrl, true)), callback)
                    result = 2
                    continue
                }
                val h = probePlaylist(link, refs, cookies, dbg)
                if (h != null) {
                    emit(link, h["Referer"] ?: embedUrl, label, ExtractorLinkType.M3U8, withCookie(h), callback)
                    result = 2
                } else if (weak == null) {
                    weak = link
                }
            }
            if (result == 2) break
        }
        if (result == 0 && weak != null) {
            emit(weak, embedUrl, label, ExtractorLinkType.M3U8, withCookie(hdrFor(embedUrl, true)), callback)
            dbg.add("playlist doğrulanamadı: ${weak.removePrefix("https://").take(60)}")
            result = 1
        }
        if (result == 2) return 2

        // 3) oynatıcı sayfasında / cevapta YouTube varsa
        for (y in (youtubeIds(html) + youtubeIds(apiText)).distinct()) {
            playYoutube(y, pageUrl, subtitleCallback) { l -> yt.add(l) }
        }
        if (yt.isNotEmpty()) return 2
        return result
    }

    // Bir bölüm sayfası -> tüm akışlar
    private suspend fun processPage(
        page: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dbg: MutableList<String>
    ): Boolean {
        val html = app.get(page, headers = pageHeaders()).text
        val label = if (page.contains("dublaj", true)) "Türkçe Dublaj" else "Altyazılı"
        val yt = ArrayList<ExtractorLink>()
        var strong = false

        val embeds = extractEmbeds(html)
        dbg.add("sayfa ${page.substringAfter(".com/").take(40)} embed=${embeds.size}")

        val out = ArrayList<ExtractorLink>()
        val collect: (ExtractorLink) -> Unit = { out.add(it) }
        val seen = HashSet<String>()

        for (e in embeds) {
            try {
                val ids = youtubeIds(e)
                if (ids.isNotEmpty()) {
                    for (id in ids) playYoutube(id, page, subtitleCallback) { l -> yt.add(l) }
                    continue
                }
                var good = false

                // 1) CloudStream'in hazır çözücüsü (PeaceMakerst vb.) - çalışıyorsa aynen kullanılır
                val n0 = out.size
                try {
                    loadExtractor(e, page, subtitleCallback, collect)
                } catch (_: Exception) {
                }
                for (l in out.drop(n0)) if (emitValidated(l, page, seen, callback, dbg)) good = true

                // 2) çalışmadıysa kendi çözücümüz (farklı Referer/Origin/Cookie + POST denemeleri)
                if (!good) {
                    val n1 = out.size
                    resolvePlayer(e, page, label, subtitleCallback, collect, yt, dbg)
                    for (l in out.drop(n1)) if (emitValidated(l, page, seen, callback, dbg)) good = true
                }
                if (good) strong = true
            } catch (ex: Exception) {
                dbg.add("HATA ${e.removePrefix("https://").take(35)} ${ex.message?.take(60)}")
            }
        }

        if (yt.isEmpty() && !strong) {
            for (id in youtubeIds(html)) playYoutube(id, page, subtitleCallback) { l -> yt.add(l) }
        }

        val best = pickYoutube(yt)
        if (best != null) {
            callback.invoke(best)
            strong = true
        }
        return strong
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pages = data.split("|").map { it.trim() }.filter { it.startsWith("http") }
        val dbg = ArrayList<String>()
        var any = false

        for (p in pages) {
            try {
                if (processPage(p, subtitleCallback, callback, dbg)) any = true
            } catch (e: Exception) {
                dbg.add("HATA sayfa ${e.message?.take(80)}")
            }
        }

        if (DEBUG && !any) {
            dbg.take(14).forEachIndexed { i, s ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = "DEBUG ${i + 1}: ${s.take(220)}",
                        url    = "https://debug.invalid/${i + 1}.mp4"
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return dbg.isNotEmpty()
        }
        return any
    }
}
