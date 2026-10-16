package com.bymesut1

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // ---------------- Kategoriler ----------------

    override val mainPage = mainPageOf(
        "$mainUrl/filmler-1/" to "Son Filmler",
        "$mainUrl/yabanci-dizi-izle-1/" to "Diziler",
        "$mainUrl/ulke/turkiye-fm4/" to "Yerli Filmler",
        "$mainUrl/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "$mainUrl/seri-filmler-izle-1/" to "Seri Filmler",
        "$mainUrl/kanal/netflix-fm1/" to "Netflix",
        "$mainUrl/kanal/disney-fmgj6b/" to "Disney",
        "$mainUrl/kanal/amazon/" to "PrimeVideo",
        "$mainUrl/ulke/guney-kore-fm2/" to "Kore Filmleri",
        "$mainUrl/tur/aksiyon-fmy54y/film/" to "Aksiyon",
        "$mainUrl/tur/aile-fm2/film/" to "Aile",
        "$mainUrl/tur/animasyon-fm2/film/" to "Animasyon",
        "$mainUrl/tur/belgesel/film/" to "Belgesel",
        "$mainUrl/tur/biyografi/film/" to "Biyografi",
        "$mainUrl/tur/bilim-kurgu-fmy58w/film/" to "Bilim Kurgu",
        "$mainUrl/tur/dram-fm1/film/" to "Dram",
        "$mainUrl/tur/fantastik-fm1/film/" to "Fantastik",
        "$mainUrl/tur/gerilim-fm1/film/" to "Gerilim",
        "$mainUrl/tur/gizem/film/" to "Gizem",
        "$mainUrl/tur/komedi-fm1/film/" to "Komedi",
        "$mainUrl/tur/korku-fm2/film/" to "Korku",
        "$mainUrl/tur/macera-fm1/film/" to "Macera",
        "$mainUrl/tur/muzik/film/" to "Müzik",
        "$mainUrl/tur/polisiye/film/" to "Polisiye",
        "$mainUrl/tur/romantik-fm1/film/" to "Romantik",
        "$mainUrl/tur/savas-fm1/film/" to "Savaş",
        "$mainUrl/tur/spor/film/" to "Spor",
        "$mainUrl/tur/tarih-fm1/film/" to "Tarih",
        "$mainUrl/tur/western-fm1/film/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/') + "/"
        val url = if (page <= 1) base else "${base}sayfa/$page/"
        val list = try {
            parseCards(app.get(url, headers = pageHeaders).document)
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    // ---------------- Arama ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/arama/?s=$q", headers = pageHeaders).document
        return parseCards(doc)
    }

    private val cardHref = Regex("^(?:https?://[^/]+)?/(film|dizi)/[^/]+/?$")

    private fun parseCards(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        return doc.select("a.item").mapNotNull { el ->
            if (el.hasClass("soon") || el.hasClass("item-ep")) return@mapNotNull null
            val href = el.attr("href")
            val m = cardHref.find(href) ?: return@mapNotNull null
            val url = fixUrl(href)
            if (!seen.add(url)) return@mapNotNull null

            val img = el.selectFirst("img")
            val title = el.attr("data-title").ifBlank {
                el.selectFirst(".item-title")?.text()
                    ?: el.selectFirst(".title")?.text()
                    ?: img?.attr("alt") ?: ""
            }.trim()
            if (title.isBlank()) return@mapNotNull null

            var poster = img?.attr("src") ?: ""
            if (poster.isBlank() || poster.startsWith("data:")) poster = img?.attr("data-src") ?: ""
            val posterUrl = fixUrlNull(poster.ifBlank { null })

            val year = Regex("(19|20)\\d{2}")
                .find(el.select("div.info span").text())?.value?.toIntOrNull()

            if (m.groupValues[1] == "dizi") {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                }
            }
        }
    }

    // ---------------- Detay sayfası ----------------

    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\s*izle\\s*(\\(\\d{4}\\))?\\s*$", RegexOption.IGNORE_CASE), "").trim()


    // ---------------- TMDB ----------------

    // https://www.themoviedb.org/settings/api adresinden ücretsiz anahtar al
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"

    data class TmdbSearch(@JsonProperty("results") val results: List<TmdbShow>? = null)
    data class TmdbShow(@JsonProperty("id") val id: Int? = null)
    data class TmdbSeason(@JsonProperty("episodes") val episodes: List<TmdbEp>? = null)
    data class TmdbEp(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null
    )

    private suspend fun tmdbFindId(title: String, year: Int?): Int? {
        if (tmdbKey.startsWith("BURAYA")) return null
        val q = URLEncoder.encode(title, "UTF-8")
        suspend fun search(withYear: Boolean): Int? = try {
            val y = if (withYear && year != null) "&first_air_date_year=$year" else ""
            app.get("https://api.themoviedb.org/3/search/tv?api_key=$tmdbKey&language=tr-TR&query=$q$y")
                .parsedSafe<TmdbSearch>()?.results?.firstOrNull()?.id
        } catch (e: Exception) { null }
        return search(true) ?: search(false)
    }

    private suspend fun tmdbSeason(id: Int, season: Int): Map<Int, TmdbEp> {
        suspend fun fetch(lang: String): List<TmdbEp> = try {
            app.get("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$tmdbKey&language=$lang")
                .parsedSafe<TmdbSeason>()?.episodes ?: emptyList()
        } catch (e: Exception) { emptyList() }

        val tr = fetch("tr-TR")
        // Türkçe açıklama/görsel yoksa İngilizceden tamamla
        val en = if (tr.any { it.overview.isNullOrBlank() || it.stillPath == null }) fetch("en-US") else emptyList()
        val enMap = en.associateBy { it.episodeNumber }
        return tr.mapNotNull { t ->
            val n = t.episodeNumber ?: return@mapNotNull null
            val f = enMap[n]
            n to t.copy(
                overview = t.overview?.takeIf { it.isNotBlank() } ?: f?.overview,
                stillPath = t.stillPath ?: f?.stillPath,
                name = t.name?.takeIf { it.isNotBlank() } ?: f?.name
            )
        }.toMap()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = pageHeaders).document
        val html = doc.html()
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".thumbnail-outer img")?.attr("src")
        )
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("(19|20)\\d{2}").findAll(url).lastOrNull()?.value?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val seen = HashSet<String>()
            val found = ArrayList<Triple<String, Int, Int>>() // url, sezon, bölüm
            Regex("href=\"([^\"]*?/sezon-(\\d+)/bolum-(\\d+)/?)\"")
                .findAll(html).forEach { m ->
                    val s = m.groupValues[2].toInt()
                    val e = m.groupValues[3].toInt()
                    if (seen.add("$s-$e")) found.add(Triple(fixUrl(m.groupValues[1]), s, e))
                }

            // TMDB'den resmi bölüm bilgileri (isim, açıklama, görsel, yayın tarihi)
            val tmdbId = tmdbFindId(title, year)
            val seasonCache = HashMap<Int, Map<Int, TmdbEp>>()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            val episodes = ArrayList<Episode>()
            for ((epUrl, s, e) in found) {
                val info = if (tmdbId != null)
                    seasonCache.getOrPut(s) { tmdbSeason(tmdbId, s) }[e]
                else null

                // Yayın tarihi bugünden sonraysa listeye ekleme; yayın günü kendiliğinden düşer
                val air = info?.airDate
                if (!air.isNullOrBlank() && air > today) continue

                episodes.add(newEpisode(epUrl) {
                    this.season = s
                    this.episode = e
                    this.name = info?.name?.takeIf { it.isNotBlank() } ?: "$s. Sezon $e. Bölüm"
                    this.description = info?.overview?.takeIf { it.isNotBlank() }
                    this.posterUrl = info?.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    this.runTime = info?.runtime
                    this.date = air?.let {
                        try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)?.time } catch (_: Exception) { null }
                    }
                })
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
        val dbg = ArrayList<String>()
        var any = false

        val res = try { app.get(data, headers = pageHeaders) } catch (e: Exception) {
            dbg.add("sayfa hata ${e.message?.take(80)}")
            null
        }
        if (res != null) {
            dbg.add("sayfa ${res.code} len=${res.text.length}")
            val parts = extractParts(res.document, res.text)
            dbg.add("kaynak ${parts.size}")

            for ((label, embed) in parts) {
                dbg.add("E $label ${embed.take(70)}")
                val origin = originOf(embed)

                // 1) Statik çözüm
                var found = try { resolveEmbed(embed, data, dbg) } catch (e: Exception) {
                    dbg.add("EM hata ${e.message?.take(60)}"); null
                }
                // 2) Gerçekten çalışıyor mu? (#EXTM3U + farklı header kombinasyonları)
                if (found != null) found = verifyFound(found, origin, dbg, label)
                // 3) Olmadıysa WebView ile gerçek istekleri yakala (JS sandbox yerine)
                if (found == null) {
                    found = try { webViewFind(embed, data, origin, dbg, label) } catch (e: Exception) {
                        dbg.add("WV hata $label ${e.message?.take(60)}"); null
                    }
                }

                if (found != null) {
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
                    val ok = try {
                        loadExtractor(embed, data, subtitleCallback, callback)
                    } catch (_: Exception) { false }
                    dbg.add("$label COZULEMEDI, loadExtractor=$ok")
                    if (ok) any = true
                }
            }
        }

        return any
    }


    // ---------------- Akış doğrulama + WebView yedeği ----------------

    private suspend fun verifyFound(f: Found, origin: String, dbg: MutableList<String>, label: String): Found? {
        if (!f.hls) return f
        val variants = listOf(
            f.headers,
            mapOf("User-Agent" to androidUA, "Referer" to "$origin/", "Origin" to origin),
            mapOf("User-Agent" to androidUA, "Referer" to "$mainUrl/", "Origin" to mainUrl),
            mapOf("User-Agent" to androidUA)
        )
        for ((i, h) in variants.withIndex()) {
            val r = try { app.get(f.url, headers = h) } catch (e: Exception) {
                dbg.add("V $label h$i hata ${e.message?.take(40)}"); null
            } ?: continue
            dbg.add("V $label h$i ${r.code} ${r.text.take(30).replace(Regex("\\s+"), " ")}")
            if (r.code == 200 && r.text.trimStart().startsWith("#EXTM3U")) return f.copy(headers = h)
        }
        return null
    }

    private suspend fun webViewFind(
        embed: String, pageUrl: String, origin: String, dbg: MutableList<String>, label: String
    ): Found? {
        val resp = app.get(
            embed,
            referer = pageUrl,
            headers = mapOf("User-Agent" to androidUA),
            interceptor = WebViewResolver(
                Regex("""\.m3u8|\.mp4(\?|$)|master\.txt|/hls/""", RegexOption.IGNORE_CASE)
            )
        )
        val u = resp.url
        dbg.add("WV $label ${u.take(90)}")
        if (u == embed || badExt.containsMatchIn(u)) return null
        val cand = Found(u, !isMp4(u), mapOf("User-Agent" to androidUA, "Referer" to "$origin/", "Origin" to origin))
        return verifyFound(cand, origin, dbg, label) ?: cand
    }

    private val skipHosts = Regex(
        "youtube|youtu\\.be|googletagmanager|google-analytics|cloudflare|facebook|twitter|agl005|cvt-s2|fonts\\.g",
        RegexOption.IGNORE_CASE
    )

    private fun extractParts(doc: Document, html: String): List<Pair<String, String>> {
        val map = LinkedHashMap<String, String>() // url -> label

        fun addPart(raw: String, label: String) {
            val u = raw.trim().replace("&amp;", "&").replace("\\/", "/")
            if (!u.startsWith("http")) return
            if (skipHosts.containsMatchIn(u)) return
            if (label.contains("fragman", true)) return
            if (!map.containsKey(u)) map[u] = label.ifBlank { "Kaynak" }
        }

        doc.select("a[data-video_url]").forEach { a ->
            addPart(
                a.attr("data-video_url"),
                a.text().replace(Regex("\\s+"), " ").trim()
            )
        }
        Regex("data-video_url=\"([^\"]+)\"").findAll(html).forEach { addPart(it.groupValues[1], "Kaynak") }

        doc.select("iframe[data-src], iframe[src]").forEach { f ->
            addPart(f.attr("data-src").ifBlank { f.attr("src") }, "Kaynak")
        }

        return map.map { it.value to it.key }
    }

    // ---------------- Embed çözücü ----------------

    private data class Found(val url: String, val hls: Boolean, val headers: Map<String, String>)

    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String, dbg: MutableList<String>): Found? {
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
            } catch (e: Exception) {
                dbg.add("EM hata ${e.message?.take(60)}"); ""
            }
            if (html.isBlank()) { dbg.add("EM sayfa bos"); continue }

            val texts = unpackAll(html)
            val r = extractEmbed(html, origin, texts)
            if (r != null) {
                return Found(r.first, r.second, mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin))
            }
            dbg.add(
                "EM len=${html.length} script=${Regex("<script", RegexOption.IGNORE_CASE).findAll(html).count()} " +
                    "paket=${texts.size - 1} eval=${html.contains("eval(")} ld=${html.contains("contentUrl")} " +
                    "av=${html.contains("av(")} m3u8=${texts.last().contains("m3u8")}"
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
    private val b64Chars = Regex("^[A-Za-z0-9+/=_\\-]+$")
    private val httpStart = Regex("https?://[A-Za-z0-9]")

    private fun originOf(u: String) = Regex("^https?://[^/]+").find(u)?.value ?: ""

    private fun isMp4(u: String) =
        Regex("\\.mp4(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u)

    /** (url, hls?) */
    private fun extractEmbed(html: String, origin: String, texts: List<String>): Pair<String, Boolean>? {
        Regex("<(?:source|video)[^>]*\\ssrc=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.let {
                val u = it.groupValues[1].replace("&amp;", "&")
                if (!badExt.containsMatchIn(u)) return u to !isMp4(u)
            }

        for (t in texts) {
            Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").find(t)?.let {
                val v = hexUnescape(it.groupValues[1])
                if (v.startsWith("http") && !badExt.containsMatchIn(v)) return v to !isMp4(v)
            }

            Regex("av\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").find(t)?.let {
                try {
                    val d = decodeSecret(it.groupValues[1])
                    findStreamUrl(d)?.let { f -> return f }
                    if (Regex("^https?://\\S+$").matches(d)) return d to true
                } catch (_: Exception) {}
            }

            for (m in Regex("\"?file\"?\\s*:\\s*\"([^\"]+)\"").findAll(t)) {
                val v = hexUnescape(m.groupValues[1])
                if (v.startsWith("http") && !badExt.containsMatchIn(v)) return v to !isMp4(v)
            }

            findStreamUrl(t)?.let { return it }

            Regex("[\"'](/[^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']").find(hexUnescape(t))?.let {
                return (origin + it.groupValues[1]) to true
            }
        }

        return try { bruteFindStream(texts) } catch (_: Exception) { null }
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

    // ---------------- Yedek (brute-force) çözücü ----------------

    private fun bruteFindStream(texts: List<String>): Pair<String, Boolean>? {
        val cands = ArrayList<String>()
        val re = Regex("""["']([A-Za-z0-9+/=_\-]{30,6000})["']""")
        for (t in texts) for (m in re.findAll(t)) {
            if (!cands.contains(m.groupValues[1])) cands.add(m.groupValues[1])
        }
        cands.sortByDescending { it.length }
        for (c in cands.take(8)) {
            val d = tryDecodeString(c)
            if (d.isNotEmpty()) {
                findStreamUrl(d)?.let { return it }
                val u = Regex("""https?://[^\s"'<>\\]+""").find(d)?.value
                if (u != null && !badExt.containsMatchIn(u)) return u to !isMp4(u)
            }
        }
        return null
    }

    private fun tryDecodeString(str: String): String {
        val variants = listOf(str, str.reversed(), rot13(str), rot13(str).reversed())
        for (v in variants) {
            val bytes = b64(v)
            if (bytes.size < 12) continue
            val plain = latin1(bytes)
            if (httpStart.containsMatchIn(plain)) return plain
            val inner0 = latin1(b64(plain))
            if (httpStart.containsMatchIn(inner0)) return inner0

            for (a in 0..5) for (b in 0..5) for (c in 0..5) {
                if (a == 0 && b == 0 && c == 0) continue
                val off = intArrayOf(a, b, c)
                val n = minOf(bytes.size, 48)
                val pre = String(ByteArray(n) { i -> (bytes[i] - off[i % 3]).toByte() }, Charsets.ISO_8859_1)
                if (!httpStart.containsMatchIn(pre) && !b64Chars.matches(pre)) continue
                val s2 = String(ByteArray(bytes.size) { i -> (bytes[i] - off[i % 3]).toByte() }, Charsets.ISO_8859_1)
                if (httpStart.containsMatchIn(s2)) return s2
                if (b64Chars.matches(s2)) {
                    val s3 = latin1(b64(s2))
                    if (httpStart.containsMatchIn(s3)) return s3
                }
            }
        }
        return ""
    }

    // ---------------- Yardımcılar ----------------

    private fun b64(s: String): ByteArray {
        var x = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        if (x.length % 4 == 1) x = x.dropLast(1)
        while (x.length % 4 != 0) x += "="
        return try { Base64.decode(x, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
    }

    private fun latin1(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    private fun rot13(s: String): String = s.map { c ->
        when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")

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
