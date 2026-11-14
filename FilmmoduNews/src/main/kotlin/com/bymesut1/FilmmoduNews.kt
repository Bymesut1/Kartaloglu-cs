package com.bymesut1

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * FilmmoduNews — filmmodu.news
 * Film sayfasındaki oyuncu, gizli oynatıcıyı `data-pv` slug'ı ile kurar:
 * `<player>/assets/js/s.php?s=<slug>` → window.__PLAYER__ {stream, subs, audios}.
 * Oynatıcı host'u sayfadaki core.js kaynağından türetilir (pilavyerplay.top).
 */

class FilmmoduNews : MainAPI() {
    override var mainUrl = "https://filmmodu.news"
    override var name = "FilmmoduNews"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!FilmmoduNewsSettings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Filmler" -> MainPageData(name, "$mainUrl/filmler?page=SAYFA")
            "En Yeni" -> MainPageData(name, "$mainUrl/filmler?sirala=yeni&page=SAYFA")
            "En Yüksek Puan" -> MainPageData(name, "$mainUrl/filmler?sirala=puan&page=SAYFA")
            "Aile" -> MainPageData(name, "$mainUrl/tur/aile?page=SAYFA")
            "Aksiyon" -> MainPageData(name, "$mainUrl/tur/aksiyon?page=SAYFA")
            "Animasyon" -> MainPageData(name, "$mainUrl/tur/animasyon?page=SAYFA")
            "Belgesel" -> MainPageData(name, "$mainUrl/tur/belgesel?page=SAYFA")
            "Bilim Kurgu" -> MainPageData(name, "$mainUrl/tur/bilim-kurgu?page=SAYFA")
            "Dram" -> MainPageData(name, "$mainUrl/tur/dram?page=SAYFA")
            "Fantastik" -> MainPageData(name, "$mainUrl/tur/fantastik?page=SAYFA")
            "Gerilim" -> MainPageData(name, "$mainUrl/tur/gerilim?page=SAYFA")
            "Gizem" -> MainPageData(name, "$mainUrl/tur/gizem?page=SAYFA")
            "Komedi" -> MainPageData(name, "$mainUrl/tur/komedi?page=SAYFA")
            "Korku" -> MainPageData(name, "$mainUrl/tur/korku?page=SAYFA")
            "Romantik" -> MainPageData(name, "$mainUrl/tur/romantik?page=SAYFA")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url },
            hasNext = doc.selectFirst("link[rel=next]") != null,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/ara?q=$encoded", headers = uaHeaders).document
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    /** Tembel yüklenen (lazy) afişlerde gerçek adres data-src / data-lazy-src içinde olabilir. */
    private fun Element.posterSrcLazy(): String? {
        val candidates = listOf("data-src", "data-lazy-src", "data-original", "src")
        for (attrName in candidates) {
            val value = attr(attrName).trim()
            if (value.isNotEmpty() && !value.startsWith("data:")) return value
        }
        return null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
            this.year = selectFirst("p")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(selectFirst("span.badge-rating")?.text()?.trim())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val schema = movieSchema(doc)
        val title = doc.selectFirst("main h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: schema?.optString("name")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val duration = Regex("""PT(\d+)M""").find(schema?.optString("duration").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val actors = schema?.optJSONArray("actor")?.let { names(it) }.orEmpty()

        val sitePoster = schema?.optString("image")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: fixUrlNull(doc.selectFirst("main img[src*='/posters/']")?.attr("src"))
        val sitePlot = schema?.optString("description")?.takeIf { it.isNotBlank() }
        val siteYear = schema?.optString("datePublished")?.take(4)?.toIntOrNull()
        val siteTags = schema?.optJSONArray("genre")?.let { names(it) }
        val siteTrailer = schema?.optJSONObject("trailer")?.optString("embedUrl")?.takeIf { it.isNotBlank() }

        // TMDB: yalnızca filme tıklayınca (detay sayfasında) çalışır; liste/arama aynen kalır
        val searchTitle = title
            .replace(Regex("""\s*(Film\s*)?izle.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()
        val tmdbId = tmdbFind(searchTitle, siteYear)
        val tmdb = tmdbId?.let { tmdbDetails(it) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = tmdb?.poster ?: sitePoster
            this.backgroundPosterUrl = tmdb?.backdrop ?: sitePoster
            this.plot = tmdb?.plot ?: sitePlot
            this.year = siteYear ?: tmdb?.year
            this.tags = tmdb?.genres?.takeIf { it.isNotEmpty() } ?: siteTags
            this.score = Score.from10(schema?.optJSONObject("aggregateRating")?.optString("ratingValue"))
                ?: tmdb?.rating?.let { Score.from10(it) }
            this.duration = duration ?: tmdb?.runtime
            val finalActors = tmdb?.actors?.takeIf { it.isNotEmpty() }
            if (finalActors != null) {
                addActors(finalActors)
            } else if (actors.isNotEmpty()) {
                addActors(actors)
            }
            (tmdb?.trailer ?: siteTrailer)?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = {
            if (FilmmoduNewsSettings.qualityAllowed(it.quality)) callback(it)
        }
        val subtitleFilter: (SubtitleFile) -> Unit = {
            if (FilmmoduNewsSettings.subtitleAllowed(it.lang)) subtitleCallback(it)
        }
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val playerBase = doc.selectFirst("script[src*='/assets/js/core.js']")?.attr("src")
            ?.substringBefore("/assets/js/core.js")?.takeIf { it.startsWith("http") }
            ?: PLAYER_HOST

        var found = false
        for (slot in doc.select("div[data-pv]")) {
            val slug = slot.attr("data-pv").trim()
            if (slug.isEmpty()) continue
            val shellUrl = "$playerBase/assets/js/s.php?s=${URLEncoder.encode(slug, "UTF-8")}"
            // s.php origin kilidi: referer olmadan 403.
            val html = runCatching { app.get(shellUrl, referer = data, headers = uaHeaders).text }
                .getOrNull() ?: continue
            val json = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: continue
            val player = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val stream = player.optString("stream").takeIf { it.startsWith("http") } ?: continue

            val subs = player.optJSONArray("subs")
            for (i in 0 until (subs?.length() ?: 0)) {
                val sub = subs?.optJSONObject(i) ?: continue
                val src = sub.optString("src").takeIf { it.startsWith("http") } ?: continue
                val label = sub.optString("label").takeIf { it.isNotBlank() }
                    ?: sub.optString("lang").takeIf { it.isNotBlank() }
                    ?: "Altyazı"
                subtitleFilter.invoke(newSubtitleFile(label, src))
            }

            // Dublaj ayrı akış değil: master m3u8'in HLS ses gruplarında gelir (audios[]), oynatıcı seçer.
            linkCallback.invoke(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = shellUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** Film sayfasındaki Movie düğümü @graph içinde gelir. */
    private fun movieSchema(doc: Document): JSONObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            val graph = root.optJSONArray("@graph") ?: JSONArray().put(root)
            for (i in 0 until graph.length()) {
                val node = graph.optJSONObject(i) ?: continue
                if (node.optString("@type") == "Movie") return node
            }
        }
        return null
    }

    /** [{"@type":"Person","name":"..."}] dizisinden adları çıkarır. */
    private fun names(array: JSONArray): List<String> =
        (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotBlank() } }

    // ---------------- TMDB ----------------

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
        if (TMDB_API_KEY.isBlank() || TMDB_API_KEY.startsWith("BURAYA")) return null
        return try {
            val q = (listOf("api_key" to TMDB_API_KEY) + params)
                .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
            JSONObject(app.get("$TMDB_API/$path?$q", timeout = 20L).text)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tmdbFind(title: String, year: Int?): Int? {
        if (title.isBlank()) return null
        val attempts: List<Int?> = if (year != null) listOf(year, null) else listOf(null)
        for (y in attempts) {
            val params = mutableListOf(
                "query" to title,
                "language" to "tr-TR",
                "include_adult" to "false"
            )
            if (y != null) params.add("year" to y.toString())
            val results = tmdbJson("search/movie", *params.toTypedArray())?.optJSONArray("results") ?: continue
            if (results.length() > 0) {
                val id = results.optJSONObject(0)?.optInt("id", 0) ?: 0
                if (id > 0) return id
            }
        }
        return null
    }

    private suspend fun tmdbDetails(id: Int): TmdbInfo? {
        val d = tmdbJson(
            "movie/$id",
            "language" to "tr-TR",
            "append_to_response" to "credits,videos",
            "include_video_language" to "tr,en,null"
        ) ?: return null

        // Türkçe özet yoksa İngilizce'ye düş
        val plot = d.str("overview")
            ?: tmdbJson("movie/$id", "language" to "en-US")?.str("overview")

        val genres = mutableListOf<String>()
        d.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.str("name")?.let { genres.add(it) }
        }

        val actors = mutableListOf<Actor>()
        d.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 20)) {
                val c = arr.optJSONObject(i) ?: continue
                val n = c.str("name") ?: continue
                actors.add(Actor(n, c.str("profile_path")?.let { TMDB_PROFILE + it }))
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

        return TmdbInfo(
            plot = plot,
            poster = d.str("poster_path")?.let { TMDB_POSTER + it },
            backdrop = d.str("backdrop_path")?.let { TMDB_BACKDROP + it },
            year = d.str("release_date")?.take(4)?.toIntOrNull(),
            genres = genres,
            rating = d.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            runtime = d.optInt("runtime", 0).takeIf { it > 0 },
            actors = actors,
            trailer = trailer
        )
    }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf("Filmler", "En Yeni", "En Yüksek Puan", "Aile", "Aksiyon", "Animasyon",
            "Belgesel", "Bilim Kurgu", "Dram", "Fantastik", "Gerilim", "Gizem", "Komedi", "Korku",
            "Romantik")

        private const val CARD = "a[class*=group][href*='/film/']"
        private const val PLAYER_HOST = "https://pilavyerplay.top"
        private const val REPORT_HINT = "Sorun sürüyorsa biraz sonra tekrar dene."

        // TMDB API anahtarını buraya yaz (themoviedb.org > Ayarlar > API). Boş/yer tutucu kalırsa TMDB atlanır, site bilgileri kullanılır.
        private const val TMDB_API_KEY = "BURAYA_TMDB_API_KEY"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_POSTER = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_BACKDROP = "https://image.tmdb.org/t/p/original"
        private const val TMDB_PROFILE = "https://image.tmdb.org/t/p/w185"
    }
}

