package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs
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

    // Aynı adı taşıyan farklı yapımlar yüzünden TMDB'nin yanlış diziyi seçtiği içerikler için elle düzeltme.
    // anahtar: sitedeki dizi slug'ı. Değer: TMDB'de aranacak ad, yıl ve orijinal dil.
    private data class TmdbOverride(val query: String, val year: Int?, val lang: String?)

    private val tmdbOverrides = mapOf(
        // Gizli Bahçe = Kore dizisi "Secret Garden" (2010), 2024 Türk dizisi değil
        "gizli-bahce" to TmdbOverride("Secret Garden", 2010, "ko")
    )

    // Bu içeriklerde sadece dosyaload kaynağı kullanılır (vidmixi yedeği atlanır)
    private val dosyaloadOnlySlugs = setOf("gizli-bahce")

    private fun slugOf(url: String): String =
        Regex("""/dizi/([^/?#]+)""").find(url)?.groupValues?.getOrNull(1).orEmpty()

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
        var searchTitle = title
            .replace(Regex("""\s*izle.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()
        var searchYear = year
        var langHint: String? = null

        val override = if (isSeries) tmdbOverrides[slugOf(cleanUrl)] else null
        if (override != null) {
            searchTitle = override.query
            searchYear = override.year
            langHint = override.lang
        }

        val siteEpCount = if (isSeries) document.select("a.dz-bolum[href]").size else 0
        val tmdbId = tmdbFind(
            isSeries, searchTitle, searchYear, description,
            actors.map { it.name }, siteEpCount, langHint, trustYear = override != null || !isSeries
        )
        val tmdb = tmdbId?.let { tmdbDetails(isSeries, it) }
        Log.d("FilmmoduDBG", "load $cleanUrl searchTitle=$searchTitle year=$searchYear lang=$langHint -> tmdbId=$tmdbId")

        val finalPoster = tmdb?.poster ?: poster
        val finalPlot = tmdb?.plot ?: description
        val finalYear = if (override != null) (tmdb?.year ?: searchYear) else (year ?: tmdb?.year)
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
    private val tmdbApiKey = "000316508321ce461cf81e7c6815eec7"
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

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace('ı', 'i')
            .lowercase()
            .trim()

    private fun words(s: String?): Set<String> =
        if (s.isNullOrBlank()) emptySet()
        else norm(s).split(Regex("""[^a-z0-9]+""")).filter { it.length > 3 }.toSet()

    // Aynı isimde birden çok yapım olabilir (ör. "Gizli Bahçe": Kore dizisi, Türk dizisi, film).
    // Adayları sitedeki bilgilere göre puanlıyoruz: yıl, dil, oyuncu eşleşmesi, özet benzerliği, bölüm sayısı.
    // Her zaman puanlama yapılır (tek aday bile olsa yıl filtresi yanlış yapımı getirmiş olabilir).
    private suspend fun tmdbFind(
        isTv: Boolean,
        title: String,
        year: Int?,
        siteDesc: String?,
        siteActors: List<String>,
        siteEpCount: Int,
        langHint: String?,
        trustYear: Boolean
    ): Int? {
        if (title.isBlank()) return null
        val kind = if (isTv) "tv" else "movie"
        val yearKey = if (isTv) "first_air_date_year" else "year"
        // Dizilerde sitedeki yıl güvenilir olmayabilir; yıllı ve yılsız aramaları birlikte topla
        val attempts: List<Int?> = if (year != null) listOf(year, null) else listOf(null)

        val ids = LinkedHashSet<Int>()
        for (y in attempts) {
            val params = mutableListOf(
                "query" to title,
                "language" to "tr-TR",
                "include_adult" to "false"
            )
            if (y != null) params.add(yearKey to y.toString())
            val results = tmdbJson("search/$kind", *params.toTypedArray())?.optJSONArray("results") ?: continue
            for (i in 0 until minOf(results.length(), 6)) {
                val id = results.optJSONObject(i)?.optInt("id", 0) ?: 0
                if (id > 0) ids.add(id)
            }
        }
        if (ids.isEmpty()) return null

        val siteWords = words(siteDesc)
        val siteActorSet = siteActors.map { norm(it) }.toSet()
        var bestId: Int? = null
        var best = -1000.0
        var rank = 0

        for (id in ids.take(8)) {
            val d = tmdbJson("$kind/$id", "language" to "tr-TR", "append_to_response" to "credits") ?: continue
            var score = -(rank * 0.1)
            rank++

            val cy = d.str(if (isTv) "first_air_date" else "release_date")?.take(4)?.toIntOrNull()
            if (year != null && cy != null) {
                val diff = abs(year - cy)
                val w = if (trustYear) 1.0 else 0.3
                if (diff == 0) score += 3.0 * w else if (diff == 1) score += 1.0 * w
            }

            // Orijinal dil ipucu (ör. Kore dizisi için "ko")
            if (langHint != null) {
                if (d.str("original_language") == langHint) score += 12.0 else score -= 6.0
            }

            val cast = d.optJSONObject("credits")?.optJSONArray("cast")
            if (cast != null && siteActorSet.isNotEmpty()) {
                var hit = 0
                for (i in 0 until minOf(cast.length(), 30)) {
                    val n = cast.optJSONObject(i)?.str("name")?.let { norm(it) } ?: continue
                    if (n in siteActorSet) hit++
                }
                score += minOf(hit, 5) * 2.0
            }

            val cw = words(d.str("overview"))
            if (siteWords.isNotEmpty() && cw.isNotEmpty()) {
                val inter = siteWords.intersect(cw).size.toDouble()
                val uni = siteWords.union(cw).size.toDouble()
                score += inter / uni * 10.0
            }

            if (isTv && siteEpCount > 0) {
                val ne = d.optInt("number_of_episodes", 0)
                if (ne == siteEpCount) score += 5.0 else if (ne > 0 && abs(ne - siteEpCount) <= 2) score += 1.5
            }

            Log.d("FilmmoduDBG", "tmdb cand $kind/$id '${d.str(if (isTv) "name" else "title")}' year=$cy lang=${d.str("original_language")} score=$score")
            if (score > best) {
                best = score
                bestId = id
            }
        }
        return bestId ?: ids.first()
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

        val dosyaloadOnly = slugOf(data) in dosyaloadOnlySlugs

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

                if (dosyaloadOnly && !embedUrl.contains("dosyaload", ignoreCase = true)) {
                    Log.d("FilmmoduDBG", "skip non-dosyaload embed (dosyaload-only): $embedUrl")
                    continue
                }

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
            val embedResp = app.get(embedUrl, referer = "$mainUrl/")
            val embedHtml = embedResp.text
            val embedCookies = embedResp.cookies
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
                                // thumbs.vtt altyazı değil, oynatıcı önizleme dosyası
                                if (rawSubFile.contains("thumbs", ignoreCase = true)) continue
                                val subUrl = if (rawSubFile.startsWith("http")) rawSubFile else "$hostOrigin$rawSubFile"
                                val label = sub.optString("label").takeIf { it.isNotBlank() }
                                    ?: sub.optString("language").takeIf { it.isNotBlank() }
                                    ?: "Türkçe"
                                subtitleCallback.invoke(SubtitleFile(label, subUrl))
                            }
                        }

                        // Teşhis: çözülen JSON'u kısa parçalar halinde logla (uzun satırlar logcat'te kayboluyor)
                        logChunks("beData", embedUrl + " " + dumpJson(beData))
                        val mediaRefs = Regex("""[^"'\s\\]*(?:m3u8|\.mp4|/list/|/hls/|playlist)[^"'\s\\]*""")
                            .findAll(embedHtml).map { it.value }.distinct().take(20).toList()
                        mediaRefs.forEach { Log.d("FilmmoduDBG", "embedHtml media ref: $it") }
                        val bpIdx = embedHtml.indexOf("bePlayer")
                        if (bpIdx >= 0) {
                            Log.d(
                                "FilmmoduDBG",
                                "embedHtml near bePlayer: " +
                                    embedHtml.substring(maxOf(0, bpIdx - 150), minOf(embedHtml.length, bpIdx + 150)).replace("\n", " ")
                            )
                        }

                        emitPlaylist(
                            listUrl = videoLoc,
                            label = "$name (Bepeak)",
                            referer = "$hostOrigin/",
                            embedUrl = embedUrl,
                            cookies = embedCookies,
                            extraCandidates = mediaRefs.filter { it.startsWith("http") && it != videoLoc },
                            callback = callback
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

    private fun logChunks(tag: String, text: String) {
        val clean = text.replace("\n", " ")
        var i = 0
        var n = 0
        while (i < clean.length && n < 6) {
            Log.d("FilmmoduDBG", "$tag[$n] " + clean.substring(i, minOf(clean.length, i + 700)))
            i += 700
            n++
        }
    }

    // Vidmixi master list'indeki BANDWIDTH değeri Int sınırını aşabiliyor ve hem CloudStream'in M3u8Helper'ı
    // hem ExoPlayer NumberFormatException ile çöküyor. Master list'i burada kendimiz indirip, her kaliteyi
    // ayrı medya playlist linki olarak veriyoruz.
    // Logda görüldüğü gibi dosyaload list'i 404, vidmixi ise 200 ama boş gövde döndürüyor. Bu yüzden
    // referer / cookie / header kombinasyonlarının yanında dosyaload <-> vidmixi host değişimini de deniyoruz
    // ve her denemenin durum kodu, uzunluğu ve başlıklarını "FilmmoduDBG" etiketiyle logluyoruz.
    private suspend fun emitPlaylist(
        listUrl: String,
        label: String,
        referer: String,
        embedUrl: String,
        cookies: Map<String, String>,
        extraCandidates: List<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val referers = listOf(referer, embedUrl, "$mainUrl/").distinct()
        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        // Aday adresler: orijinal, diğer host'taki aynı yol, embed sayfasında geçen medya adresleri
        val candidates = LinkedHashSet<String>()
        candidates.add(listUrl)
        when {
            listUrl.contains("://dosyaload.com") -> candidates.add(listUrl.replace("://dosyaload.com", "://vidmixi.com"))
            listUrl.contains("://vidmixi.com") -> candidates.add(listUrl.replace("://vidmixi.com", "://dosyaload.com"))
        }
        candidates.addAll(extraCandidates)

        var body: String? = null
        var usedUrl = listUrl
        var usedReferer = referer
        var usedCookies = false

        loop@ for (cand in candidates) {
            val origin = Regex("""^(https?://[^/]+)""").find(cand)?.groupValues?.getOrNull(1).orEmpty()
            val headerSets = listOf(
                mapOf("Origin" to origin, "Accept" to "*/*"),
                mapOf(
                    "Origin" to origin,
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                mapOf(
                    "Accept" to "application/vnd.apple.mpegurl,application/x-mpegURL,*/*",
                    "Range" to "bytes=0-"
                )
            )
            for (ref in referers) {
                for (withCookies in listOf(true, false)) {
                    if (withCookies && cookies.isEmpty()) continue
                    for ((hi, hdr) in headerSets.withIndex()) {
                        try {
                            val r = app.get(
                                cand,
                                referer = ref,
                                headers = hdr,
                                cookies = if (withCookies) cookies else emptyMap()
                            )
                            val t = r.text
                            Log.d(
                                "FilmmoduDBG",
                                "list GET $cand ref=$ref cookies=$withCookies hdr=$hi code=${r.code} len=${t.length} " +
                                    "ctype=${r.headers["content-type"]} clen=${r.headers["content-length"]} head=" +
                                    t.take(200).replace("\n", " ")
                            )
                            if (r.code in 200..299 && t.trimStart().startsWith("#EXTM3U")) {
                                body = t
                                usedUrl = cand
                                usedReferer = ref
                                usedCookies = withCookies
                                break@loop
                            }
                            // 404 yol seviyesinde bir sorun: aynı referer için diğer header setlerini boşuna deneme
                            if (r.code == 404) break
                        } catch (e: Exception) {
                            Log.d("FilmmoduDBG", "list GET failed $cand ref=$ref: ${e.message}")
                        }
                    }
                }
            }
        }

        // Liste isteği app.get'in UA'sı + Origin ile geçti; ExoPlayer bunları göndermediği için alt playlist 404 olabilir.
        // Aynı header'ları oynatıcıya da veriyoruz.
        val usedOrigin = Regex("""^(https?://[^/]+)""").find(usedUrl)?.groupValues?.getOrNull(1).orEmpty()
        val linkHeaders = buildMap<String, String> {
            if (usedCookies && cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
            if (usedOrigin.isNotBlank()) put("Origin", usedOrigin)
            put("User-Agent", USER_AGENT)
            put("Accept", "*/*")
        }

        var added = 0
        if (body != null && body.contains("#EXT-X-STREAM-INF")) {
            val lines = body.lines().map { it.trim() }
            val seen = HashSet<String>()
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val uri = (i + 1 until lines.size)
                    .map { lines[it] }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                val full = resolveHlsUrl(usedUrl, uri)
                if (!seen.add(full)) continue
                Log.d("FilmmoduDBG", "variant uri raw=" + uri.take(300))
                probeVariant(full, usedReferer, linkHeaders, cookies)

                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                val variantLabel = if (height != null) "$label ${height}p" else label

                callback.invoke(
                    newExtractorLink(
                        source = variantLabel,
                        name = variantLabel,
                        url = full,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = usedReferer
                        this.headers = linkHeaders
                        this.quality = height ?: Qualities.P1080.value
                    }
                )
                added++
            }
        } else if (body != null) {
            // Zaten medya playlist (BANDWIDTH yok): olduğu gibi ver, ama doğru referer/cookie ile
            callback.invoke(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = usedUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = usedReferer
                    this.headers = linkHeaders
                    this.quality = Qualities.P1080.value
                }
            )
            added++
        }

        if (added == 0) {
            Log.d("FilmmoduDBG", "no playable list found for $listUrl, emitting raw link")
            callback.invoke(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = listUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    // Teşhis: alt playlist'i app.get ile farklı header kombinasyonlarıyla dene, sonucu ve ilk parçayı logla
    private suspend fun probeVariant(
        url: String,
        referer: String,
        linkHeaders: Map<String, String>,
        cookies: Map<String, String>
    ) {
        val noOrigin = linkHeaders - "Origin"
        val sets = listOf(
            "full" to Pair(linkHeaders, cookies),
            "noOrigin" to Pair(noOrigin, cookies),
            "noCookie" to Pair(linkHeaders - "Cookie", emptyMap<String, String>())
        )
        for ((tag, pair) in sets) {
            try {
                val r = app.get(url, referer = referer, headers = pair.first - "Cookie", cookies = pair.second)
                val t = r.text
                Log.d(
                    "FilmmoduDBG",
                    "variant[$tag] $url code=${r.code} len=${t.length} ctype=${r.headers["content-type"]} head=" +
                        t.take(300).replace("\n", " ")
                )
                if (r.code in 200..299 && t.trimStart().startsWith("#EXTM3U")) {
                    val seg = t.lines().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (seg != null) {
                        val segUrl = resolveHlsUrl(url, seg)
                        val sr = app.get(
                            segUrl, referer = referer,
                            headers = pair.first - "Cookie" + mapOf("Range" to "bytes=0-1"),
                            cookies = pair.second
                        )
                        Log.d("FilmmoduDBG", "segment[$tag] ${segUrl.take(200)} code=${sr.code}")
                    }
                    return
                }
            } catch (e: Exception) {
                Log.d("FilmmoduDBG", "variant[$tag] failed: ${e.message}")
            }
        }
    }

    private fun dumpJson(o: JSONObject?): String {
        if (o == null) return "null"
        val sb = StringBuilder("{")
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.opt(k)?.toString().orEmpty().replace("\n", " ")
            sb.append(k).append("=").append(if (v.length > 220) v.take(220) + "…" else v).append(" | ")
        }
        return sb.append("}").toString()
    }

    private fun resolveHlsUrl(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        return try {
            URI(base).resolve(uri).toString()
        } catch (_: Exception) {
            val origin = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.getOrNull(1).orEmpty()
            when {
                uri.startsWith("//") -> "https:$uri"
                uri.startsWith("/") -> "$origin$uri"
                else -> base.substringBeforeLast('/') + "/" + uri
            }
        }
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
