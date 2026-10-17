package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLEncoder

data class FdSearch(val results: List<FdSearchItem>? = null)

data class FdSearchItem(
    val id: Int? = null,
    val title: String? = null,
    val original_title: String? = null,
    val release_date: String? = null
)

data class FdGenre(val name: String? = null)
data class FdCast(val name: String? = null)
data class FdCredits(val cast: List<FdCast>? = null)
data class FdVideo(val key: String? = null, val site: String? = null, val type: String? = null)
data class FdVideos(val results: List<FdVideo>? = null)

data class FdMovie(
    val id: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val runtime: Int? = null,
    val vote_average: Double? = null,
    val genres: List<FdGenre>? = null,
    val credits: FdCredits? = null,
    val videos: FdVideos? = null
)

class FilmDozu : MainAPI() {
    override var mainUrl = "https://filmdozu.com"
    override var name = "FilmDozu"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    // TMDB API anahtarını buraya yaz. Boş/varsayılan kalırsa TMDB kapalı, eklenti siteden çalışır.
    private val tmdbKey = "BURAYA_TMDB_API_KEY"

    private val tmdbOn: Boolean
        get() = tmdbKey.isNotBlank() && !tmdbKey.startsWith("000316508321ce461cf81e7c6815eec7")
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val tmdbCache = HashMap<String, FdMovie?>()

