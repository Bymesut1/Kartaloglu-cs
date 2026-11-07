@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.bymesut1

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class DiziYou : MainAPI() {
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            // peekBody: govde tuketilmez, sayfa normal sekilde okunabilir
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.text().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override var mainUrl              = "https://www.diziyou.one"
    override var name                 = "DiziYou"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Aile"                 to "Aile",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Aksiyon"              to "Aksiyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Animasyon"            to "Animasyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Belgesel"             to "Belgesel",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Bilim+Kurgu"          to "Bilim Kurgu",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Dram"                 to "Dram",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Fantazi"              to "Fantazi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Gerilim"              to "Gerilim",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Gizem"                to "Gizem",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Komedi"               to "Komedi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Korku"                to "Korku",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Macera"               to "Macera",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Sava%C5%9F"           to "Savaş",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Su%C3%A7"             to "Suç",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Vah%C5%9Fi+Bat%C4%B1" to "Vahşi Batı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = app.get(url, interceptor = interceptor).document
        val home     = document.select("div.single-item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div#categorytitle a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("div#categorytitle a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", interceptor = interceptor).document

        return document.select("div.incontent div#list-series").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // Ana sayfa ve arama sonuclari sitenin kendi verisiyle gelir; TMDB yalnizca diziye tiklaninca (load) devreye girer.
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val sitePoster      = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src"))
        val siteDesc        = document.selectFirst("div.diziyou_desc")?.ownText()?.trim()
        val siteYear        = document.selectFirst("span.dizimeta:contains(Yapım Yılı)")?.nextSibling()?.toString()?.trim()?.toIntOrNull()
        val siteTags        = document.select("div.genres a").map { it.text() }
        val siteRating      = document.selectFirst("span.dizimeta:contains(IMDB)")?.nextSibling()?.toString()?.trim()?.toRatingInt()
        val siteActors      = document.selectFirst("span.dizimeta:contains(Oyuncular)")?.nextSibling()?.toString()?.trim()?.split(", ")?.map { Actor(it) }
        val trailer         = document.selectFirst("iframe.trailer-video")?.attr("src")

        val siteEpisodes = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.ownText()?.trim() ?: return@mapNotNull null
            val epHref    = it.closest("a")?.attr("href")?.let { href -> fixUrlNull(href) } ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\. Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\. Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name = it.selectFirst("div.bolumismi")?.text()?.trim()?.replace(Regex("""[()]"""), "")?.trim() ?: epName
                this.season = epSeason
                this.episode = epEpisode
            }
        }

        // TMDB: dizi bilgisi (afis, arka plan, aciklama, tur, puan, oyuncular) ve bolum bilgisi; hata olursa site verisi aynen kalir
        val counts = siteEpisodes.groupBy { it.season ?: 0 }.mapValues { it.value.size }
        val tm = try { tmdbMatch(title, counts) } catch (e: Exception) { null }
        val episodes = try { tmdbBolumleri(tm, siteEpisodes) } catch (e: Exception) { siteEpisodes }

        val poster      = tm?.poster_path?.let { "$tmdbImg/w500$it" } ?: sitePoster
        val backdrop    = tm?.backdrop_path?.let { "$tmdbImg/w1280$it" }
        val description = tm?.overview?.takeIf { it.isNotBlank() } ?: siteDesc
        val year        = tm?.first_air_date?.take(4)?.toIntOrNull() ?: siteYear
        val tags        = tm?.genres.orEmpty().mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.ifEmpty { siteTags }
        val rating      = tm?.vote_average?.takeIf { it > 0.0 }?.let { (it * 1000).toInt() } ?: siteRating
        val actors      = tm?.credits?.cast.orEmpty().take(20).mapNotNull { c ->
            val n = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Actor(n, c.profile_path?.let { "$tmdbImg/w185$it" })
        }.ifEmpty { siteActors.orEmpty() }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = backdrop
            this.plot                = description
            this.year                = year
            this.tags                = tags
            this.rating              = rating
            addActors(actors)
            addTrailer(trailer)
        }
    }

    // ---------------- TMDB: yalnizca diziye tiklaninca (load) kullanilir ----------------
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

    private suspend fun tmdbSearch(q: String): List<DyTmdbTvResult> = try {
        app.get(
            "$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${java.net.URLEncoder.encode(q, "UTF-8")}",
            timeout = 15L
        ).parsedSafe<DyTmdbSearchResponse>()?.results.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun tmdbDetails(id: Int): DyTmdbTvDetails? = try {
        app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=tr-TR&append_to_response=credits", timeout = 15L)
            .parsedSafe<DyTmdbTvDetails>()
    } catch (e: Exception) {
        null
    }

    // Sitedeki diziyi TMDB'deki dogru diziyle esler; ayni adli birden fazla dizi varsa sezon basina bolum sayisi en yakin olan secilir
    private suspend fun tmdbMatch(title: String, siteCounts: Map<Int, Int>): DyTmdbTvDetails? {
        val key = norm(title)
        if (key.isEmpty()) return null
        val results = tmdbSearch(title)
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
        if (cands.size == 1) return cands[0].id?.let { tmdbDetails(it) }
        val details = cands.amap { c -> c.id?.let { tmdbDetails(it) } }.filterNotNull()
        if (details.isEmpty()) return null
        return details.minByOrNull { d ->
            val cnt = d.seasons.orEmpty().associate { (it.season_number ?: -1) to (it.episode_count ?: 0) }
            var score = 0
            for ((sn, c) in siteCounts) score += kotlin.math.abs(c - (cnt[sn] ?: 0))
            score * 1000 - (d.popularity ?: 0.0).toInt().coerceAtMost(999)
        }
    }

    private suspend fun tmdbSeason(id: Int, sn: Int, lang: String): Map<Int, DyTmdbEpisode> = try {
        app.get("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=$lang", timeout = 15L)
            .parsedSafe<DyTmdbSeasonDetails>()?.episodes.orEmpty()
            .mapNotNull { e -> e.episode_number?.let { it to e } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    // Sitedeki her bolum satirina TMDB'den Turkce ad, aciklama, gorsel, sure ve tarih ekler. Sitede olmayan bolum eklenmez.
    private suspend fun tmdbBolumleri(tm: DyTmdbTvDetails?, siteEps: List<Episode>): List<Episode> {
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
            // Turkce ad yoksa ("Bolum 3" gibi) Ingilizce ad, o da yoksa sitenin adi
            val epName = tr?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: us?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: old.name
            val overview = tr?.overview?.takeIf { it.isNotBlank() } ?: us?.overview?.takeIf { it.isNotBlank() }
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZY", "data » $data")
        val document = app.get(data, interceptor = interceptor).document

        val itemId     = document.selectFirst("iframe#diziyouPlayer")?.attr("src")?.split("/")?.lastOrNull()?.substringBefore(".html") ?: return false
        Log.d("DZY", "itemId » $itemId")

        val subTitles  = mutableListOf<DiziyouSubtitle>()
        val streamUrls = mutableListOf<DiziyouStream>()
        val storage    = mainUrl.replace("www", "storage")

        document.select("span.diziyouOption").forEach {
            val optId   = it.attr("id")

            if (optId == "turkceAltyazili") {
                subTitles.add(DiziyouSubtitle("Turkish", "${storage}/subtitles/${itemId}/tr.vtt"))
                streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
            }

            if (optId == "ingilizceAltyazili") {
                subTitles.add(DiziyouSubtitle("English", "${storage}/subtitles/${itemId}/en.vtt"))
                streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
            }

            if (optId == "turkceDublaj") {
                streamUrls.add(DiziyouStream("Türkçe Dublaj", "${storage}/episodes/${itemId}_tr/play.m3u8"))
            }
        }

        for (sub in subTitles) {
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = sub.name,
                    url  = fixUrl(sub.url)
                )
            )
        }

        // Ayni ad + ayni adres iki kez eklenmesin (iki altyazi secenegi ayni Orjinal Dil akisini verir)
        for (stream in streamUrls.distinctBy { it.name + "|" + it.url }) {
            callback.invoke(
                newExtractorLink(
                    source = stream.name,
                    name   = stream.name,
                    url    = stream.url,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "${mainUrl}/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }

    data class DiziyouSubtitle(val name: String, val url: String)
    data class DiziyouStream(val name: String, val url: String)
}

// ---------------- TMDB JSON veri siniflari (alan adlari TMDB JSON'u ile birebir ayni) ----------------
data class DyTmdbTvResult(
    val id: Int? = null,
    val name: String? = null,
    val original_name: String? = null,
    val popularity: Double? = null
)

data class DyTmdbSearchResponse(
    val results: List<DyTmdbTvResult>? = null
)

data class DyTmdbSeasonInfo(
    val season_number: Int? = null,
    val episode_count: Int? = null
)

data class DyTmdbGenre(
    val name: String? = null
)

data class DyTmdbCast(
    val name: String? = null,
    val profile_path: String? = null
)

data class DyTmdbCredits(
    val cast: List<DyTmdbCast>? = null
)

data class DyTmdbTvDetails(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Double? = null,
    val first_air_date: String? = null,
    val popularity: Double? = null,
    val genres: List<DyTmdbGenre>? = null,
    val seasons: List<DyTmdbSeasonInfo>? = null,
    val credits: DyTmdbCredits? = null
)

data class DyTmdbEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val runtime: Int? = null,
    val air_date: String? = null
)

data class DyTmdbSeasonDetails(
    val episodes: List<DyTmdbEpisode>? = null
)
