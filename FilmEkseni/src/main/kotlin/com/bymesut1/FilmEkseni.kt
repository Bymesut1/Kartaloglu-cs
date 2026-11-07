package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

private const val DEBUG = true

private fun dbg(msg: String) {
    if (!DEBUG) return
    msg.chunked(3500).forEach { Log.d("FilmEkseniDBG", it) }
}

private fun dumpHints(html: String) {
    listOf("videoPlayerData", "eplayer", "eksenload", "vidmoly", "/api/", "csrf", "iframe")
        .forEach { k ->
            val i = html.indexOf(k, ignoreCase = true)
            val snip = if (i >= 0)
                html.substring(maxOf(0, i - 150), minOf(html.length, i + 450)).replace("\n", " ")
            else ""
            dbg("hint '$k' idx=$i $snip")
        }
}

class FilmEkseni : MainAPI() {
    override var mainUrl = "https://filmekseni.vip"
    override var name = "FilmEkseni"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/kategori/tavsiye-filmler/" to "Tavsiye Filmler",
        "$mainUrl/kategori/netflix-yapimlari/" to "Netflix",
        "$mainUrl/kategori/marvel-yapimlari/" to "Marvel",
        "$mainUrl/kategori/dc-yapimlari/" to "DC",
        "$mainUrl/tur/aksiyon-filmleri/" to "Aksiyon",
        "$mainUrl/tur/komedi-filmleri/" to "Komedi",
        "$mainUrl/tur/korku-filmleri/" to "Korku",
        "$mainUrl/tur/gerilim-filmleri/" to "Gerilim",
        "$mainUrl/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "$mainUrl/tur/macera-filmleri/" to "Macera",
        "$mainUrl/tur/aile-filmleri/" to "Aile",
        "$mainUrl/ulke/yerli-film-izle/" to "Yerli Filmler"
    )

    private val skipPaths = listOf(
        "/tur/", "/kategori/", "/ulke/", "/diziler", "/api/", "/load-more", "/yil/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, referer = mainUrl).document
        val items = doc.select("a[href]:has(img)")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (skipPaths.any { href.contains(it) }) return null
        if (href.trimEnd('/') == mainUrl) return null

        val img = selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { attr("title") }.trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        )

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = app.get(
            "$mainUrl/api/search?q=$query",
            referer = mainUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val arr: JSONArray = try {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else {
                val o = JSONObject(trimmed)
                o.optJSONArray("results") ?: o.optJSONArray("data")
                ?: o.optJSONArray("items") ?: JSONArray()
            }
        } catch (e: Exception) {
            JSONArray()
        }

        val list = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { o.optString("name") }
            val link = o.optString("url").ifBlank { o.optString("link") }
                .ifBlank { o.optString("slug") }
            if (title.isBlank() || link.isBlank()) continue
            val href = if (link.startsWith("http")) link else fixUrl(link)
            val poster = listOf("posterUrl", "poster_url", "poster", "image", "img")
                .map { o.optString(it) }.firstOrNull { it.isNotBlank() }
            val isSeries = href.contains("/dizi/") ||
                    o.optString("type").contains("dizi", true)
            list += if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster)
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val imgPoster = doc.select("img")
            .firstOrNull { it.attr("alt").trim().equals(title, true) }
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val poster = fixUrlNull(
            imgPoster?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
        )

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = Regex("""yapımı\s+(.+?)\s+türündeki""")
            .find(plot ?: "")?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
        val year = Regex("""\((19|20)\d{2}\)""")
            .find(plot ?: "")?.value?.trim('(', ')')?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(doc.select("h1, h2, time").text())
                ?.value?.toIntOrNull()

        val epEls = doc.select("a[href*='/sezon-'][href*='/bolum-']")
        val isSeries = url.contains("/dizi/") || epEls.isNotEmpty()

        return if (isSeries) {
            val episodes = epEls.mapNotNull { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val season = Regex("/sezon-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                val ep = Regex("/bolum-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = el.text().trim().ifBlank { "Bölüm $ep" }
                    this.season = season
                    this.episode = ep
                }
            }.distinctBy { it.data }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    private fun unescape(input: String): String {
        var s = input
        s = Regex("""\\x([0-9a-fA-F]{2})""").replace(s) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        s = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun isYoutube(u: String) =
        u.contains("youtube.com", true) || u.contains("youtu.be", true)

    private fun isAsset(u: String) =
        Regex("""\.(jpg|jpeg|png|webp|svg|gif|css|js|ico|woff2?)(\?.*)?$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(u)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        dbg("loadLinks data=$data")
        val raw = app.get(data, referer = mainUrl).text
        dbg("page length=${raw.length}")

        val entries = LinkedHashMap<String, String>()

        fun add(u0: String, tag: String = "") {
            var u = u0.trim()
            if (u.startsWith("//")) u = "https:$u"
            else if (u.startsWith("/")) u = mainUrl + u
            if (!u.startsWith("http") || isYoutube(u) || isAsset(u)) return
            val base = u.substringBefore('#')
            if (base.startsWith(mainUrl) && !base.contains("/eplayer/")) return
            val full = if (tag.isNotBlank() && !u.contains('#')) "$base#$tag" else u
            entries.putIfAbsent(base, full)
        }

        val jsonRaw = Regex("""videoPlayerData\(JSON\.parse\('([^']+)'\)""")
            .find(raw)?.groupValues?.get(1)
        dbg("videoPlayerData found=${jsonRaw != null}")
        if (jsonRaw != null) {
            val json = unescape(Parser.unescapeEntities(jsonRaw, false))
            try {
                val root = JSONObject(json)
                for (group in root.keys()) {
                    val arr = root.optJSONArray(group) ?: continue
                    val tag = when {
                        group.equals("dual", true) -> "dublaj"
                        group.contains("sub", true) || group.contains("alt", true) -> "altyazi"
                        else -> ""
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val link = o.optString("link")
                        val slug = o.optString("slug")
                        val tpl = o.optString("template")
                        if (link.isBlank() || tpl.isBlank()) continue
                        try {
                            val html = String(Base64.decode(tpl, Base64.DEFAULT), Charsets.UTF_8)
                            val src = Regex("""(?:data-src|src)\s*=\s*"([^"]+)"""")
                                .find(html)?.groupValues?.get(1) ?: continue
                            val embed = src.replace("{url}", link).replace("{slug}", slug)
                            dbg("group=$group service=${o.optString("service_name")} embed=$embed")
                            add(embed, tag)
                        } catch (e: Exception) {
                            dbg("template error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                dbg("json parse error: ${e.message}")
            }
        }

        val text = unescape(Parser.unescapeEntities(raw, false))
        Regex("""(?:https?:)?//eksenload\.top/eplayer/[A-Za-z0-9]+""")
            .findAll(text).forEach { add(it.value) }
        Regex("""https?://vidmoly\.[a-z]+/[^"'\s<>\\]+""")
            .findAll(text).forEach { add(it.value) }
        Jsoup.parse(raw, data).select("iframe[src], iframe[data-src]").forEach {
            add(it.attr("src").ifBlank { it.attr("data-src") })
        }

        dbg("sources=${entries.values}")
        if (entries.isEmpty()) dumpHints(raw)

        var count = 0
        val cb: (ExtractorLink) -> Unit = { count++; callback(it) }

        entries.values.forEach { u ->
            try {
                if (u.contains("eksenload.top")) {
                    EksenLoadExtractor().getUrl(u, "$mainUrl/", subtitleCallback, cb)
                } else {
                    loadExtractor(u.substringBefore('#'), mainUrl, subtitleCallback, cb)
                }
            } catch (e: Exception) {
                dbg("extractor error for $u : ${e.message}")
            }
        }

        dbg("links produced=$count")
        return count > 0
    }
}

class EksenLoadExtractor : ExtractorApi() {
    override val name = "Eksenload"
    override val mainUrl = "https://eksenload.top"
    override val requiresReferer = true

    // JavaScript string kaçışlarını (\uXXXX, \xXX, \\, \/ ...) gerçek karakterlere çevirir
    private fun jsUnescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                if (n == 'u' && i + 6 <= s.length) {
                    val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar()); i += 6; continue
                    }
                }
                if (n == 'x' && i + 4 <= s.length) {
                    val code = s.substring(i + 2, i + 4).toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar()); i += 4; continue
                    }
                }
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun findM3u8(s: String): String? =
        Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""").find(s)?.value

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = url.substringAfter('#', "")
        val playerUrl = url.substringBefore('#')
        val label = when (tag) {
            "dublaj" -> " - Türkçe Dublaj"
            "altyazi" -> " - Türkçe Altyazılı"
            else -> ""
        }

        val raw = app.get(playerUrl, referer = referer ?: "https://filmekseni.vip/").text
        dbg("eksenload url=$playerUrl len=${raw.length}")

        var m3u8: String? = null
        val tracks = ArrayList<Pair<String, String>>()

        // jwplayer('player').setup(JSON.parse('{...}'))
        try {
            val payload = Regex("""setup\(JSON\.parse\('(.*?)'\)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.groupValues?.get(1)
            if (payload != null) {
                val obj = JSONObject(jsUnescape(payload))
                m3u8 = obj.optString("file").takeIf { it.startsWith("http") }
                val arr = obj.optJSONArray("tracks")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        val f = t.optString("file")
                        val kind = t.optString("kind")
                        if (f.isBlank() || !f.contains(".vtt")) continue
                        if (kind.equals("thumbnails", true)) continue
                        tracks += t.optString("label").ifBlank { "Altyazı" } to f
                    }
                }
            } else {
                dbg("setup(JSON.parse(...)) bulunamadi")
            }
        } catch (e: Exception) {
            dbg("player json error: ${e.message}")
        }

        // Yedek: tüm sayfayı çözüp m3u8 ara
        if (m3u8 == null) {
            m3u8 = findM3u8(jsUnescape(raw).replace("\\/", "/"))
        }

        val link = m3u8
        if (link == null) {
            dbg("m3u8 NOT found. html=${raw.take(3000)}")
            return
        }
        dbg("m3u8=$link tracks=${tracks.size}")

        tracks.forEach { (lbl, file) ->
            try {
                subtitleCallback(SubtitleFile(lbl, file, mapOf("Referer" to playerUrl)))
            } catch (e: Exception) {
                dbg("subtitle error: ${e.message}")
            }
        }

        callback(
            newExtractorLink(name, name + label, link, ExtractorLinkType.M3U8) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
