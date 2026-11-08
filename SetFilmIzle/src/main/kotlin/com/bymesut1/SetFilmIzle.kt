@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Element
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

// =====================================================================================
//  SetFilmIzle (film + dizi)  -  filmler: FastPlay, diziler: SetPlay (-> FastPlay / manifest)
// =====================================================================================
class SetFilmIzle : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // Kataloglar: sitenin kendi turleri
    override val mainPage = mainPageOf(
        "${mainUrl}/film/" to "Filmler",
        "${mainUrl}/dizi/" to "Diziler",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/" to "Biyografi",
        "${mainUrl}/tur/dini/" to "Dini",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/genclik/" to "Gençlik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/gizem/" to "Gizem",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/mini-dizi/" to "Mini Dizi",
        "${mainUrl}/tur/muzik/" to "Müzik",
        "${mainUrl}/tur/program/" to "Program",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/spor/" to "Spor",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih",
        "${mainUrl}/tur/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        val items = document.select("div.fgrid a.card-link")
            .mapNotNull { toMainPageResult(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun imgUrlOf(img: Element?): String? {
        if (img == null) return null
        for (a in listOf("data-src", "data-lazy-src", "src")) {
            val v = img.attr(a)
            if (v.startsWith("http")) return v
        }
        return null
    }

    private val reYear = Regex("""(19|20)\d{2}""")

    private fun toMainPageResult(el: Element): SearchResponse? {
        val href = fixUrlNull(el.attr("href")) ?: return null
        val title = el.selectFirst("div.card-info h2.card-ad")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = imgUrlOf(el.selectFirst("div.poster-art img"))
        val meta = el.selectFirst("div.card-info p.meta")?.text()?.trim().orEmpty()
        val year = reYear.find(meta)?.value?.toIntOrNull()
        val isSeries = href.contains("/dizi/") || meta.contains("Sezon")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // Canli aramadaki kucuk onizleme resimlerini buyuk surume cevirir (TMDB boyutu / WordPress -300x450 eki)
    private fun hiRes(u: String?): String? {
        if (u.isNullOrBlank()) return u
        var x = Regex("""(image\.tmdb\.org/t/p/)(?:w|h)\d+(/)""").replace(u) { it.groupValues[1] + "w500" + it.groupValues[2] }
        x = Regex("""-\d{2,4}x\d{2,4}(\.(?:jpe?g|png|webp))((?:\?.*)?)$""").replace(x) { it.groupValues[1] + it.groupValues[2] }
        return x
    }

    // ---------------- Arama: admin-ajax stf_live_search (JSON) ----------------
    private fun collectItems(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (node.has("title") && node.has("url")) out.add(node)
                else node.keys().forEach { collectItems(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectItems(node.opt(i), out)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val results = ArrayList<SearchResponse>()

        try {
            val text = app.get(
                "$mainUrl/wp-admin/admin-ajax.php?action=stf_live_search&keyword=${java.net.URLEncoder.encode(q, "UTF-8")}",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                referer = "$mainUrl/"
            ).text
            val items = ArrayList<JSONObject>()
            collectItems(JSONTokener(text).nextValue(), items)
            for (o in items) {
                val title = o.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
                val href = fixUrlNull(o.optString("url").trim()) ?: continue
                if (!href.contains("/film/") && !href.contains("/dizi/")) continue
                val img = hiRes(fixUrlNull(o.optString("img").trim().takeIf { it.isNotBlank() }))
                val year = reYear.find(o.optJSONObject("extra")?.optString("date").orEmpty())?.value?.toIntOrNull()
                if (href.contains("/dizi/")) {
                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = img; this.year = year })
                } else {
                    results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = img; this.year = year })
                }
            }
        } catch (e: Exception) {
            Log.e("SetFilmIzle", "Arama JSON hatasi: ${e.message}")
        }

        // Normal arama sayfasi: kartlardaki afisler ana sayfadakiyle ayni (net). Varsa onlar kullanilir; JSON'da olmayanlar eklenir.
        try {
            val doc = app.get("$mainUrl/?s=${java.net.URLEncoder.encode(q, "UTF-8")}", headers = browserHeaders).document
            val htmlItems = doc.select("div.fgrid a.card-link").mapNotNull { toMainPageResult(it) }.distinctBy { it.url }
            val byUrl = htmlItems.associateBy { it.url }
            for (r in results) {
                val hp = byUrl[r.url]?.posterUrl
                if (!hp.isNullOrBlank()) r.posterUrl = hp
            }
            val have = results.map { it.url }.toHashSet()
            for (h in htmlItems) if (h.url !in have) results.add(h)
        } catch (e: Exception) {
            Log.e("SetFilmIzle", "HTML arama hatasi: ${e.message}")
        }
        return newSearchResponseList(results.distinctBy { it.url }, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    // ---------------- Detay: site bilgisi + TMDB (yalnizca tiklaninca) ----------------
    private val reTitleJunk = Regex("""(?i)\b(izle|türkçe dublaj|dual)\b""")
    private val reTitleYear = Regex("""\s*\(\d{4}\)\s*$""")

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()
        val title = rawTitle.replace(reTitleYear, "").replace(reTitleJunk, "").replace(Regex("""\s{2,}"""), " ").trim()
        if (title.isBlank()) return null

        // --- site verisi (yedek) ---
        val ogImage: String? = document.selectFirst("meta[property=og:image]")?.attr("content")
        val sitePoster: String? = fixUrlNull(ogImage?.ifBlank { null } ?: imgUrlOf(document.selectFirst("div.poster-art img")))
        val descP: String? = document.select("div.fbox-desc p, div.wp-content p")
            .firstOrNull { it.text().isNotBlank() }?.text()?.trim()
        val metaDesc: String? = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val siteDesc: String? = descP?.ifBlank { null } ?: metaDesc?.ifBlank { null }

        var siteYear: Int? = null
        var siteTags: List<String> = emptyList()
        var siteDuration: Int? = null
        var siteRating: Int? = null
        for (sp in document.select("div.fbox-info span").map { it.text().trim() }) {
            when {
                sp.startsWith("Yıl") -> siteYear = reYear.find(sp)?.value?.toIntOrNull()
                sp.startsWith("Tür") -> siteTags = sp.substringAfter(":").split(",").map { it.trim() }.filter { it.isNotBlank() }
                sp.startsWith("Süre") -> siteDuration = Regex("""(\d+)""").find(sp)?.groupValues?.get(1)?.toIntOrNull()
                sp.startsWith("IMDb") -> siteRating = Regex("""(\d+(?:[.,]\d+)?)""").find(sp)?.groupValues?.get(1)
                    ?.replace(",", ".")?.toFloatOrNull()?.let { (it * 1000).toInt() }
            }
        }
        if (siteYear == null) siteYear = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val siteActors = LinkedHashMap<String, Actor>()
        for (a in document.select("a.fk-k[href*='/oyuncu/']")) {
            val n = a.selectFirst("span.fk-t b")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: continue
            if (!siteActors.containsKey(n)) siteActors[n] = Actor(n, imgUrlOf(a.selectFirst("img")))
        }

        val trailerId = document.selectFirst("button.fbtn-trailer")?.attr("data-trailer")?.trim().orEmpty()
        val trailer = if (trailerId.isNotBlank()) {
            if (trailerId.startsWith("http")) trailerId else "https://www.youtube.com/embed/$trailerId"
        } else null

        val recommendations = document.select("div.fgrid a.card-link")
            .mapNotNull { toMainPageResult(it) }
            .distinctBy { it.url }
            .filter { it.url != url }

        // --- bolumler ---
        val reSE = Regex("""-(\d+)-sezon-(\d+)-bolum""")
        val seen = HashSet<String>()
        val siteEpisodes = ArrayList<Episode>()
        for (el in document.select("a.fep, a[href*='/bolum/']")) {
            val href = fixUrlNull(el.attr("href")) ?: continue
            if (!seen.add(href)) continue
            val m = reSE.find(href)
            val season = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val ep = m?.groupValues?.get(2)?.toIntOrNull() ?: (siteEpisodes.size + 1)
            val epTitle = el.selectFirst("div.fep-title, .fep-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "Bölüm $ep"
            val epBody = el.selectFirst("div.fep-body")?.text()?.trim()?.takeIf { it.isNotBlank() }
            siteEpisodes.add(newEpisode(href) {
                this.name = epTitle
                this.season = season
                this.episode = ep
                this.description = epBody
            })
        }

        val isSeries = url.contains("/dizi/") || siteEpisodes.isNotEmpty()

        // ---------------- FILM ----------------
        if (!isSeries) {
            val tm = try { tmdbMatchMovie(title) } catch (e: Exception) { null }
            val tags = tm?.genres.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.ifEmpty { siteTags }
            val actors = tmdbActors(tm?.credits).ifEmpty { siteActors.values.toList() }
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = tm?.poster_path?.let { "$tmdbImg/w500$it" } ?: sitePoster
                this.backgroundPosterUrl = tm?.backdrop_path?.let { "$tmdbImg/w1280$it" }
                this.plot = tm?.overview?.takeIf { it.isNotBlank() } ?: siteDesc
                this.year = tm?.release_date?.take(4)?.toIntOrNull() ?: siteYear
                this.tags = tags
                this.rating = tm?.vote_average?.takeIf { it > 0.0 }?.let { (it * 1000).toInt() } ?: siteRating
                this.duration = tm?.runtime?.takeIf { it > 0 } ?: siteDuration
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
                addTrailer(trailer)
            }
        }

        // ---------------- DIZI ----------------
        val counts = siteEpisodes.groupBy { it.season ?: 0 }.mapValues { it.value.size }
        val tm = try { tmdbMatchTv(title, counts) } catch (e: Exception) { null }
        val episodes = try { tmdbBolumleri(tm, siteEpisodes) } catch (e: Exception) { siteEpisodes }
        val tags = tm?.genres.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.ifEmpty { siteTags }
        val actors = tmdbActors(tm?.credits).ifEmpty { siteActors.values.toList() }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = tm?.poster_path?.let { "$tmdbImg/w500$it" } ?: sitePoster
            this.backgroundPosterUrl = tm?.backdrop_path?.let { "$tmdbImg/w1280$it" }
            this.plot = tm?.overview?.takeIf { it.isNotBlank() } ?: siteDesc
            this.year = tm?.first_air_date?.take(4)?.toIntOrNull() ?: siteYear
            this.tags = tags
            this.rating = tm?.vote_average?.takeIf { it > 0.0 }?.let { (it * 1000).toInt() } ?: siteRating
            this.duration = siteDuration
            this.recommendations = recommendations
            if (actors.isNotEmpty()) addActors(actors)
            addTrailer(trailer)
        }
    }

    // ---------------- TMDB ----------------
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val reGenericEp = Regex("""(?iu)^\s*(bölüm|episode|ep\.?)\s*\d+\s*$""")

    private fun norm(t: String): String = t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private fun dateMs(s: String?): Long? = try {
        if (s.isNullOrBlank()) null else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    private fun tmdbActors(c: SfTmdbCredits?): List<Actor> =
        c?.cast.orEmpty().take(20).mapNotNull { a ->
            val n = a.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Actor(n, a.profile_path?.let { "$tmdbImg/w185$it" })
        }

    private suspend fun tmdbMatchMovie(title: String): SfTmdbMovieDetails? {
        val key = norm(title)
        if (key.isEmpty()) return null
        val results = try {
            app.get(
                "$tmdbApi/search/movie?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${java.net.URLEncoder.encode(title, "UTF-8")}",
                timeout = 15L
            ).parsedSafe<SfTmdbMovieSearch>()?.results.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        if (results.isEmpty()) return null
        val exact = results.filter {
            norm(it.title.orEmpty()) == key || norm(it.original_title.orEmpty()) == key
        }
        val pick = exact.maxByOrNull { it.popularity ?: 0.0 }
            ?: results.firstOrNull {
                val n = norm(it.title.orEmpty())
                n.isNotEmpty() && (n.contains(key) || key.contains(n))
            }
            ?: return null
        val id = pick.id ?: return null
        return try {
            app.get("$tmdbApi/movie/$id?api_key=$tmdbKey&language=tr-TR&append_to_response=credits", timeout = 15L)
                .parsedSafe<SfTmdbMovieDetails>()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tmdbSearchTv(q: String): List<SfTmdbTvResult> = try {
        app.get(
            "$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${java.net.URLEncoder.encode(q, "UTF-8")}",
            timeout = 15L
        ).parsedSafe<SfTmdbTvSearch>()?.results.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun tmdbTvDetails(id: Int): SfTmdbTvDetails? = try {
        app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=tr-TR&append_to_response=credits", timeout = 15L)
            .parsedSafe<SfTmdbTvDetails>()
    } catch (e: Exception) {
        null
    }

    private suspend fun tmdbMatchTv(title: String, siteCounts: Map<Int, Int>): SfTmdbTvDetails? {
        val key = norm(title)
        if (key.isEmpty()) return null
        val results = tmdbSearchTv(title)
        if (results.isEmpty()) return null
        val exact = results.filter {
            norm(it.name.orEmpty()) == key || norm(it.original_name.orEmpty()) == key
        }
        val cands = if (exact.isNotEmpty()) exact.take(3) else listOfNotNull(
            results.firstOrNull {
                val n = norm(it.name.orEmpty())
                n.isNotEmpty() && (n.contains(key) || key.contains(n))
            }
        )
        if (cands.isEmpty()) return null
        if (cands.size == 1) return cands[0].id?.let { tmdbTvDetails(it) }
        val details = cands.amap { c -> c.id?.let { tmdbTvDetails(it) } }.filterNotNull()
        if (details.isEmpty()) return null
        return details.minByOrNull { d ->
            val cnt = d.seasons.orEmpty().associate { (it.season_number ?: -1) to (it.episode_count ?: 0) }
            var score = 0
            for ((sn, c) in siteCounts) score += kotlin.math.abs(c - (cnt[sn] ?: 0))
            score * 1000 - (d.popularity ?: 0.0).toInt().coerceAtMost(999)
        }
    }

    private suspend fun tmdbSeason(id: Int, sn: Int, lang: String): Map<Int, SfTmdbEpisode> = try {
        app.get("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=$lang", timeout = 15L)
            .parsedSafe<SfTmdbSeasonDetails>()?.episodes.orEmpty()
            .mapNotNull { e -> e.episode_number?.let { it to e } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private suspend fun tmdbBolumleri(tm: SfTmdbTvDetails?, siteEps: List<Episode>): List<Episode> {
        if (tm == null || siteEps.isEmpty()) return siteEps
        val tid = tm.id ?: return siteEps
        val seasons = siteEps.map { it.season ?: 0 }.distinct().filter { it > 0 }
        val rows = seasons.amap { sn ->
            Triple(sn, tmdbSeason(tid, sn, "tr-TR"), tmdbSeason(tid, sn, "en-US"))
        }
        val trMap = rows.associate { it.first to it.second }
        val enMap = rows.associate { it.first to it.third }

        return siteEps.map { old ->
            val sn = old.season ?: 0
            val en = old.episode ?: 0
            val tr = trMap[sn]?.get(en)
            val us = enMap[sn]?.get(en)
            if (tr == null && us == null) return@map old
            val epName = tr?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: us?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: old.name
            val overview = tr?.overview?.takeIf { it.isNotBlank() } ?: us?.overview?.takeIf { it.isNotBlank() } ?: old.description
            val still = (tr?.still_path ?: us?.still_path)?.let { "$tmdbImg/w500$it" }
            val runtime = tr?.runtime ?: us?.runtime
            val airMs = dateMs(tr?.air_date ?: us?.air_date)
            newEpisode(old.data) {
                this.season = old.season
                this.episode = old.episode
                this.name = epName
                this.description = overview
                this.posterUrl = still
                if (runtime != null && runtime > 0) this.runTime = runtime
                if (airMs != null) this.date = airMs
            }
        }
    }

    // ---------------- Kaynaklar ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SetFilmIzle", "loadLinks: $data")
        val found = AtomicBoolean(false)
        val document = app.get(data, headers = browserHeaders).document
        val html = document.html()

        val postId = Regex("""data-post-id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""postid-(\d+)""").find(html)?.groupValues?.get(1)
        if (postId.isNullOrBlank()) {
            Log.e("SetFilmIzle", "post-id bulunamadi")
            return false
        }

        val nonce = Regex("""nonces:\s*\{[^}]*video:\s*"([a-f0-9]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""video:\s*"([a-f0-9]+)"""").find(html)?.groupValues?.get(1)
        if (nonce.isNullOrBlank()) {
            Log.e("SetFilmIzle", "video nonce bulunamadi")
            return false
        }

        val kaynaklar = document.select("div.fsrc-list button.fsrc, button.fsrc.src-tab").mapNotNull { b ->
            val ad = b.attr("data-player-name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ad to b.attr("data-part-key").trim()
        }.distinct().ifEmpty { listOf("SetPlay" to "") }

        kaynaklar.amap { (playerName, partKey) ->
            try {
                val text = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "get_video_url",
                        "nonce" to nonce,
                        "post_id" to postId,
                        "player_name" to playerName,
                        "part_key" to partKey
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = data
                ).text
                val streamUrl = JSONObject(text).optJSONObject("data")?.optJSONObject("stream")?.optString("url")?.trim().orEmpty()
                Log.d("SetFilmIzle", "postId=$postId | kaynak=$playerName -> ${streamUrl.take(120)}")
                if (streamUrl.isBlank()) {
                    if (SF_DEBUG) emitDebug(callback, "SetFilmIzle", listOf("$playerName: stream url bos, yanit=${text.take(200)}"))
                    return@amap
                }
                val full = fixUrl(streamUrl)
                val sink: (ExtractorLink) -> Unit = { l -> callback(l); found.set(true) }
                routeLink(full, "$mainUrl/", subtitleCallback, sink)
            } catch (e: Exception) {
                Log.e("SetFilmIzle", "$playerName hata: ${e.message}")
                if (SF_DEBUG) emitDebug(callback, "SetFilmIzle", listOf("$playerName hata: ${e.message}"))
            }
        }
        return found.get()
    }

    // Adrese gore dogru cozucuyu secer (alan adlari sik degistigi icin kayit adresine bakilmaz)
    private suspend fun routeLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("fastplay", true) -> FastPlay().getUrl(url, referer, subtitleCallback, callback)
            url.contains("setplay", true) && !url.contains("manifest.php", true) ->
                SetPlay().getUrl(url, referer, subtitleCallback, callback)
            url.contains("manifest.php", true) || url.contains(".m3u8", true) ->
                emitDirectM3u8(url, url, "SetPlay", callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}

// Hata ayiklama: kaynak cikmazsa sebebi kaynak listesinde "DEBUG" satirlari olarak gorunur. Sorun cozulunce false yap.
internal const val SF_DEBUG = false

internal suspend fun emitDebug(callback: (ExtractorLink) -> Unit, tag: String, notes: List<String>) {
    var i = 0
    for (n in notes.take(8)) {
        i++
        callback(
            newExtractorLink("DEBUG", "DEBUG $i [$tag] " + n.replace("\n", " ").take(300), "https://debug.invalid/$i", ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// Dogrudan m3u8 / manifest.php adresi
internal suspend fun emitDirectM3u8(
    manifestUrl: String,
    pageUrl: String,
    sourceName: String,
    callback: (ExtractorLink) -> Unit
) {
    val origin = Regex("""^(https?://[^/]+)""").find(pageUrl)?.groupValues?.get(1)
        ?: Regex("""^(https?://[^/]+)""").find(manifestUrl)?.groupValues?.get(1).orEmpty()
    callback(
        newExtractorLink(sourceName, sourceName, manifestUrl, ExtractorLinkType.M3U8) {
            this.referer = "$origin/"
            this.quality = Qualities.Unknown.value
            this.headers = mapOf("Referer" to "$origin/")
        }
    )
}

// =====================================================================================
//  FastPlay (filmler)
//  Sayfadan SPG_A (sp, spT) ve STF_KOPRU (src, altyazi) okunur; master + oynatma listeleri
//  X-Sp basligiyla indirilip yerel (127.0.0.1) sunucudan oynaticiya verilir.
// =====================================================================================
open class FastPlay : ExtractorApi() {
    override val name = "FastPlay"
    override val mainUrl = "https://fastplay.mom"
    override val requiresReferer = true

    private val alfabe36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    private fun base36(n: Long): String {
        if (n <= 0L) return "0"
        val sb = StringBuilder()
        var x = n
        while (x > 0L) {
            sb.append(alfabe36[(x % 36L).toInt()])
            x /= 36L
        }
        return sb.reverse().toString()
    }

    private fun fnv1a(s: String): String {
        var h = 0x811c9dc5L
        for (ch in s) {
            h = h xor ch.code.toLong()
            h = (h * 0x01000193L) and 0xffffffffL
        }
        return h.toString(16)
    }

    // X-Sp = "<spT>.<rastgele>.<fnv1a(sp|spT|rastgele)>"
    private fun xSp(sp: String, spT: Long): String {
        val r = base36(Random.nextLong(2176782336L)) // 36^6
        val h = fnv1a("$sp|$spT|$r")
        return "$spT.$r.$h"
    }

    private suspend fun tokenliCek(url: String, sp: String, spT: Long, origin: String): String? {
        for (deneme in 1..3) {
            try {
                val r = app.get(
                    url,
                    headers = mapOf("Accept" to "*/*", "X-Sp" to xSp(sp, spT)),
                    referer = "$origin/"
                )
                if (r.code == 200 && r.text.isNotBlank()) return r.text
                Log.e(name, "HTTP ${r.code}: ${r.text.take(120)}")
            } catch (e: Exception) {
                Log.e(name, "Istek hatasi ($deneme): ${e.message}")
            }
            try { Thread.sleep(400L * deneme) } catch (e: Exception) { }
        }
        return null
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http")) return rel
        return try {
            java.net.URI(base).resolve(rel).toString()
        } catch (e: Exception) {
            val origin = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1).orEmpty()
            if (rel.startsWith("/")) origin + rel else base.substringBefore("?").substringBeforeLast("/") + "/" + rel
        }
    }

    private val reUri = Regex("""URI="([^"]+)"""")

    // Alt listedeki goreli adresleri mutlak yapar (yerel sunucudan servis edilirken bozulmasin)
    private fun mutlaklastir(body: String, base: String): String {
        val sb = StringBuilder()
        for (line in body.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val m = reUri.find(line)
                if (m != null) {
                    val abs = resolveUrl(base, m.groupValues[1].replace("&amp;", "&"))
                    sb.append(line.replace(m.value, "URI=\"$abs\""))
                } else sb.append(line)
            } else {
                sb.append(resolveUrl(base, line.trim()))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("&amp;", "&")

    private fun uniDecode(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )
        ).text

        // Sayfanin kendi alan adi (FastPlay ya da SetPlay); goreli adresler buna gore cozulur
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl

        val spg = Regex("""window\.SPG_A=(\{.*?\});""").find(html)?.groupValues?.get(1).orEmpty()
        val sp = Regex(""""sp":"([^"]*)"""").find(spg)?.groupValues?.get(1).orEmpty()
        val spT = Regex(""""spT":(\d+)""").find(spg)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val kopru = Regex("""window\.STF_KOPRU\s*=\s*\{(.*)\};""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1).orEmpty()
        val src = Regex("""src:\s*"([^"]+)"""").find(kopru)?.groupValues?.get(1)?.let { unescape(it) }

        // Altyazilar
        val subSeen = HashSet<String>()
        for (m in Regex(""""file":"([^"]+\.vtt)","label":"([^"]+)"""").findAll(html)) {
            var u = unescape(m.groupValues[1])
            if (!u.startsWith("http")) u = resolveUrl(url, u)
            if (!subSeen.add(u)) continue
            subtitleCallback.invoke(newSubtitleFile(uniDecode(m.groupValues[2]), u))
        }

        if (!src.isNullOrBlank()) {
            val masterUrl = resolveUrl(url, src)
            val master = tokenliCek(masterUrl, sp, spT, origin) ?: throw ErrorLoadingException("FastPlay master alinamadi")

            val port = FastPlayServer.baslat()
            val prefix = "p" + base36(Random.nextLong(Long.MAX_VALUE)) + "_"
            var n = 0
            var ok = 0
            val out = StringBuilder()

            if (!master.contains("#EXT-X-STREAM-INF") && master.contains("#EXTINF")) {
                // master yok, dogrudan oynatma listesi
                FastPlayServer.ekle("${prefix}master.m3u8", mutlaklastir(master, masterUrl))
                ok = 1
            } else {
                var pendingInf: String? = null
                for (line in master.lineSequence()) {
                    if (line.isBlank()) continue
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        pendingInf = line
                        continue
                    }
                    if (line.startsWith("#")) {
                        val m = reUri.find(line)
                        if (m == null) {
                            out.append(line).append('\n')
                            continue
                        }
                        val abs = resolveUrl(masterUrl, m.groupValues[1].replace("&amp;", "&"))
                        val body = tokenliCek(abs, sp, spT, origin)
                        if (body == null) {
                            Log.e(name, "URI alinamadi: ${abs.take(100)}")
                            continue
                        }
                        val key = "$prefix${n++}.m3u8"
                        FastPlayServer.ekle(key, mutlaklastir(body, abs))
                        out.append(line.replace(m.value, "URI=\"http://127.0.0.1:$port/$key\"")).append('\n')
                    } else {
                        val abs = resolveUrl(masterUrl, line.trim().replace("&amp;", "&"))
                        val body = tokenliCek(abs, sp, spT, origin)
                        if (body == null) {
                            Log.e(name, "Varyant alinamadi: ${abs.take(100)}")
                            pendingInf = null
                            continue
                        }
                        val key = "$prefix${n++}.m3u8"
                        FastPlayServer.ekle(key, mutlaklastir(body, abs))
                        pendingInf?.let { out.append(it).append('\n') }
                        pendingInf = null
                        out.append("http://127.0.0.1:$port/$key").append('\n')
                        ok++
                    }
                }
                if (ok == 0) throw ErrorLoadingException("FastPlay playlistleri alinamadi")
                FastPlayServer.ekle("${prefix}master.m3u8", out.toString())
            }

            val local = "http://127.0.0.1:$port/${prefix}master.m3u8"
            Log.d(name, "yerel link: $local ($ok playlist)")
            callback.invoke(
                newExtractorLink(name, name, local, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to "$origin/")
                }
            )
            return
        }

        // Eski bicim
        Log.e(name, "Yeni format bulunamadi, eski format deneniyor: $url")
        val old = url.substringBefore("?").replace("/video/", "/manifests/") + "/master.txt"
        callback.invoke(
            newExtractorLink(name, name, old, ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Referer" to url)
            }
        )
    }
}

// Yerel (127.0.0.1) m3u8 sunucusu: X-Sp basligi gerektiren listeleri oynaticiya duz m3u8 olarak verir
object FastPlayServer {
    private val icerikler = ConcurrentHashMap<String, String>()
    private val sira = ConcurrentLinkedQueue<String>()

    @Volatile
    private var port = 0

    @Synchronized
    fun baslat(): Int {
        if (port != 0) return port
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = ss.localPort
        thread(name = "MRC-FastPlay-Sunucu", isDaemon = true) {
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    thread(isDaemon = true) { istekIsle(s) }
                } catch (e: Exception) {
                    if (ss.isClosed) break
                }
            }
        }
        Log.d("FastPlay", "yerel sunucu basladi: 127.0.0.1:$port")
        return port
    }

    fun ekle(anahtar: String, icerik: String) {
        icerikler[anahtar] = icerik
        sira.add(anahtar)
        while (sira.size > 300) {
            val eski = sira.poll() ?: break
            icerikler.remove(eski)
        }
    }

    private fun satirOku(inp: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun istekIsle(s: Socket) {
        try {
            s.soTimeout = 15000
            val inp = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())
            val ilk = satirOku(inp)
            while (satirOku(inp).isNotEmpty()) { /* basliklari atla */ }
            val parca = ilk.split(" ")
            val metod = parca.getOrNull(0) ?: "GET"
            val yol = (parca.getOrNull(1) ?: "/").removePrefix("/").substringBefore("?")
            val govde = icerikler[yol]
            if (govde == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            } else {
                val bytes = govde.toByteArray(Charsets.UTF_8)
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: " + bytes.size +
                        "\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                if (metod != "HEAD") out.write(bytes)
            }
            out.flush()
            s.close()
        } catch (e: Exception) {
            Log.e("FastPlay", "sunucu hatasi: ${e.message}")
            try { s.close() } catch (_: Exception) { }
        }
    }
}

// =====================================================================================
//  SetPlay (diziler)
//  Sayfadaki SPG.cerceve("..", "<veri>", "<anahtar>") XOR ile cozulur; cikan adreslerden
//  FastPlay varsa ona, manifest.php / m3u8 varsa dogrudan oynaticiya, yoksa iframe'e gidilir.
// =====================================================================================
open class SetPlay : ExtractorApi() {
    override val name = "SetPlay"
    override val mainUrl = "https://setplay.shop"
    override val requiresReferer = true

    private fun b64(s: String): ByteArray = try {
        Base64.decode(s, Base64.DEFAULT)
    } catch (e: Exception) {
        Base64.decode(s, Base64.URL_SAFE)
    }

    private fun xorCoz(veriB64: String, anahtarB64: String): List<String> = try {
        val veri = String(b64(veriB64), Charsets.ISO_8859_1)
        val anahtar = String(b64(anahtarB64), Charsets.ISO_8859_1)
        if (veri.isEmpty() || anahtar.isEmpty()) emptyList()
        else {
            val sb = StringBuilder(veri.length)
            for (i in veri.indices) sb.append((veri[i].code xor anahtar[i % anahtar.length].code).toChar())
            sb.toString().split("|")
        }
    } catch (e: Exception) {
        Log.e(name, "xorCoz hatasi: ${e.message}")
        emptyList()
    }

    private val cerceve = Regex(
        "SPG\\.\\w+\\(\\s*\"[^\"]*\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)"
    )

    private fun baglam(text: String, anahtar: String, adet: Int = 2, pay: Int = 90): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (out.size < adet) {
            val i = text.indexOf(anahtar, from, ignoreCase = true)
            if (i < 0) break
            out.add("[" + anahtar + "] " + text.substring(maxOf(0, i - pay / 3), minOf(text.length, i + pay)).replace("\n", " "))
            from = i + anahtar.length
        }
        return out
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Adresin kendisi zaten manifest ise dogrudan ver
        if (url.contains("manifest.php", true) || url.contains(".m3u8", true)) {
            emitDirectM3u8(url, referer ?: url, name, callback)
            return
        }

        val notes = ArrayList<String>()
        val resp = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )
        )
        val html = resp.text
        val clean = html.replace("\\/", "/")
        notes.add("sayfa kod=${resp.code} uzunluk=${html.length} url=${url.take(60)}")

        // 1) SPG.<fonksiyon>("..", "<veri>", "<anahtar>") -> XOR
        val m = cerceve.find(html)
        if (m != null) {
            val parts = xorCoz(
                m.groupValues[1].replace("\\/", "/"),
                m.groupValues[2].replace("\\/", "/")
            ).map { it.trim() }.filter { it.startsWith("http") }
            notes.add("XOR parca=${parts.size}: " + parts.joinToString(" , ") { it.take(60) })

            val fast = parts.firstOrNull { it.contains("fastplay", true) }
            if (fast != null) {
                Log.d(name, "fastplay -> $fast")
                FastPlay().getUrl(fast, url, subtitleCallback, callback)
                return
            }
            val manifest = parts.firstOrNull { it.contains("manifest.php", true) || it.contains(".m3u8", true) }
            if (manifest != null) {
                Log.d(name, "manifest -> $manifest")
                emitDirectM3u8(manifest, url, name, callback)
                return
            }
            if (parts.isNotEmpty()) {
                for (p in parts) loadExtractor(p, url, subtitleCallback, callback)
                return
            }
        } else {
            Log.e(name, "SPG.cerceve bulunamadi")
            notes.add("SPG.<fn>(...) bulunamadi")
        }

        // 2) Sayfa FastPlay ile ayni bicimde (SPG_A + STF_KOPRU src=manifest.php?t=...) -> ayni akis
        if (html.contains("STF_KOPRU") && html.contains("SPG_A")) {
            Log.d(name, "STF_KOPRU bulundu, FastPlay akisi: ${url.take(80)}")
            FastPlay().getUrl(url, referer, subtitleCallback, callback)
            return
        }

        // 2b) Sayfada acik adresler
        val urls = Regex("""https?://[^\s"'<>\\]+""").findAll(clean).map { it.value.replace("&amp;", "&") }.distinct().toList()
        val fastUrl = urls.firstOrNull { it.contains("fastplay", true) && it.contains("?") }
        if (fastUrl != null) {
            Log.d(name, "sayfada fastplay -> $fastUrl")
            FastPlay().getUrl(fastUrl, url, subtitleCallback, callback)
            return
        }
        val manUrl = urls.firstOrNull { it.contains("manifest.php", true) || it.contains(".m3u8", true) }
        if (manUrl != null) {
            Log.d(name, "sayfada manifest -> $manUrl")
            emitDirectM3u8(manUrl, url, name, callback)
            return
        }

        // 3) stfplay.php?t=TOKEN -> manifest.php?t=TOKEN (ayni belirtec) dene
        val derived = url.replace("stfplay.php", "manifest.php")
        if (derived != url) {
            try {
                val r = app.get(derived, referer = url, cookies = resp.cookies, headers = mapOf("Accept" to "*/*"))
                val body = r.text
                notes.add("manifest kod=${r.code} govde=${body.take(160).replace("\n", " ")}")
                if (r.code == 200 && body.trimStart().startsWith("#EXTM3U")) {
                    Log.d(name, "turetilmis manifest -> $derived")
                    emitDirectM3u8(derived, url, name, callback)
                    return
                }
            } catch (e: Exception) {
                notes.add("manifest hata: ${e.message}")
            }
        }

        // 4) iframe yedegi
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!iframe.isNullOrBlank() && iframe.startsWith("http")) {
            Log.d(name, "iframe -> $iframe")
            loadExtractor(iframe, url, subtitleCallback, callback)
            return
        }

        // Hata ayiklama: sayfa hakkinda ipuclari
        if (SF_DEBUG) {
            val scripts = Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.take(6).toList()
            notes.add("script src: " + scripts.joinToString(" , "))
            notes.addAll(baglam(clean, "SPG", 2))
            notes.addAll(baglam(clean, "manifest", 2))
            notes.add("govde: " + html.replace(Regex("""\s+"""), " ").take(250))
            emitDebug(callback, name, notes)
            return
        }
        throw ErrorLoadingException("SetPlay player bulunamadi")
    }
}

// setplay alan adi sik degisir (.shop / .sbs ...)
class SetPlaySbs : SetPlay() {
    override val mainUrl = "https://setplay.sbs"
}

// ---------------- TMDB JSON veri siniflari (alan adlari TMDB JSON'u ile birebir ayni) ----------------
data class SfTmdbGenre(val name: String? = null)

data class SfTmdbCast(val name: String? = null, val profile_path: String? = null)

data class SfTmdbCredits(val cast: List<SfTmdbCast>? = null)

data class SfTmdbMovieResult(
    val id: Int? = null,
    val title: String? = null,
    val original_title: String? = null,
    val popularity: Double? = null
)

data class SfTmdbMovieSearch(val results: List<SfTmdbMovieResult>? = null)

data class SfTmdbMovieDetails(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Double? = null,
    val release_date: String? = null,
    val runtime: Int? = null,
    val genres: List<SfTmdbGenre>? = null,
    val credits: SfTmdbCredits? = null
)

data class SfTmdbTvResult(
    val id: Int? = null,
    val name: String? = null,
    val original_name: String? = null,
    val popularity: Double? = null
)

data class SfTmdbTvSearch(val results: List<SfTmdbTvResult>? = null)

data class SfTmdbSeasonInfo(
    val season_number: Int? = null,
    val episode_count: Int? = null
)

data class SfTmdbTvDetails(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Double? = null,
    val first_air_date: String? = null,
    val popularity: Double? = null,
    val genres: List<SfTmdbGenre>? = null,
    val seasons: List<SfTmdbSeasonInfo>? = null,
    val credits: SfTmdbCredits? = null
)

data class SfTmdbEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val runtime: Int? = null,
    val air_date: String? = null
)

data class SfTmdbSeasonDetails(val episodes: List<SfTmdbEpisode>? = null)
