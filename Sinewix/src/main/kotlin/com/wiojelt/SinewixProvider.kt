package com.wiojelt

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class SinewixProvider : MainAPI() {
    override var mainUrl = "https://www.ydfvfdizipanel.ru"
    override var name = "Sinewix"
    override var lang = "tr"
    override val hasMainPage = true

    // data format: "endpoint1,endpoint2,endpoint3||fallbackSearchWord"
    // Endpoint'ler sirayla denenir, ilk dolu donen kullanilir.
    // Hicbiri dolu donmezse fallbackSearchWord ile arama yapilir (arama zaten calisiyor).
    override val mainPage = mainPageOf(
        "media/trending,media/popularmovies,media/showcase||man" to "Trend",
        "movies/latest,media/latestmovies,media/newmovies||love" to "Son Eklenen Filmler",
        "series/latest,series/recents,series/newepisodes||dead" to "Son Eklenen Diziler",
        "movies/popular,media/popularmovies,media/mostwatched||war" to "Popüler Filmler",
        "series/popular,series/trending,series/mostwatched||night" to "Popüler Diziler",
        "animes/latest,animes/popular,animes/recents||city" to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split("||")
        val endpoints = parts.getOrNull(0).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val word = parts.getOrNull(1).orEmpty()

        var items: List<SearchResponse> = emptyList()

        for (ep in endpoints) {
            items = try {
                val o = SinewixApi.get("/public/api/$ep/${SinewixApi.API_KEY}")
                if (o == null) emptyList() else toResponses(SinewixApi.items(o))
            } catch (e: Exception) {
                emptyList()
            }
            if (items.isNotEmpty()) break
        }
        if (items.isEmpty() && word.isNotBlank()) {
            items = try { search(word) } catch (e: Exception) { emptyList() }
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun isSerie(o: JSONObject) =
        Regex("serie|anime|tv", RegexOption.IGNORE_CASE).containsMatchIn(
            o.optString("type") + " " + o.optString("media_type")
        )

    private fun poster(o: JSONObject): String? =
        listOf("poster_path", "poster", "cover", "image", "backdrop_path").map { o.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
            ?.let { if (it.startsWith("http")) it else if (it.startsWith("/")) "https://image.tmdb.org/t/p/w500$it" else "$mainUrl/$it" }

    private fun title(o: JSONObject) =
        listOf("name", "title", "original_name", "original_title").map { o.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: ""

    private fun toResponses(list: List<JSONObject>): List<SearchResponse> =
        list.filter { it.optString("id").isNotBlank() && title(it).isNotBlank() }.map { it0 ->
            val serie = isSerie(it0)
            val url = "$mainUrl/${if (serie) "s" else "m"}/${it0.optString("id")}"
            if (serie) newTvSeriesSearchResponse(title(it0), url, TvType.TvSeries) { posterUrl = poster(it0) }
            else newMovieSearchResponse(title(it0), url, TvType.Movie) { posterUrl = poster(it0) }
        }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val o = SinewixApi.get("/public/api/search/$q/${SinewixApi.API_KEY}") ?: return emptyList()
        return toResponses(SinewixApi.items(o))
    }

    private fun links(videos: JSONArray?): String {
        val out = mutableListOf<String>()
        if (videos != null) for (i in 0 until videos.length()) {
            val v = videos.opt(i)
            val raw = if (v is JSONObject) v.optString("link") else v?.toString() ?: ""
            raw.split("|||").map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.forEach { out += it }
        }
        return out.distinct().joinToString("\n")
    }

    override suspend fun load(url: String): LoadResponse? {
        val serie = url.contains("/s/")
        val id = url.substringAfterLast("/")
        val first = if (serie) "series/show" else "media/detail"
        val second = if (serie) "media/detail" else "series/show"
        var d = SinewixApi.get("/public/api/$first/$id/${SinewixApi.API_KEY}") as? JSONObject
        if (d == null || !(d.has("videos") || d.has("seasons") || d.has("name") || d.has("title")))
            d = SinewixApi.get("/public/api/$second/$id/${SinewixApi.API_KEY}") as? JSONObject
        d ?: return null
        val name = title(d)
        val plot = d.optString("overview").takeIf { it.isNotBlank() && it != "null" }
        val year = d.optString("release_date", d.optString("first_air_date")).take(4).toIntOrNull()
        return if (d.has("seasons")) {
            val eps = mutableListOf<Episode>()
            val seasons = d.optJSONArray("seasons") ?: JSONArray()
            for (i in 0 until seasons.length()) {
                val s = seasons.getJSONObject(i)
                val sn = s.optInt("season_number")
                val el = s.optJSONArray("episodes") ?: continue
                for (j in 0 until el.length()) {
                    val e = el.getJSONObject(j)
                    eps += newEpisode(links(e.optJSONArray("videos"))) {
                        this.season = sn
                        this.episode = e.optInt("episode_number")
                        this.name = e.optString("name").takeIf { it.isNotBlank() && it != "null" }
                    }
                }
            }
            newTvSeriesLoadResponse(name, url, TvType.TvSeries, eps) {
                posterUrl = poster(d); this.plot = plot; this.year = year
            }
        } else {
            var data = links(d.optJSONArray("videos"))
            d.optString("direct_link").takeIf { it.isNotBlank() && it != "null" }?.let { data += "\n$it" }
            newMovieLoadResponse(name, url, TvType.Movie, data.trim()) {
                posterUrl = poster(d); this.plot = plot; this.year = year
            }
        }
    }

    private fun quality(s: String) =
        Regex("(2160|1440|1080|720|480|360)").find(s)?.value?.toIntOrNull() ?: Qualities.Unknown.value

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        val ua = mapOf("User-Agent" to SinewixApi.WEB_UA)
        for (u in data.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.take(8)) {
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