/** Eklenti ayarları: kalite üst sınırı, altyazı dili ve ana sayfa kategorileri. */
object FilmmoduNewsSettings {
    private const val FILE = "bymesut1_filmmodunews"
    private const val ROW_KEY = "rows"
    private var prefs: SharedPreferences? = null

    private val qualityLabels = arrayOf("Sınırsız", "1080p", "720p", "480p", "360p")
    private val qualityValues = intArrayOf(0, 1080, 720, 480, 360)
    private val subtitleLabels = arrayOf("Tümü", "Türkçe", "İngilizce", "Kapalı")
    private val subtitleValues = arrayOf("all", "tr", "en", "off")

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var qualityCap: Int
        get() = prefs?.getInt("quality_cap", 0) ?: 0
        set(value) {
            prefs?.edit()?.putInt("quality_cap", value)?.apply()
        }

    var subtitleLang: String
        get() = prefs?.getString("subtitle_lang", "all") ?: "all"
        set(value) {
            prefs?.edit()?.putString("subtitle_lang", value)?.apply()
        }

    fun qualityAllowed(quality: Int): Boolean {
        val cap = qualityCap
        return cap == 0 || quality <= 0 || quality <= cap
    }

    fun subtitleAllowed(language: String?): Boolean {
        val pref = subtitleLang
        if (pref == "off") return false
        if (pref == "all") return true
        val label = language?.lowercase().orEmpty()
        return when (pref) {
            "tr" -> label.contains("tr") || label.contains("türk") || label.contains("turk")
            "en" -> label.contains("en") || label.contains("ingiliz") || label.contains("english")
            else -> true
        }
    }

