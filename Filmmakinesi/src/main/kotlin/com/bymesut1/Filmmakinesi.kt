package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class Filmmakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "Fimmakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val androidUA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
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

    data class TmdbSearch(val results: List<TmdbShow>? = null)
    data class TmdbShow(val id: Int? = null)
    data class TmdbDetail(val overview: String? = null)
    data class TmdbSeason(val episodes: List<TmdbEp>? = null)
    data class TmdbEp(
        val episode_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val air_date: String? = null,
        val runtime: Int? = null
    )

    private suspend fun tmdbFindId(title: String, year: Int?, isTv: Boolean = true): Int? {
        if (tmdbKey.startsWith("BURAYA")) return null
        val q = URLEncoder.encode(title, "UTF-8")
        val kind = if (isTv) "tv" else "movie"
        val yearParam = if (isTv) "first_air_date_year" else "year"
        suspend fun search(withYear: Boolean): Int? = try {
            val y = if (withYear && year != null) "&$yearParam=$year" else ""
            app.get("https://api.themoviedb.org/3/search/$kind?api_key=$tmdbKey&language=tr-TR&query=$q$y")
                .parsedSafe<TmdbSearch>()?.results?.firstOrNull()?.id
        } catch (e: Exception) { null }
        return search(true) ?: search(false)
    }

    // Dizi / film açıklaması: yalnızca Türkçe (TR). Türkçe yoksa null döner.
    private suspend fun tmdbOverviewTr(id: Int, isTv: Boolean): String? = try {
        val kind = if (isTv) "tv" else "movie"
        app.get("https://api.themoviedb.org/3/$kind/$id?api_key=$tmdbKey&language=tr-TR")
            .parsedSafe<TmdbDetail>()?.overview?.trim()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    private suspend fun tmdbSeason(id: Int, season: Int): Map<Int, TmdbEp> {
        suspend fun fetch(lang: String): List<TmdbEp> = try {
            app.get("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$tmdbKey&language=$lang")
                .parsedSafe<TmdbSeason>()?.episodes ?: emptyList()
        } catch (e: Exception) { emptyList() }

        val tr = fetch("tr-TR")
        // Açıklama her zaman Türkçe; İngilizce yalnızca eksik görsel ve isim için kullanılır
        val en = if (tr.any { it.still_path == null || it.name.isNullOrBlank() }) fetch("en-US") else emptyList()
        val enMap = en.associateBy { it.episode_number }
        return tr.mapNotNull { t ->
            val n = t.episode_number ?: return@mapNotNull null
            val f = enMap[n]
            n to t.copy(
                overview = t.overview?.takeIf { it.isNotBlank() },
                still_path = t.still_path ?: f?.still_path,
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
        val sitePlot = doc.selectFirst("meta[property=og:description]")?.attr("content")
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
            val tmdbId = tmdbFindId(title, year, true)
            val plot = (if (tmdbId != null) tmdbOverviewTr(tmdbId, true) else null) ?: sitePlot
            val seasonCache = HashMap<Int, Map<Int, TmdbEp>>()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            val episodes = ArrayList<Episode>()
            for ((epUrl, s, e) in found) {
                val info = if (tmdbId != null)
                    seasonCache.getOrPut(s) { tmdbSeason(tmdbId, s) }[e]
                else null

                // Yayın tarihi bugünden sonraysa listeye ekleme; yayın günü kendiliğinden düşer
                val air = info?.air_date
                if (!air.isNullOrBlank() && air > today) continue

                episodes.add(newEpisode(epUrl) {
                    this.season = s
                    this.episode = e
                    this.name = info?.name?.takeIf { it.isNotBlank() } ?: "$s. Sezon $e. Bölüm"
                    this.description = info?.overview?.takeIf { it.isNotBlank() }
                    this.posterUrl = info?.still_path?.let { "https://image.tmdb.org/t/p/w500$it" }
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

        val movieId = tmdbFindId(title, year, false)
        val plot = (if (movieId != null) tmdbOverviewTr(movieId, false) else null) ?: sitePlot
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ---------------- Linkler ----------------

    private fun log(msg: String) {
        try {
            Log.d("Filmmakinesi", msg)
        } catch (_: Throwable) {
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = try {
            app.get(data, headers = pageHeaders)
        } catch (e: Exception) {
            log("sayfa hata ${e.message?.take(80)}")
            return false
        }
        val parts = extractParts(res.document, res.text)
        log("sayfa ${res.code} len=${res.text.length} kaynak=${parts.size}")
        if (parts.isEmpty()) return false

        val seenUrl = Collections.synchronizedSet(HashSet<String>())
        val gotExt = AtomicBoolean(false)

        parts.amap { part ->
            val label = part.first
            val embed = part.second
            try {
                log("E $label ${embed.take(80)}")
                val found = findStream(embed, data, label)
                if (found != null) {
                    if (seenUrl.add(found.url)) {
                        callback(
                            newExtractorLink(
                                name,
                                "$name - $label",
                                found.url,
                                if (found.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = found.headers["Referer"] ?: "${originOf(embed)}/"
                                this.quality = Qualities.Unknown.value
                                this.headers = found.headers
                            }
                        )
                    }
                } else {
                    val ok = try {
                        loadExtractor(embed, data, subtitleCallback, callback)
                    } catch (_: Exception) {
                        false
                    }
                    log("$label COZULEMEDI loadExtractor=$ok")
                    if (ok) gotExt.set(true)
                }
            } catch (e: Exception) {
                log("$label hata ${e.message?.take(80)}")
            }
            Unit
        }
        return seenUrl.isNotEmpty() || gotExt.get()
    }

    // ---------------- Akış bulma / doğrulama ----------------

    private data class Verified(val ok: Boolean, val deep: Boolean, val headers: Map<String, String>)

    private val wvRegex = Regex(
        """\.m3u8|\.mp4(\?|$)|/(?:master|playlist|index|sublist)[^/?]*\.txt""",
        RegexOption.IGNORE_CASE
    )

    private fun rank(u: String): Int = when {
        u.contains("master", true) -> 0
        u.contains(".m3u8", true) -> 1
        else -> 2
    }

    private fun absUrl(base: String, ref: String): String = when {
        ref.startsWith("http", true) -> ref
        ref.startsWith("//") -> "https:$ref"
        ref.startsWith("/") -> originOf(base) + ref
        else -> base.substringBefore("?").substringBeforeLast('/') + "/" + ref
    }

    // Rapid ve Close CDN'leri Referer/Origin olarak oynatıcı alt alan adını bekliyor
    // (tarayıcıda görülen: rapid.<site> ve closeload.<site>). Adresi doğrudan açınca 403 vermesi bu yüzden.
    private fun knownOrigin(streamUrl: String): String? {
        val host = originOf(streamUrl).substringAfter("://").lowercase()
        val site = mainUrl.substringAfter("://").trimEnd('/').removePrefix("www.")
        return when {
            host.contains("rapidrame") -> "https://rapid.$site"
            host.contains("cdnimages") -> "https://closeload.$site"
            else -> null
        }
    }

    private fun streamHeaderSets(embedBase: String, streamUrl: String): List<Map<String, String>> {
        fun hs(ref: String?, origin: String?): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            m["User-Agent"] = androidUA
            if (ref != null) m["Referer"] = ref
            if (origin != null) m["Origin"] = origin
            return m
        }
        val out = ArrayList<Map<String, String>>()
        val org = originOf(embedBase)
        if (org.isNotBlank()) out.add(hs("$org/", org))
        knownOrigin(streamUrl)?.let { out.add(hs("$it/", it)) }
        out.add(hs(embedBase, null))
        out.add(hs("$mainUrl/", mainUrl))
        val own = originOf(streamUrl)
        if (own.isNotBlank()) out.add(hs("$own/", own))
        out.add(hs(null, null))
        return out.distinct()
    }

    // Ana liste -> alt liste -> ilk parça zincirini aynı header'larla dener.
    // Tarayıcıda 403 veren asıl nokta parçalar (seg/image) olduğu için orası da kontrol edilir.
    private suspend fun deepCheck(startUrl: String, startText: String, h: Map<String, String>): Boolean {
        var curUrl = startUrl
        var curText = startText
        for (i in 0..2) {
            val line = curText.lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            val next = absUrl(curUrl, line)
            if (curText.contains("#EXTINF")) {
                val r = try {
                    app.get(next, headers = h + mapOf("Range" to "bytes=0-2047"), timeout = 8L)
                } catch (e: Exception) {
                    null
                } ?: return false
                val ok = r.code == 200 || r.code == 206
                if (ok) r.text.length
                return ok
            }
            val r = try {
                app.get(next, headers = h, timeout = 8L)
            } catch (e: Exception) {
                null
            } ?: return false
            if (r.code != 200) return false
            val t = r.text
            if (!t.trimStart().startsWith("#EXTM3U")) return false
            curUrl = next
            curText = t
        }
        return false
    }

    private suspend fun verifyUrl(u: String, sets: List<Map<String, String>>): Verified {
        if (isMp4(u)) return Verified(true, true, sets[0])
        var firstOk: Verified? = null
        for (h in sets) {
            val r = try {
                app.get(u, headers = h, timeout = 8L)
            } catch (e: Exception) {
                null
            }
            if (r != null && (r.code == 200 || r.code == 206)) {
                val t = r.text
                if (t.trimStart().startsWith("#EXTM3U")) {
                    if (deepCheck(u, t, h)) return Verified(true, true, h)
                    if (firstOk == null) firstOk = Verified(true, false, h)
                }
            }
        }
        return firstOk ?: Verified(false, false, sets[0])
    }

    private suspend fun webViewFind(embed: String, pageUrl: String, base: String, label: String): Found? {
        val resp = app.get(
            embed,
            referer = pageUrl,
            headers = mapOf("User-Agent" to androidUA),
            interceptor = WebViewResolver(wvRegex)
        )
        val u = resp.url
        log("WV $label ${u.take(100)}")
        if (u == embed || badExt.containsMatchIn(u) || !wvRegex.containsMatchIn(u)) return null
        val v = verifyUrl(u, streamHeaderSets(base, u))
        return Found(u, !isMp4(u), v.headers)
    }

    private suspend fun findStream(embed: String, pageUrl: String, label: String): Found? {
        var base = embed
        var unverified: Found? = null

        val er = try {
            resolveEmbed(embed, pageUrl)
        } catch (e: Exception) {
            log("EM hata $label ${e.message?.take(60)}")
            null
        }
        if (er != null) {
            base = er.base
            val cands = er.urls
                .sortedWith(compareBy<String>({ if (er.strict.contains(it)) 0 else 1 }, { rank(it) }))
                .take(6)
            val verified = cands.amap { u -> u to verifyUrl(u, streamHeaderSets(er.base, u)) }
            verified.forEach { log("V $label ok=${it.second.ok} deep=${it.second.deep} ${it.first.take(90)}") }

            val best = verified.firstOrNull { it.second.deep } ?: verified.firstOrNull { it.second.ok }
            if (best != null) return Found(best.first, !isMp4(best.first), best.second.headers)

            val st = verified.firstOrNull { er.strict.contains(it.first) }
            if (st != null) unverified = Found(st.first, !isMp4(st.first), st.second.headers)
        }

        val wv = try {
            webViewFind(embed, pageUrl, base, label)
        } catch (e: Exception) {
            log("WV hata $label ${e.message?.take(60)}")
            null
        }
        return wv ?: unverified
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

    private data class Embed(val urls: List<String>, val strict: Set<String>, val base: String)

    private fun embedHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to androidUA,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to ref
    )

    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String, depth: Int = 0): Embed? {
        val refs = listOf(pageUrl, "$mainUrl/").distinct()
        for (rf in refs) {
            val r = try {
                app.get(embedUrl, headers = embedHeaders(rf), timeout = 10L)
            } catch (e: Exception) {
                log("EM hata ${e.message?.take(60)}")
                null
            }
            if (r == null || r.code != 200) {
                log("EM kod=${r?.code} ${embedUrl.take(70)}")
                continue
            }
            val html = r.text
            if (html.isBlank()) continue
            val finalUrl = r.url.ifBlank { embedUrl }

            // Yeni çözücü: p.a.c.k.e.r, atob, dc_xxx([...]), av("..."), iç içe base64
            val blobs = FmDecoder.collectBlobs(html)
            val c = FmDecoder.candFrom(blobs)
            val urls = LinkedHashSet<String>()
            val strict = LinkedHashSet<String>(c.strict)
            val ld = Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (ld != null && ld.startsWith("http", true) && !badExt.containsMatchIn(ld)) {
                urls.add(ld)
                strict.add(ld)
            }
            urls.addAll(c.urls)

            // Eski çözücü yedek olarak kalsın
            if (urls.isEmpty()) {
                try {
                    extractEmbed(html, originOf(finalUrl), unpackAll(html))?.let { urls.add(it.first) }
                } catch (_: Exception) {
                }
            }
            log("EM len=${html.length} blob=${blobs.size} url=${urls.size} strict=${strict.size} final=${finalUrl.take(60)}")
            if (urls.isNotEmpty()) return Embed(urls.toList(), strict, finalUrl)

            val inner = Jsoup.parse(html).selectFirst("iframe[data-src], iframe[src]")
            if (inner != null && depth < 1) {
                val raw = inner.attr("data-src").ifBlank { inner.attr("src") }
                if (raw.startsWith("http") || raw.startsWith("//")) {
                    val iu = if (raw.startsWith("//")) "https:$raw" else raw
                    val rr = resolveEmbed(iu, finalUrl, depth + 1)
                    if (rr != null) return rr
                }
            }
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

// ================= Çözücü (Rapid / Close gizleme katmanları) =================
private data class FmCand(val urls: List<String>, val strict: Set<String>)

private object FmDecoder {
    private val IC = RegexOption.IGNORE_CASE
    private const val B64CH = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun unesc(s: String): String {
        var t = s.replace("\\/", "/")
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return t.replace("&amp;", "&")
    }

    // ---------- p.a.c.k.e.r ----------
    private val packedRe = Regex(
        """\}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val packedRe2 = Regex(
        """\}\("((?:[^"\\]|\\.)*)",\s*(\d+),\s*(\d+),\s*"((?:[^"\\]|\\.)*)"\.split\("\|"\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun unpackWith(re: Regex, q: String, src: String, out: MutableList<String>) {
        for (m in re.findAll(src)) {
            try {
                val payload = m.groupValues[1].replace("\\" + q, q).replace("\\\\", "\\")
                val radix = m.groupValues[2].toInt()
                val count = m.groupValues[3].toInt()
                val dict = m.groupValues[4].split("|")

                fun enc(c: Int): String {
                    val pre = if (c < radix) "" else enc(c / radix)
                    val r = c % radix
                    return pre + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
                }

                val map = HashMap<String, String>()
                for (i in count - 1 downTo 0) {
                    val k = enc(i)
                    map[k] = dict.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: k
                }
                out.add(Regex("""\b\w+\b""").replace(payload) { map[it.value] ?: it.value })
            } catch (e: Exception) {
            }
        }
    }

    private fun unpack(src: String): List<String> {
        val out = ArrayList<String>()
        unpackWith(packedRe, "'", src, out)
        unpackWith(packedRe2, "\"", src, out)
        return out
    }

    // ---------- Base64 ----------
    private val atobRe = Regex("""atob\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")
    private val longB64 = Regex("""["']([A-Za-z0-9+/]{80,}={0,2})["']""")

    private fun b64(src: String): List<String> {
        val out = ArrayList<String>()
        val raws = atobRe.findAll(src).map { it.groupValues[1] }.take(20).toList() +
            longB64.findAll(src).map { it.groupValues[1] }.take(20).toList()
        for (raw in raws) {
            val bytes = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (e: Exception) {
                try {
                    Base64.decode(raw, Base64.URL_SAFE)
                } catch (e2: Exception) {
                    null
                }
            } ?: continue
            val d = String(bytes, Charsets.UTF_8)
            if (d.contains("http", true) || d.contains("eval(") || d.contains("m3u8", true)) out.add(d)
        }
        return out
    }

    // Hoşgörülü base64 çözücü (URL-safe ve bozuk karakterleri atar), baytları Latin-1 metin olarak verir
    private fun b64d(s0: String): String {
        val s = StringBuilder()
        for (ch in s0) {
            val c = if (ch == '-') '+' else if (ch == '_') '/' else ch
            if (B64CH.indexOf(c) >= 0) s.append(c)
        }
        if (s.length < 8) return ""
        val o = StringBuilder()
        var buf = 0
        var bits = 0
        for (ch in s) {
            buf = (buf shl 6) or B64CH.indexOf(ch)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                o.append(((buf shr bits) and 255).toChar())
                buf = buf and ((1 shl bits) - 1)
            }
        }
        return o.toString()
    }

    private fun rot13(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch in 'a'..'z') sb.append('a' + (ch - 'a' + 13) % 26)
            else if (ch in 'A'..'Z') sb.append('A' + (ch - 'A' + 13) % 26)
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun unmix(s: String, salt: Long): String {
        val sb = StringBuilder()
        for (i in s.indices) {
            val k = (salt % (i + 5)).toInt()
            sb.append((((s[i].code - k) % 256 + 256) % 256).toChar())
        }
        return sb.toString()
    }

    // ---------- Close: dc_xxx(["parça","parça",...]) ----------
    private val dcRe = Regex("""dc_\w+\s*\(\s*\[([^\]]*)\]\s*\)""")
    private val partRe = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'")
    private val saltRe = Regex("""(\d{6,12})\s*%\s*\(\s*\w+\s*\+\s*\d+\s*\)""")
    private val httpRe = Regex("""https?://""")

    private fun dcDecode(blob: String): List<String> {
        val out = ArrayList<String>()
        val salts = ArrayList<Long>()
        val found = saltRe.find(blob)?.groupValues?.get(1)?.toLongOrNull()
        if (found != null) salts.add(found)
        if (!salts.contains(399756995L)) salts.add(399756995L)
        for (m in dcRe.findAll(blob).take(10)) {
            val sb = StringBuilder()
            for (pm in partRe.findAll(m.groupValues[1])) {
                sb.append(if (pm.groupValues[1].isNotEmpty()) pm.groupValues[1] else pm.groupValues[2])
            }
            val s = sb.toString()
            if (s.length < 8) continue
            for (p in listOf(s, s.reversed(), rot13(s), rot13(s.reversed()))) {
                val d = b64d(p)
                if (d.isEmpty()) continue
                val cands = ArrayList<String>()
                cands.add(d)
                cands.add(d.reversed())
                for (sl in salts) {
                    val um = unmix(d, sl)
                    cands.add(um)
                    cands.add(um.reversed())
                    cands.add(unmix(d.reversed(), sl))
                }
                for (c in cands) {
                    if (httpRe.containsMatchIn(c)) out.add(c)
                }
            }
        }
        return out
    }

    // İç içe base64 (base64 -> ters -> base64 ...): en çok 3 kat
    private val mediaHintRe = Regex("""https?://|\.m3u8|\.mp4""")
    private val b64OnlyRe = Regex("""^[A-Za-z0-9+/=_-]+$""")
    private val wsRe = Regex("""\s+""")

    private fun deepB64(s: String, depth: Int, out: MutableList<String>) {
        if (depth > 3 || out.size > 40) return
        for (p in listOf(s, s.reversed())) {
            val d = b64d(p)
            if (d.isEmpty()) continue
            for (c in listOf(d, d.reversed())) {
                if (mediaHintRe.containsMatchIn(c)) {
                    out.add(c)
                    continue
                }
                val t = c.replace(wsRe, "")
                if (t.length >= 24 && b64OnlyRe.matches(t)) deepB64(t, depth + 1, out)
            }
        }
    }

    private val longStrRe = Regex("""["'`]([A-Za-z0-9+/=_-]{40,})["'`]""")

    private fun b64Strings(src: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (m in longStrRe.findAll(src)) {
            if (out.size >= 40) break
            if (seen.add(m.groupValues[1])) deepB64(m.groupValues[1], 0, out)
        }
        return out
    }

    // ---------- Rapid: av("...") ----------
    private val avRe = Regex("""\bav\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")

    private fun avDecode(input: String): String? {
        val first = b64d(input.reversed())
        if (first.isEmpty()) return null
        val key = "K9L"
        val sb = StringBuilder()
        for (i in first.indices) {
            val off = key[i % key.length].code % 5 + 1
            sb.append((first[i].code - off).toChar())
        }
        val second = b64d(sb.toString())
        return if (second.startsWith("http", true)) second else null
    }

    private fun dcAv(src: String): List<String> {
        val out = ArrayList<String>()
        out.addAll(dcDecode(src))
        for (m in avRe.findAll(src).take(10)) {
            val d = avDecode(m.groupValues[1])
            if (d != null) out.add(d)
        }
        return out
    }

    // ---------- Tüm çözülmüş metinleri topla ----------
    fun collectBlobs(html: String): List<String> {
        val out = LinkedHashSet<String>()
        var queue = ArrayList<String>()
        queue.add(html)
        var round = 0
        while (queue.isNotEmpty() && round < 3) {
            val next = ArrayList<String>()
            for (b in queue) {
                val u = unesc(b)
                if (!out.add(u)) continue
                next.addAll(unpack(u))
                next.addAll(b64(u))
                next.addAll(dcAv(u))
                next.addAll(b64Strings(u))
            }
            queue = next
            round++
        }
        return out.toList()
    }

    // ---------- Akış adayları ----------
    private val urlRe = Regex("""https?://[^\s"'<>\\)]+""")
    private val streamRe = Regex("""\.m3u8|\.mp4|sublist|/hls\d?/|/(?:master|playlist|index)[^/"']*\.txt""", IC)
    private val badRe = Regex("""\.(?:vtt|srt|jpe?g|png|gif|webp|css|js|svg|ico|woff2?)(?:\?|#|/?\z)""", IC)
    private val junkRe = Regex(
        """cloudflareinsights|cloudflare\.com|googletagmanager|google-analytics|googlesyndication|gstatic|googleapis|doubleclick|facebook\.|twitter\.|jquery|cdnjs|jsdelivr|unpkg|bootstrapcdn|histats|yandex|adsterra|popads|beacon\.min\.js|/cdn-cgi/""",
        IC
    )
    private val keyedRe = Regex(
        """["']?(?:file|file_link|contentUrl|source|src|hls|stream|stream_url|video_url|url|m3u8|playlist)["']?\s*[:=]\s*["'](https?://[^"'\s\\]+)["']""",
        IC
    )
    private val keyedBadRe = Regex("""\.(?:vtt|srt|jpe?g|png|webp|gif|css|js|ico|svg|woff2?|html?)(?:\?|$)""", IC)
    private val subDirRe = Regex("""/(?:srt|sub|subtitle|vtt)/""", IC)

    private fun junkUrl(u: String): Boolean = junkRe.containsMatchIn(u)

    private fun cleanBlob(b: String): String =
        b.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    fun candFrom(blobs: List<String>): FmCand {
        val urls = LinkedHashSet<String>()
        val strict = LinkedHashSet<String>()
        for (b in blobs) {
            val t = cleanBlob(b)
            for (m in urlRe.findAll(t)) {
                val u = m.value.trimEnd(',', ';', '.', ']', ')')
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u) && !junkUrl(u)) {
                    urls.add(u)
                    strict.add(u)
                }
            }
        }
        // Akış desenine uyan yoksa file:"..." / src="..." gibi anahtarlı adresler aday olur (doğrulamadan geçerse kullanılır)
        if (strict.isEmpty()) {
            for (b in blobs) {
                val t = cleanBlob(b)
                for (m in keyedRe.findAll(t)) {
                    val u = m.groupValues[1]
                    if (keyedBadRe.containsMatchIn(u) || junkUrl(u) || subDirRe.containsMatchIn(u) || u.contains(".js/")) continue
                    urls.add(u)
                }
            }
        }
        return FmCand(urls.toList(), strict)
    }
}
