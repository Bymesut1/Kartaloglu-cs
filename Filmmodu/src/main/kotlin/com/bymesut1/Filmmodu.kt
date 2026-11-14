package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Filmmodu : MainAPI() {
    override var mainUrl = "https://filmmodu.cc"
    override var name = "Filmmodu"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/filmizle/aksiyon-filmleri-izle" to "Aksiyon Filmleri",
        "$mainUrl/filmizle/bilim-kurgu-filmleri-izle" to "Bilim Kurgu Filmleri",
        "$mainUrl/filmizle/dram-filmleri-izle" to "Dram Filmleri",
        "$mainUrl/filmizle/korku-filmleri-izle" to "Korku Filmleri",
        "$mainUrl/filmizle/komedi-filmleri-hd-izle" to "Komedi Filmleri",
        "$mainUrl/filmizle/yerli-film-izle" to "Yerli Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val targetUrl = if (page <= 1) baseUrl else "$baseUrl/$page"
        val document = app.get(targetUrl).document
        val items = parseMovieBoxes(document)
        val hasNext = document.selectFirst("a[href*='/${page + 1}'], a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun parseMovieBoxes(document: Document): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<SearchResponse>()

        document.select("article.movie_box").forEach { box ->
            val item = box.toSearchResult() ?: return@forEach
            if (seen.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a.image[href], a[href]") ?: return null
        val url = fixUrlNull(linkEl.attr("href")) ?: return null
        if (!url.contains("/film/") && !url.contains("/dizi/")) return null

        val title = (selectFirst("h2, .kart-ad")?.text()
            ?: linkEl.attr("title").replace(Regex("""\s*izle.*""", RegexOption.IGNORE_CASE), "")
            ).trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val year = selectFirst(".year")?.text()?.trim()?.toIntOrNull()

        return if (url.contains("/dizi/")) {
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

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val homeResp = app.get(mainUrl)
        val csrf = homeResp.document.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()

        val searchResp = app.post(
            "$mainUrl/arama/oneri",
            headers = mapOf(
                "X-CSRF-TOKEN" to csrf,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json",
                "Referer" to "$mainUrl/"
            ),
            cookies = homeResp.cookies,
            data = mapOf("q" to trimmed)
        ).text

        val parsed = runCatching { JSONObject(searchResp) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        val seen = LinkedHashSet<String>()

        val filmsArr = parsed.optJSONArray("films")
        if (filmsArr != null) {
            for (i in 0 until filmsArr.length()) {
                val item = filmsArr.optJSONObject(i) ?: continue
                val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
                val url = fixUrlNull(item.optString("url")) ?: continue
                if (seen.add(url)) {
                    results.add(
                        newMovieSearchResponse(title, url, TvType.Movie) {
                            this.posterUrl = fixUrlNull(item.optString("poster"))
                            this.year = item.optInt("year").takeIf { it > 0 }
                        }
                    )
                }
            }
        }

        val seriesArr = parsed.optJSONArray("series")
        if (seriesArr != null) {
            for (i in 0 until seriesArr.length()) {
                val item = seriesArr.optJSONObject(i) ?: continue
                val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
                val url = fixUrlNull(item.optString("url")) ?: continue
                if (seen.add(url)) {
                    results.add(
                        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                            this.posterUrl = fixUrlNull(item.optString("poster"))
                            this.year = item.optInt("year").takeIf { it > 0 }
                        }
                    )
                }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.replace(Regex("""/dizi/([^/?#]+)/sezon-\d+/bolum-\d+.*"""), "/dizi/$1")
        val document = app.get(cleanUrl).document

        val ldJson = document.select("script[type=application/ld+json]")
            .map { it.data() }
            .joinToString("\n")

        val title = Regex(""""name"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(ldJson)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" (")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("link[rel=image_src]")?.attr("href")
        )

        val description = Regex(""""description"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(ldJson)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex(""""dateCreated"\s*:\s*"(\d{4})""")
            .find(ldJson)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = document.select("a[href*='/filmizle/'], a[href*='/tur/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() && !t.contains("Filmleri", ignoreCase = true) }
        }.ifEmpty {
            Regex(""""genre"\s*:\s*\[(.*?)]""").find(ldJson)?.groupValues?.getOrNull(1)
                ?.let { raw ->
                    Regex(""""([^"]+)"""").findAll(raw).map { it.groupValues[1] }.toList()
                }.orEmpty()
        }.distinct()

        val actors = Regex(""""@type"\s*:\s*"Person"\s*,\s*"name"\s*:\s*"([^"]+)"""")
            .findAll(ldJson)
            .map { Actor(it.groupValues[1]) }
            .distinctBy { it.name }
            .toList()

        val recommendations = parseMovieBoxes(document).filter { it.url != cleanUrl }

        val isSeries = cleanUrl.contains("/dizi/")

        // TMDB: yalnızca içeriğin detay sayfasında (tıklayınca) çalışır; liste/arama aynen kalır
        val searchTitle = title
            .replace(Regex("""\s*izle.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()
        val tmdbId = tmdbFind(isSeries, searchTitle, year)
        val tmdb = tmdbId?.let { tmdbDetails(isSeries, it) }

        val finalPoster = tmdb?.poster ?: poster
        val finalPlot = tmdb?.plot ?: description
        val finalYear = year ?: tmdb?.year
        val finalTags = tmdb?.genres?.takeIf { it.isNotEmpty() } ?: tags
        val finalActors = tmdb?.actors?.takeIf { it.isNotEmpty() } ?: actors

        if (isSeries) {
            val siteEps = mutableListOf<SiteEp>()
            val epRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")

            document.select("a.dz-bolum[href]").forEach { epLink ->
                val epUrl = fixUrlNull(epLink.attr("href")) ?: return@forEach
                val match = epRegex.find(epUrl)
                val sNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val eNum = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                val epLabel = epLink.selectFirst(".dz-ad")?.text()?.trim()
                val epSubTitle = epLink.attr("title").trim().takeIf { it.isNotBlank() }
                val fullEpName = when {
                    epLabel != null && epSubTitle != null -> "$epLabel - $epSubTitle"
                    epLabel != null -> epLabel
                    epSubTitle != null -> epSubTitle
                    else -> "$sNum. Sezon ${eNum ?: 1}. Bölüm"
                }
                siteEps.add(SiteEp(epUrl, sNum, eNum, fullEpName))
            }

            // Sitedeki sezonlar için TMDB bölüm bilgilerini çek
            val tmdbSeasons = HashMap<Int, Map<Int, JSONObject>>()
            if (tmdbId != null) {
                for (s in siteEps.map { it.season }.distinct()) {
                    tmdbSeasons[s] = tmdbSeason(tmdbId, s)
                }
            }

            val episodes = siteEps.map { se ->
                val te = se.episode?.let { tmdbSeasons[se.season]?.get(it) }
                newEpisode(se.url) {
                    this.name = te?.str("name") ?: se.name
                    this.season = se.season
                    this.episode = se.episode
                    if (te != null) {
                        this.posterUrl = te.str("still_path")?.let { tmdbStill + it }
                        this.description = te.str("overview")
                        te.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let {
                            this.score = Score.from10(it)
                        }
                        te.optInt("runtime", 0).takeIf { it > 0 }?.let { this.runTime = it }
                        addDate(te.str("air_date"))
                    }
                }
            }

            return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = tmdb?.backdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags
                this.recommendations = recommendations
                tmdb?.rating?.let { this.score = Score.from10(it) }
                tmdb?.runtime?.let { this.duration = it }
                if (finalActors.isNotEmpty()) addActors(finalActors)
                tmdb?.trailer?.let { addTrailer(it) }
            }
        }

        return newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = tmdb?.backdrop
            this.plot = finalPlot
            this.year = finalYear
            this.tags = finalTags
            this.recommendations = recommendations
            tmdb?.rating?.let { this.score = Score.from10(it) }
            tmdb?.runtime?.let { this.duration = it }
            if (finalActors.isNotEmpty()) addActors(finalActors)
            tmdb?.trailer?.let { addTrailer(it) }
        }
    }

    // ---------------- TMDB ----------------

    // TMDB API anahtarını buraya yaz (themoviedb.org > Ayarlar > API). Boş/yer tutucu kalırsa TMDB atlanır, site bilgileri kullanılır.
    private val tmdbApiKey = "BURAYA_TMDB_API_KEY"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbPoster = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdrop = "https://image.tmdb.org/t/p/original"
    private val tmdbStill = "https://image.tmdb.org/t/p/w300"
    private val tmdbProfile = "https://image.tmdb.org/t/p/w185"

    private data class SiteEp(val url: String, val season: Int, val episode: Int?, val name: String)

    private data class TmdbInfo(
        val plot: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val genres: List<String>,
        val rating: Double?,
        val runtime: Int?,
        val actors: List<Actor>,
        val trailer: String?
    )

    // JSON null / boş değerleri null döndürür ("null" yazısı çıkmasın)
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }

    private suspend fun tmdbJson(path: String, vararg params: Pair<String, String>): JSONObject? {
        if (tmdbApiKey.isBlank() || tmdbApiKey.startsWith("BURAYA")) return null
        return try {
            val q = (listOf("api_key" to tmdbApiKey) + params)
                .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
            JSONObject(app.get("$tmdbApi/$path?$q", timeout = 20L).text)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tmdbFind(isTv: Boolean, title: String, year: Int?): Int? {
        if (title.isBlank()) return null
        val kind = if (isTv) "tv" else "movie"
        val yearKey = if (isTv) "first_air_date_year" else "year"
        val attempts: List<Int?> = if (year != null) listOf(year, null) else listOf(null)
        for (y in attempts) {
            val params = mutableListOf(
                "query" to title,
                "language" to "tr-TR",
                "include_adult" to "false"
            )
            if (y != null) params.add(yearKey to y.toString())
            val results = tmdbJson("search/$kind", *params.toTypedArray())?.optJSONArray("results") ?: continue
            if (results.length() > 0) {
                val id = results.optJSONObject(0)?.optInt("id", 0) ?: 0
                if (id > 0) return id
            }
        }
        return null
    }

    private suspend fun tmdbDetails(isTv: Boolean, id: Int): TmdbInfo? {
        val kind = if (isTv) "tv" else "movie"
        val d = tmdbJson(
            "$kind/$id",
            "language" to "tr-TR",
            "append_to_response" to "credits,videos",
            "include_video_language" to "tr,en,null"
        ) ?: return null

        // Türkçe özet yoksa İngilizce'ye düş
        val plot = d.str("overview")
            ?: tmdbJson("$kind/$id", "language" to "en-US")?.str("overview")

        val genres = mutableListOf<String>()
        d.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.str("name")?.let { genres.add(it) }
        }

        val actors = mutableListOf<Actor>()
        d.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 20)) {
                val c = arr.optJSONObject(i) ?: continue
                val n = c.str("name") ?: continue
                actors.add(Actor(n, c.str("profile_path")?.let { tmdbProfile + it }))
            }
        }

        var trailer: String? = null
        d.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                if (v.str("site") == "YouTube" && v.str("type") == "Trailer") {
                    trailer = v.str("key")?.let { "https://www.youtube.com/watch?v=$it" }
                    if (trailer != null) break
                }
            }
        }

        val dateStr = d.str(if (isTv) "first_air_date" else "release_date")
        val runtime = if (isTv) d.optJSONArray("episode_run_time")?.optInt(0, 0) else d.optInt("runtime", 0)

        return TmdbInfo(
            plot = plot,
            poster = d.str("poster_path")?.let { tmdbPoster + it },
            backdrop = d.str("backdrop_path")?.let { tmdbBackdrop + it },
            year = dateStr?.take(4)?.toIntOrNull(),
            genres = genres,
            rating = d.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            runtime = runtime?.takeIf { it > 0 },
            actors = actors,
            trailer = trailer
        )
    }

    // Bir sezonun bölümleri: bölüm numarası -> TMDB bölüm JSON'u (Türkçe, eksikse İngilizce ile tamamlanır)
    private suspend fun tmdbSeason(id: Int, season: Int): Map<Int, JSONObject> {
        val arr = tmdbJson("tv/$id/season/$season", "language" to "tr-TR")?.optJSONArray("episodes")
            ?: return emptyMap()
        val map = LinkedHashMap<Int, JSONObject>()
        var needFallback = false
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val n = e.optInt("episode_number", -1)
            if (n < 0) continue
            map[n] = e
            if (e.str("overview") == null || e.str("name") == null) needFallback = true
        }
        if (needFallback) {
            val en = tmdbJson("tv/$id/season/$season", "language" to "en-US")?.optJSONArray("episodes")
            if (en != null) {
                for (i in 0 until en.length()) {
                    val ee = en.optJSONObject(i) ?: continue
                    val target = map[ee.optInt("episode_number", -1)] ?: continue
                    if (target.str("overview") == null) ee.str("overview")?.let { target.put("overview", it) }
                    if (target.str("name") == null) ee.str("name")?.let { target.put("name", it) }
                }
            }
        }
        return map
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResp = app.get(data)
        val document = pageResp.document
        val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
        val dataKRaw = document.selectFirst("#f_player[data-k]")?.attr("data-k").orEmpty()
        if (dataKRaw.isBlank()) return false

        val sourcesArr = runCatching { JSONArray(dataKRaw) }.getOrNull() ?: return false
        var foundAny = false

        for (idx in 0 until sourcesArr.length()) {
            val src = sourcesArr.optJSONObject(idx) ?: continue
            val idVal = src.optInt("i", -1).takeIf { it >= 0 } ?: continue
            val sigVal = src.optString("s").takeIf { it.isNotBlank() } ?: continue
            if (src.optString("y").isNotBlank()) continue // Skip YouTube trailers

            try {
                val pxText = app.post(
                    "$mainUrl/px",
                    headers = mapOf(
                        "X-CSRF-TOKEN" to csrf,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json",
                        "Referer" to data
                    ),
                    cookies = pageResp.cookies,
                    data = mapOf(
                        "i" to idVal.toString(),
                        "s" to sigVal
                    )
                ).text

                val embedUrl = runCatching { JSONObject(pxText).optString("u") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: continue
                if (extractEmbedStream(embedUrl, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (_: Exception) {
            }
        }

        return foundAny
    }

    private suspend fun extractEmbedStream(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val hostOrigin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: "https://vidmixi.com"

        try {
            val embedHtml = app.get(embedUrl, referer = "$mainUrl/").text
            val beMatch = Regex(
                """bePlayer\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{.*?\})['"]\s*\)""",
                RegexOption.DOT_MATCHES_ALL
            ).find(embedHtml)

            if (beMatch != null) {
                val pass = beMatch.groupValues[1]
                val cipherJson = beMatch.groupValues[2]
                val decryptedJson = decryptCryptoJsAes(pass, cipherJson)
                if (!decryptedJson.isNullOrBlank()) {
                    val beData = runCatching { JSONObject(decryptedJson) }.getOrNull()
                    val videoLoc = beData?.optString("video_location")?.takeIf { it.isNotBlank() }
                    if (!videoLoc.isNullOrBlank()) {
                        val subsArr = beData.optJSONArray("strSubtitles")
                        if (subsArr != null) {
                            for (i in 0 until subsArr.length()) {
                                val sub = subsArr.optJSONObject(i) ?: continue
                                val rawSubFile = sub.optString("file").trim().takeIf { it.isNotBlank() } ?: continue
                                val subUrl = if (rawSubFile.startsWith("http")) rawSubFile else "$hostOrigin$rawSubFile"
                                val label = sub.optString("label").takeIf { it.isNotBlank() }
                                    ?: sub.optString("language").takeIf { it.isNotBlank() }
                                    ?: "Türkçe"
                                subtitleCallback.invoke(SubtitleFile(label, subUrl))
                            }
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = "$name (Bepeak)",
                                name = "$name (Bepeak)",
                                url = videoLoc,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$hostOrigin/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) {
                found = true
            }
        } catch (_: Exception) {
        }

        return found
    }

    private fun decryptCryptoJsAes(passphrase: String, jsonPayload: String): String? {
        return try {
            val cipherObj = runCatching { JSONObject(jsonPayload) }.getOrNull() ?: return null
            val ctBase64 = cipherObj.optString("ct").takeIf { it.isNotBlank() } ?: return null
            val ivHex = cipherObj.optString("iv").takeIf { it.isNotBlank() } ?: return null
            val saltHex = cipherObj.optString("s").takeIf { it.isNotBlank() } ?: return null

            val ctBytes = Base64.decode(ctBase64, Base64.DEFAULT)
            val ivBytes = hexToBytes(ivHex)
            val saltBytes = hexToBytes(saltHex)
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)

            // OpenSSL EVP_BytesToKey with MD5 (32-byte key for AES-256)
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(passBytes)
            md5.update(saltBytes)
            val d1 = md5.digest()

            md5.reset()
            md5.update(d1)
            md5.update(passBytes)
            md5.update(saltBytes)
            val d2 = md5.digest()

            val keyBytes = ByteArray(32)
            System.arraycopy(d1, 0, keyBytes, 0, 16)
            System.arraycopy(d2, 0, keyBytes, 16, 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )
            String(cipher.doFinal(ctBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}