    /** Hiç kapatılmayan kategoriler görünür kalır; yeni kurulumda hepsi görünür. */
    fun rowEnabled(name: String): Boolean = prefs?.getStringSet(ROW_KEY, null)?.contains(name) != true

    fun setRows(names: Collection<String>, enabled: Set<String>) {
        prefs?.edit()?.putStringSet(ROW_KEY, names.filterNot { it in enabled }.toSet())?.apply()
    }

    fun showDialog(context: Context, title: String, rows: List<String>) {
        val activity = context as? Activity ?: return
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 16)
        }
        layout.addView(TextView(activity).apply { text = "Kalite üst sınırı" })
        val qualitySpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, qualityLabels)
            setSelection(qualityValues.indexOf(qualityCap).coerceAtLeast(0))
        }
        layout.addView(qualitySpinner)
        layout.addView(TextView(activity).apply { text = "Altyazı" })
        val subtitleSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, subtitleLabels)
            setSelection(subtitleValues.indexOf(subtitleLang).coerceAtLeast(0))
        }
        layout.addView(subtitleSpinner)
        layout.addView(TextView(activity).apply { text = "Ana sayfa kategorileri" })
        val checks = rows.map { name ->
            CheckBox(activity).apply {
                text = name
                isChecked = rowEnabled(name)
            }.also { layout.addView(it) }
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Kaydet") { _, _ ->
                qualityCap = qualityValues[qualitySpinner.selectedItemPosition]
                subtitleLang = subtitleValues[subtitleSpinner.selectedItemPosition]
                setRows(rows, checks.filter { it.isChecked }.map { it.text.toString() }.toSet())
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
