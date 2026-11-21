package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Filmmakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "Fimmakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Site Cloudflare doğrulaması (403 + küçük HTML) veriyor; WebView ile çözüp çerezi saklar
    @Volatile
    private var cfKiller = CloudflareKiller()
    private val cfLock = Semaphore(1)
    @Volatile
    private var cfWarm = false

    // ---- Kalıcı çerez: eklenti yeniden yüklense / uygulama kapansa da çerez diskte kalır ----
    // Android'de java.io.tmpdir uygulamanın cache klasörüdür; ek izin ya da API gerekmez.
    private val cfFile by lazy {
        java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "filmmakinesi_cf.txt")
    }
    private val cfMaxAgeMs = 12L * 60 * 60 * 1000 // bundan eski kayıt kullanılmaz, yeniden çözülür
    @Volatile
    private var cfSavedSig = ""

    private fun cfRestore() {
        try {
            if (!cfFile.exists()) return
            val lines = cfFile.readLines()
            val ts = lines.firstOrNull()?.trim()?.toLongOrNull() ?: return
            if (System.currentTimeMillis() - ts > cfMaxAgeMs) {
                cfFile.delete()
                return
            }
            var n = 0
            for (ln in lines.drop(1)) {
                val tab = ln.indexOf('\t')
                if (tab <= 0) continue
                val host = ln.substring(0, tab)
                val map = LinkedHashMap<String, String>()
                for (p in ln.substring(tab + 1).split(";")) {
                    val i = p.indexOf('=')
                    if (i > 0) map[p.substring(0, i).trim()] = p.substring(i + 1).trim()
                }
                if (map.isNotEmpty()) {
                    cfKiller.savedCookies[host] = map
                    n++
                }
            }
            if (n > 0) {
                cfWarm = true
                cfSavedSig = lines.drop(1).joinToString("\n")
                log("CF çerez diskten yüklendi host=$n")
            }
        } catch (e: Throwable) {
            log("CF restore hata ${e.message?.take(60)}")
        }
    }

    private fun cfSave() {
        try {
            val entries = cfKiller.savedCookies.entries.toList().filter { it.value.isNotEmpty() }
            if (entries.isEmpty()) return
            val body = entries.joinToString("\n") { en ->
                en.key + "\t" + en.value.entries.joinToString(";") { "${it.key}=${it.value}" }
            }
            if (body == cfSavedSig) return // değişmediyse zaman damgasını yenileme
            cfSavedSig = body
            cfFile.writeText("${System.currentTimeMillis()}\n$body")
            log("CF çerez diske kaydedildi")
        } catch (e: Throwable) {
            log("CF save hata ${e.message?.take(60)}")
        }
    }

    private fun cfClear() {
        try {
            cfSavedSig = ""
            if (cfFile.exists()) cfFile.delete()
        } catch (_: Throwable) {
        }
    }

    init {
        cfRestore()
    }

    // CloudflareKiller çerezi bir kez alınca süresi dolsa bile onu kullanmaya devam eder (yeniden çözmez),
    // bu yüzden bir süre sonra hep 403 gelir. 403 alırsak çözücüyü sıfırlayıp bir kez daha deneriz.
    // İlk çözüm sırasında onlarca istek aynı anda WebView açmasın diye ısınana kadar sıraya alınır.
    private suspend fun cfGet(url: String, headers: Map<String, String>, timeout: Long = 30L): NiceResponse {
        val k = cfKiller
        var r = if (cfWarm) {
            app.get(url, headers = headers, interceptor = k, timeout = timeout)
        } else {
            cfLock.acquire()
            try {
                app.get(url, headers = headers, interceptor = cfKiller, timeout = timeout)
            } finally {
                cfLock.release()
            }
        }
        if (r.code == 403 || r.code == 503) {
            log("CF 403: çözücü sıfırlanıyor ${url.take(70)}")
            cfLock.acquire()
            try {
                if (cfKiller === k) {
                    cfKiller = CloudflareKiller()
                    cfWarm = false
                    cfClear()
                }
                r = app.get(url, headers = headers, interceptor = cfKiller, timeout = timeout)
            } finally {
                cfLock.release()
            }
        }
        if (r.code in 200..299) {
            cfWarm = true
            cfSave()
        }
        return r
    }

    private val androidUA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val pageHeaders
        get() = mapOf(
            "User-Agent" to androidUA,
            "Accept-Language" to "tr-TR,tr;q=0.9",
            "Referer" to "$mainUrl/"
        )

    // ---------------- Site istekleri için ayarlar (HDFilmCehennemi ile aynı) ----------------
    private val IC = RegexOption.IGNORE_CASE
    private val hosts = listOf("https://filmmakinesi.to", "https://www.filmmakinesi.to")
    private val uaSiteMobile = "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
    private var good: Pair<String, Boolean>? = null   // çalışan adres + UA biçimi

    // ---------------- Kategoriler ----------------

    override val mainPage = mainPageOf(
        "/filmler-1/" to "Son Filmler",
        "/yabanci-dizi-izle-1/" to "Diziler",
        "/ulke/turkiye-fm4/" to "Yerli Filmler",
        "/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "/seri-filmler-izle-1/" to "Seri Filmler",
        "/kanal/netflix-fm1/" to "Netflix",
        "/kanal/disney-fmgj6b/" to "Disney",
        "/kanal/amazon/" to "PrimeVideo",
        "/ulke/guney-kore-fm2/" to "Kore Filmleri",
        "/tur/aksiyon-fmy54y/film/" to "Aksiyon",
        "/tur/aile-fm2/film/" to "Aile",
        "/tur/animasyon-fm2/film/" to "Animasyon",
        "/tur/belgesel/film/" to "Belgesel",
        "/tur/biyografi/film/" to "Biyografi",
        "/tur/bilim-kurgu-fmy58w/film/" to "Bilim Kurgu",
        "/tur/dram-fm1/film/" to "Dram",
        "/tur/fantastik-fm1/film/" to "Fantastik",
        "/tur/gerilim-fm1/film/" to "Gerilim",
        "/tur/gizem/film/" to "Gizem",
        "/tur/komedi-fm1/film/" to "Komedi",
        "/tur/korku-fm2/film/" to "Korku",
        "/tur/macera-fm1/film/" to "Macera",
        "/tur/muzik/film/" to "Müzik",
        "/tur/polisiye/film/" to "Polisiye",
        "/tur/romantik-fm1/film/" to "Romantik",
        "/tur/savas-fm1/film/" to "Savaş",
        "/tur/spor/film/" to "Spor",
        "/tur/tarih-fm1/film/" to "Tarih",
        "/tur/western-fm1/film/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site HTTP 429 (hız sınırı) veriyor ve Cloudstream tüm satırları aynı anda isteyip toplam 120 sn'de pes ediyor.
        // Bu yüzden burada ASLA uzun beklenmez: taze önbellek -> ağ (yalnızca sınır yoksa) -> bayat önbellek -> boş liste.
        val path = request.data.trimEnd('/') + "/"
        val p = if (page <= 1) path else "${path}sayfa/$page/"
        var html = diskGet(p).orEmpty()
        val fresh = html.isNotBlank()
        if (!fresh && !isRateLimited()) {
            html = try {
                siteGet(p, patient = false)?.text.orEmpty()
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            if (html.isBlank() && !isRateLimited()) {
                val alt = findCatalogPath(path)
                if (alt != null && alt != path) {
                    log("kategori yolu bulundu: $path -> $alt")
                    val alt2 = alt.trimEnd('/') + "/"
                    val p2 = if (page <= 1) alt2 else "${alt2}sayfa/$page/"
                    html = try {
                        siteGet(p2, patient = false)?.text.orEmpty()
                    } catch (e: java.util.concurrent.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ""
                    }
                }
            }
        }
        var list = try { parseCards(Jsoup.parse(html)) } catch (e: Exception) { emptyList() }
        if (!fresh && list.isNotEmpty()) {
            diskPut(p, html)
        } else if (list.isEmpty()) {
            // Ağ başarısız / hız sınırı: bayat önbellek (24 sa'e kadar) varsa onu göster
            val stale = diskGet(p, catStaleMs).orEmpty()
            if (stale.isNotBlank()) list = try { parseCards(Jsoup.parse(stale)) } catch (e: Exception) { emptyList() }
        }
        log("anasayfa ${request.name} p=$page fresh=$fresh html=${html.length} kart=${list.size} limit=${isRateLimited()}")
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    // ---- Katalog sayfaları için kısa süreli disk önbelleği (uygulama yeniden açılınca anında gelsin) ----
    private val catDir by lazy { java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp") }
    private val catTtlMs = 2L * 60 * 60 * 1000           // taze önbellek (2 saat: uygulamayı tekrar açınca anında gelir)
    private val catStaleMs = 24L * 60 * 60 * 1000   // bayat önbellek (site hız sınırı verirse bunu göster)

    private fun catFile(key: String): java.io.File =
        java.io.File(catDir, "fm_cat_" + key.hashCode().toString().replace('-', 'm') + ".html")

    private fun diskGet(key: String, maxAge: Long = catTtlMs): String? {
        return try {
            val f = catFile(key)
            if (!f.exists()) return null
            val txt = f.readText()
            val nl = txt.indexOf('\n')
            if (nl <= 0) return null
            val ts = txt.substring(0, nl).toLongOrNull() ?: return null
            if (System.currentTimeMillis() - ts > catStaleMs) {
                f.delete()
                return null
            }
            if (System.currentTimeMillis() - ts > maxAge) return null
            txt.substring(nl + 1).takeIf { it.length > 500 }
        } catch (e: Throwable) {
            null
        }
    }

    private fun diskPut(key: String, html: String) {
        try {
            catFile(key).writeText(System.currentTimeMillis().toString() + "\n" + html)
        } catch (e: Throwable) {
        }
    }

    // Ana sayfa HTML'sinden bir kategorinin güncel yolunu bulur (ör. /tur/aksiyon-fm2/film/ -> /tur/aksiyon-fm3/film/):
    // adresteki "-fm.." / "-sayı" sonekli bölümün gövdesi aynı kalır, yalnızca sonek değişir.
    private suspend fun findCatalogPath(path: String): String? {
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        val sufRe = Regex("-(?:fm\\w*|\\d+)$", IC)
        val idx = segs.indexOfFirst { sufRe.containsMatchIn(it) }
        if (idx < 0) return null // sonek yok -> adres zaten sabit, başka kategoriyle karıştırma
        val stem = segs[idx].replace(sufRe, "")
        if (stem.length < 4) return null
        val home = try {
            siteGet("/", patient = false)?.text.orEmpty()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        if (home.isBlank()) return null
        val re = Regex("""href=["'](?:https?://[^/"']+)?(/[^"'#?]*${Regex.escape(stem)}[^"'#?]*)["']""", IC)
        val hits = re.findAll(home).map { it.groupValues[1] }.distinct().toList()
        val sameShape = hits.filter { h ->
            val hs = h.trim('/').split('/')
            hs.size == segs.size && hs.getOrNull(0) == segs[0] && hs.last().replace(sufRe, "") == segs.last().replace(sufRe, "")
        }
        return sameShape.firstOrNull() ?: hits.firstOrNull { it.trim('/').split('/').size == segs.size }
    }

    // ---------------- Arama ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        return fetchList("/arama/?s=$q")
    }

    // Arama listesi: siteGet (düz istek -> Cloudflare görürse WebView) ile alınır
    @Volatile
    private var lastInfo = ""

    private suspend fun fetchList(path: String): List<SearchResponse> {
        val r = try {
            siteGet(path, patient = true)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastInfo = "hata ${e.message?.take(40)}"
            log("liste hata ${e.message?.take(60)} ${path.take(80)}")
            null
        }
        if (r == null) {
            log("liste alinamadi ${path.take(80)}")
            return emptyList()
        }
        val list = try { parseCards(Jsoup.parse(r.text)) } catch (e: Exception) { emptyList() }
        lastInfo = "kod=${r.code} len=${r.text.length}"
        log("liste kod=${r.code} len=${r.text.length} kart=${list.size} ${path.take(80)}")
        return list
    }

    private val cardHref = Regex("^(?:https?://[^/]+)?/(film|dizi)/[^/]+/?$")

    private fun parseCards(doc: Document): List<SearchResponse> {
        val a = parseCardsMain(doc)
        return if (a.isNotEmpty()) a else parseCardsLoose(doc)
    }

    // Site işaretlemesi değişirse: /film/xxx/ veya /dizi/xxx/ adresine giden, görseli olan her bağlantıyı kart say
    private fun parseCardsLoose(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (el in doc.select("a[href]")) {
            val href = el.attr("href")
            val m = cardHref.find(href) ?: continue
            val box = el.parent()
            val img = el.selectFirst("img") ?: box?.selectFirst("img") ?: continue
            val url = fixUrl(href)
            if (!seen.add(url)) continue

            val sel = ".item-title, .title, h2, h3"
            val raw = listOf(
                el.attr("data-title"),
                el.attr("title"),
                img.attr("alt"),
                el.selectFirst(sel)?.text().orEmpty(),
                box?.selectFirst(sel)?.text().orEmpty(),
                el.text()
            ).firstOrNull { it.isNotBlank() } ?: continue
            val title = cleanTitle(raw).ifBlank { raw.trim() }

            var poster = ""
            for (k in listOf("src", "data-src", "data-original", "data-lazy-src")) {
                val v = img.attr(k)
                if (v.isNotBlank() && !v.startsWith("data:")) { poster = v; break }
            }
            if (poster.isBlank()) poster = img.attr("srcset").trim().substringBefore(" ")
            val posterUrl = fixUrlNull(poster.ifBlank { null })
            val year = Regex("(19|20)\\d{2}").find(el.text())?.value?.toIntOrNull()

            if (m.groupValues[1] == "dizi") {
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                })
            } else {
                out.add(newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                })
            }
        }
        return out
    }

    private fun parseCardsMain(doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        return doc.select("a.item").mapNotNull { el ->
            if (el.hasClass("soon") || el.hasClass("item-ep")) return@mapNotNull null
            val href = el.attr("href")
            val m = cardHref.find(href) ?: return@mapNotNull null
            val url = fixUrl(href)
            if (!seen.add(url)) return@mapNotNull null

            val img = el.selectFirst("img")
            val title = el.attr("data-title").ifBlank {
                el.selectFirst(".item-title")?.text()
                    ?: el.selectFirst(".title")?.text()
                    ?: img?.attr("alt") ?: ""
            }.trim()
            if (title.isBlank()) return@mapNotNull null

            var poster = img?.attr("src") ?: ""
            if (poster.isBlank() || poster.startsWith("data:")) poster = img?.attr("data-src") ?: ""
            val posterUrl = fixUrlNull(poster.ifBlank { null })

            val year = Regex("(19|20)\\d{2}")
                .find(el.select("div.info span").text())?.value?.toIntOrNull()

            if (m.groupValues[1] == "dizi") {
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
    }

    // ---------------- Detay sayfası ----------------

    private fun cleanTitle(t: String): String =
        t.replace(
            Regex("\\s+(?:(?:19|20)\\d{2}\\s+)?(?:Filmi\\s+|Dizisi\\s+)?(?:Full\\s+)?(?:HD\\s+)?[iİıI]zle\\b.*$", RegexOption.IGNORE_CASE),
            ""
        ).trim()


    // ---------------- TMDB ----------------

    // https://www.themoviedb.org/settings/api adresinden ücretsiz anahtar al
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"

    data class TmdbSearch(val results: List<TmdbShow>? = null)
    data class TmdbShow(val id: Int? = null)
    data class TmdbDetail(val overview: String? = null)
    data class TmdbSeason(val episodes: List<TmdbEp>? = null)
    data class TmdbEp(
        val episode_number: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val still_path: String? = null,
        val air_date: String? = null,
        val runtime: Int? = null
    )

    private suspend fun tmdbFindId(title: String, year: Int?, isTv: Boolean = true): Int? {
        if (tmdbKey.startsWith("BURAYA")) return null
        val q = URLEncoder.encode(title, "UTF-8")
        val kind = if (isTv) "tv" else "movie"
        val yearParam = if (isTv) "first_air_date_year" else "year"
        suspend fun search(withYear: Boolean): Int? = try {
            val y = if (withYear && year != null) "&$yearParam=$year" else ""
            app.get("https://api.themoviedb.org/3/search/$kind?api_key=$tmdbKey&language=tr-TR&query=$q$y")
                .parsedSafe<TmdbSearch>()?.results?.firstOrNull()?.id
        } catch (e: Exception) { null }
        return search(true) ?: search(false)
    }

    // Dizi / film açıklaması: yalnızca Türkçe (TR). Türkçe yoksa null döner.
    private suspend fun tmdbOverviewTr(id: Int, isTv: Boolean): String? = try {
        val kind = if (isTv) "tv" else "movie"
        app.get("https://api.themoviedb.org/3/$kind/$id?api_key=$tmdbKey&language=tr-TR")
            .parsedSafe<TmdbDetail>()?.overview?.trim()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    private suspend fun tmdbSeason(id: Int, season: Int): Map<Int, TmdbEp> {
        suspend fun fetch(lang: String): List<TmdbEp> = try {
            app.get("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$tmdbKey&language=$lang")
                .parsedSafe<TmdbSeason>()?.episodes ?: emptyList()
        } catch (e: Exception) { emptyList() }

        val tr = fetch("tr-TR")
        // Açıklama her zaman Türkçe; İngilizce yalnızca eksik görsel ve isim için kullanılır
        val en = if (tr.any { it.still_path == null || it.name.isNullOrBlank() }) fetch("en-US") else emptyList()
        val enMap = en.associateBy { it.episode_number }
        return tr.mapNotNull { t ->
            val n = t.episode_number ?: return@mapNotNull null
            val f = enMap[n]
            n to t.copy(
                overview = t.overview?.takeIf { it.isNotBlank() },
                still_path = t.still_path ?: f?.still_path,
                name = t.name?.takeIf { it.isNotBlank() } ?: f?.name
            )
        }.toMap()
    }

    override suspend fun load(url: String): LoadResponse? {
        val resp = siteGet(pathOf(url), patient = true) ?: return null
        val doc = Jsoup.parse(resp.text)
        val html = resp.text
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".thumbnail-outer img")?.attr("src")
        )
        val sitePlot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("(19|20)\\d{2}").findAll(url).lastOrNull()?.value?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val seen = HashSet<String>()
            val found = ArrayList<Triple<String, Int, Int>>() // url, sezon, bölüm
            Regex("href=\"([^\"]*?/sezon-(\\d+)/bolum-(\\d+)/?)\"")
                .findAll(html).forEach { m ->
                    val s = m.groupValues[2].toInt()
                    val e = m.groupValues[3].toInt()
                    if (seen.add("$s-$e")) found.add(Triple(fixUrl(m.groupValues[1]), s, e))
                }

            // TMDB'den resmi bölüm bilgileri (isim, açıklama, görsel, yayın tarihi)
            val tmdbId = tmdbFindId(title, year, true)
            val plot = (if (tmdbId != null) tmdbOverviewTr(tmdbId, true) else null) ?: sitePlot
            val seasonCache = HashMap<Int, Map<Int, TmdbEp>>()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            val episodes = ArrayList<Episode>()
            for ((epUrl, s, e) in found) {
                val info = if (tmdbId != null)
                    seasonCache.getOrPut(s) { tmdbSeason(tmdbId, s) }[e]
                else null

                // Yayın tarihi bugünden sonraysa listeye ekleme; yayın günü kendiliğinden düşer
                val air = info?.air_date
                if (!air.isNullOrBlank() && air > today) continue

                episodes.add(newEpisode(epUrl) {
                    this.season = s
                    this.episode = e
                    this.name = info?.name?.takeIf { it.isNotBlank() } ?: "$s. Sezon $e. Bölüm"
                    this.description = info?.overview?.takeIf { it.isNotBlank() }
                    this.posterUrl = info?.still_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                    this.runTime = info?.runtime
                    this.date = air?.let {
                        try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)?.time } catch (_: Exception) { null }
                    }
                })
            }
            episodes.sortWith(compareBy({ it.season }, { it.episode }))
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val movieId = tmdbFindId(title, year, false)
        val plot = (if (movieId != null) tmdbOverviewTr(movieId, false) else null) ?: sitePlot
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ---------------- Site istekleri ----------------
    private fun siteHeaders(host: String, desktop: Boolean, extra: Map<String, String>): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to (if (desktop) desktopUA else uaSiteMobile),
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
            "Referer" to "$host/"
        )
        if (!extra.containsKey("X-Requested-With")) {
            // Gerçek mobil Chrome'un gönderdiği başlıklar (uygulama/bot tespiti için)
            val cv = if (desktop) "124" else "146"
            h["sec-ch-ua"] = "\"Chromium\";v=\"$cv\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"$cv\""
            h["sec-ch-ua-mobile"] = if (desktop) "?0" else "?1"
            h["sec-ch-ua-platform"] = if (desktop) "\"Windows\"" else "\"Android\""
            h["Upgrade-Insecure-Requests"] = "1"
            h["Sec-Fetch-Dest"] = "document"
            h["Sec-Fetch-Mode"] = "navigate"
            h["Sec-Fetch-Site"] = "same-origin"
            h["Sec-Fetch-User"] = "?1"
        }
        h.putAll(extra)
        return h
    }

    // Site yanıtı (düz istek ya da WebView'den gelen)
    private class SiteResp(val code: Int, val text: String)

    private fun denied(r: SiteResp?): Boolean {
        if (r == null || r.code !in 200..299) return true
        val t = r.text
        return t.length < 700 &&
            Regex("access denied|forbidden|attention required|just a moment|you have been blocked", IC).containsMatchIn(t)
    }

    // ---- WebView ile istek ----
    // Düz (OkHttp) istek Cloudflare'de 403 alırken gerçek WebView (Chrome) geçiyor ve cf_clearance çerezi hiç oluşmuyor;
    // CloudflareKiller bu çerezi beklediği için 60 sn bekleyip zaman aşımına düşüyordu.
    // Burada çerez beklenmez: WebView'de aynı siteden hafif bir sayfa açılır, sayfanın içinden (aynı origin)
    // senkron XHR ile istenen adres çekilir ve yanıt doğrudan alınır.
    private val wvLock = Semaphore(1)
    // Site hız sınırı koyuyor (HTTP 429 "Biraz yavaşlayalım"): HDFilmCehennemi'ndeki gibi 6 paralel WebView burada
    // sınırı hemen aşıyordu. Bu yüzden istekler tek tek, aralarında boşlukla gider; 429 gelirse herkes bekler.
    private val wvPar = Semaphore(2)
    private val gapMs = 1_000L
    @Volatile
    private var nextSlotAt = 0L
    @Volatile
    private var rateLimitedUntil = loadRl()

    // Site IP'yi "birkaç dakika" engelliyor (tarayıcıda da aynı uyarı çıkıyor). Bekleme süresi uygulama kapanınca kaybolmasın diye diske yazılır.
    private fun rlFile() = java.io.File(catDir, "fm_rl.txt")
    private fun loadRl(): Long = try { rlFile().readText().trim().toLong() } catch (e: Throwable) { 0L }
    private fun markRateLimited() {
        rateLimitedUntil = System.currentTimeMillis() + rlCooldownMs
        try { rlFile().writeText(rateLimitedUntil.toString()) } catch (e: Throwable) { }
        log("429: ${rlCooldownMs / 1000} sn siteye istek atılmayacak")
    }

    // Son 60 sn'deki ağ sayfa istekleri: sınıra hiç ulaşmadan durmak için (ban 24 sayfa/25 sn'de geldi, 31/dk güvenliydi)
    private val netTimes = java.util.ArrayDeque<Long>()
    @Synchronized
    private fun netCount(): Int {
        val lim = System.currentTimeMillis() - 60_000L
        while (netTimes.isNotEmpty() && netTimes.peekFirst() < lim) netTimes.pollFirst()
        return netTimes.size
    }
    @Synchronized
    private fun noteNet() { netTimes.addLast(System.currentTimeMillis()) }
    private val homeBudget = 14   // ana sayfa satırları dakikada en çok bu kadar sayfa çeker
    private val allBudget = 22    // kullanıcı eylemleri dahil üst sınır

    private fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntil
    private val rlCooldownMs = 5 * 60_000L // 429 sonrası bu süre siteye HİÇ istek atılmaz (yeniden denemek yasağı uzatıyordu)

    @Synchronized
    private fun reserveSlot(): Long {
        val now = System.currentTimeMillis()
        val slot = maxOf(nextSlotAt, now)
        nextSlotAt = slot + gapMs
        return slot - now
    }

    private fun waitTurn() {
        val w = reserveSlot()
        if (w > 0) Thread.sleep(w)
    }
    @Volatile
    private var wvLightFails = 0
    @Volatile
    private var wvLightBad = false
    private val wvCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SiteResp>>()
    // WebView bir adreste başarısız olursa kısa süre tekrar denenmez (katalog satırları art arda 25 sn beklemesin)
    private val wvFailUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile
    private var wvCleared = false
    @Volatile
    private var plainBlockedUntil = 0L

    // Not: WebViewResolver 'script' değerini sayfanın YAPTIĞI HER İSTEKTE çalıştırır.
    // Eski sürüm ilk çalışan sonucu (Cloudflare doğrulaması bitmeden, 403) alıp WebView'i hemen kapatıyordu.
    // Şimdi: script yalnızca 2xx gelince "bitti" işareti gönderir; 403 ise WebView açık kalır, doğrulama
    // çözülünce (sonraki isteklerde) script yeniden dener. Ayrıca /cdn-cgi/trace sayfası doğrulama çalıştırmaz,
    // bu yüzden çözülmezse gerçek sayfa ("/") üzerinden denenir.
    private suspend fun wvFetch(host: String, path: String, extra: Map<String, String>, patient: Boolean = true): SiteResp? {
        val hdr = extra.entries.filter {
            it.key.equals("Accept", true) || it.key.equals("X-Requested-With", true)
        }
        val key = host + path + "|" + hdr.joinToString(",") { it.value }
        val ttl = 5L * 60 * 1000
        fun cached(): SiteResp? {
            val c = wvCache[key] ?: return null
            return if (System.currentTimeMillis() - c.first < ttl) c.second else null
        }
        fun failed(): Boolean = System.currentTimeMillis() < (wvFailUntil[host] ?: 0L)
        cached()?.let { return it }
        if (failed()) return null

        // Android WebView her isteğe X-Requested-With: <paket adı> ekler; site bunu "Uygulama desteklenmiyor" diye reddediyor.
        // XHR'de bu başlığı kendimiz yazarak ezmeye çalışıyoruz.
        val hmap = LinkedHashMap<String, String>()
        hmap["X-Requested-With"] = "XMLHttpRequest"
        hdr.forEach { hmap[it.key] = it.value }
        val hdrJs = hmap.entries.joinToString("") {
            "x.setRequestHeader(" + JSONObject.quote(it.key) + "," + JSONObject.quote(it.value) + ");"
        }
        val script = "(function(){try{" +
            "if(window.__fmOk){return window.__fmOk;}" +
            "var now=Date.now();" +
            "if(window.__fmT&&now-window.__fmT<1500){return window.__fmLast||'0||';}" +
            "window.__fmT=now;" +
            "var st=0,out='',cfm='';" +
            "try{var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(path) + ",false);" + hdrJs +
            "x.send();st=x.status;out=x.responseText||'';cfm=x.getResponseHeader('cf-mitigated')||'';}catch(e){}" +
            "var res=st+'|'+cfm+'|'+out;window.__fmLast=res;" +
            "if(st>=200&&st<300&&out.length>0){window.__fmOk=res;" +
            "setTimeout(function(){try{new Image().src='/__fm_done__?'+Date.now();}catch(e){}},50);}" +
            // Cloudflare doğrulaması DEĞİL, kesin ret ise (ör. 'Uygulama desteklenmiyor') uzun beklemeden bitir
            "else if(st>0&&cfm==''&&out.indexOf('Just a moment')<0&&out.indexOf('cf-chl')<0&&!window.__fmF){window.__fmF=1;" +
            "setTimeout(function(){try{if(!window.__fmOk){new Image().src='/__fm_done__?'+Date.now();}}catch(e){}},2500);}" +
            "return res;}catch(e){return '0||';}})();"

        // Sırada en çok 40 sn (kullanıcı eyleminde 100 sn) beklenir; olmazsa boş dön (toplam 120 sn zaman aşımı tüm sayfayı öldürüyor)
        if (!wvPar.tryAcquire(if (patient) 100L else 45L, java.util.concurrent.TimeUnit.SECONDS)) {
            log("WV sıra zaman aşımı ${path.take(60)}")
            return null
        }
        try {
            cached()?.let { return it }
            if (failed()) return null
            if (isRateLimited()) {
                if (!patient) return SiteResp(429, "")
                val w = rateLimitedUntil - System.currentTimeMillis()
                // Ban uzun sürecekse boşuna bekleyip yasağı yenileme: hemen vazgeç
                if (w > 60_000L) return SiteResp(429, "")
                if (w > 0) Thread.sleep(w + 1_000L)
            }
            if (!patient && netCount() >= homeBudget) {
                log("bütçe doldu (${netCount()}/dk), ağ atlanıyor ${path.take(50)}")
                return SiteResp(429, "")
            }
            if (patient && netCount() >= allBudget) {
                Thread.sleep(15_000L)
            }
            waitTurn()
            noteNet()
            var lastBad: SiteResp? = null
            var sawError = false
            // Log analizi: site, X-Requested-With başlığı olan (WebView/uygulama) istekleri "Uygulama desteklenmiyor" (403)
            // diye reddediyor; başlık ezilince bu kez normal Cloudflare doğrulaması ("Just a moment") geliyor.
            // Sayfa YÜKLEMESİ de bu başlıkla gittiği için doğrulama hiç çözülemiyordu. Çözüm: WebView'in ana sayfa
            // isteğinde başlığı loadUrl ek başlığıyla ezmek (boş değer) ve doğrulamayı gerçek sayfada çözdürmek.
            // Doğrulama çerezi WebView'de kalır; sonraki istekler hafif /cdn-cgi/trace sayfasıyla yapılır.
            val attempts = ArrayList<Array<Any?>>()
            // Çalışan yöntem: ana sayfa ("/") yüklenirken XHR. Ağır olduğu için, bir kez çalıştıktan sonra
            // küçük bir 404 sayfası denenir; 2 kez işe yaramazsa bir daha denenmez (eski yönteme dönülür).
            val lightAttempt = wvCleared && !wvLightBad
            if (lightAttempt) attempts.add(arrayOf("/robots.txt", null, "", 3_500L))
            attempts.add(arrayOf("/", null, "", 18_000L))
            attempts.add(arrayOf("/", null, "XMLHttpRequest", 18_000L))
            for (att in attempts) {
                val anchor = att[0] as String
                val wvUa = att[1] as String?
                val xrw = att[2] as String
                val waitMs = att[3] as Long
                val ok = AtomicReference<String?>(null)
                val last = AtomicReference<String?>(null)
                try {
                    app.get(
                        host + anchor,
                        interceptor = WebViewResolver(
                            Regex("__fm_done__"),
                            userAgent = wvUa,
                            useOkhttp = false,
                            script = script,
                            scriptCallback = { v ->
                                if (v != null && v != "null") {
                                    last.set(v)
                                    if (v.startsWith("\"2")) ok.set(v)
                                }
                            },
                            timeout = waitMs
                        ),
                        headers = mapOf("X-Requested-With" to xrw),
                        timeout = 40L
                    )
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e // kullanıcı sayfadan çıktı / istek iptal: başarısızlık sayılmaz
                } catch (e: Exception) {
                    log("WV hata ${e.javaClass.simpleName} ${(host + anchor).take(60)}")
                    sawError = true
                }
                var n = 0
                while (ok.get() == null && last.get() == null && n < 15) {
                    Thread.sleep(100)
                    n++
                }
                val raw = ok.get() ?: last.get() ?: continue
                val str = try {
                    JSONTokener(raw).nextValue() as? String
                } catch (e: Exception) {
                    null
                } ?: continue
                val i = str.indexOf('|')
                if (i <= 0) continue
                val code = str.substring(0, i).toIntOrNull() ?: continue
                if (code == 0) continue
                val j = str.indexOf('|', i + 1)
                val cfm = if (j > i) str.substring(i + 1, j) else ""
                val body = if (j >= 0) str.substring(j + 1) else str.substring(i + 1)
                val resp = SiteResp(code, body)
                log("WV kod=$code len=${body.length} cfm=$cfm anchor=$anchor ua=${if (wvUa == null) "wv" else "chrome"} xrw='$xrw' ${(host + path).take(70)}")
                if (code in 200..299 && body.isNotEmpty()) {
                    wvCache[key] = Pair(System.currentTimeMillis(), resp)
                    wvFailUntil.remove(host)
                    wvCleared = true
                    if (anchor == "/") {
                        if (lightAttempt) {
                            wvLightFails++
                            if (wvLightFails >= 2) {
                                wvLightBad = true
                                log("WV hafif sayfa çalışmadı, ana sayfa yöntemi kullanılacak")
                            }
                        }
                    } else {
                        wvLightFails = 0
                    }
                    return resp
                }
                log("WV gövde: " + body.take(200).replace(Regex("\\s+"), " "))
                lastBad = resp
                if (code == 429) {
                    // Hız sınırı: Cloudflare/uygulama engeli DEĞİL. Başka anchor/host denemek sınırı daha da kötüleştirir.
                    markRateLimited()
                    return resp
                }
                if (code == 404 || code == 410) {
                    // Sayfa gerçekten yok: Cloudflare/uygulama engeli değil. Tekrar deneme, host'u da kilitleme.
                    return resp
                }
            }
            // Yalnızca site gerçekten ret verdiyse kısa süre tekrar denenmez (hata/iptalde kilitleme yok)
            if (lastBad != null && !sawError && (lastBad.code == 403 || lastBad.code == 503)) {
                wvFailUntil[host] = System.currentTimeMillis() + 15_000L
            }
            return lastBad
        } finally {
            wvPar.release()
        }
    }

    private suspend fun siteGet(path: String, extra: Map<String, String> = emptyMap(), patient: Boolean = true): SiteResp? {
        // 1) Düz istekler (Cloudflare engeli görülürse 10 dk boyunca denenmez, doğrudan WebView)
        if (System.currentTimeMillis() >= plainBlockedUntil) {
            val tries = ArrayList<Pair<String, Boolean>>()
            good?.let { tries.add(it) }
            hosts.forEach { h ->
                listOf(false, true).forEach { dsk ->
                    val p = Pair(h, dsk)
                    if (p != good) tries.add(p)
                }
            }
            var cfFails = 0
            for ((host, desk) in tries) {
                val r: NiceResponse? = try {
                    app.get(host + path, headers = siteHeaders(host, desk, extra), timeout = 8L)
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val sr = r?.let { SiteResp(it.code, it.text) }
                if (r != null && r.code == 429) {
                    markRateLimited()
                    return sr
                }
                if (!denied(sr)) {
                    good = Pair(host, desk)
                    return sr
                }
                if (r != null && (r.code == 403 || r.code == 503)) {
                    log("DÜZ kod=${r.code} desk=$desk ${(host + path).take(50)} gövde: " + r.text.take(120).replace(Regex("\\s+"), " "))
                }
                if (r != null && (r.code == 403 || r.code == 503) &&
                    r.headers["server"].orEmpty().contains("cloudflare", true)
                ) {
                    cfFails++
                    if (cfFails >= 2) {
                        plainBlockedUntil = System.currentTimeMillis() + 3L * 60 * 1000
                        log("düz istek Cloudflare 403 -> WebView moduna geçildi")
                        break
                    }
                }
            }
        }

        // 2) WebView ile (çerez beklemeden, sayfa içinden XHR). 429 gelirse YENİDEN DENENMEZ (yasak uzuyor).
        val cands = listOf(good?.first ?: hosts[0], hosts[1], hosts[0]).distinct()
        for (host in cands) {
            val r = try {
                wvFetch(host, path, extra, patient)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("WV istisna ${e.javaClass.simpleName}")
                null
            }
            if (!denied(r)) {
                good = Pair(host, false)
                return r
            }
            if (r != null && r.code == 429) return r
            if (r == null && !patient) return null
        }
        return null
    }

    private fun siteBase(): String = good?.first ?: mainUrl

    private fun pathOf(u: String): String = u.replace(Regex("^https?://[^/]+", IC), "").ifEmpty { "/" }

    // ---------------- Linkler ----------------

    private fun log(msg: String) {
        try {
            Log.d("Filmmakinesi", msg)
        } catch (_: Throwable) {
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = try {
            siteGet(pathOf(data), patient = true)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("sayfa hata ${e.message?.take(80)}")
            null
        }
        if (res == null) {
            log("sayfa alinamadi $data")
            return false
        }
        val parts = extractParts(Jsoup.parse(res.text), res.text)
        log("sayfa ${res.code} len=${res.text.length} kaynak=${parts.size}")
        if (parts.isEmpty()) return false

        val seenUrl = Collections.synchronizedSet(HashSet<String>())
        val gotExt = AtomicBoolean(false)

        parts.amap { part ->
            val label = part.first
            val embed = part.second
            try {
                log("E $label ${embed.take(80)}")
                val found = findStream(embed, data, label)
                if (found != null) {
                    if (seenUrl.add(found.url)) {
                        callback(
                            newExtractorLink(
                                name,
                                "$name - $label",
                                found.url,
                                if (found.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = found.headers["Referer"] ?: "${originOf(embed)}/"
                                this.quality = Qualities.Unknown.value
                                this.headers = found.headers
                            }
                        )
                    }
                } else {
                    val ok = try {
                        loadExtractor(embed, data, subtitleCallback, callback)
                    } catch (_: Exception) {
                        false
                    }
                    log("$label COZULEMEDI loadExtractor=$ok")
                    if (ok) gotExt.set(true)
                }
            } catch (e: Exception) {
                log("$label hata ${e.message?.take(80)}")
            }
            Unit
        }
        return seenUrl.isNotEmpty() || gotExt.get()
    }

    // ---------------- Akış bulma / doğrulama ----------------

    private data class Verified(val ok: Boolean, val deep: Boolean, val headers: Map<String, String>)

    private val wvRegex = Regex(
        """\.m3u8|\.mp4(\?|$)|/(?:master|playlist|index|sublist)[^/?]*\.txt""",
        RegexOption.IGNORE_CASE
    )

    private fun rank(u: String): Int = when {
        u.contains("master", true) -> 0
        u.contains(".m3u8", true) -> 1
        else -> 2
    }

    private fun absUrl(base: String, ref: String): String = when {
        ref.startsWith("http", true) -> ref
        ref.startsWith("//") -> "https:$ref"
        ref.startsWith("/") -> originOf(base) + ref
        else -> base.substringBefore("?").substringBeforeLast('/') + "/" + ref
    }

    // Rapid ve Close CDN'leri Referer/Origin olarak oynatıcı alt alan adını bekliyor
    // (tarayıcıda görülen: rapid.<site> ve closeload.<site>). Adresi doğrudan açınca 403 vermesi bu yüzden.
    private fun knownOrigin(streamUrl: String): String? {
        val host = originOf(streamUrl).substringAfter("://").lowercase()
        val site = mainUrl.substringAfter("://").trimEnd('/').removePrefix("www.")
        return when {
            host.contains("rapidrame") -> "https://rapid.$site"
            host.contains("cdnimages") -> "https://closeload.$site"
            else -> null
        }
    }

    private fun streamHeaderSets(embedBase: String, streamUrl: String): List<Map<String, String>> {
        fun hs(ref: String?, origin: String?): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            m["User-Agent"] = androidUA
            if (ref != null) m["Referer"] = ref
            if (origin != null) m["Origin"] = origin
            return m
        }
        val out = ArrayList<Map<String, String>>()
        val org = originOf(embedBase)
        if (org.isNotBlank()) out.add(hs("$org/", org))
        knownOrigin(streamUrl)?.let { out.add(hs("$it/", it)) }
        out.add(hs(embedBase, null))
        out.add(hs("$mainUrl/", mainUrl))
        val own = originOf(streamUrl)
        if (own.isNotBlank()) out.add(hs("$own/", own))
        out.add(hs(null, null))
        return out.distinct()
    }

    // Ana liste -> alt liste -> ilk parça zincirini aynı header'larla dener.
    // Tarayıcıda 403 veren asıl nokta parçalar (seg/image) olduğu için orası da kontrol edilir.
    private suspend fun deepCheck(startUrl: String, startText: String, h: Map<String, String>): Boolean {
        var curUrl = startUrl
        var curText = startText
        for (i in 0..2) {
            val line = curText.lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            val next = absUrl(curUrl, line)
            if (curText.contains("#EXTINF")) {
                val r = try {
                    app.get(next, headers = h + mapOf("Range" to "bytes=0-2047"), timeout = 8L)
                } catch (e: Exception) {
                    null
                } ?: return false
                val ok = r.code == 200 || r.code == 206
                if (ok) r.text.length
                return ok
            }
            val r = try {
                app.get(next, headers = h, timeout = 8L)
            } catch (e: Exception) {
                null
            } ?: return false
            if (r.code != 200) return false
            val t = r.text
            if (!t.trimStart().startsWith("#EXTM3U")) return false
            curUrl = next
            curText = t
        }
        return false
    }

    private suspend fun verifyUrl(u: String, sets: List<Map<String, String>>): Verified {
        if (isMp4(u)) return Verified(true, true, sets[0])
        var firstOk: Verified? = null
        for (h in sets) {
            val r = try {
                app.get(u, headers = h, timeout = 8L)
            } catch (e: Exception) {
                null
            }
            if (r != null && (r.code == 200 || r.code == 206)) {
                val t = r.text
                if (t.trimStart().startsWith("#EXTM3U")) {
                    if (deepCheck(u, t, h)) return Verified(true, true, h)
                    if (firstOk == null) firstOk = Verified(true, false, h)
                }
            }
        }
        return firstOk ?: Verified(false, false, sets[0])
    }

    // Oynatıcıyı WebView'de gerçekten başlatmayı dener (autoplay engelli olduğundan tıklama gerekebilir)
    private val playScript =
        "(function(){try{" +
            "var v=document.querySelector('video');" +
            "if(v){v.muted=true;var p=v.play();if(p&&p.catch){p.catch(function(){});}}" +
            "var b=document.querySelector('.jw-icon-display,.jw-display-icon-container,.vjs-big-play-button,.plyr__control--overlayed,.play-button,.play,#play,[class*=play]');" +
            "if(b&&b.click){b.click();}" +
            "}catch(e){}return '';})();"

    private suspend fun webViewFind(embed: String, pageUrl: String, base: String, label: String): Found? {
        // Log analizi: WebView embed sayfasını Android'in X-Requested-With: <paket> başlığıyla açınca Cloudflare
        // hata sayfası (cf.errors.css) dönüyor ve 60 sn boşuna bekleniyordu. Başlık boş değerle ezilir (ana sitede de böyle çözüldü).
        val resp = app.get(
            embed,
            referer = pageUrl,
            headers = mapOf("X-Requested-With" to ""),
            interceptor = WebViewResolver(
                wvRegex,
                script = playScript,
                scriptCallback = { },
                timeout = 25_000L
            )
        )
        val u = resp.url
        log("WV $label ${u.take(100)}")
        if (u == embed || badExt.containsMatchIn(u) || !wvRegex.containsMatchIn(u)) return null
        val v = verifyUrl(u, streamHeaderSets(base, u))
        return Found(u, !isMp4(u), v.headers)
    }

    // Log analizi: embed adreslerini (closeload / rapid) WebView'de DOĞRUDAN açınca Cloudflare anında hata sayfası (cf.errors.css)
    // veriyor; OkHttp de 404 alıyor. Tarayıcıda ise embed, film sayfasının İÇİNDE iframe olarak yüklendiği için çalışıyor
    // (Sec-Fetch-Dest: iframe + Referer: film sayfası). Burada aynısı yapılır: film sayfası WebView'de açılır, içine
    // embed adresi iframe olarak eklenir ve iframe'in yaptığı .m3u8/.mp4 istekleri yakalanır.
    private suspend fun webViewFrameFind(embed: String, pageUrl: String, base: String, label: String, waitMs: Long = 35_000L): Found? {
        val script = "(function(){try{" +
            "if(window.__fmI){return '';}" +
            "if(!document.body||document.title.toLowerCase().indexOf('moment')>=0){return '';}" +
            "window.__fmI=1;" +
            "var f=document.createElement('iframe');" +
            "f.src=" + JSONObject.quote(embed) + ";" +
            "f.setAttribute('allow','autoplay; fullscreen; encrypted-media');" +
            "f.setAttribute('allowfullscreen','true');" +
            "f.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;border:0;z-index:99999;background:#000';" +
            "document.body.appendChild(f);" +
            "}catch(e){}return '';})();"
        val resp = app.get(
            pageUrl,
            headers = mapOf("X-Requested-With" to ""),
            interceptor = WebViewResolver(
                wvRegex,
                useOkhttp = false,
                script = script,
                scriptCallback = { },
                timeout = waitMs
            ),
            timeout = (waitMs / 1000L) + 10L
        )
        val u = resp.url
        log("WVF $label ${u.take(120)}")
        if (u == pageUrl || u == embed || badExt.containsMatchIn(u) || !wvRegex.containsMatchIn(u)) return null
        val v = verifyUrl(u, streamHeaderSets(base, u))
        return Found(u, !isMp4(u), v.headers)
    }

    // Düz istek 404/403 alınca embed sayfasının HTML'ini WebView'den (çizilmiş haliyle) alır.
    // X-Requested-With ezilir; Cloudflare doğrulaması varsa sayfa gerçekten açılana kadar beklenir.
    private val wvHtmlScript =
        "(function(){try{" +
            "var d=document;if(!d.documentElement){return '';}" +
            "var h=d.documentElement.outerHTML||'';" +
            "var t=d.title||'';" +
            "if(h.length>2500&&h.indexOf('<script')>=0&&h.indexOf('cf-chl')<0&&h.indexOf('challenge-platform')<0&&!/just a moment|access denied|attention required|404|not found/i.test(t)){" +
            "if(!window.__fmH){window.__fmH=1;setTimeout(function(){try{new Image().src='/__fm_done__?'+Date.now();}catch(e){}},1800);}" +
            "return h;}" +
            "return '';}catch(e){return '';}})();"

    private suspend fun wvHtml(url: String, referer: String): String? {
        val got = AtomicReference<String?>(null)
        try {
            app.get(
                url,
                referer = referer,
                headers = mapOf("X-Requested-With" to ""),
                interceptor = WebViewResolver(
                    Regex("__fm_done__"),
                    useOkhttp = false,
                    script = wvHtmlScript,
                    scriptCallback = { v ->
                        if (v != null && v != "null" && v.length > 10) {
                            val s = try {
                                JSONTokener(v).nextValue() as? String
                            } catch (e: Exception) {
                                null
                            }
                            if (!s.isNullOrBlank()) got.set(s)
                        }
                    },
                    timeout = 25_000L
                ),
                timeout = 40L
            )
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("WVH hata ${e.javaClass.simpleName} ${url.take(60)}")
        }
        var n = 0
        while (got.get() == null && n < 10) {
            Thread.sleep(100)
            n++
        }
        val h = got.get()
        log("WVH len=${h?.length ?: 0} ${url.take(70)}")
        return h
    }

    private suspend fun findStream(embed: String, pageUrl: String, label: String): Found? {
        var base = embed
        var unverified: Found? = null

        val er = try {
            resolveEmbed(embed, pageUrl)
        } catch (e: Exception) {
            log("EM hata $label ${e.message?.take(60)}")
            null
        }
        if (er != null) {
            base = er.base
            val cands = er.urls
                .sortedWith(compareBy<String>({ if (er.strict.contains(it)) 0 else 1 }, { rank(it) }))
                .take(6)
            val verified = cands.amap { u -> u to verifyUrl(u, streamHeaderSets(er.base, u)) }
            verified.forEach { log("V $label ok=${it.second.ok} deep=${it.second.deep} ${it.first.take(90)}") }

            val best = verified.firstOrNull { it.second.deep } ?: verified.firstOrNull { it.second.ok }
            if (best != null) return Found(best.first, !isMp4(best.first), best.second.headers)

            val st = verified.firstOrNull { er.strict.contains(it.first) }
            if (st != null) unverified = Found(st.first, !isMp4(st.first), st.second.headers)

            // Doğrulanamayan aday akış değil de başka bir embed sayfası olabilir: bir adım daha içine gir
            val pages = verified.filter { !it.second.ok && !isMp4(it.first) && !wvRegex.containsMatchIn(it.first) }
                .map { it.first }.take(2)
            for (pg in pages) {
                val hop = try {
                    resolveEmbed(pg, er.base, 1)
                } catch (e: Exception) {
                    log("EM hop hata $label ${e.message?.take(60)}")
                    null
                } ?: continue
                val hv = hop.urls.take(6).map { u -> u to verifyUrl(u, streamHeaderSets(hop.base, u)) }
                hv.forEach { log("V2 $label ok=${it.second.ok} deep=${it.second.deep} ${it.first.take(90)}") }
                val hb = hv.firstOrNull { it.second.deep } ?: hv.firstOrNull { it.second.ok }
                if (hb != null) return Found(hb.first, !isMp4(hb.first), hb.second.headers)
            }
        }

        val isRapid = originOf(embed).contains("rapid", true)
        if (isRapid) {
            val fu = try {
                frameStreamWv(embed, pageUrl)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("FW hata ${e.javaClass.simpleName} ${e.message?.take(60)}")
                null
            }
            if (fu != null) {
                val fv = verifyUrl(fu, streamHeaderSets(base, fu))
                log("V FW $label ok=${fv.ok} deep=${fv.deep} ${fu.take(90)}")
                return Found(fu, !isMp4(fu), fv.headers)
            }
            // Rapid'de eski iframe yolu ayni basliktan dolayi duser; ek 24 sn beklenmesin
            return unverified
        }
        val wvf = try {
            webViewFrameFind(embed, pageUrl, base, label, if (isRapid) 24_000L else 35_000L)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("WVF hata $label ${e.message?.take(60)}")
            null
        }
        if (wvf != null) return wvf

        // Rapid'de embed'i WebView'de doğrudan açmak anında Cloudflare hata sayfası veriyor; boşuna 25 sn beklenmesin
        if (isRapid) return unverified
        val wv = try {
            webViewFind(embed, pageUrl, base, label)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("WV hata $label ${e.message?.take(60)}")
            null
        }
        return wv ?: unverified
    }

    private val skipHosts = Regex(
        "youtube|youtu\\.be|googletagmanager|google-analytics|cloudflare|facebook|twitter|agl005|cvt-s2|fonts\\.g",
        RegexOption.IGNORE_CASE
    )

    private fun extractParts(doc: Document, html: String): List<Pair<String, String>> {
        val map = LinkedHashMap<String, String>() // url -> label

        fun addPart(raw: String, label: String) {
            val u = raw.trim().replace("&amp;", "&").replace("\\/", "/")
            if (!u.startsWith("http")) return
            if (skipHosts.containsMatchIn(u)) return
            if (label.contains("fragman", true)) return
            if (!map.containsKey(u)) map[u] = label.ifBlank { "Kaynak" }
        }

        doc.select("a[data-video_url]").forEach { a ->
            addPart(
                a.attr("data-video_url"),
                a.text().replace(Regex("\\s+"), " ").trim()
            )
        }
        Regex("data-video_url=\"([^\"]+)\"").findAll(html).forEach { addPart(it.groupValues[1], "Kaynak") }

        doc.select("iframe[data-src], iframe[src]").forEach { f ->
            addPart(f.attr("data-src").ifBlank { f.attr("src") }, "Kaynak")
        }

        return map.map { it.value to it.key }
    }

    // ---------------- Embed çözücü ----------------

    private data class Found(val url: String, val hls: Boolean, val headers: Map<String, String>)

    private data class Embed(val urls: List<String>, val strict: Set<String>, val base: String)

    private fun embedHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to androidUA,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to ref
    )

    // Gerçek mobil Chrome'un iframe içinde embed isterken gönderdiği başlıklar (Sec-Fetch-Dest: iframe, cross-site)
    private fun browserEmbedHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to uaSiteMobile,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to ref,
        "sec-ch-ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site"
    )

    private suspend fun fetchEmbed(url: String, headers: Map<String, String>, tag: String, allowCf: Boolean = true): NiceResponse? {
        var r = try {
            app.get(url, headers = headers, timeout = 10L)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("EM hata $tag ${e.message?.take(60)}")
            null
        }
        val cfBody = r != null && r.code != 200 && Regex("challenge-platform|Just a moment|cf-chl|cf\\.errors", IC).containsMatchIn(r.text)
        if (allowCf && r != null && (r.code == 403 || r.code == 503 || cfBody)) {
            r = try {
                cfGet(url, headers)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("EM cf hata $tag ${e.message?.take(60)}")
                r
            }
        }
        if (r != null && r.code != 200) {
            log("EM $tag kod=${r.code} len=${r.text.length} server=${r.headers["server"]} ray=${r.headers["cf-ray"]} mit=${r.headers["cf-mitigated"]} ${url.take(60)}")
        }
        return r
    }

    // ---------- Gerçek WebView'de oynatıcı betiğini çalıştır ----------
    private fun appContext(): android.content.Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
    } catch (e: Throwable) {
        null
    }

    // jwplayer / Hls / video.src çağrılarını yakalayan sahte nesneler (harici betikler engellendiği için gerekli)
    private val jsStub = """<script>(function(){var o2;
function mk(){var b=function(){return o2};return new Proxy(b,{get:function(t,k){
if(k==='setup'){return function(c){try{window.__cfg=JSON.stringify(c)}catch(e){window.__cfg='ERR'}return o2}}
if(typeof k==='symbol'||k==='then'){return undefined}
return function(){return o2}},apply:function(){return o2}})}
o2=mk();
window.jwplayer=function(){return o2};
window['$']=window.jQuery=function(){return o2};
window.Hls=function(){this.loadSource=function(u){window.__u=u};this.attachMedia=function(){};this.on=function(){};this.startLoad=function(){};this.destroy=function(){}};
window.Hls.isSupported=function(){return true};window.Hls.Events={};
try{Object.defineProperty(HTMLMediaElement.prototype,'src',{set:function(v){window.__u=v},get:function(){return ''}});}catch(e){}
})();</script>"""

    private fun jsStream(html: String, baseUrl: String, waitMs: Long = 9_000L): String? {
        val ctx = appContext()
        if (ctx == null) {
            log("JS: context yok")
            return null
        }
        val res = AtomicReference<String?>(null)
        val hit = AtomicReference<String?>(null)
        val holder = AtomicReference<android.webkit.WebView?>(null)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val hm = Regex("<head[^>]*>", IC).find(html)
        val page = if (hm != null) html.substring(0, hm.range.last + 1) + jsStub + html.substring(hm.range.last + 1) else jsStub + html
        handler.post {
            try {
                val w = android.webkit.WebView(ctx)
                holder.set(w)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.userAgentString = uaSiteMobile
                w.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        if (u.startsWith("http") && wvRegex.containsMatchIn(u) && !badExt.containsMatchIn(u)) hit.set(u)
                        // Ağa hiçbir şey gönderme (Cloudflare'e takılmasın); yalnızca satır içi betikler çalışsın
                        return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                w.loadDataWithBaseURL(baseUrl, page, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                log("JS WebView hata ${e.javaClass.simpleName} ${e.message?.take(60)}")
            }
        }
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end && res.get() == null && hit.get() == null) {
            Thread.sleep(400)
            handler.post {
                try {
                    holder.get()?.evaluateJavascript("(function(){return window.__cfg||window.__u||''})()") { v ->
                        if (v != null && v != "null" && v != "\"\"") res.set(v)
                    }
                } catch (_: Throwable) {
                }
            }
            Thread.sleep(150)
        }
        handler.post {
            try {
                holder.get()?.stopLoading()
                holder.get()?.destroy()
            } catch (_: Throwable) {
            }
        }
        val raw = res.get()
        if (raw != null) {
            var str = ""
            try {
                str = (JSONTokener(raw).nextValue() as? String).orEmpty()
            } catch (e: Exception) {
            }
            log("JS sonuç: " + str.take(260))
            val f = Regex("\"file\"\\s*:\\s*\"(https?:[^\"]+)\"").findAll(str)
                .map { it.groupValues[1].replace("\\/", "/") }
                .firstOrNull { !badExt.containsMatchIn(it) }
            if (f != null) return f
            if (str.startsWith("http") && !badExt.containsMatchIn(str)) return str
        }
        val h = hit.get()
        if (h != null) {
            log("JS ağ isteği yakalandı: " + h.take(110))
            return h
        }
        log("JS: sonuç yok")
        return null
    }

    // Android WebView her istege (alt cerceveler dahil) X-Requested-With: <paket> ekler; Chrome'da bu baslik yok.
    // Rapid embed'i bu basligi gorunce Cloudflare engel sayfasi veriyor. Baslik, androidx.webkit varsa kapatilir.
    private fun stripRequestedWith(w: android.webkit.WebView) {
        try {
            val cls = Class.forName("androidx.webkit.WebSettingsCompat")
            val m = cls.getMethod("setRequestedWithHeaderOriginAllowList", android.webkit.WebSettings::class.java, java.util.Set::class.java)
            m.invoke(null, w.settings, HashSet<String>())
            log("XRW kapatildi")
        } catch (e: Throwable) {
            log("XRW kapatilamadi ${e.javaClass.simpleName} ${e.message?.take(60)}")
        }
    }

    // Film sayfasini kendi WebView'imizde (X-Requested-With'siz) acar, embed'i iframe olarak gomer ve
    // iframe'in yaptigi .m3u8 istegini yakalar.
    private fun frameStreamWv(embed: String, pageUrl: String, waitMs: Long = 25_000L): String? {
        val ctx = appContext()
        if (ctx == null) {
            log("FW: context yok")
            return null
        }
        val hit = AtomicReference<String?>(null)
        val holder = AtomicReference<android.webkit.WebView?>(null)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val inject = "(function(){try{" +
            "if(window.__fmI){return '';}" +
            "if(!document.body||document.title.toLowerCase().indexOf('moment')>=0){return '';}" +
            "window.__fmI=1;" +
            "var f=document.createElement('iframe');" +
            "f.src=" + JSONObject.quote(embed) + ";" +
            "f.setAttribute('allow','autoplay; fullscreen; encrypted-media');" +
            "f.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;border:0;z-index:99999;background:#000';" +
            "document.body.appendChild(f);" +
            "}catch(e){}return '';})();"
        handler.post {
            try {
                val w = android.webkit.WebView(ctx)
                holder.set(w)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.mediaPlaybackRequiresUserGesture = false
                w.settings.userAgentString = uaSiteMobile
                stripRequestedWith(w)
                try {
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                } catch (_: Throwable) {
                }
                w.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        if (u.startsWith("http") && wvRegex.containsMatchIn(u) && !badExt.containsMatchIn(u)) {
                            hit.compareAndSet(null, u)
                        }
                        return null
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        try {
                            view?.evaluateJavascript(inject, null)
                        } catch (_: Throwable) {
                        }
                    }
                }
                w.loadUrl(pageUrl, mapOf("X-Requested-With" to ""))
            } catch (e: Throwable) {
                log("FW WebView hata ${e.javaClass.simpleName} ${e.message?.take(60)}")
            }
        }
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end && hit.get() == null) {
            Thread.sleep(500)
            handler.post {
                try {
                    holder.get()?.evaluateJavascript(inject, null)
                } catch (_: Throwable) {
                }
            }
        }
        handler.post {
            try {
                holder.get()?.stopLoading()
                holder.get()?.destroy()
            } catch (_: Throwable) {
            }
        }
        val h = hit.get()
        log("FW sonuc: " + (h?.take(140) ?: "yok"))
        return h
    }

    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String, depth: Int = 0): Embed? {
        val refs = listOf(pageUrl, "$mainUrl/").distinct()
        var wvTried = false // WebView yedeği çağrı başına yalnızca bir kez (her referer için 25 sn beklenmesin)
        for (rf in refs) {
            // Log analizi: Rapid sayfası eski (sade) başlıklarla düz istekte 200 veriyor, tarayıcı başlıklarıyla Cloudflare 403 + 25 sn çözücü bekleme;
            // Close sayfası tersine tarayıcı (iframe) başlıklarıyla 200 veriyor, sade başlıklarla 404. Bu yüzden sıra siteye göre seçilir.
            val rapidHost = originOf(embedUrl).contains("rapid", true)
            val hA = if (rapidHost) embedHeaders(rf) else browserEmbedHeaders(rf)
            val hB = if (rapidHost) browserEmbedHeaders(rf) else embedHeaders(rf)
            var r = fetchEmbed(embedUrl, hA, "A", false)
            if (r == null || r.code != 200) {
                val r2 = fetchEmbed(embedUrl, hB, "B", false)
                if (r2 != null && (r2.code == 200 || r == null)) r = r2
            }
            // Log analizi (04:51): Rapid embed'i hem sade hem tarayıcı başlıklarıyla 403 (Cloudflare hata sayfası, cf.errors.css) veriyor.
            // CloudflareKiller "çözdü" görünüp yine 403 alıyor, WebView doğrudan açınca da hata sayfası geliyor ve 30 sn boşa gidiyor
            // (Job was cancelled). Rapid için bu yollar atlanır; akış, film sayfasına iframe olarak gömülen embed'in
            // rapidrame .m3u8 isteği yakalanarak (webViewFrameFind) alınır.
            if (rapidHost && (r == null || r.code != 200)) {
                // Log analizi (05:16): iframe içinde bile cf.errors.css geliyor => Cloudflare bu bağlantıyı (IP/VPN) engelliyor.
                val body = r?.text.orEmpty()
                val ecode = Regex("Error\\s+(\\d{3,4})|error code:?\\s*(\\d{3,4})", IC).find(body)?.value
                val plain = body
                    .replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(IC, RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim()
                log("EM Rapid CF: kod=${r?.code} hata=$ecode ref=${rf.take(40)} metin=" + plain.take(600))
                continue
            }
            if (r == null || r.code != 200) {
                val r3 = fetchEmbed(embedUrl, hB, "CF", true)
                if (r3 != null) r = r3
            }

            var html: String
            var finalUrl: String
            if (r != null && r.code == 200 && r.text.isNotBlank()) {
                html = r.text
                finalUrl = r.url.ifBlank { embedUrl }
            } else {
                log("EM kod=${r?.code} ${embedUrl.take(70)}")
                if (r != null) log("EM gövde: " + r.text.take(220).replace(Regex("\\s+"), " "))
                // Düz istek (OkHttp) Cloudflare/uygulama filtresine takılıyor: sayfayı WebView'den (X-Requested-With ezilmiş) al
                if (wvTried) continue
                wvTried = true
                val w = try {
                    wvHtml(embedUrl, rf)
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("EM wv hata ${e.message?.take(60)}")
                    null
                }
                if (w.isNullOrBlank()) continue
                html = w
                finalUrl = embedUrl
                log("EM WV ile alındı len=${html.length} ${embedUrl.take(60)}")
            }

            // Log analizi: Rapid sayfasındaki asıl adres her yüklemede değişen (rastgele isimli, tuzlu, karıştırmalı) bir JS ile
            // çözülüyor; Close sayfasındaki sabit görünen master.txt ise sahte (404). Bu betikleri gerçek bir WebView'de çalıştırıp
            // jwplayer.setup'a verilen ayarı yakalıyoruz.
            val js = try {
                jsStream(html, finalUrl)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("JS hata ${e.javaClass.simpleName} ${e.message?.take(60)}")
                null
            }
            if (js != null) {
                log("EM JS ile bulundu ${js.take(110)}")
                return Embed(listOf(js), setOf(js), finalUrl)
            }

            // Yeni çözücü: p.a.c.k.e.r, atob, dc_xxx([...]), av("..."), iç içe base64
            val blobs = FmDecoder.collectBlobs(html)
            val c = FmDecoder.candFrom(blobs)
            val urls = LinkedHashSet<String>()
            val strict = LinkedHashSet<String>(c.strict)
            val ld = Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (ld != null && ld.startsWith("http", true) && !badExt.containsMatchIn(ld)) {
                urls.add(ld)
                strict.add(ld)
            }
            urls.addAll(c.urls)

            // Eski çözücü yedek olarak kalsın
            if (urls.isEmpty()) {
                try {
                    extractEmbed(html, originOf(finalUrl), unpackAll(html))?.let { urls.add(it.first) }
                } catch (_: Exception) {
                }
            }
            log("EM len=${html.length} blob=${blobs.size} url=${urls.size} strict=${strict.size} final=${finalUrl.take(60)}")
            run {
                val ix = html.indexOf("closeload", ignoreCase = true)
                if (ix >= 0) log("EM ipucu: " + html.substring(maxOf(0, ix - 160), minOf(html.length, ix + 200)).replace(Regex("\\s+"), " "))
                Regex("<iframe[^>]*>", IC).find(html)?.let { log("EM iframe: " + it.value.take(200)) }
                log("EM başlık: " + (Jsoup.parse(html).title().take(80)))
                try {
                    var k = 0
                    for (sc in Jsoup.parse(html).select("script:not([src])")) {
                        val d = sc.data()
                        if (d.length < 200) continue
                        if (d.contains("olc()") || d.contains("devtoolsDetector") || d.startsWith("Date.now||") || d.contains("__CF\$cv\$params")) continue
                        k++
                        if (k > 6) break
                        log("EM script#$k len=${d.length}")
                        var o = 0
                        while (o < d.length && o < 7000) {
                            log("EM s$k[$o]: " + d.substring(o, minOf(d.length, o + 1800)).replace(Regex("\\s+"), " "))
                            o += 1800
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (urls.isNotEmpty()) return Embed(urls.toList(), strict, finalUrl)

            val inner = Jsoup.parse(html).selectFirst("iframe[data-src], iframe[src]")
            if (inner != null && depth < 1) {
                val raw = inner.attr("data-src").ifBlank { inner.attr("src") }
                if (raw.startsWith("http") || raw.startsWith("//")) {
                    val iu = if (raw.startsWith("//")) "https:$raw" else raw
                    val rr = resolveEmbed(iu, finalUrl, depth + 1)
                    if (rr != null) return rr
                }
            }
        }
        return null
    }

    private val badExt =
        Regex("""\.(vtt|srt|jpg|jpeg|png|webp|gif|css|js|ico|svg)(\?|$)""", RegexOption.IGNORE_CASE)
    private val cdnHost = Regex(
        """^https?://[^/]*(?:\.shop|pictabox\.[a-z]+|rapidrame\.[a-z]+|cdnimages?\d*\.[a-z]+|cdnimgs?\d*\.[a-z]+|static\d+\.[a-z]+)(?:[/:?]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val b64Chars = Regex("^[A-Za-z0-9+/=_\\-]+$")
    private val httpStart = Regex("https?://[A-Za-z0-9]")

    private fun originOf(u: String) = Regex("^https?://[^/]+").find(u)?.value ?: ""

    private fun isMp4(u: String) =
        Regex("\\.mp4(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(u)

    /** (url, hls?) */
    private fun extractEmbed(html: String, origin: String, texts: List<String>): Pair<String, Boolean>? {
        Regex("<(?:source|video)[^>]*\\ssrc=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.let {
                val u = it.groupValues[1].replace("&amp;", "&")
                if (!badExt.containsMatchIn(u)) return u to !isMp4(u)
            }

        for (t in texts) {
            Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").find(t)?.let {
                val v = hexUnescape(it.groupValues[1])
                if (v.startsWith("http") && !badExt.containsMatchIn(v)) return v to !isMp4(v)
            }

            Regex("av\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").find(t)?.let {
                try {
                    val d = decodeSecret(it.groupValues[1])
                    findStreamUrl(d)?.let { f -> return f }
                    if (Regex("^https?://\\S+$").matches(d)) return d to true
                } catch (_: Exception) {}
            }

            for (m in Regex("\"?file\"?\\s*:\\s*\"([^\"]+)\"").findAll(t)) {
                val v = hexUnescape(m.groupValues[1])
                if (v.startsWith("http") && !badExt.containsMatchIn(v)) return v to !isMp4(v)
            }

            findStreamUrl(t)?.let { return it }

            Regex("[\"'](/[^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']").find(hexUnescape(t))?.let {
                return (origin + it.groupValues[1]) to true
            }
        }

        return try { bruteFindStream(texts) } catch (_: Exception) { null }
    }

    private fun findStreamUrl(text0: String): Pair<String, Boolean>? {
        val text = hexUnescape(text0).replace("&amp;", "&")
        val urls = Regex("""https?://[^\s"'<>\\]+""").findAll(text).map { it.value }.toList()

        val m3u8 = urls.filter { it.contains(".m3u8", true) && !badExt.containsMatchIn(it) }
        if (m3u8.isNotEmpty()) {
            val best = m3u8.firstOrNull { it.contains("master", true) } ?: m3u8.first()
            return best to true
        }
        urls.firstOrNull { cdnHost.containsMatchIn(it) && !badExt.containsMatchIn(it) }
            ?.let { return it to true }

        Regex("""file\s*["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(text)?.let {
            return it.groupValues[1] to false
        }
        return null
    }

    // ---------------- Yedek (brute-force) çözücü ----------------

    private fun bruteFindStream(texts: List<String>): Pair<String, Boolean>? {
        val cands = ArrayList<String>()
        val re = Regex("""["']([A-Za-z0-9+/=_\-]{30,6000})["']""")
        for (t in texts) for (m in re.findAll(t)) {
            if (!cands.contains(m.groupValues[1])) cands.add(m.groupValues[1])
        }
        cands.sortByDescending { it.length }
        for (c in cands.take(8)) {
            val d = tryDecodeString(c)
            if (d.isNotEmpty()) {
                findStreamUrl(d)?.let { return it }
                val u = Regex("""https?://[^\s"'<>\\]+""").find(d)?.value
                if (u != null && !badExt.containsMatchIn(u)) return u to !isMp4(u)
            }
        }
        return null
    }

    private fun tryDecodeString(str: String): String {
        val variants = listOf(str, str.reversed(), rot13(str), rot13(str).reversed())
        for (v in variants) {
            val bytes = b64(v)
            if (bytes.size < 12) continue
            val plain = latin1(bytes)
            if (httpStart.containsMatchIn(plain)) return plain
            val inner0 = latin1(b64(plain))
            if (httpStart.containsMatchIn(inner0)) return inner0

            for (a in 0..5) for (b in 0..5) for (c in 0..5) {
                if (a == 0 && b == 0 && c == 0) continue
                val off = intArrayOf(a, b, c)
                val n = minOf(bytes.size, 48)
                val pre = String(ByteArray(n) { i -> (bytes[i] - off[i % 3]).toByte() }, Charsets.ISO_8859_1)
                if (!httpStart.containsMatchIn(pre) && !b64Chars.matches(pre)) continue
                val s2 = String(ByteArray(bytes.size) { i -> (bytes[i] - off[i % 3]).toByte() }, Charsets.ISO_8859_1)
                if (httpStart.containsMatchIn(s2)) return s2
                if (b64Chars.matches(s2)) {
                    val s3 = latin1(b64(s2))
                    if (httpStart.containsMatchIn(s3)) return s3
                }
            }
        }
        return ""
    }

    // ---------------- Yardımcılar ----------------

    private fun b64(s: String): ByteArray {
        var x = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        if (x.length % 4 == 1) x = x.dropLast(1)
        while (x.length % 4 != 0) x += "="
        return try { Base64.decode(x, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
    }

    private fun latin1(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    private fun rot13(s: String): String = s.map { c ->
        when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")

    // RapidVid: ters çevir -> b64 -> "K9L" anahtarı ile kaydır -> b64
    private fun decodeSecret(input: String): String {
        val bytes = b64(input.reversed())
        val key = "K9L"
        val out = ByteArray(bytes.size) { i ->
            val off = (key[i % key.length].code % 5) + 1
            (bytes[i] - off).toByte()
        }
        val inner = latin1(out)
        if (Regex("https?://").containsMatchIn(inner)) return inner
        return latin1(b64(inner))
    }

    private fun hexUnescape(s: String): String {
        return s
            .let { Regex("""\\x([0-9a-fA-F]{2})""").replace(it) { m -> m.groupValues[1].toInt(16).toChar().toString() } }
            .let { Regex("""\\u([0-9a-fA-F]{4})""").replace(it) { m -> m.groupValues[1].toInt(16).toChar().toString() } }
            .replace("\\/", "/")
    }

    private fun unpackAll(text: String): List<String> {
        val list = arrayListOf(text)
        var cur = text
        repeat(3) {
            val u = try { getAndUnpack(cur) } catch (e: Exception) { "" }
            if (u.isBlank() || u == cur) return list
            list.add(u)
            cur = u
        }
        return list
    }
}

// ================= Çözücü (Rapid / Close gizleme katmanları) =================
private data class FmCand(val urls: List<String>, val strict: Set<String>)

private object FmDecoder {
    private val IC = RegexOption.IGNORE_CASE
    private const val B64CH = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun unesc(s: String): String {
        var t = s.replace("\\/", "/")
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return t.replace("&amp;", "&")
    }

    // ---------- p.a.c.k.e.r ----------
    private val packedRe = Regex(
        """\}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val packedRe2 = Regex(
        """\}\("((?:[^"\\]|\\.)*)",\s*(\d+),\s*(\d+),\s*"((?:[^"\\]|\\.)*)"\.split\("\|"\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun unpackWith(re: Regex, q: String, src: String, out: MutableList<String>) {
        for (m in re.findAll(src)) {
            try {
                val payload = m.groupValues[1].replace("\\" + q, q).replace("\\\\", "\\")
                val radix = m.groupValues[2].toInt()
                val count = m.groupValues[3].toInt()
                val dict = m.groupValues[4].split("|")

                fun enc(c: Int): String {
                    val pre = if (c < radix) "" else enc(c / radix)
                    val r = c % radix
                    return pre + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
                }

                val map = HashMap<String, String>()
                for (i in count - 1 downTo 0) {
                    val k = enc(i)
                    map[k] = dict.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: k
                }
                out.add(Regex("""\b\w+\b""").replace(payload) { map[it.value] ?: it.value })
            } catch (e: Exception) {
            }
        }
    }

    private fun unpack(src: String): List<String> {
        val out = ArrayList<String>()
        unpackWith(packedRe, "'", src, out)
        unpackWith(packedRe2, "\"", src, out)
        return out
    }

    // ---------- Base64 ----------
    private val atobRe = Regex("""atob\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")
    private val longB64 = Regex("""["']([A-Za-z0-9+/]{80,}={0,2})["']""")

    private fun b64(src: String): List<String> {
        val out = ArrayList<String>()
        val raws = atobRe.findAll(src).map { it.groupValues[1] }.take(20).toList() +
            longB64.findAll(src).map { it.groupValues[1] }.take(20).toList()
        for (raw in raws) {
            val bytes = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (e: Exception) {
                try {
                    Base64.decode(raw, Base64.URL_SAFE)
                } catch (e2: Exception) {
                    null
                }
            } ?: continue
            val d = String(bytes, Charsets.UTF_8)
            if (d.contains("http", true) || d.contains("eval(") || d.contains("m3u8", true)) out.add(d)
        }
        return out
    }

    // Hoşgörülü base64 çözücü (URL-safe ve bozuk karakterleri atar), baytları Latin-1 metin olarak verir
    private fun b64d(s0: String): String {
        val s = StringBuilder()
        for (ch in s0) {
            val c = if (ch == '-') '+' else if (ch == '_') '/' else ch
            if (B64CH.indexOf(c) >= 0) s.append(c)
        }
        if (s.length < 8) return ""
        val o = StringBuilder()
        var buf = 0
        var bits = 0
        for (ch in s) {
            buf = (buf shl 6) or B64CH.indexOf(ch)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                o.append(((buf shr bits) and 255).toChar())
                buf = buf and ((1 shl bits) - 1)
            }
        }
        return o.toString()
    }

    private fun rot13(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch in 'a'..'z') sb.append('a' + (ch - 'a' + 13) % 26)
            else if (ch in 'A'..'Z') sb.append('A' + (ch - 'A' + 13) % 26)
            else sb.append(ch)
        }
        return sb.toString()
    }

    private fun unmix(s: String, salt: Long): String {
        val sb = StringBuilder()
        for (i in s.indices) {
            val k = (salt % (i + 5)).toInt()
            sb.append((((s[i].code - k) % 256 + 256) % 256).toChar())
        }
        return sb.toString()
    }

    // ---------- Close: dc_xxx(["parça","parça",...]) ----------
    private val dcRe = Regex("""dc_\w+\s*\(\s*\[([^\]]*)\]\s*\)""")
    private val partRe = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'")
    private val saltRe = Regex("""(\d{6,12})\s*%\s*\(\s*\w+\s*\+\s*\d+\s*\)""")
    private val httpRe = Regex("""https?://""")

    private fun dcDecode(blob: String): List<String> {
        val out = ArrayList<String>()
        val salts = ArrayList<Long>()
        val found = saltRe.find(blob)?.groupValues?.get(1)?.toLongOrNull()
        if (found != null) salts.add(found)
        if (!salts.contains(399756995L)) salts.add(399756995L)
        for (m in dcRe.findAll(blob).take(10)) {
            val sb = StringBuilder()
            for (pm in partRe.findAll(m.groupValues[1])) {
                sb.append(if (pm.groupValues[1].isNotEmpty()) pm.groupValues[1] else pm.groupValues[2])
            }
            val s = sb.toString()
            if (s.length < 8) continue
            for (p in listOf(s, s.reversed(), rot13(s), rot13(s.reversed()))) {
                val d = b64d(p)
                if (d.isEmpty()) continue
                val cands = ArrayList<String>()
                cands.add(d)
                cands.add(d.reversed())
                for (sl in salts) {
                    val um = unmix(d, sl)
                    cands.add(um)
                    cands.add(um.reversed())
                    cands.add(unmix(d.reversed(), sl))
                }
                for (c in cands) {
                    if (httpRe.containsMatchIn(c)) out.add(c)
                }
            }
        }
        return out
    }

    // İç içe base64 (base64 -> ters -> base64 ...): en çok 3 kat
    private val mediaHintRe = Regex("""https?://|\.m3u8|\.mp4""")
    private val b64OnlyRe = Regex("""^[A-Za-z0-9+/=_-]+$""")
    private val wsRe = Regex("""\s+""")

    private fun deepB64(s: String, depth: Int, out: MutableList<String>) {
        if (depth > 3 || out.size > 40) return
        for (p in listOf(s, s.reversed())) {
            val d = b64d(p)
            if (d.isEmpty()) continue
            for (c in listOf(d, d.reversed())) {
                if (mediaHintRe.containsMatchIn(c)) {
                    out.add(c)
                    continue
                }
                val t = c.replace(wsRe, "")
                if (t.length >= 24 && b64OnlyRe.matches(t)) deepB64(t, depth + 1, out)
            }
        }
    }

    private val longStrRe = Regex("""["'`]([A-Za-z0-9+/=_-]{40,})["'`]""")

    private fun b64Strings(src: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (m in longStrRe.findAll(src)) {
            if (out.size >= 40) break
            if (seen.add(m.groupValues[1])) deepB64(m.groupValues[1], 0, out)
        }
        return out
    }

    // ---------- Rapid: av("...") ----------
    private val avRe = Regex("""\bav\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")

    private fun avDecode(input: String): String? {
        val first = b64d(input.reversed())
        if (first.isEmpty()) return null
        val key = "K9L"
        val sb = StringBuilder()
        for (i in first.indices) {
            val off = key[i % key.length].code % 5 + 1
            sb.append((first[i].code - off).toChar())
        }
        val second = b64d(sb.toString())
        return if (second.startsWith("http", true)) second else null
    }

    private fun dcAv(src: String): List<String> {
        val out = ArrayList<String>()
        out.addAll(dcDecode(src))
        for (m in avRe.findAll(src).take(10)) {
            val d = avDecode(m.groupValues[1])
            if (d != null) out.add(d)
        }
        return out
    }

    // ---------- Tüm çözülmüş metinleri topla ----------
    fun collectBlobs(html: String): List<String> {
        val out = LinkedHashSet<String>()
        var queue = ArrayList<String>()
        queue.add(html)
        var round = 0
        while (queue.isNotEmpty() && round < 3) {
            val next = ArrayList<String>()
            for (b in queue) {
                val u = unesc(b)
                if (!out.add(u)) continue
                next.addAll(unpack(u))
                next.addAll(b64(u))
                next.addAll(dcAv(u))
                next.addAll(b64Strings(u))
            }
            queue = next
            round++
        }
        return out.toList()
    }

    // ---------- Akış adayları ----------
    private val urlRe = Regex("""https?://[^\s"'<>\\)]+""")
    private val streamRe = Regex("""\.m3u8|\.mp4|sublist|/hls\d?/|/(?:master|playlist|index)[^/"']*\.txt""", IC)
    private val badRe = Regex("""\.(?:vtt|srt|jpe?g|png|gif|webp|css|js|svg|ico|woff2?)(?:\?|#|/?\z)""", IC)
    private val junkRe = Regex(
        """cloudflareinsights|cloudflare\.com|googletagmanager|google-analytics|googlesyndication|gstatic|googleapis|doubleclick|facebook\.|twitter\.|jquery|cdnjs|jsdelivr|unpkg|bootstrapcdn|histats|yandex|adsterra|popads|beacon\.min\.js|/cdn-cgi/|/ah/?(?:[?#]|$)""",
        IC
    )
    private val keyedRe = Regex(
        """["']?(?:file|file_link|contentUrl|source|src|hls|stream|stream_url|video_url|url|m3u8|playlist)["']?\s*[:=]\s*["'](https?://[^"'\s\\]+)["']""",
        IC
    )
    private val keyedBadRe = Regex("""\.(?:vtt|srt|jpe?g|png|webp|gif|css|js|ico|svg|woff2?|html?)(?:\?|$)""", IC)
    private val subDirRe = Regex("""/(?:srt|sub|subtitle|vtt)/""", IC)

    private fun junkUrl(u: String): Boolean = junkRe.containsMatchIn(u)

    private fun cleanBlob(b: String): String =
        b.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    fun candFrom(blobs: List<String>): FmCand {
        val urls = LinkedHashSet<String>()
        val strict = LinkedHashSet<String>()
        for (b in blobs) {
            val t = cleanBlob(b)
            for (m in urlRe.findAll(t)) {
                val u = m.value.trimEnd(',', ';', '.', ']', ')')
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u) && !junkUrl(u)) {
                    urls.add(u)
                    strict.add(u)
                }
            }
        }
        // Akış desenine uyan yoksa file:"..." / src="..." gibi anahtarlı adresler aday olur (doğrulamadan geçerse kullanılır)
        if (strict.isEmpty()) {
            for (b in blobs) {
                val t = cleanBlob(b)
                for (m in keyedRe.findAll(t)) {
                    val u = m.groupValues[1]
                    if (keyedBadRe.containsMatchIn(u) || junkUrl(u) || subDirRe.containsMatchIn(u) || u.contains(".js/")) continue
                    urls.add(u)
                }
            }
        }
        return FmCand(urls.toList(), strict)
    }
}
