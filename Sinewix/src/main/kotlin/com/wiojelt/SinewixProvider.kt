package com.wiojelt

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class SinewixProvider : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "Sinewix"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val imgBase = "https://image.tmdb.org/t/p"

    // Kataloglar tamamen TMDB'den gelir. data = TMDB endpoint'i (+ sorgu parametreleri)
    override val mainPage = mainPageOf(
        "/trending/all/week" to "Trend",
        "/movie/now_playing?region=TR" to "Vizyondaki Filmler",
        "/tv/on_the_air" to "Yayındaki Diziler",
        "/movie/popular" to "Popüler Filmler",
        "/tv/popular" to "Popüler Diziler",
        "/movie/top_rated" to "En Çok Oy Alan Filmler",
        "/tv/top_rated" to "En Çok Oy Alan Diziler",
        "/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Animeler"
    )

    // ---------------------------------------------------------------- TMDB yardımcıları

    private fun JSONObject.str(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun img(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { "$imgBase/$size$it" }

    private suspend fun tmdb(path: String, extra: String = "", lang: String = "tr-TR"): JSONObject? =
        try {
            val sep = if (path.contains("?")) "&" else "?"
            JSONObject(app.get("$mainUrl$path${sep}api_key=$tmdbKey&language=$lang$extra").text)
        } catch (e: Exception) {
            null
        }

    private fun toResponse(o: JSONObject): SearchResponse? {
        val mt = o.optString("media_type")
        if (mt == "person") return null
        val isTv = mt == "tv" || (mt.isBlank() && o.has("name") && !o.has("title"))
        val id = o.optInt("id")
        val title = o.str(if (isTv) "name" else "title") ?: return null
        if (id == 0) return null
        val poster = img(o.str("poster_path"))
        return if (isTv) newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) { posterUrl = poster }
    }

    private fun parseResults(o: JSONObject?): List<SearchResponse> {
        val arr = o?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { toResponse(arr.getJSONObject(it)) }
    }

    // ---------------------------------------------------------------- Ana sayfa / arama

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val o = tmdb(request.data, "&page=$page")
        val items = parseResults(o).distinctBy { it.url }
        val totalPages = o?.optInt("total_pages") ?: 1
        return newHomePageResponse(request.name, items, page < totalPages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        return parseResults(tmdb("/search/multi", "&include_adult=false&query=$q"))
    }

    // ---------------------------------------------------------------- Detay

    private fun streamData(tv: Boolean, id: String, title: String, orig: String?, year: Int?, s: Int = 0, e: Int = 0) =
        JSONObject()
            .put("tv", tv).put("id", id).put("t", title).put("o", orig ?: "")
            .put("y", year ?: 0).put("s", s).put("e", e).toString()

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val id = url.substringAfterLast("/")
        val path = if (isTv) "/tv/$id" else "/movie/$id"

        val d = tmdb(path, "&append_to_response=credits,videos,recommendations&include_video_language=tr,en,null")
            ?: return null

        val title = d.str(if (isTv) "name" else "title") ?: return null
        val orig = d.str(if (isTv) "original_name" else "original_title")
        val plot = d.str("overview") ?: tmdb(path, "", "en-US")?.str("overview")
        val year = (d.str(if (isTv) "first_air_date" else "release_date"))?.take(4)?.toIntOrNull()
        val poster = img(d.str("poster_path"))
        val backdrop = img(d.str("backdrop_path"), "w1280")

        val genreArr = d.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genreArr.length()).map { genreArr.getJSONObject(it) }
        val tags = genres.mapNotNull { it.str("name") }
        val isAnime = isTv && d.str("original_language") == "ja" && genres.any { it.optInt("id") == 16 }

        val castArr = d.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
        val actors = (0 until minOf(castArr.length(), 15)).mapNotNull {
            val c = castArr.getJSONObject(it)
            c.str("name")?.let { n -> Actor(n, img(c.str("profile_path"), "w185")) }
        }

        val vidArr = d.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
        val trailerKey = (0 until vidArr.length()).map { vidArr.getJSONObject(it) }
            .firstOrNull { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
            ?.str("key")

        val recs = parseResults(d.optJSONObject("recommendations"))

        return if (isTv) {
            val seasonArr = d.optJSONArray("seasons") ?: JSONArray()
            val seasonNums = (0 until seasonArr.length())
                .map { seasonArr.getJSONObject(it).optInt("season_number") }
                .filter { it > 0 }

            val seasonData = coroutineScope {
                seasonNums.map { sn -> async { sn to tmdb("$path/season/$sn") } }.awaitAll()
            }

            val eps = mutableListOf<Episode>()
            for ((sn, sd) in seasonData) {
                val el = sd?.optJSONArray("episodes") ?: continue
                for (j in 0 until el.length()) {
                    val e = el.getJSONObject(j)
                    val en = e.optInt("episode_number")
                    eps += newEpisode(streamData(true, id, title, orig, year, sn, en)) {
                        this.name = e.str("name") ?: "Bölüm $en"
                        this.season = sn
                        this.episode = en
                        this.posterUrl = img(e.str("still_path"), "w300")
                        this.description = e.str("overview")
                        this.runTime = e.optInt("runtime").takeIf { it > 0 }
                        addDate(e.str("air_date"))
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recs
                addActors(actors)
                addTrailer(trailerKey?.let { "https://www.youtube.com/watch?v=$it" })
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, streamData(false, id, title, orig, year)) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = d.optInt("runtime").takeIf { it > 0 }
                this.recommendations = recs
                addActors(actors)
                addTrailer(trailerKey?.let { "https://www.youtube.com/watch?v=$it" })
            }
        }
    }

    // ---------------------------------------------------------------- Yayın kaynağı (Sinewix paneli)
    // TMDB sadece katalog/görsel verir, video linki vermez. Linkler için eski panelde
    // TMDB id'si veya başlık+yıl ile eşleşen kaydı bulup oradaki linkler kullanılır.

    private fun panelIsSerie(o: JSONObject): Boolean {
        val t = (o.optString("type") + " " + o.optString("media_type")).lowercase()
        if (Regex("serie|anime|tv").containsMatchIn(t)) return true
        if (t.contains("movie")) return false
        return o.has("seasons") || o.has("number_of_seasons") ||
            o.optString("first_air_date").let { it.isNotBlank() && it != "null" }
    }

    private fun panelTitles(o: JSONObject) =
        listOf("name", "title", "original_name", "original_title").mapNotNull { o.str(it) }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    private fun panelYear(o: JSONObject) =
        (o.str("release_date") ?: o.str("first_air_date"))?.take(4)?.toIntOrNull()

    private suspend fun findPanelId(titles: List<String>, year: Int?, tv: Boolean, tmdbId: String): String? {
        for (t in titles.filter { it.isNotBlank() }.distinct()) {
            val q = URLEncoder.encode(t, "UTF-8").replace("+", "%20")
            val o = (try { SinewixApi.get("/public/api/search/$q/${SinewixApi.API_KEY}") } catch (e: Exception) { null })
                ?: continue
            val cands = SinewixApi.items(o).filter { panelIsSerie(it) == tv && it.optString("id").isNotBlank() }
            cands.firstOrNull { it.str("tmdb_id") == tmdbId }?.let { return it.optString("id") }
            val nt = norm(t)
            val named = cands.filter { c -> panelTitles(c).any { norm(it) == nt } }
            val pick = named.firstOrNull { year != null && panelYear(it) == year } ?: named.firstOrNull()
            if (pick != null) return pick.optString("id")
        }
        return null
    }

    private fun links(videos: JSONArray?): List<String> {
        val out = mutableListOf<String>()
        if (videos != null) for (i in 0 until videos.length()) {
            val v = videos.opt(i)
            val raw = if (v is JSONObject) v.optString("link") else v?.toString() ?: ""
            raw.split("|||").map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.forEach { out += it }
        }
        return out.distinct()
    }

    private suspend fun panelDetail(id: String, tv: Boolean): JSONObject? {
        val first = if (tv) "series/show" else "media/detail"
        val second = if (tv) "media/detail" else "series/show"
        var d = (try { SinewixApi.get("/public/api/$first/$id/${SinewixApi.API_KEY}") } catch (e: Exception) { null }) as? JSONObject
        if (d == null || !(d.has("videos") || d.has("seasons") || d.has("name") || d.has("title")))
            d = (try { SinewixApi.get("/public/api/$second/$id/${SinewixApi.API_KEY}") } catch (e: Exception) { null }) as? JSONObject
        return d
    }

    private fun quality(s: String) =
        Regex("(2160|1440|1080|720|480|360)").find(s)?.value?.toIntOrNull() ?: Qualities.Unknown.value

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val j = try { JSONObject(data) } catch (e: Exception) { return false }
        val tv = j.optBoolean("tv")
        val tmdbId = j.optString("id")
        val year = j.optInt("y").takeIf { it > 0 }

        val pid = findPanelId(listOf(j.optString("t"), j.optString("o")), year, tv, tmdbId) ?: return false
        val d = panelDetail(pid, tv) ?: return false

        val found = mutableListOf<String>()
        if (tv) {
            val sn = j.optInt("s")
            val en = j.optInt("e")
            val seasons = d.optJSONArray("seasons") ?: JSONArray()
            for (i in 0 until seasons.length()) {
                val s = seasons.getJSONObject(i)
                if (s.optInt("season_number") != sn) continue
                val el = s.optJSONArray("episodes") ?: continue
                for (k in 0 until el.length()) {
                    val e = el.getJSONObject(k)
                    if (e.optInt("episode_number") == en) found += links(e.optJSONArray("videos"))
                }
            }
        } else {
            found += links(d.optJSONArray("videos"))
            d.str("direct_link")?.let { found += it }
        }

        var ok = false
        val ua = mapOf("User-Agent" to SinewixApi.WEB_UA)
        for (u in found.distinct().take(8)) {
            var direct: String? = null
            var type = ExtractorLinkType.VIDEO
            if (u.contains("mediafire.com", true)) {
                val html = app.get(u, headers = ua).text
                direct = Regex("""href=["'](https://download[^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
                    ?: Regex("""data-scrambled-url=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                        ?.let { String(SinewixApi.b64(it)) }
            } else if (u.contains("drive.google.com")) {
                val g = Regex("""/file/d/([^/?#]+)""").find(u)?.groupValues?.get(1)
                    ?: Regex("""[?&]id=([^&#]+)""").find(u)?.groupValues?.get(1)
                if (g != null) direct = "https://drive.usercontent.google.com/download?id=$g&export=download&confirm=t"
            } else if (Regex("""\.m3u8(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
                direct = u; type = ExtractorLinkType.M3U8
            } else if (Regex("""\.(mp4|mkv|webm)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
                direct = u
            }
            if (direct == null) continue
            callback(newExtractorLink(name, name, direct, type) {
                this.quality = quality(u)
                this.headers = ua
            })
            ok = true
        }
        return ok
    }
}
