package com.wiojelt

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Sinewix.
 *
 * BerkStream ile ayni mantik:
 *  - Ana sayfa raflari TMDB API'sinden (Turkce baslik, afis, sonsuz kaydirma) gelir.
 *  - Icerik sayfasi TMDB'den doldurulur (ozet, oyuncular, fragman, sezon/bolum, oneriler).
 *  - Sinewix taramasi yalnizca OYNAT'a basildiginda calisir: TMDB iceriginin adi/yili ile
 *    Sinewix'te aranir, bulunan kaydin linkleri oynaticiya verilir.
 *
 * Arama bilerek Sinewix'in KENDI aramasi olarak birakildi: BerkStream Sinewix'i kaynak olarak
 * `search()` + `load()` ile tarıyor ve oradan "gercekten Sinewix'te var" bilgisini bekliyor.
 */
class SinewixProvider : MainAPI() {
    override var mainUrl = "https://www.ydfvfdizipanel.ru"
    override var name = "Sinewix"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val listImg = "https://image.tmdb.org/t/p/w342"
    private val posterImg = "https://image.tmdb.org/t/p/w500"
    private val backdropImg = "https://image.tmdb.org/t/p/w1280"
    private val stillImg = "https://image.tmdb.org/t/p/w300"
    private val profileImg = "https://image.tmdb.org/t/p/w185"

    private val cacheMs = 10 * 60 * 1000L
    private val detailTtlMs = 30 * 60 * 1000L

