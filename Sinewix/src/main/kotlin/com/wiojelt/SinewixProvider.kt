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

    // data format: "endpoint||tur||yedekAramaKelimesi"
    // tur: m = film, s = dizi, e = yeni bolumler
    // Endpoint'ler orijinal Sinewix/EasyPlex uygulamasindaki katalog adresleridir.
    override val mainPage = mainPageOf(
        "genres/latestmovies/all||m||love" to "Son Eklenen Filmler",
        "genres/latestseries/all||s||dead" to "Son Eklenen Diziler",
        "media/seriesEpisodesAll||e||" to "Yeni Bölümler",
        "genres/latestanimes/all||s||city" to "Son Animeler",
        "genres/mediaLibrary/show/80/movie||m||" to "Suç Filmleri",
        "genres/mediaLibrary/show/80/serie||s||" to "Suç Dizileri",
        "genres/mediaLibrary/show/9648/movie||m||" to "Gizem Filmleri",
        "genres/mediaLibrary/show/9648/serie||s||" to "Gizem Dizileri",
        "genres/mediaLibrary/show/10769/serie||s||" to "Kore Diziler",
        "genres/mediaLibrary/show/16/movie||m||" to "Animasyonlar"
    )

    private val debugEndpoints = listOf(
        "genres/latestmovies/all", "genres/latestseries/all", "genres/latestanimes/all",
        "media/seriesEpisodesAll", "genres/mediaLibrary/show/80/movie",
        "genres/mediaLibrary/show/80/serie", "genres/mediaLibrary/show/9648/movie",
        "genres/mediaLibrary/show/10769/serie", "genres/mediaLibrary/show/16/movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("debug")) {
            val d = newMovieSearchResponse("DEBUG - buna dokun", "$mainUrl/debug", TvType.Movie)
            return newHomePageResponse(request.name, listOf(d), false)
        }
        val parts = request.data.split("||")
        val ep = parts.getOrNull(0).orEmpty()
        val kind = parts.getOrNull(1).orEmpty()
        val word = parts.getOrNull(2).orEmpty()

        val path = "/public/api/$ep/${SinewixApi.API_KEY}" + if (page > 1) "?page=$page" else ""
        val all: List<JSONObject> = try {
            val o = SinewixApi.get(path)
            if (o == null) emptyList() else SinewixApi.items(o)
        } catch (e: Exception) {
            emptyList()
        }

        var out: List<SearchResponse> = when {
            all.isEmpty() -> emptyList()
            kind == "e" -> toEpisodeResponses(all)
            kind == "s" || kind == "m" -> {
                val wantSerie = kind == "s"
                val filtered = all.filter { isSerie(it) == wantSerie }
                if (filtered.isNotEmpty()) toResponses(filtered) else toResponses(all, wantSerie)
            }
            else -> toResponses(all)
        }
        var hasNext = out.isNotEmpty()

        // Katalog endpoint'i bos donerse (yalnizca ilk sayfada) aramayla doldur
        if (out.isEmpty() && page == 1 && word.isNotBlank()) {
            val s = try { search(word) } catch (e: Exception) { emptyList() }
            val want = if (kind == "s") TvType.TvSeries else TvType.Movie
            val f = s.filter { it.type == want }
            out = if (f.isNotEmpty()) f else s
            hasNext = false
        }
        val result = if (kind == "e") out else out.distinctBy { it.url }
        return newHomePageResponse(request.name, result, hasNext)
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun isSerie(o: JSONObject): Boolean {
        val t = (o.optString("type") + " " + o.optString("media_type")).lowercase()
        if (Regex("serie|anime|tv").containsMatchIn(t)) return true
        if (t.contains("movie")) return false
        return o.has("seasons") || o.has("number_of_seasons") ||
            o.optString("first_air_date").let { it.isNotBlank() && it != "null" }
    }

    private fun poster(o: JSONObject): String? =
        listOf("poster_path", "poster", "cover", "image", "backdrop_path", "still_path").map { o.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
            ?.let { if (it.startsWith("http")) it else if (it.startsWith("/")) "https://image.tmdb.org/t/p/w500$it" else "$mainUrl/$it" }

    private fun title(o: JSONObject) =
        listOf("name", "title", "original_name", "original_title").map { o.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: ""

    private fun toResponses(list: List<JSONObject>, forceSerie: Boolean? = null): List<SearchResponse> =
        list.filter { it.optString("id").isNotBlank() && title(it).isNotBlank() }.map { it0 ->
            val serie = forceSerie ?: isSerie(it0)
            val url = "$mainUrl/${if (serie) "s" else "m"}/${it0.optString("id")}"
            if (serie) newTvSeriesSearchResponse(title(it0), url, TvType.TvSeries) { posterUrl = poster(it0) }
            else newMovieSearchResponse(title(it0), url, TvType.Movie) { posterUrl = poster(it0) }
        }

    private fun toEpisodeResponses(list: List<JSONObject>): List<SearchResponse> =
        list.mapNotNull { o ->
            val sid = o.optString("serie_id").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("id")
            val t = title(o)
            if (sid.isBlank() || t.isBlank()) return@mapNotNull null
            val sn = o.optInt("season_number")
            val en = o.optInt("episode_number")
            val label = if (en > 0) "$t S%02dE%02d".format(sn, en) else t
            newTvSeriesSearchResponse(label, "$mainUrl/s/$sid", TvType.TvSeries) { posterUrl = poster(o) }
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
        if (url.endsWith("/debug")) {
            val sb = StringBuilder()
            for (ep in debugEndpoints) {
                val o = try { SinewixApi.get("/public/api/$ep/${SinewixApi.API_KEY}") } catch (e: Exception) { null }
                val n = if (o == null) 0 else SinewixApi.items(o).size
                val kindText = when (o) {
                    null -> "BOS"
                    is JSONObject -> "obj[" + o.keys().asSequence().take(5).joinToString(",") + "]"
                    is JSONArray -> "dizi"
                    else -> "diger"
                }
                sb.append("$ep: $kindText, item=$n\n")
            }
            val sr = try { SinewixApi.get("/public/api/search/love/${SinewixApi.API_KEY}") } catch (e: Exception) { null }
            val first = sr?.let { SinewixApi.items(it).firstOrNull() }
            sb.append("\nARAMA ORNEK:\n").append(first?.toString()?.take(1200) ?: "yok")
            return newMovieLoadResponse("DEBUG", url, TvType.Movie, "") { this.plot = sb.toString() }
        }
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