    private val androidUA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    private val pageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to androidUA,
            "Accept-Language" to "tr-TR,tr;q=0.9",
            "Referer" to "$mainUrl/"
        )

    // ---------------- Kategoriler ----------------

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/trend" to "Trendler",
        "$mainUrl/dublaj/" to "Türkçe Dublaj Filmler",
        "$mainUrl/altyazili/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/kategori/aksiyon/" to "Aksiyon",
        "$mainUrl/kategori/macera/" to "Macera",
        "$mainUrl/kategori/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/kategori/fantastik/" to "Fantastik",
        "$mainUrl/kategori/animasyon/" to "Animasyon",
        "$mainUrl/kategori/komedi/" to "Komedi",
        "$mainUrl/kategori/dram/" to "Dram",
        "$mainUrl/kategori/romantik/" to "Romantik",
        "$mainUrl/kategori/aile/" to "Aile",
        "$mainUrl/kategori/korku/" to "Korku",
        "$mainUrl/kategori/gerilim/" to "Gerilim",
        "$mainUrl/kategori/gizem/" to "Gizem",
        "$mainUrl/kategori/suc/" to "Suç",
        "$mainUrl/kategori/tarih/" to "Tarih",
        "$mainUrl/kategori/savas/" to "Savaş",
        "$mainUrl/kategori/belgesel/" to "Belgesel",
        "$mainUrl/kategori/muzik/" to "Müzik",
        "$mainUrl/kategori/spor/" to "Spor",
        "$mainUrl/kategori/tv-film/" to "TV Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) base else base + (if (base.contains("?")) "&" else "?") + "page=$page"
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
        val doc = app.get("$mainUrl/ara?q=$q", headers = pageHeaders).document
        return parseCards(doc)
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        return doc.select("article.card").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/film/]") ?: return@mapNotNull null
            val url = fixUrl(a.attr("href"))
            if (!seen.add(url)) return@mapNotNull null

            val img = el.selectFirst("img")
            val title = (el.selectFirst(".card-title")?.text() ?: img?.attr("alt") ?: "").trim()
            if (title.isBlank()) return@mapNotNull null

            var poster = img?.attr("src") ?: ""
            if (poster.isBlank() || poster.startsWith("data:")) poster = img?.attr("data-src") ?: ""
            val posterUrl = fixUrlNull(poster.ifBlank { null })
            val year = el.selectFirst(".card-info span")?.text()?.trim()?.toIntOrNull()

            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    // ---------------- Detay sayfası (TMDB Türkçe) ----------------

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = pageHeaders).document
        val siteTitle = doc.selectFirst(".hero-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val sub = doc.selectFirst(".hero-sub")?.text()?.trim() ?: ""
        val siteYear = Regex("\\((\\d{4})\\)").find(sub)?.groupValues?.get(1)?.toIntOrNull()
        val origTitle = sub.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val sitePoster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".poster img")?.attr("src")
        )
        val sitePlot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val tm = if (tmdbOn) {
            try { tmdbMovie(siteTitle, origTitle, siteYear) } catch (e: Exception) { null }
        } else null

        val title = tm?.title?.takeIf { it.isNotBlank() } ?: siteTitle
        val poster = tm?.poster_path?.let { "$tmdbImg/w780$it" } ?: sitePoster
        val backdrop = tm?.backdrop_path?.let { "$tmdbImg/w1280$it" }
        val finalYear = tm?.release_date?.take(4)?.toIntOrNull() ?: siteYear
        val overview = tm?.overview?.takeIf { it.isNotBlank() }
        val rating = tm?.vote_average?.takeIf { it > 0 }
        val plot = listOfNotNull(
            rating?.let { "⭐ ${"%.1f".format(it)} / 10" },
            overview ?: sitePlot
        ).joinToString("\n\n").ifBlank { null }
        val runtime = tm?.runtime?.takeIf { it > 0 }
        val genres = tm?.genres?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() }
        val actorNames = tm?.credits?.cast?.mapNotNull { it.name }?.take(12)?.takeIf { it.isNotEmpty() }
        val trailerKey = tm?.videos?.results
            ?.filter { it.site.equals("YouTube", true) && !it.key.isNullOrBlank() }
            ?.let { l -> l.firstOrNull { it.type.equals("Trailer", true) } ?: l.firstOrNull() }
            ?.key

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = finalYear
            this.duration = runtime
            this.tags = genres
            if (actorNames != null) addActors(actorNames)
            if (trailerKey != null) addTrailer("https://www.youtube.com/watch?v=$trailerKey")
        }
    }

    // ---------------- TMDB ----------------

    private fun norm(s: String?): String =
        (s ?: "").replace("İ", "i").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun yearClose(r: FdSearchItem, year: Int?): Boolean {
        val y = r.release_date?.take(4)?.toIntOrNull()
        if (year == null || y == null) return true
        return kotlin.math.abs(y - year) <= 1
    }

    private suspend fun tmdbFindId(title: String, orig: String, year: Int?): Int? {
        val queries = listOf(orig, title).filter { it.isNotBlank() }.distinct()
        val passes = if (year != null) listOf(true, false) else listOf(false)
        for (withYear in passes) {
            for (q in queries) {
                val enc = URLEncoder.encode(q, "UTF-8")
                val url = "$tmdbApi/search/movie?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=$enc" +
                    (if (withYear) "&year=$year" else "")
                val results = try {
                    app.get(url).parsedSafe<FdSearch>()?.results
                } catch (e: Exception) {
                    null
                } ?: continue
                val nq = norm(q)
                results.firstOrNull {
                    yearClose(it, year) && (norm(it.original_title) == nq || norm(it.title) == nq)
                }?.id?.let { return it }
                if (withYear) {
                    results.firstOrNull { yearClose(it, year) }?.id?.let { return it }
                }
            }
        }
        return null
    }

    private suspend fun tmdbMovie(title: String, orig: String, year: Int?): FdMovie? {
        val key = "$orig|$title|$year"
        if (tmdbCache.containsKey(key)) return tmdbCache[key]

        val id = tmdbFindId(title, orig, year)
        var result: FdMovie? = null
        if (id != null) {
            val extra = "&append_to_response=credits,videos&include_video_language=tr,en,null"
            val tr = app.get("$tmdbApi/movie/$id?api_key=$tmdbKey&language=tr-TR$extra")
                .parsedSafe<FdMovie>()
            if (tr != null) {
                var en: FdMovie? = null
                if (tr.overview.isNullOrBlank() || tr.poster_path.isNullOrBlank() || tr.backdrop_path.isNullOrBlank()) {
                    en = try {
                        app.get("$tmdbApi/movie/$id?api_key=$tmdbKey&language=en-US").parsedSafe<FdMovie>()
                    } catch (e: Exception) {
                        null
                    }
                }
                result = tr.copy(
                    overview = tr.overview?.takeIf { it.isNotBlank() } ?: en?.overview,
                    poster_path = tr.poster_path?.takeIf { it.isNotBlank() } ?: en?.poster_path,
                    backdrop_path = tr.backdrop_path?.takeIf { it.isNotBlank() } ?: en?.backdrop_path
                )
            }
        }
        tmdbCache[key] = result
        return result
    }

    // ---------------- Linkler ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(data, headers = pageHeaders).text
        } catch (e: Exception) {
            return false
        }

        var any = false
        for ((label, embed) in extractSources(html)) {
            val found = try { resolve(embed) } catch (e: Exception) { emptyList() }
            if (found.isNotEmpty()) {
                for (f in found) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - $label",
                            f.url,
                            if (f.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = f.referer
                            this.quality = f.quality
                            this.headers = f.headers
                        }
                    )
                }
                any = true
            } else {
                try {
                    if (loadExtractor(embed, data, subtitleCallback, callback)) any = true
                } catch (_: Exception) {
                }
            }
        }
        return any
    }

    private fun decodeHtml(s: String): String = Parser.unescapeEntities(s, false)

    private fun extractSources(html: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val re = Regex("""loadSource\('([^']+)'\s*,\s*this\)[^>]*>([\s\S]*?)</button>""")
        re.findAll(html).forEach { m ->
            var url = decodeHtml(m.groupValues[1]).trim()
            if (url.startsWith("//")) url = "https:$url"
            val label = decodeHtml(m.groupValues[2].replace(Regex("<[^>]*>"), ""))
                .replace(Regex("\\s+"), " ").trim()
            if (!url.startsWith("http")) return@forEach
            if (Regex("youtube\\.com|youtu\\.be").containsMatchIn(url) || label.contains("fragman", true)) return@forEach
            if (out.none { it.second == url }) out.add(label.ifBlank { "Kaynak" } to url)
        }
        if (out.isEmpty()) {
            Regex("data-src=\"([^\"]+)\"").find(html)?.let {
                val u = decodeHtml(it.groupValues[1]).trim()
                if (u.contains("ok.ru")) out.add("Varsayılan" to u)
            }
        }
        return out
    }

    // ---------------- Embed çözücüler ----------------

    private data class Found(
        val url: String,
        val hls: Boolean,
        val quality: Int,
        val referer: String,
        val headers: Map<String, String>
    )

    private fun originOf(u: String) = Regex("^https?://[^/]+").find(u)?.value ?: ""

    private suspend fun resolve(embed: String): List<Found> =
        if (embed.contains("ok.ru")) resolveOk(embed) else resolveVidmoly(embed)

    private fun findStream(text0: String): Pair<String, Boolean>? {
        val text = text0.replace("\\/", "/").replace("&amp;", "&")
        val m3u8 = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""").findAll(text).map { it.value }.toList()
        if (m3u8.isNotEmpty()) {
            return (m3u8.firstOrNull { it.contains("master", true) } ?: m3u8.first()) to true
        }
        Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(text)?.let {
            return it.groupValues[1] to false
        }
        return null
    }

    private suspend fun resolveVidmoly(embed: String): List<Found> {
        val clean = embed.substringBefore("?")
        val tries = arrayListOf(clean)
        val biz = clean.replace(Regex("^https?://[^/]+"), "https://vidmoly.biz")
        if (biz != clean) tries.add(biz)

        for (u in tries) {
            val html = try {
                app.get(
                    u,
                    headers = mapOf(
                        "User-Agent" to androidUA,
                        "Accept" to "text/html,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9",
                        "Referer" to "$mainUrl/"
                    )
                ).text
            } catch (e: Exception) {
                ""
            }
            if (html.isBlank()) continue

            var f = findStream(html)
            if (f == null) {
                val un = try { getAndUnpack(html) } catch (e: Exception) { "" }
                if (un.isNotBlank()) f = findStream(un)
            }
            if (f != null) {
                val ref = if (u.contains("vidmoly")) "https://vidmoly.biz/" else originOf(u) + "/"
                return listOf(
                    Found(
                        f.first, f.second, Qualities.Unknown.value, ref,
                        mapOf("User-Agent" to androidUA, "Referer" to ref)
                    )
                )
            }
        }
        return emptyList()
    }

    private suspend fun resolveOk(embed: String): List<Found> {
        val clean = embed.substringBefore("?")
        val doc = app.get(
            clean,
            headers = mapOf("User-Agent" to androidUA, "Referer" to "$mainUrl/")
        ).document
        val raw = doc.selectFirst("[data-options]")?.attr("data-options") ?: return emptyList()

        val opts = JSONObject(raw)
        val flash = opts.optJSONObject("flashvars") ?: return emptyList()
        val meta = flash.optJSONObject("metadata") ?: JSONObject(flash.optString("metadata", "{}"))

        val hdr = mapOf("User-Agent" to androidUA, "Referer" to "https://ok.ru/")
        val hls = listOf("hlsManifestUrl", "ondemandHls", "hlsMasterPlaylistUrl")
            .map { meta.optString(it) }
            .firstOrNull { it.isNotBlank() }
        if (hls != null) {
            return listOf(Found(hls, true, Qualities.Unknown.value, "https://ok.ru/", hdr))
        }

        val out = ArrayList<Found>()
        val vids = meta.optJSONArray("videos") ?: return emptyList()
        val qmap = mapOf("full" to 1080, "hd" to 720, "sd" to 480, "low" to 360, "lowest" to 240, "mobile" to 144)
        for (i in 0 until vids.length()) {
            val v = vids.optJSONObject(i) ?: continue
            val q = qmap[v.optString("name")] ?: continue
            val u = v.optString("url")
            if (u.isNotBlank()) out.add(Found(u, false, q, "https://ok.ru/", hdr))
        }
        return out
    }
}