    // ------------------------------------------------------------------------------------
    // Raflar
    //   "tmdb|<tur>|<tmdb yolu>"  -> TMDB rafi (tur: movie / tv / mixed)
    //   "native|ep1,ep2||kelime||m veya s" -> Sinewix'in kendi rafi (endpoint'ler sirayla denenir)
    //   "debug" -> teshis ekrani
    // ------------------------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "tmdb|mixed|trending/all/day" to "🔥  GÜNÜN TRENDLERİ",
        "tmdb|movie|discover/movie?sort_by=popularity.desc&with_watch_monetization_types=flatrate&vote_count.gte=50" to "🏆  BUGÜN İLK 10 FİLM",
        "tmdb|tv|discover/tv?sort_by=popularity.desc&with_watch_monetization_types=flatrate&vote_count.gte=50" to "🏆  BUGÜN İLK 10 DİZİ",
        "tmdb|movie|movie/now_playing?region=TR" to "🎬  VİZYONDAKİ FİLMLER",
        "tmdb|movie|movie/upcoming?region=TR" to "🗓️  YAKINDA",
        "tmdb|movie|trending/movie/week" to "📈  HAFTANIN POPÜLER FİLMLERİ",
        "tmdb|tv|trending/tv/week" to "📺  HAFTANIN POPÜLER DİZİLERİ",
        "tmdb|movie|discover/movie?sort_by=primary_release_date.desc&vote_count.gte=25" to "🆕  YENİ ÇIKAN FİLMLER",
        "tmdb|movie|discover/movie?sort_by=vote_average.desc&vote_count.gte=400" to "⭐  EN YÜKSEK PUANLI FİLMLER",
        "tmdb|tv|discover/tv?sort_by=vote_average.desc&vote_count.gte=250" to "⭐  EN YÜKSEK PUANLI DİZİLER",
        "tmdb|tv|discover/tv?with_original_language=tr&sort_by=popularity.desc" to "🇹🇷  TÜRK DİZİLERİ",
        "tmdb|movie|discover/movie?with_original_language=tr&sort_by=popularity.desc" to "🇹🇷  TÜRK FİLMLERİ",
        "native|movies/latest,media/latestmovies,media/newmovies||love||m" to "🎞️  SİNEWİX • SON EKLENEN FİLMLER",
        "native|series/latest,series/recents,series/newepisodes||dead||s" to "🎞️  SİNEWİX • SON EKLENEN DİZİLER",
        "tmdb|movie|discover/movie?with_watch_providers=8&sort_by=popularity.desc&vote_count.gte=30" to "ᴺ  NETFLIX • FİLM",
        "tmdb|tv|discover/tv?with_watch_providers=8&sort_by=popularity.desc&vote_count.gte=30" to "ᴺ  NETFLIX • DİZİ",
        "tmdb|movie|discover/movie?with_watch_providers=119&sort_by=popularity.desc&vote_count.gte=30" to "▶  PRIME VIDEO • FİLM",
        "tmdb|tv|discover/tv?with_watch_providers=119&sort_by=popularity.desc&vote_count.gte=30" to "▶  PRIME VIDEO • DİZİ",
        "tmdb|movie|discover/movie?with_watch_providers=337&sort_by=popularity.desc&vote_count.gte=30" to "✨  DISNEY+ • FİLM",
        "tmdb|tv|discover/tv?with_watch_providers=337&sort_by=popularity.desc&vote_count.gte=30" to "✨  DISNEY+ • DİZİ",
        "tmdb|movie|discover/movie?with_watch_providers=1899&sort_by=popularity.desc&vote_count.gte=30" to "🟣  HBO MAX • FİLM",
        "tmdb|tv|discover/tv?with_watch_providers=1899&sort_by=popularity.desc&vote_count.gte=30" to "🟣  HBO MAX • DİZİ",
        "tmdb|movie|discover/movie?with_watch_providers=350&watch_region=US&sort_by=popularity.desc&vote_count.gte=30" to "  APPLE TV+ • FİLM",
        "tmdb|tv|discover/tv?with_watch_providers=350&watch_region=US&sort_by=popularity.desc&vote_count.gte=30" to "  APPLE TV+ • DİZİ",
        "tmdb|tv|discover/tv?with_watch_providers=2235&sort_by=popularity.desc&vote_count.gte=10" to "🔴  TABİİ",
        "tmdb|movie|discover/movie?with_genres=28&sort_by=popularity.desc" to "💥  AKSİYON",
        "tmdb|movie|discover/movie?with_genres=35&sort_by=popularity.desc" to "😂  KOMEDİ",
        "tmdb|movie|discover/movie?with_genres=878&sort_by=popularity.desc" to "👽  BİLİM KURGU",
        "tmdb|movie|discover/movie?with_genres=27&sort_by=popularity.desc" to "😱  KORKU",
        "tmdb|movie|discover/movie?with_genres=18&sort_by=popularity.desc" to "🎭  DRAM",
        "tmdb|movie|discover/movie?with_genres=53&sort_by=popularity.desc" to "🕵️  GERİLİM",
        "tmdb|movie|discover/movie?with_genres=10749&sort_by=popularity.desc" to "💘  ROMANTİK",
        "tmdb|movie|discover/movie?with_genres=16&sort_by=popularity.desc" to "🎨  ANİMASYON • FİLM",
        "tmdb|tv|discover/tv?with_genres=16&sort_by=popularity.desc" to "🧒  ANİMASYON • DİZİ",
        "tmdb|tv|discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "🎌  ANİME",
        "tmdb|movie|discover/movie?with_genres=99&sort_by=popularity.desc" to "📖  BELGESEL",
        "tmdb|movie|discover/movie?with_genres=36&sort_by=popularity.desc" to "🏛️  TARİH • FİLM",
        "tmdb|movie|discover/movie?with_genres=10752&sort_by=popularity.desc" to "🎖️  SAVAŞ • FİLM",
        "tmdb|movie|discover/movie?with_genres=37&sort_by=popularity.desc" to "🤠  WESTERN • FİLM",
        "tmdb|tv|tv/on_the_air" to "📅  BU HAFTA YENİ BÖLÜM",
        "tmdb|movie|discover/movie?sort_by=vote_average.desc&vote_count.gte=200&vote_count.lte=1200" to "💎  GİZLİ CEVHERLER",
        "tmdb|movie|discover/movie?with_runtime.lte=95&vote_count.gte=300&sort_by=vote_average.desc" to "⏱️  KISA GECE • 95 DK ALTI",
        "tmdb|movie|discover/movie?primary_release_date.gte=1990-01-01&primary_release_date.lte=1999-12-31&sort_by=vote_average.desc&vote_count.gte=500" to "🕰️  90'LAR KLASİKLERİ",
        "tmdb|movie|discover/movie?sort_by=vote_average.desc&vote_count.gte=1500&primary_release_date.gte=2015-01-01" to "🏆  OSCAR YOLUNDA",
        "debug||||" to "Debug (bana ekran goruntusu at)"
    )

    private val debugEndpoints = listOf(
        "media/trending", "media/popularmovies", "media/showcase", "media/latestmovies",
        "media/newmovies", "media/mostwatched", "media/recents", "media/latest", "media/all",
        "movies/latest", "movies/popular", "movies/all",
        "series/latest", "series/recents", "series/newepisodes", "series/popular",
        "series/trending", "series/mostwatched", "series/all",
        "animes/latest", "animes/popular", "animes/recents",
        "genres/list", "genres/latest", "home", "featured", "media/featured"
    )

    // ------------------------------------------------------------------------------------
    // TMDB yardimcilari
    // ------------------------------------------------------------------------------------
    private val shelfCache = ConcurrentHashMap<String, Pair<Long, Pair<List<SearchResponse>, Boolean>>>()
    private val detailCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    private fun JSONObject.str(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private suspend fun tmdbJson(url: String): JSONObject? = try {
        JSONObject(app.get(url).text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun tmdbGet(path: String, extra: String = "", language: String = "tr-TR"): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        return tmdbJson("$tmdbApi/$path${sep}api_key=$tmdbKey&language=$language$extra")
    }

    /** TMDB kaydi -> kart. [fallbackKind] "movie", "tv" ya da "mixed" (media_type'a bakilir). */
    private fun JSONObject.toCard(fallbackKind: String): SearchResponse? {
        if (optBoolean("adult", false)) return null
        val itemId = optInt("id", 0).takeIf { it > 0 } ?: return null
        val kind = if (fallbackKind == "mixed") str("media_type") ?: return null else fallbackKind
        if (kind != "movie" && kind != "tv") return null
        val label = (if (kind == "tv") str("name") ?: str("title") else str("title") ?: str("name"))
            ?.trim() ?: return null
        val poster = str("poster_path")?.let { "$listImg$it" }
        val year = (if (kind == "tv") str("first_air_date") else str("release_date"))
            ?.take(4)?.toIntOrNull()
        val vote = optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        val url = "https://www.themoviedb.org/$kind/$itemId"
        return if (kind == "tv") {
            newTvSeriesSearchResponse(label, url, TvType.TvSeries) {
                this.id = itemId
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(vote)
            }
        } else {
            newMovieSearchResponse(label, url, TvType.Movie) {
                this.id = itemId
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(vote)
            }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private suspend fun tmdbShelf(path: String, kind: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val cacheKey = "$kind|$path|$page"
        shelfCache[cacheKey]
            ?.takeIf { System.currentTimeMillis() - it.first < cacheMs }
            ?.let { return it.second }

        val sep = if (path.contains("?")) "&" else "?"
        // Raf kendi bolgesini belirtmisse (Apple TV+ -> US) dokunma.
        val region = if (path.contains("watch_region=")) "" else "&watch_region=TR"
        val o = tmdbJson(
            "$tmdbApi/$path${sep}api_key=$tmdbKey&language=tr-TR&include_adult=false$region&page=$page"
        ) ?: return emptyList<SearchResponse>() to false

        val items = o.optJSONArray("results").objects().mapNotNull { it.toCard(kind) }
        val hasNext = page < o.optInt("total_pages", 1).coerceAtMost(500)
        val result = items to hasNext
        if (items.isNotEmpty()) shelfCache[cacheKey] = System.currentTimeMillis() to result
        return result
    }

    // ------------------------------------------------------------------------------------
    // Ana sayfa
    // ------------------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data

        if (data.startsWith("debug")) {
            val d = newMovieSearchResponse("DEBUG - buna dokun", "$mainUrl/debug", TvType.Movie)
            return newHomePageResponse(request.name, listOf(d), false)
        }

        if (data.startsWith("tmdb|")) {
            val kind = data.substringAfter("tmdb|").substringBefore("|")
            val path = data.substringAfter("tmdb|").substringAfter("|")
            val (items, hasNext) = try {
                tmdbShelf(path, kind, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<SearchResponse>() to false
            }
            return newHomePageResponse(request, items, hasNext)
        }

        // Sinewix'in kendi rafi: "ep1,ep2||kelime||m/s"
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val parts = data.removePrefix("native|").split("||")
        val endpoints = parts.getOrNull(0).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val word = parts.getOrNull(1).orEmpty()
        val kind = parts.getOrNull(2).orEmpty()

        var items: List<SearchResponse> = emptyList()
        for (ep in endpoints) {
            val o = apiGet("/public/api/$ep/${SinewixApi.API_KEY}")
            items = if (o == null) emptyList() else toResponses(SinewixApi.items(o))
            if (items.isNotEmpty()) break
        }
        if (items.isEmpty() && word.isNotBlank()) {
            items = try { search(word) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
        if (kind == "m" || kind == "s") {
            val want = if (kind == "s") TvType.TvSeries else TvType.Movie
            val filtered = items.filter { it.type == want }
            if (filtered.isNotEmpty()) items = filtered
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
    }

    // ------------------------------------------------------------------------------------
    // Sinewix API yardimcilari (eski davranis)
    // ------------------------------------------------------------------------------------
    private suspend fun apiGet(path: String): Any? = try {
        SinewixApi.get(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun isSerie(o: JSONObject): Boolean {
        val t = (o.optString("type") + " " + o.optString("media_type")).lowercase()
        if (Regex("serie|anime|tv").containsMatchIn(t)) return true
        if (t.contains("movie")) return false
        return o.has("seasons") || o.has("number_of_seasons") ||
            o.optString("first_air_date").let { it.isNotBlank() && it != "null" }
    }

    private fun poster(o: JSONObject): String? =
        listOf("poster_path", "poster", "cover", "image", "backdrop_path").map { o.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
            ?.let { if (it.startsWith("http")) it else if (it.startsWith("/")) "$posterImg$it" else "$mainUrl/$it" }

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

    private suspend fun fetchDetail(id: String, serie: Boolean): JSONObject? {
        val first = if (serie) "series/show" else "media/detail"
        val second = if (serie) "media/detail" else "series/show"
        var d = apiGet("/public/api/$first/$id/${SinewixApi.API_KEY}") as? JSONObject
        if (d == null || !(d.has("videos") || d.has("seasons") || d.has("name") || d.has("title")))
            d = apiGet("/public/api/$second/$id/${SinewixApi.API_KEY}") as? JSONObject
        return d
    }

    // ------------------------------------------------------------------------------------
    // TMDB icerik -> Sinewix eslestirme (yalnizca oynat'a basilinca calisir)
    // ------------------------------------------------------------------------------------
    /** loadLinks'e giden veri: hangi TMDB icerigi, hangi sezon/bolum. */
    private data class Target(
        val tmdbId: Int,
        val series: Boolean,
        val names: List<String>,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("sx", 1)
            put("tmdb", tmdbId)
            put("series", series)
            put("names", JSONArray(names))
            year?.let { put("year", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
        }.toString()

        companion object {
            fun parse(text: String): Target? = runCatching {
                val o = JSONObject(text)
                if (!o.has("sx")) return@runCatching null
                val names = o.optJSONArray("names")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                }.orEmpty()
                Target(
                    tmdbId = o.getInt("tmdb"),
                    series = o.optBoolean("series"),
                    names = names,
                    year = if (o.has("year")) o.optInt("year") else null,
                    season = if (o.has("season")) o.optInt("season") else null,
                    episode = if (o.has("episode")) o.optInt("episode") else null,
                )
            }.getOrNull()
        }
    }

    private fun norm(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('ı', 'i')
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")

    private fun yearOf(o: JSONObject): Int? =
        listOf("release_date", "first_air_date", "year")
            .map { o.optString(it).take(4).toIntOrNull() }
            .firstOrNull { it != null }

    private fun yearFits(o: JSONObject, want: Int?): Boolean {
        val got = yearOf(o)
        return want == null || got == null || kotlin.math.abs(got - want) <= 1
    }

    private fun candidateNames(o: JSONObject): List<String> =
        listOf("name", "title", "original_name", "original_title")
            .mapNotNull { o.str(it) }
            .map(::norm)
            .filter { it.isNotBlank() }

    private fun tmdbIdOf(o: JSONObject): String? =
        listOf("tmdb_id", "tmdbId").mapNotNull { o.str(it) }.firstOrNull()

    private fun pick(items: List<JSONObject>, t: Target, names: List<String>, strictKind: Boolean): JSONObject? {
        val wanted = names.map(::norm).filter { it.isNotBlank() }
        val pool = items.filter {
            it.optString("id").isNotBlank() && (!strictKind || isSerie(it) == t.series)
        }
        // 1) Sinewix kaydi TMDB kimligini tasiyorsa kesin eslesme.
        pool.firstOrNull { tmdbIdOf(it) == t.tmdbId.toString() }?.let { return it }
        // 2) Ad birebir + yil uyumlu.
        pool.firstOrNull { c -> yearFits(c, t.year) && candidateNames(c).any { it in wanted } }?.let { return it }
        // 3) "Baslik: Alt baslik" farklari: yil BIREBIR tutuyorsa icerme kabul.
        if (strictKind) {
            pool.firstOrNull { c ->
                t.year != null && yearOf(c) == t.year && candidateNames(c).any { cn ->
                    wanted.any { w -> minOf(cn.length, w.length) >= 5 && (cn.contains(w) || w.contains(cn)) }
                }
            }?.let { return it }
        }
        return null
    }

    private suspend fun findByNames(t: Target, queries: List<String>, allNames: List<String>): String? {
        for (q in queries) {
            val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
            val o = apiGet("/public/api/search/$enc/${SinewixApi.API_KEY}") ?: continue
            val items = SinewixApi.items(o)
            val hit = pick(items, t, allNames, true) ?: pick(items, t, allNames, false)
            if (hit != null) return hit.optString("id")
        }
        return null
    }

    /** Sinewix'teki karsilik: once Turkce/orijinal adla, olmazsa Ingilizce adla, son care ayni kimlikle. */
    private suspend fun resolveDetail(t: Target): JSONObject? {
        val key = "${if (t.series) "s" else "m"}${t.tmdbId}"
        detailCache[key]
            ?.takeIf { System.currentTimeMillis() - it.first < detailTtlMs }
            ?.let { return it.second }

        var names = t.names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        var id = findByNames(t, names, names)
        if (id == null) {
            val kind = if (t.series) "tv" else "movie"
            val english = tmdbGet("$kind/${t.tmdbId}", language = "en-US")
                ?.let { if (t.series) it.str("name") else it.str("title") }
            if (english != null && english !in names) {
                names = names + english
                id = findByNames(t, listOf(english), names)
            }
        }
        var detail = id?.let { fetchDetail(it, t.series) }
        if (detail == null) {
            // Bazi panellerde kayit kimligi TMDB kimligiyle ayni; ad dogrulanirsa kabul.
            val direct = fetchDetail(t.tmdbId.toString(), t.series)
            if (direct != null) {
                val got = norm(title(direct))
                if (got.isNotBlank() && names.any { norm(it) == got }) detail = direct
            }
        }
        detail?.let { detailCache[key] = System.currentTimeMillis() to it }
        return detail
    }

    /** Hedef icin "\n" ile ayrilmis ham link listesi (eski loadLinks bicimi). */
    private suspend fun resolveLinks(t: Target): String? {
        val d = resolveDetail(t) ?: return null
        if (!t.series) {
            var data = links(d.optJSONArray("videos"))
            d.str("direct_link")?.let { data += "\n$it" }
            return data.trim().ifBlank { null }
        }
        val seasons = d.optJSONArray("seasons").objects()
        val season = seasons.firstOrNull { it.optInt("season_number") == t.season }
            ?: seasons.singleOrNull()
        val episodes = season?.optJSONArray("episodes").objects()
        val ep = episodes.firstOrNull { it.optInt("episode_number") == t.episode } ?: return null
        return links(ep.optJSONArray("videos")).ifBlank { null }
    }

    // ------------------------------------------------------------------------------------
    // load
    // ------------------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("themoviedb.org/")) return loadTmdb(url)
        return loadSinewix(url)
    }

    private fun List<JSONObject>.trailerUrls(): List<String> = this
        .filter { it.str("site").equals("YouTube", true) && it.str("key") != null }
        .filter { it.str("type") == "Trailer" || it.str("type") == "Teaser" }
        .sortedWith(
            compareBy<JSONObject>(
                { if (it.str("iso_639_1") == "tr") 0 else 1 },
                { if (it.str("type") == "Trailer") 0 else 1 },
                { if (it.optBoolean("official")) 0 else 1 },
            )
        )
        .map { "https://www.youtube.com/watch?v=${it.str("key")}" }
        .distinct()

    private suspend fun loadTmdb(url: String): LoadResponse? {
        val m = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url) ?: return null
        val kind = m.groupValues[1]
        val id = m.groupValues[2].toIntOrNull() ?: return null
        val isTv = kind == "tv"

        val d = tmdbGet(
            "$kind/$id",
            "&append_to_response=videos,credits,recommendations&include_video_language=tr,en,null"
        ) ?: return null

        val title = (if (isTv) d.str("name") else d.str("title")) ?: return null
        val original = if (isTv) d.str("original_name") else d.str("original_title")
        val year = (if (isTv) d.str("first_air_date") else d.str("release_date"))?.take(4)?.toIntOrNull()
        val posterUrl = d.str("poster_path")?.let { "$posterImg$it" }
        val backdrop = d.str("backdrop_path")?.let { "$backdropImg$it" }
        val plot = d.str("overview")
        val tags = d.optJSONArray("genres").objects().mapNotNull { it.str("name") }
        val genreIds = d.optJSONArray("genres").objects().map { it.optInt("id") }
        val vote = d.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        val runtime = if (isTv) {
            d.optJSONArray("episode_run_time")?.optInt(0, 0)
        } else {
            d.optInt("runtime", 0)
        }?.takeIf { it > 0 }

        val actors = d.optJSONObject("credits")?.optJSONArray("cast").objects().take(15).mapNotNull { c ->
            val n = c.str("name") ?: return@mapNotNull null
            ActorData(
                Actor(n, c.str("profile_path")?.let { "$profileImg$it" }),
                roleString = c.str("character"),
            )
        }
        val trailers = d.optJSONObject("videos")?.optJSONArray("results").objects().trailerUrls()
        val recs = d.optJSONObject("recommendations")?.optJSONArray("results").objects()
            .mapNotNull { it.toCard(kind) }

        val names = listOfNotNull(title, original).distinct()

        if (!isTv) {
            val target = Target(id, false, names, year, null, null)
            return newMovieLoadResponse(title, url, TvType.Movie, target.toJson()) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = runtime
                this.score = Score.from10(vote)
                this.actors = actors
                this.recommendations = recs
                if (trailers.isNotEmpty()) addTrailer(trailers)
            }
        }

        val anime = d.str("original_language") == "ja" && 16 in genreIds
        val seasonNumbers = d.optJSONArray("seasons").objects()
            .map { it.optInt("season_number", -1) to it.optInt("episode_count", 0) }
            .filter { it.first >= 1 }

        val episodes = seasonNumbers.amap { (seasonNo, count) ->
            val s = tmdbGet("tv/$id/season/$seasonNo")
            val list = s?.optJSONArray("episodes").objects()
            if (list.isNotEmpty()) {
                list.map { e ->
                    val epNo = e.optInt("episode_number")
                    newEpisode(Target(id, true, names, year, seasonNo, epNo).toJson()) {
                        this.season = seasonNo
                        this.episode = epNo
                        this.name = e.str("name")
                        this.posterUrl = e.str("still_path")?.let { "$stillImg$it" }
                        this.description = e.str("overview")
                        addDate(e.str("air_date"))
                    }
                }
            } else {
                // Bolum listesi alinamazsa sayiya gore uret.
                (1..count).map { epNo ->
                    newEpisode(Target(id, true, names, year, seasonNo, epNo).toJson()) {
                        this.season = seasonNo
                        this.episode = epNo
                    }
                }
            }
        }.flatten()

        return newTvSeriesLoadResponse(
            title, url, if (anime) TvType.Anime else TvType.TvSeries, episodes
        ) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = runtime
            this.score = Score.from10(vote)
            this.actors = actors
            this.recommendations = recs
            if (trailers.isNotEmpty()) addTrailer(trailers)
        }
    }

    /** Sinewix'in kendi arama/raf sonuclari (eski davranis; BerkStream de bu yolu kullanir). */
    private suspend fun loadSinewix(url: String): LoadResponse? {
        if (url.endsWith("/debug")) {
            val sb = StringBuilder()
            for (ep in debugEndpoints) {
                val o = apiGet("/public/api/$ep/${SinewixApi.API_KEY}")
                val n = if (o == null) 0 else SinewixApi.items(o).size
                val kindText = when (o) {
                    null -> "BOS"
                    is JSONObject -> "obj[" + o.keys().asSequence().take(5).joinToString(",") + "]"
                    is JSONArray -> "dizi"
                    else -> "diger"
                }
                sb.append("$ep: $kindText, item=$n\n")
            }
            val sr = apiGet("/public/api/search/love/${SinewixApi.API_KEY}")
            val first = sr?.let { SinewixApi.items(it).firstOrNull() }
            sb.append("\nARAMA ORNEK:\n").append(first?.toString()?.take(1200) ?: "yok")
            return newMovieLoadResponse("DEBUG", url, TvType.Movie, "") { this.plot = sb.toString() }
        }
        val serie = url.contains("/s/")
        val id = url.substringAfterLast("/")
        val d = fetchDetail(id, serie) ?: return null
        val name = title(d)
        val plot = d.str("overview")
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
                        this.name = e.str("name")
                    }
                }
            }
            newTvSeriesLoadResponse(name, url, TvType.TvSeries, eps) {
                posterUrl = poster(d); this.plot = plot; this.year = year
            }
        } else {
            var data = links(d.optJSONArray("videos"))
            d.str("direct_link")?.let { data += "\n$it" }
            newMovieLoadResponse(name, url, TvType.Movie, data.trim()) {
                posterUrl = poster(d); this.plot = plot; this.year = year
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // loadLinks
    // ------------------------------------------------------------------------------------
    private fun quality(s: String) =
        Regex("(2160|1440|1080|720|480|360)").find(s)?.value?.toIntOrNull() ?: Qualities.Unknown.value

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TMDB'den gelen veri: once Sinewix'te karsiligini bul, sonra linkleri cikar.
        // Eski (Sinewix kendi) veri: dogrudan "\n" ile ayrilmis link listesi.
        val raw = Target.parse(data)?.let { resolveLinks(it) ?: return false } ?: data
        return emitLinks(raw, callback)
    }

    private suspend fun emitLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean {
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
