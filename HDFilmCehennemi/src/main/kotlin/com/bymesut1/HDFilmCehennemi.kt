package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener
import kotlin.math.abs

private data class SiteItem(
    val url: String,
    val title: String,
    val year: Int,
    val type: String,
    val poster: String? = null,
    val isPoster: Boolean = false
)
private data class Alt(val id: String, val name: String, val lang: String, val active: Boolean = false)
private data class Player(val name: String, val lang: String, val id: String?, val embed: String?)
private data class EmbedResult(
    val urls: List<String>,
    val subs: List<SubItem>,
    val strict: Set<String>,
    val base: String,
    val keys: List<Pair<String, String>> = emptyList()   // /srt/<no>/<code>_Turkish.vtt anahtarları
)
private data class Verified(val ok: Boolean, val headers: Map<String, String>, val text: String = "")

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ---------------- Cloudflare çerezi (kalıcı) ----------------
    // Site bazen Cloudflare doğrulaması (403/503) istiyor; WebView ile çözüp çerezi diske kaydeder.
    // CloudflareKiller çerezi bir kez alınca süresi dolsa bile onu kullanmaya devam eder (yeniden çözmez),
    // bu yüzden 403 alırsak çözücüyü sıfırlayıp bir kez daha deneriz.
    @Volatile
    private var cfKiller = CloudflareKiller()
    private val cfLock = Semaphore(1)
    @Volatile
    private var cfWarm = false

    // Android'de java.io.tmpdir uygulamanın cache klasörüdür; ek izin ya da API gerekmez.
    // Her eklentinin dosya adı farklı olmalı (aynı klasörü paylaşıyorlar).
    private val cfFile by lazy {
        java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "hdfilmcehennemi_cf.txt")
    }
    private val cfMaxAgeMs = 12L * 60 * 60 * 1000 // bundan eski kayıt kullanılmaz, yeniden çözülür
    @Volatile
    private var cfSavedSig = ""

    private fun log(msg: String) {
        try {
            Log.d("HDFilmCehennemi", msg)
        } catch (_: Throwable) {
        }
    }

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

    // İlk çözüm sırasında onlarca istek aynı anda WebView açmasın diye ısınana kadar sıraya alınır.
    private suspend fun cfGet(url: String, headers: Map<String, String>, timeout: Long = 30L, retry: Boolean = true): NiceResponse {
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
        if (retry && (r.code == 403 || r.code == 503)) {
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

    init {
        cfRestore()
    }

    // ---------------- Ayarlar ----------------
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbSite = "https://www.themoviedb.org"
    private val imgBase = "https://image.tmdb.org/t/p"
    private val hosts = listOf("https://hdfilmcehennemi.nl", "https://www.hdfilmcehennemi.nl")
    private val showSubbed = false          // true: "Altyazılı" oynatıcılar her zaman eklensin (false: yalnızca dual yoksa / çalışmazsa)
    private val maxPlayers = 4              // en fazla kaç oynatıcı (Close, Rapid ...)
    private val subLangs = listOf("tr", "en")   // gömülü altyazı dilleri
    private val uaMobile = "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
    // Tarayıcıda (devtools) görülen gerçek UA: oynatıcı / CDN istekleri bununla gider
    private val uaEmbed = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // /srt/<no>/<kod>_<ad>.vtt/ dosya adı varyantları (Turkish, Turkish2 ...)
    private val trSrtNames = listOf("Turkish", "Turkish2", "Turkish1", "Turkish3", "Turkce")
    private val enSrtNames = listOf("English", "English2")

    private var good: Pair<String, Boolean>? = null   // çalışan adres + UA biçimi
    private val IC = RegexOption.IGNORE_CASE

    // ---------------- Net afiş (arama / geçmiş için) ----------------
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun upscale(u: String?): String? {
        if (u.isNullOrBlank()) return u
        var s: String = u
        s = s.replace(Regex("""/t/p/w(?:92|154|185|300|342)/"""), "/t/p/w500/")
        s = s.replace(Regex("""-\d{2,3}x\d{2,3}(\.(?:jpe?g|png|webp))""", IC), "$1")
        s = s.replace(Regex("""([?&](?:w|width))=\d{1,3}(?=&|$)""", IC), "$1=500")
        return s
    }

    private suspend fun tmdbPoster(title: String, year: Int?, isTv: Boolean): String? {
        val key = "$isTv|${Decoder.norm(title)}|${year ?: 0}"
        posterCache[key]?.let { return it.ifEmpty { null } }
        var found: String? = null
        try {
            val parts = (title.split(Regex("""\s+[-–—]\s+""")) + title)
                .map { it.trim() }.filter { it.length >= 2 }.distinct()
            val ep = if (isTv) "tv" else "movie"
            for (q0 in parts) {
                val q = URLEncoder.encode(q0, "UTF-8")
                val yp = if (year != null) (if (isTv) "&first_air_date_year=$year" else "&year=$year") else ""
                val res = app.get("$tmdbApi/search/$ep?api_key=$tmdbKey&language=tr-TR&query=$q$yp")
                    .parsedSafe<TmdbList>()?.results.orEmpty().filter { it.poster_path != null }
                val want = Decoder.norm(q0)
                fun names(it: TmdbItem) = listOf(it.title, it.name, it.original_title, it.original_name)
                    .map { n -> Decoder.norm(n ?: "") }.filter { n -> n.isNotEmpty() }
                val hit = res.firstOrNull { r -> names(r).any { n -> n == want } }
                    ?: res.firstOrNull { r ->
                        want.length >= 6 && names(r).any { n -> n.contains(want) || want.contains(n) }
                    }
                    ?: (if (year != null) res.firstOrNull() else null)
                if (hit?.poster_path != null) {
                    found = "$imgBase/w500${hit.poster_path}"
                    break
                }
            }
        } catch (e: Exception) {
        }
        posterCache[key] = found ?: ""
        return found
    }

    // Sitede konu yazılmamışsa ("Konu Bulunamadı") TMDB'den Türkçe özet al
    private suspend fun tmdbOverview(title: String, year: Int?, isTv: Boolean): String? {
        try {
            val parts = (title.split(Regex("""\s+[-–—]\s+""")) + title)
                .map { it.trim() }.filter { it.length >= 2 }.distinct()
            val ep = if (isTv) "tv" else "movie"
            for (q0 in parts) {
                val q = URLEncoder.encode(q0, "UTF-8")
                val yp = if (year != null) (if (isTv) "&first_air_date_year=$year" else "&year=$year") else ""
                val res = app.get("$tmdbApi/search/$ep?api_key=$tmdbKey&language=tr-TR&query=$q$yp")
                    .parsedSafe<TmdbList>()?.results.orEmpty().filter { !it.overview.isNullOrBlank() }
                val want = Decoder.norm(q0)
                val hit = res.firstOrNull { r ->
                    listOf(r.title, r.name, r.original_title, r.original_name)
                        .any { n -> Decoder.norm(n ?: "") == want }
                } ?: (if (year != null) res.firstOrNull() else null)
                val ov = hit?.overview
                if (!ov.isNullOrBlank()) return ov.trim()
            }
        } catch (e: Exception) {
        }
        return null
    }

    private suspend fun toSearchResponseHi(item: SiteItem): SearchResponse {
        val isTv = isSeriesItem(item)
        val t = cleanSiteTitle(item.title).ifBlank { item.title }
        val hi = tmdbPoster(t, if (item.year != 0) item.year else null, isTv) ?: upscale(item.poster)
        return toSearchResponse(item.copy(poster = hi))
    }

    // ---------------- Ana sayfa (SİTEDEN: kategoriler + afişler) ----------------
    override val mainPage = mainPageOf(
        "/" to "Yeni Eklenenler",
        "/category/tavsiye-filmler-izle2/" to "Tavsiye Filmler",
        "/imdb-7-puan-uzeri-filmler-2/" to "IMDb 7+ Filmler",
        "/en-cok-yorumlananlar-2/" to "En Çok Yorumlananlar",
        "/en-cok-begenilen-filmleri-izle-4/" to "En Çok Beğenilenler",
        "/category/film-izle-2/" to "Filmler",
        "/yabancidiziizle-5/" to "Diziler",
        "/dil/turkce-dublajli-film-izleyin-6/" to "Türkçe Dublaj",
        "/dil/turkce-altyazili-filmleri-izleme-sitesi-3/" to "Türkçe Altyazılı",
        "/tur/aksiyon-filmleri-izleyin-8/" to "Aksiyon",
        "/tur/korku-filmlerini-izle-9/" to "Korku",
        "/tur/komedi-filmlerini-izleyin-2/" to "Komedi",
        "/tur/bilim-kurgu-filmlerini-izleyin-5/" to "Bilim Kurgu",
        "/tur/gerilim-filmlerini-izle-4/" to "Gerilim",
        "/tur/dram-filmlerini-izle-2/" to "Dram",
        "/tur/animasyon-filmlerini-izleyin-5/" to "Animasyon",
        "/tur/macera-filmlerini-izleyin-5/" to "Macera",
        "/tur/romantik-filmleri-izle-3/" to "Romantik",
        "/tur/fantastik-filmlerini-izleyin-4/" to "Fantastik",
        "/tur/suc-filmleri-izle-3/" to "Suç",
        "/tur/savas-filmleri-izle-5/" to "Savaş",
        "/tur/belgesel-filmlerini-izle-2/" to "Belgesel",
        "/ulke/turkiye-4/" to "Türk Filmleri",
        "/ulke/guney-kore-1/" to "Kore Filmleri",
        "/category/marvel-yapimlarini-izle-5/" to "Marvel",
        "/category/dc-yapimlarini-izle-1/" to "DC",
        "/serifilmlerim-4/" to "Seri Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val p = if (page <= 1) path else path.trimEnd('/') + "/page/$page/"
        val cachedHtml = diskGet(p)
        var html = cachedHtml ?: try {
            siteGet(p)?.text.orEmpty()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        if (html.isBlank() && path != "/") {
            // Site kategori adresini değiştirmiş olabilir (ör. -2 -> -3): ana sayfadaki menüden gerçek bağlantıyı bul
            val alt = findCatalogPath(path)
            if (alt != null && alt != path) {
                log("kategori yolu bulundu: $path -> $alt")
                val p2 = if (page <= 1) alt else alt.trimEnd('/') + "/page/$page/"
                html = try {
                    siteGet(p2)?.text.orEmpty()
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
            }
        }
        val items = parseCatalog(html, false).map { toSearchResponse(it) }
        if (cachedHtml == null && items.isNotEmpty()) diskPut(p, html)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ---- Katalog sayfaları için kısa süreli disk önbelleği (uygulama yeniden açılınca anında gelsin) ----
    private val catDir by lazy { java.io.File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp") }
    private val catTtlMs = 15L * 60 * 1000

    private fun catFile(key: String): java.io.File =
        java.io.File(catDir, "hdfc_cat_" + key.hashCode().toString().replace('-', 'm') + ".html")

    private fun diskGet(key: String): String? {
        return try {
            val f = catFile(key)
            if (!f.exists()) return null
            val txt = f.readText()
            val nl = txt.indexOf('\n')
            if (nl <= 0) return null
            val ts = txt.substring(0, nl).toLongOrNull() ?: return null
            if (System.currentTimeMillis() - ts > catTtlMs) {
                f.delete()
                return null
            }
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

    // Ana sayfa HTML'sinden (önbellekten) bir kategorinin güncel yolunu bulur: aynı gövde, farklı sonek
    private suspend fun findCatalogPath(path: String): String? {
        val stem = path.trim('/').substringAfterLast('/').replace(Regex("-\\d+$"), "")
        if (stem.length < 5) return null
        val home = try {
            siteGet("/")?.text.orEmpty()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        if (home.isBlank()) return null
        val prefix = path.trim('/').substringBeforeLast('/', "")
        val re = Regex("""href=["'](?:https?://[^/"']+)?(/[^"'#?]*${Regex.escape(stem)}[^"'#?]*)["']""", IC)
        val hits = re.findAll(home).map { it.groupValues[1] }.distinct().toList()
        return hits.firstOrNull { it.trim('/').substringBeforeLast('/', "") == prefix } ?: hits.firstOrNull()
    }

    // Sitede olan film / dizi sayfası mı? (menü, kategori vb. değil)
    private fun isContentItem(it: SiteItem): Boolean {
        if (it.isPoster) return true
        val p = pathOf(it.url).substringBefore("?").substringBefore("#")
        if (p.contains("/sezon-") || p.contains("/bolum-")) return false
        if (Regex("^/dizi/[^/]+/?$").containsMatchIn(p)) return true
        if (!Regex("^/[^/]+/?$").containsMatchIn(p)) return false
        val slug = p.trim('/')
        val blocked = setOf("dizi", "film", "filmler", "diziler", "search", "kategori", "category", "tag", "yil", "imdb")
        if (slug in blocked) return false
        return slug.endsWith("-izle") || it.year > 0
    }

    private fun parseCatalog(src: String, needPoster: Boolean): List<SiteItem> =
        parseResults(src).filter { isContentItem(it) && (!needPoster || it.poster != null) }

    private fun cleanSiteTitle(t: String): String = t
        .replace(Regex("""\s+[İIiı]zle\b.*$"""), "")
        .replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun toSearchResponse(item: SiteItem): SearchResponse {
        val title = cleanSiteTitle(item.title).ifBlank { item.title }
        val yr = if (item.year != 0) item.year else null
        return if (isSeriesItem(item)) {
            newTvSeriesSearchResponse(title, item.url, TvType.TvSeries) {
                this.posterUrl = item.poster
                this.year = yr
            }
        } else {
            newMovieSearchResponse(title, item.url, TvType.Movie) {
                this.posterUrl = item.poster
                this.year = yr
            }
        }
    }

    // ---------------- Arama (SİTEDEN) ----------------
    override suspend fun search(query: String): List<SearchResponse> =
        searchSite(query).filter { isContentItem(it) }.take(30).amap { toSearchResponseHi(it) }

    // ---------------- Detay (SİTEDEN, dizilerde TMDB bölüm bilgisi) ----------------
    override suspend fun load(url: String): LoadResponse? {
        val path = pathOf(url)
        val r = siteGet(path) ?: return null
        val html = r.text
        val doc = Jsoup.parse(html)
        val ogTitle: String? = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
        val h1Text: String? = doc.selectFirst("h1")?.text()
        val rawTitle: String = ogTitle ?: h1Text ?: doc.title()
        val title: String = cleanSiteTitle(rawTitle)
        if (title.isBlank()) return null
        val sitePoster: String? = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val sitePlotRaw: String? = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        // Sitede konu yoksa "Konu Bulunamadı" yazıyor; onu yok say
        val sitePlot: String? = sitePlotRaw?.takeUnless { Regex("""bulunamad""", IC).containsMatchIn(it) }
        val yearTxt: String? = doc.selectFirst("[class*=year]")?.text()
        val year: Int? = Regex("""\((19|20)\d{2}\)""").find(rawTitle)?.value?.trim('(', ')')?.toIntOrNull()
            ?: yearTxt?.let { Regex("""(19|20)\d{2}""").find(it)?.value }?.toIntOrNull()
        val poster: String? = tmdbPoster(title, year, path.startsWith("/dizi/")) ?: upscale(sitePoster)

        if (!path.startsWith("/dizi/")) {
            val moviePlot: String? = sitePlot ?: tmdbOverview(title, year, false)
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = moviePlot
                this.year = year
            }
        }
        return loadSeriesPage(url, path, html, title, poster, sitePlot, year)
    }

    private suspend fun loadSeriesPage(
        url: String, path: String, html: String,
        title: String, poster: String?, sitePlot: String?, year: Int?
    ): LoadResponse? {
        // Sitedeki bölümler
        val base = Regex.escape(path.trimEnd('/'))
        val epRe = Regex(
            """href=\x22(?:https?://[^\x22/]+)?($base/sezon-(\d+)/bolum-(\d+)(?:-[a-z0-9-]+)?/?)\x22""", IC
        )
        val found = LinkedHashMap<Pair<Int, Int>, String>()
        for (m in epRe.findAll(html)) {
            val sNo = m.groupValues[2].toIntOrNull() ?: continue
            val eNo = m.groupValues[3].toIntOrNull() ?: continue
            val k = Pair(sNo, eNo)
            if (!found.containsKey(k)) found[k] = toSite(m.groupValues[1])
        }

        // TMDB: yalnızca bu (sitede olan) dizi için bölüm bilgisi
        val tmdbId = tmdbFindTv(title, year)
        val detail = if (tmdbId != null) tmdbTv(tmdbId) else null

        val seasonNums: List<Int> = if (found.isNotEmpty()) {
            found.keys.map { it.first }.distinct().sorted()
        } else {
            detail?.seasons.orEmpty().mapNotNull { it.season_number }.filter { it > 0 }
        }
        val infoBySeason = HashMap<Int, Map<Int, TmdbEpisode>>()
        if (tmdbId != null) {
            for (sn in seasonNums) infoBySeason[sn] = tmdbSeasonMap(tmdbId, sn)
        }

        val eps: Map<Pair<Int, Int>, String> = if (found.isNotEmpty()) {
            found
        } else {
            val g = LinkedHashMap<Pair<Int, Int>, String>()
            for (sn in seasonNums) {
                for (e in infoBySeason[sn].orEmpty().keys.sorted()) {
                    g[Pair(sn, e)] = toSite(url.trimEnd('/') + "/sezon-$sn/bolum-$e/")
                }
            }
            g
        }
        if (eps.isEmpty()) return null

        val episodes = eps.toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second })).map { (k, epUrl) ->
            val info = infoBySeason[k.first]?.get(k.second)
            newEpisode(epUrl) {
                this.name = info?.name?.takeIf { it.isNotBlank() }
                this.season = k.first
                this.episode = k.second
                this.posterUrl = info?.still_path?.let { "$imgBase/w300$it" }
                this.description = info?.overview?.takeIf { it.isNotBlank() }
                this.runTime = info?.runtime
                addDate(info?.air_date)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster ?: detail?.poster_path?.let { "$imgBase/w500$it" }
            this.backgroundPosterUrl = detail?.backdrop_path?.let { "$imgBase/original$it" }
            this.year = year ?: detail?.first_air_date?.take(4)?.toIntOrNull()
            this.plot = detail?.overview?.trim()?.takeIf { it.isNotBlank() } ?: sitePlot
            this.seasonNames = seasonNums.map { SeasonData(it, "$it. Sezon") }
        }
    }

    // ---------------- TMDB (yalnızca sitede olan diziler için) ----------------
    private suspend fun tmdbFindTv(title: String, year: Int?): Int? {
        if (tmdbKey.startsWith("BURAYA")) return null
        val parts = (title.split(Regex("""\s+[-–—]\s+""")) + title).map { it.trim() }.filter { it.length >= 2 }.distinct()
        for (q0 in parts) {
            val q = URLEncoder.encode(q0, "UTF-8")
            val want = Decoder.norm(q0)
            for (withYear in listOf(true, false)) {
                if (withYear && year == null) continue
                try {
                    val y = if (withYear) "&first_air_date_year=$year" else ""
                    val res = app.get("$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&query=$q$y")
                        .parsedSafe<TmdbList>()?.results.orEmpty()
                    val exact = res.firstOrNull {
                        Decoder.norm(it.name ?: "") == want || Decoder.norm(it.original_name ?: "") == want
                    }
                    val id = (exact ?: res.firstOrNull())?.id
                    if (id != null) return id
                } catch (e: Exception) {
                }
            }
        }
        return null
    }

    private suspend fun tmdbTv(id: Int): TmdbDetail? = try {
        app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=tr-TR").parsedSafe<TmdbDetail>()
    } catch (e: Exception) {
        null
    }

    // Bir sezonun bölümleri: Türkçe ad + açıklama; İngilizce yalnızca eksik görsel/ad için
    private suspend fun tmdbSeasonMap(id: Int, season: Int): Map<Int, TmdbEpisode> {
        suspend fun fetch(lang: String): List<TmdbEpisode> {
            for (i in 1..2) {
                try {
                    val r = app.get("$tmdbApi/tv/$id/season/$season?api_key=$tmdbKey&language=$lang")
                        .parsedSafe<TmdbSeason>()?.episodes
                    if (r != null) return r
                } catch (e: Exception) {
                }
            }
            return emptyList()
        }
        val tr = fetch("tr-TR")
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

    // ---------------- Site istekleri ----------------
    private fun siteHeaders(host: String, desktop: Boolean, extra: Map<String, String>): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to (if (desktop) uaDesktop else uaMobile),
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
    private val wvPar = Semaphore(6) // aynı anda 6 WebView (kataloglar hızlı gelsin)
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
    private suspend fun wvFetch(host: String, path: String, extra: Map<String, String>, pageAnchor: String? = null): SiteResp? {
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
            "if(window.__hdfcOk){return window.__hdfcOk;}" +
            "var now=Date.now();" +
            "if(window.__hdfcT&&now-window.__hdfcT<1500){return window.__hdfcLast||'0||';}" +
            "window.__hdfcT=now;" +
            "var st=0,out='',cfm='';" +
            "try{var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(path) + ",false);" + hdrJs +
            "x.send();st=x.status;out=x.responseText||'';cfm=x.getResponseHeader('cf-mitigated')||'';}catch(e){}" +
            "var res=st+'|'+cfm+'|'+out;window.__hdfcLast=res;" +
            "if(st>=200&&st<300&&out.length>0){window.__hdfcOk=res;" +
            "setTimeout(function(){try{new Image().src='/__hdfc_done__?'+Date.now();}catch(e){}},50);}" +
            // Cloudflare doğrulaması DEĞİL, kesin ret ise (ör. 'Uygulama desteklenmiyor') uzun beklemeden bitir
            "else if(st>0&&cfm==''&&out.indexOf('Just a moment')<0&&out.indexOf('cf-chl')<0&&!window.__hdfcF){window.__hdfcF=1;" +
            "setTimeout(function(){try{if(!window.__hdfcOk){new Image().src='/__hdfc_done__?'+Date.now();}}catch(e){}},2500);}" +
            "return res;}catch(e){return '0||';}})();"

        val gate = wvPar
        gate.acquire()
        try {
            cached()?.let { return it }
            if (failed()) return null
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
            // Oynatıcı isteği (/video/<id>/) için önce film sayfasının KENDİSİ açılır: XHR'nin Referer'ı ve çerezleri
            // tarayıcıdakiyle aynı olsun (ana sayfadan istenince site başka filmin oynatıcısını veriyor)
            val lightAttempt = pageAnchor == null && wvCleared && !wvLightBad
            if (pageAnchor != null) attempts.add(arrayOf(pageAnchor, null, "", 18_000L))
            if (lightAttempt) attempts.add(arrayOf("/hdfc-hafif-sayfa/", null, "", 3_500L))
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
                            Regex("__hdfc_done__"),
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
                    } else if (anchor == "/hdfc-hafif-sayfa/") {
                        wvLightFails = 0
                    }
                    return resp
                }
                log("WV gövde: " + body.take(200).replace(Regex("\\s+"), " "))
                lastBad = resp
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
            gate.release()
        }
    }

    // Film sayfasını GERÇEK açılışla (WebView, sayfanın kendi JS'i çalışır) açıp DOM'u okur.
    // XHR ile alınan sayfada site başka film / yem kaynaklar verebiliyor.
    private val domCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private suspend fun wvPageDom(pageUrl: String): String? {
        val path = pathOf(pageUrl)
        domCache[path]?.let { if (System.currentTimeMillis() - it.first < 5L * 60 * 1000) return it.second }
        val script = "(function(){try{if(window.__hdfcD){return window.__hdfcD;}" +
            "var h=document.documentElement?document.documentElement.outerHTML:'';" +
            "if(h.indexOf('alternative-link')>=0){window.__hdfcD=h;" +
            "setTimeout(function(){try{new Image().src='/__hdfc_dom__?'+Date.now();}catch(e){}},50);return h;}" +
            "return '';}catch(e){return '';}})();"
        wvPar.acquire()
        try {
            for (host in listOf(good?.first ?: hosts[0], hosts[1]).distinct()) {
                val got = AtomicReference<String?>(null)
                try {
                    app.get(
                        host + path,
                        interceptor = WebViewResolver(
                            Regex("__hdfc_dom__"),
                            userAgent = null,
                            useOkhttp = false,
                            script = script,
                            scriptCallback = { v ->
                                if (v != null && v != "null" && v.length > 20) got.set(v)
                            },
                            timeout = 20_000L
                        ),
                        headers = mapOf("X-Requested-With" to ""),
                        timeout = 40L
                    )
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("DOM hata ${e.javaClass.simpleName}")
                }
                var n = 0
                while (got.get() == null && n < 15) {
                    Thread.sleep(100)
                    n++
                }
                val raw = got.get() ?: continue
                val str = try {
                    JSONTokener(raw).nextValue() as? String
                } catch (e: Exception) {
                    null
                } ?: continue
                if (str.contains("alternative-link")) {
                    log("DOM alindi len=${str.length} ${(host + path).take(80)}")
                    domCache[path] = Pair(System.currentTimeMillis(), str)
                    return str
                }
            }
        } finally {
            wvPar.release()
        }
        return null
    }

    private suspend fun siteGet(path: String, extra: Map<String, String> = emptyMap(), pageAnchor: String? = null): SiteResp? {
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

        // 2) WebView ile (çerez beklemeden, sayfa içinden XHR)
        val cands = listOf(good?.first ?: hosts[0], hosts[1], hosts[0]).distinct()
        for (host in cands) {
            val r = try {
                wvFetch(host, path, extra, pageAnchor)
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
        }
        return null
    }

    private fun siteBase(): String = good?.first ?: mainUrl

    private fun toSite(u0: String): String {
        val u = u0.replace("&amp;", "&").replace("\\/", "/")
        return when {
            Regex("^https?://", IC).containsMatchIn(u) ->
                u.replace(Regex("^https?://(?:www\\.)?hdfilmcehennemi\\.(?!mobi)[a-z]+", IC), siteBase())
            u.startsWith("//") -> "https:$u"
            else -> siteBase().trimEnd('/') + (if (u.startsWith("/")) "" else "/") + u
        }
    }

    private fun pathOf(u: String): String = u.replace(Regex("^https?://[^/]+", IC), "").ifEmpty { "/" }

    private fun originOf(u: String): String =
        Regex("^(https?://[^/]+)", IC).find(u)?.groupValues?.get(1) ?: siteBase()

    // ---------------- Site araması ----------------
    private fun isSeriesItem(it: SiteItem): Boolean =
        it.type.contains("dizi", true) || it.url.contains("/dizi/")

    private fun parseResults(src: String): List<SiteItem> {
        val out = ArrayList<SiteItem>()
        val seen = HashSet<String>()
        val doc = Jsoup.parse(src)
        // Afiş kartları varsa yalnızca onları al (menü / yan bağlantılar karışmasın)
        val posters = doc.select("a.poster:not(.poster-slider)")
        val anchors = if (posters.isNotEmpty()) posters else doc.select("a[href]")
        anchors.forEach { a ->
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:")) return@forEach
            var title = a.selectFirst("[class*=poster-title], [class*=title]")?.text().orEmpty()
            if (title.isBlank()) title = a.attr("title")
            if (title.isBlank()) title = a.selectFirst("img[alt]")?.attr("alt").orEmpty()
            if (title.isBlank()) title = a.text()
            title = title.trim()
            val yearEl = a.selectFirst("[class*=year]") ?: a.selectFirst(".poster-meta span")
            val year = yearEl?.text()
                ?.let { Regex("""\d{4}""").find(it)?.value }?.toIntOrNull() ?: 0
            val type = a.selectFirst("[class*=type]")?.text().orEmpty().trim()
            val url = toSite(href)
            val imgEl = a.selectFirst("img")
            var posterRaw: String? = null
            if (imgEl != null) {
                for (k in listOf("data-src", "data-lazy-src", "src")) {
                    val v: String = imgEl.attr(k)
                    if (v.isNotBlank() && !v.startsWith("data:")) {
                        posterRaw = v
                        break
                    }
                }
            }
            val poster: String? = posterRaw?.let { toSite(it) }
            if (title.isBlank() || !seen.add(url)) return@forEach
            out.add(SiteItem(url, title, year, type, poster, a.hasClass("poster")))
        }
        return out
    }

    private suspend fun searchSite(q: String): List<SiteItem> {
        val r = siteGet(
            "/search/?q=" + URLEncoder.encode(q, "UTF-8"),
            mapOf("Accept" to "application/json, text/plain, */*", "X-Requested-With" to "fetch")
        ) ?: return emptyList()
        val body = r.text
        val html = try {
            val j = JSONObject(body)
            val arr = j.optJSONArray("results") ?: j.optJSONArray("data")
            if (arr != null) (0 until arr.length()).joinToString("\n") { arr.optString(it) } else body
        } catch (e: Exception) {
            body
        }
        return parseResults(html)
    }

    private fun pickBest(list: List<SiteItem>, wants: List<String>, year: Int, isTv: Boolean): SiteItem? {
        var best: SiteItem? = null
        var bs = -1
        for (item in list) {
            if (isSeriesItem(item) != isTv) continue
            val n = Decoder.norm(item.title)
            val parts = (item.title.split(Regex("""\s+[-–—]\s+""")) + item.title).map { Decoder.norm(it) }
            var s = 0
            for (w in wants) {
                val wn = Decoder.norm(w)
                if (wn.isEmpty()) continue
                if (n == wn || parts.contains(wn)) s = maxOf(s, 100)
                else if (wn.length >= 6 && n.contains(wn)) s = maxOf(s, 60)
                else if (n.length >= 6 && wn.contains(n)) s = maxOf(s, 55)
            }
            if (s == 0) continue
            if (year != 0 && item.year != 0) {
                val d = abs(item.year - year)
                if (!isTv) {
                    if (d == 0) s += 30 else if (d == 1) s += 15 else s -= 60
                } else if (d == 0) s += 10
            }
            if (s > bs) {
                bs = s
                best = item
            }
        }
        return if (bs >= 50) best else null
    }

    private fun findEpisode(html: String, s: Int, e: Int, seriesPath: String): String {
        fun mk(prefix: String) = Regex(
            """href=\x22((?:https?://[^\x22/]+)?$prefix[^\x22]*?/?sezon-$s/bolum-$e(?:-[a-z0-9-]+)?/)\x22""", IC
        )
        mk(Regex.escape(seriesPath.trimEnd('/'))).find(html)?.let { return toSite(it.groupValues[1]) }
        mk("/dizi/").find(html)?.let { return toSite(it.groupValues[1]) }
        return ""
    }

    private suspend fun getEpisodeUrl(seriesUrl: String, season: Int, episode: Int): String {
        val r = siteGet(pathOf(seriesUrl))
        val u = findEpisode(r?.text.orEmpty(), season, episode, pathOf(seriesUrl))
        if (u.isNotEmpty()) return u
        return toSite(seriesUrl.trimEnd('/') + "/sezon-$season/bolum-$episode/")
    }

    private fun pageOk(r: SiteResp?): Boolean =
        r != null && !denied(r) && Regex("alternative-link|<iframe|data-video=", IC).containsMatchIn(r.text)

    private fun slugGuess(titles: List<String>, isTv: Boolean): List<String> {
        val out = ArrayList<String>()
        titles.take(3).forEach { t ->
            val s = Decoder.slugify(t)
            if (s.isEmpty()) return@forEach
            if (isTv) out.add("/dizi/$s/") else {
                out.add("/$s-izle/")
                out.add("/$s/")
            }
        }
        return out.distinct()
    }

    private suspend fun findPage(link: LinkData): String? {
        val imdb = link.imdb.orEmpty()
        val queries = (listOf(imdb) + link.titles.take(3)).filter { it.isNotBlank() }.distinct()
        val lists = queries.amap { q -> q to (try { searchSite(q) } catch (e: Exception) { emptyList<SiteItem>() }) }

        val all = LinkedHashMap<String, SiteItem>()
        val byImdb = ArrayList<SiteItem>()
        lists.forEach { (q, l) ->
            l.forEach { item ->
                if (q == imdb && isSeriesItem(item) == link.isTv) byImdb.add(item)
                if (!all.containsKey(item.url)) all[item.url] = item
            }
        }

        var best: SiteItem? = null
        if (byImdb.isNotEmpty()) {
            val yr = link.year
            best = byImdb.firstOrNull { yr != null && it.year == yr } ?: byImdb[0]
        }
        if (best == null) best = pickBest(all.values.toList(), link.titles, link.year ?: 0, link.isTv)
        if (best != null) {
            return if (link.isTv) getEpisodeUrl(best.url, link.season ?: 1, link.episode ?: 1) else best.url
        }

        for (g in slugGuess(link.titles, link.isTv)) {
            if (pageOk(siteGet(g))) {
                return if (link.isTv) getEpisodeUrl(toSite(g), link.season ?: 1, link.episode ?: 1) else toSite(g)
            }
        }
        return null
    }

    // ---------------- Film / bölüm sayfası ----------------
    private fun parseAlts(html: String): List<Alt> {
        val out = ArrayList<Alt>()
        val seen = HashSet<String>()
        val doc = Jsoup.parse(html)
        // Sitede göze görünmeyen yem düğmeler var (inert / aria-hidden / tabindex=-1, ör. ikinci "Close"): bunlar atlanır
        fun trap(b: org.jsoup.nodes.Element): Boolean {
            var e: org.jsoup.nodes.Element? = b
            while (e != null) {
                if (e.hasAttr("inert") || e.hasAttr("hidden") || e.attr("aria-hidden").equals("true", true) ||
                    e.attr("tabindex") == "-1" || Regex("""display\s*:\s*none""", IC).containsMatchIn(e.attr("style"))
                ) return true
                e = e.parent()
            }
            return false
        }
        doc.select("div.alternative-links").forEach { div ->
            val lang = div.attr("data-lang")
            div.select("button[data-video]").forEach { b ->
                val id = b.attr("data-video")
                if (id.isBlank() || trap(b) || !seen.add(id)) return@forEach
                out.add(Alt(id, b.text().trim().ifBlank { "Player" }, lang.ifBlank { b.attr("data-lang") }, b.attr("data-active") == "1"))
            }
        }
        if (out.isNotEmpty()) return out

        // Yedek: kapsayıcı sınıf adı değişirse data-video taşıyan her öğeyi al, dili üst öğelerden bul
        doc.select("[data-video]").forEach { b ->
            val id = b.attr("data-video")
            if (id.isBlank() || b.hasAttr("inert") || b.attr("aria-hidden").equals("true", true) || b.attr("tabindex") == "-1" || !seen.add(id)) return@forEach
            var lg = b.attr("data-lang")
            var p = b.parent()
            while (lg.isBlank() && p != null) {
                lg = p.attr("data-lang")
                p = p.parent()
            }
            out.add(Alt(id, b.text().trim().ifBlank { "Player" }, lg))
        }
        return out
    }

    private fun pageIframe(html: String): String {
        val f = Jsoup.parse(html).selectFirst("iframe[data-src], iframe[src]") ?: return ""
        val u = f.attr("data-src").ifBlank { f.attr("src") }
        return if (u.startsWith("http") || u.startsWith("//")) toSite(u) else ""
    }

    private fun iframeFromAjax(text: String): String {
        var t = text
        try {
            val j = JSONObject(text)
            val d = j.opt("data")
            val h = when (d) {
                is JSONObject -> d.optString("html")
                is String -> d
                else -> j.optString("html")
            }
            if (h.isNotBlank()) t = h
        } catch (e: Exception) { }
        t = t.replace("\\/", "/").replace("\\\"", "\"").replace("\\u0026", "&")
        val m = Regex("""(?:data-src|src)=\x22((?:https?:)?//[^\x22]+)\x22""", IC).find(t)
        return if (m != null) toSite(m.groupValues[1]) else ""
    }

    // ---------------- Oynatıcı çözümleme ----------------
    // Tarayıcıda site www'li adresle açılıyor; Referer olarak da www'li adres gidiyor
    private fun wwwOf(u: String): String =
        u.replace(Regex("^(https?://)(?:www\\.)?(hdfilmcehennemi\\.(?!mobi)[a-z]+)", IC), "$1www.$2")

    private fun isMobi(u: String): Boolean = u.contains("hdfilmcehennemi.mobi", true)

    // Düz (altyazı vb.) istekler için
    private fun plainHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to uaEmbed,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to ref
    )

    // Oynatıcı sayfası (iframe) için tarayıcı benzeri header'lar
    private fun embedHeaders(ref: String, target: String = ""): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["User-Agent"] = uaEmbed
        m["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        m["Accept-Language"] = "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        m["Referer"] = ref
        m["Upgrade-Insecure-Requests"] = "1"
        m["Sec-Fetch-Dest"] = "iframe"
        m["Sec-Fetch-Mode"] = "navigate"
        val same = target.isNotBlank() && originOf(target).equals(originOf(ref), true)
        m["Sec-Fetch-Site"] = if (same) "same-origin" else "cross-site"
        m["Sec-Ch-Ua"] = "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\""
        m["Sec-Ch-Ua-Mobile"] = "?1"
        m["Sec-Ch-Ua-Platform"] = "\"Android\""
        return m
    }

    private fun embedVariants(u: String): List<String> {
        val out = ArrayList<String>()
        val onSite = Regex("^https?://(?:www\\.)?hdfilmcehennemi\\.(?!mobi)[a-z]+", IC)
        when {
            // Close: tarayıcıda hdfilmcehennemi.mobi üzerinden açılıyor
            u.contains("/video/embed/", true) && u.contains("hdfilmcehennemi.", true) ->
                out.add(u.replace(Regex("^https?://[^/]+", IC), "https://hdfilmcehennemi.mobi"))
            // Rapid (/rplayer/<id>/): tarayıcıda www'li adresle açılıyor
            onSite.containsMatchIn(u) -> {
                out.add(wwwOf(u))
                out.add(u.replace(Regex("^(https?://)www\\.", IC), "$1"))
            }
            else -> out.add(u)
        }
        return out.distinct()
    }

    // Gerçek Cloudflare doğrulama sayfası mı? (düz 403'te WebView açmanın faydası yok)
    private fun isCfChallenge(r: NiceResponse?): Boolean {
        if (r == null || (r.code != 403 && r.code != 503)) return false
        if (!r.headers["server"].orEmpty().contains("cloudflare", true)) return false
        if (r.headers["cf-mitigated"].orEmpty().contains("challenge", true)) return true
        val t = r.text
        return t.contains("Just a moment", true) || t.contains("cf-chl", true) ||
            t.contains("challenge-platform", true) || t.contains("Enable JavaScript", true)
    }

    private val wvStreamRe = Regex("""master\.txt|\.m3u8|\.mp4(\?|$)""")

    // Film sayfasını WebView'de açar (son çare). Not: oynatıcı çapraz-kaynaklı iframe içinde olduğu için
    // sayfadaki script iframe'in içindeki oynat düğmesine basamıyor; asıl çözüm Decoder.nsUrls (sayfadaki şifreyi çözer).
    private suspend fun wvCapture(pageUrl: String, altId: String?, timeoutMs: Long = 15_000L): String? {
        val page = wwwOf(pageUrl)
        val idPart = (altId ?: "").filter { it.isLetterOrDigit() }
        val js = "(function(){var id='" + idPart + "';var n=0;var t=setInterval(function(){n++;try{" +
            "if(id&&n==2){var b=document.querySelector('button.alternative-link[data-video=\\''+id+'\\']');if(b)b.click();}" +
            "var f=document.querySelector('.video-container iframe');" +
            "var p=document.querySelector('.play-that-video');" +
            "if(n>=3&&p&&!(f&&(f.getAttribute('src')||'').length>0))p.click();" +
            "}catch(e){}if(n>=20)clearInterval(t);},700);})();"
        val wwwRoot = "https://www." + siteBase().substringAfter("://").removePrefix("www.").trimEnd('/') + "/"
        val bad = Regex("""\.(?:vtt|srt|jpe?g|png|webp|gif|css|js)(?:\?|$)""", IC)
        val wv = app.get(
            page,
            referer = wwwRoot,
            interceptor = WebViewResolver(wvStreamRe, timeout = timeoutMs, script = js)
        )
        val u = wv.url
        return if (u != page && wvStreamRe.containsMatchIn(u) && !bad.containsMatchIn(u)) u else null
    }

    // Uzun metni logcat'e parça parça yaz (yapıyı incelemek için)
    private fun dumpLog(tag: String, text: String) {
        val t = text.replace("\r", "").replace("\n", " ").replace(Regex("\\s{2,}"), " ")
        var i = 0
        var n = 0
        while (i < t.length && n < 16) {
            log("DUMP $tag[$n] " + t.substring(i, minOf(t.length, i + 3000)))
            i += 3000
            n++
        }
    }

    // Oynatıcı sayfasının kendi yaptığı POST .../kn/ isteği: gerçek akış adresi (yem değil) buradan geliyor
    private suspend fun knFetch(kn: KnInfo, v: String, page: NiceResponse): Pair<String, String>? {
        val p = Decoder.knF(kn.n + "|" + kn.k + "|[object Window]|[object HTMLDocument]|function", kn.seed, kn.rounds)
        val org = originOf(v)
        val url = if (kn.path.startsWith("http", true)) kn.path else org + (if (kn.path.startsWith("/")) "" else "/") + kn.path
        // Tarayıcı sayfa yüklenir yüklenmez değil, kısa bir süre sonra istiyor
        kotlinx.coroutines.delay(1100L)
        for (attempt in 1..2) {
            val form = LinkedHashMap<String, String>()
            for ((key, vv) in kn.fields) {
                form[key] = if (vv.startsWith("@")) {
                    val nm = vv.substring(1)
                    when {
                        nm == kn.nName -> kn.n
                        nm == kn.jName -> kn.j
                        nm == "p" -> p
                        nm == "d" -> attempt.toString()
                        else -> ""
                    }
                } else vv
            }
            val hdr = linkedMapOf(
                "User-Agent" to uaEmbed,
                "Accept" to "text/plain, */*; q=0.01",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to org,
                "Referer" to v,
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                "Sec-Ch-Ua-Mobile" to "?1",
                "Sec-Ch-Ua-Platform" to "\"Android\""
            )
            val r: NiceResponse? = try {
                app.post(url, headers = hdr, data = form, cookies = page.cookies, timeout = 15L)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("KN hata ${e.javaClass.simpleName} ${url.take(70)}")
                null
            }
            val raw = r?.text.orEmpty().replace(Regex("[^0-9a-f]"), "")
            log("KN kod=${r?.code} try=$attempt len=${raw.length} k=${kn.k.take(6)} p=$p ${url.take(80)}")
            if (raw.isEmpty()) {
                if (attempt == 1) Thread.sleep(600)
                continue
            }
            val s = Decoder.knC(raw, kn.n, p, kn.seed, kn.rounds)
            val nl = s.indexOf('\n')
            val u = (if (nl < 0) s else s.substring(0, nl)).trim()
            val tracks = if (nl >= 0) s.substring(nl + 1) else ""
            log("KN çözüldü ${u.take(200)} tracks=${tracks.length}")
            if (u.startsWith("http", true)) return Pair(u, tracks)
        }
        return null
    }

    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String, depth: Int = 0, altId: String? = null): EmbedResult? {
        val variants = if (depth > 0) listOf(embedUrl) else embedVariants(embedUrl)
        val wwwRoot = "https://www." + siteBase().substringAfter("://").removePrefix("www.").trimEnd('/') + "/"
        var cfTried = false // Cloudflare çözücüyü bu çağrıda en çok bir kez dene (gereksiz bekleme olmasın)

        for (v in variants) {
            // Cross-site (.mobi) isteklerde tarayıcı yalnızca kök adresi Referer olarak gönderir
            val refs = when {
                depth > 0 -> listOf(pageUrl)
                isMobi(v) -> listOf(wwwRoot)
                else -> listOf(wwwOf(pageUrl), wwwRoot).distinct()
            }
            for (rf in refs) {
                var r: NiceResponse? = try {
                    app.get(v, headers = embedHeaders(rf, v), timeout = 10L)
                } catch (e: Exception) {
                    log("EM hata ${e.javaClass.simpleName} ${v.take(60)}")
                    null
                }
                if (isCfChallenge(r) && !cfTried) {
                    cfTried = true
                    r = try {
                        cfGet(v, embedHeaders(rf, v), 20L, false)
                    } catch (e: Exception) {
                        r
                    }
                }
                log("EM kod=${r?.code} ref=${rf.take(40)} ${v.take(70)}")
                if (r != null && r.code != 200) {
                    log("EM gövde srv=${r.headers["server"]} mit=${r.headers["cf-mitigated"]} ${r.text.take(80).replace("\n", " ")}")
                }
                if (r == null || r.code != 200) continue
                val html = r.text
                if (html.isEmpty()) continue

                val blobs = Decoder.collectBlobs(html)
                val c = Decoder.candFrom(blobs)
                // Sayfa / video kimliği: gerçek akış adresi bu kimliği taşır, yem adresler taşımaz
                val idHints = listOfNotNull(
                    Regex("""rapidrame_id=([A-Za-z0-9]+)""").find(v)?.groupValues?.get(1),
                    Regex("""/(?:rplayer|embed)/([A-Za-z0-9]+)""").find(v)?.groupValues?.get(1)
                ).distinct()
                // Sayfadaki şifreli akış adresi (fn(["parça",...]) + anahtar + işlem dizisi)
                val hostName = v.substringAfter("://").substringBefore("/").substringBefore("?")
                // Close + yeni Rapid: sabitleri sayfadaki fonksiyondan okuyan genel çözücü
                val joinedSp = html + "\n" + blobs.drop(1).joinToString("\n")
                val spList = Decoder.spUrls(joinedSp, html, hostName) { log(it.take(900)) }
                // Eski şema (anahtar + işlem dizisi)
                val nsList = Decoder.nsUrls(blobs, idHints) { log(it.take(900)) }
                fun hit(u: String) = idHints.any { h -> u.contains(h, true) }
                val spMatch = spList.filter { hit(it) }
                val nsMatch = nsList.filter { hit(it) }
                val dec: List<String> = when {
                    spMatch.isNotEmpty() -> spMatch
                    nsMatch.isNotEmpty() -> nsMatch
                    spList.isNotEmpty() -> spList
                    else -> nsList
                }
                val decOk = dec.any { hit(it) }
                // Gerçek adres: sayfanın kendi POST .../kn/ isteği (yem adresler yanlış film / örnek video veriyor)
                val kn = try { Decoder.knParse(html, blobs) } catch (e: Throwable) { null }
                log("KN parse=${kn != null} ${v.take(60)}")
                var knUrl: String? = null
                var knTracks = ""
                if (kn != null) {
                    val kr = try { knFetch(kn, v, r) } catch (e: java.util.concurrent.CancellationException) { throw e } catch (e: Exception) { null }
                    if (kr != null) {
                        val oVal = Regex("""[?&]o=(\d+)""").find(dec.firstOrNull().orEmpty())?.groupValues?.get(1)
                        var u0 = kr.first
                        if (oVal != null && !Regex("""[?&]o=""").containsMatchIn(u0)) u0 += (if (u0.contains("?")) "&o=" else "?o=") + oVal
                        knUrl = u0
                        knTracks = kr.second
                    }
                }
                val subs = ArrayList<SubItem>()
                val seenS = HashSet<String>()
                blobs.forEach { b ->
                    Decoder.extractSubs(b, originOf(v)).forEach { s -> if (seenS.add(s.url)) subs.add(s) }
                }
                if (knTracks.isNotBlank()) {
                    Decoder.extractSubs(knTracks, originOf(v)).forEach { s -> if (seenS.add(s.url)) subs.add(s) }
                }
                val allUrls = LinkedHashSet<String>()
                val allStrict = LinkedHashSet<String>()
                val decGood = dec.filterNot { it.contains("videosample", true) }
                val decUse: List<String> = when {
                    knUrl != null -> listOf(knUrl)
                    decOk -> dec.filter { hit(it) }
                    else -> decGood
                }
                if (decUse.isNotEmpty()) {
                    // Çözülen adres varsa yem adresler (JSON-LD contentUrl vb.) öncelik almasın
                    allUrls.addAll(decUse)
                    allStrict.addAll(decUse)
                } else {
                    // Close vb.: JSON-LD "contentUrl" doğrudan video adresi olabilir
                    val ld = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                    allStrict.addAll(c.strict)
                    if (ld != null && ld.startsWith("http", true)) {
                        allUrls.add(ld)
                        allStrict.add(ld)
                    }
                    allUrls.addAll(c.urls)
                }

                // Altyazı anahtarları (/srt/<no>/<kod>_...): sayfa metinlerinden + akış adreslerinden
                val keySet = LinkedHashSet<Pair<String, String>>()
                keySet.addAll(Decoder.keysFrom(blobs + listOf(v), false))
                keySet.addAll(Decoder.keysFrom(allUrls.toList(), true))

                log("EM len=${html.length} blob=${blobs.size} url=${allUrls.size} dec=${dec.size} kn=${knUrl != null} decOk=$decOk hints=$idHints iframe=${Jsoup.parse(html).select("iframe").size} ${v.take(60)}")
                if ((dec.isEmpty() || !decOk || knUrl == null) && depth == 0) {
                    // Şifre çözülemedi / kimlik eşleşmedi: yapıyı incelemek için sayfayı logcat'e yaz
                    log("EM ilk150: " + html.take(150).replace("\n", " "))
                    dumpLog(if (isMobi(v)) "close" else "rapid", html)
                }
                if (allUrls.isNotEmpty()) return EmbedResult(allUrls.toList(), subs, allStrict, v, keySet.toList())

                val inner = Jsoup.parse(html).selectFirst("iframe[data-src], iframe[src]")
                if (inner != null && depth < 1) {
                    val raw = inner.attr("data-src").ifBlank { inner.attr("src") }
                    if (raw.startsWith("http") || raw.startsWith("//")) {
                        val iu = if (raw.startsWith("//")) "https:$raw" else raw
                        val rr = resolveEmbed(iu, v, depth + 1)
                        if (rr != null && rr.urls.isNotEmpty()) {
                            // dış sayfadaki altyazılar da korunsun
                            val mergedSubs = ArrayList<SubItem>(rr.subs)
                            val seenM = HashSet<String>(rr.subs.map { it.url })
                            subs.forEach { s -> if (seenM.add(s.url)) mergedSubs.add(s) }
                            val mergedKeys = LinkedHashSet<Pair<String, String>>(rr.keys)
                            mergedKeys.addAll(keySet)
                            return rr.copy(subs = mergedSubs, keys = mergedKeys.toList())
                        }
                    }
                }
            }
        }

        // Son çare: film sayfasını WebView'de aç (kısa süre; çoğu zaman sonuç vermiyor)
        if (depth == 0) {
            try {
                val u = wvCapture(pageUrl, altId, 15_000L)
                log("EM WV ${u?.take(120)}")
                if (u != null) {
                    val target = variants.firstOrNull() ?: embedUrl
                    return EmbedResult(listOf(u), emptyList(), setOf(u), target, Decoder.keysFrom(listOf(u), true))
                }
            } catch (e: Exception) {
                log("EM WV hata ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    // ---------------- Doğrulama ----------------
    // Rapid ve Close CDN'leri Referer/Origin olarak oynatıcı alt alan adını bekliyor
    // (rapid.<site> ve closeload.<site>). Adresi doğrudan açınca 403 vermesi bu yüzden.
    private fun knownOrigins(streamUrl: String): List<String> {
        val host = originOf(streamUrl).substringAfter("://").lowercase()
        val site = siteBase().substringAfter("://").trimEnd('/').removePrefix("www.")
        // Tarayıcıda görülen gerçek değer: Origin/Referer = https://hdfilmcehennemi.mobi
        val mobi = "https://hdfilmcehennemi.mobi"
        return when {
            host.contains("cdnimages") -> listOf(mobi, "https://closeload.$site")
            host.contains("rapidrame") -> listOf("https://www.$site", "https://www.rapidrame.com", "https://rapidrame.com", mobi, "https://rapid.$site")
            else -> emptyList()
        }
    }

    private fun streamHeaderSets(embedUrl: String, streamUrl: String): List<Map<String, String>> {
        fun hs(ref: String?, origin: String?): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            m["User-Agent"] = uaEmbed
            if (ref != null) m["Referer"] = ref
            if (origin != null) m["Origin"] = origin
            return m
        }
        val out = ArrayList<Map<String, String>>()
        knownOrigins(streamUrl).forEach { out.add(hs("$it/", it)) }
        val org = originOf(embedUrl)
        if (org.isNotBlank()) out.add(hs("$org/", org))
        out.add(hs(embedUrl, null))
        out.add(hs(siteBase() + "/", siteBase()))
        val own = originOf(streamUrl)
        if (own.isNotBlank()) out.add(hs("$own/", own))
        out.add(hs(null, null))
        return out.distinct()
    }

    private fun joinUrl(base: String, ref: String): String = when {
        ref.startsWith("http", true) -> ref
        ref.startsWith("//") -> "https:$ref"
        ref.startsWith("/") -> originOf(base) + ref
        else -> base.substringBefore("?").substringBeforeLast('/') + "/" + ref
    }

    // Ana liste -> alt liste -> ilk parça zincirini aynı header'larla dener (403 genelde parçalarda çıkıyor)
    private suspend fun deepCheck(startUrl: String, startText: String, h: Map<String, String>): Boolean {
        var curUrl = startUrl
        var curText = startText
        for (i in 0..2) {
            val line = curText.lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            val next = joinUrl(curUrl, line)
            if (curText.contains("#EXTINF")) {
                val r: NiceResponse? = try {
                    app.get(next, headers = h + mapOf("Range" to "bytes=0-2047"), timeout = 8L)
                } catch (e: Exception) {
                    null
                }
                return r != null && (r.code == 200 || r.code == 206)
            }
            val r: NiceResponse? = try {
                app.get(next, headers = h, timeout = 8L)
            } catch (e: Exception) {
                null
            }
            if (r == null || r.code != 200) return false
            val t = r.text
            if (!t.trimStart().startsWith("#EXTM3U")) return false
            curUrl = next
            curText = t
        }
        return false
    }

    private suspend fun verifyUrl(u: String, sets: List<Map<String, String>>): Verified {
        if (Regex("""\.mp4(\?|$)""", IC).containsMatchIn(u)) return Verified(true, sets[0])
        var firstOk: Verified? = null
        for (h in sets) {
            val r: NiceResponse? = try {
                app.get(u, headers = h, timeout = 8L)
            } catch (e: Exception) {
                null
            }
            if (r != null && (r.code == 200 || r.code == 206) && r.text.trimStart().startsWith("#EXTM3U")) {
                if (deepCheck(u, r.text, h)) return Verified(true, h, r.text)
                if (firstOk == null) firstOk = Verified(true, h, r.text)
            }
        }
        return firstOk ?: Verified(false, sets[0])
    }

    private fun rank(u: String): Int = when {
        u.contains("master", true) -> 0
        u.contains(".m3u8", true) -> 1
        else -> 2
    }

    // /srt/<no>/<kod>_<ad>.vtt/ adresini doğrula: (kod, doğrulandı mı)
    private suspend fun checkSrt(su: String, pageUrl: String): Pair<Int, Boolean> {
        var chkCode = -1
        for (rf in listOf(pageUrl, siteBase() + "/")) {
            val chk: NiceResponse? = try {
                app.get(su, headers = plainHeaders(rf), timeout = 6L)
            } catch (e: Exception) {
                null
            }
            if (chk != null) {
                chkCode = chk.code
                if (chk.code == 200 && chk.text.take(300).contains("WEBVTT")) return Pair(200, true)
            }
        }
        return Pair(chkCode, false)
    }

    // ---------------- Bağlantıları ver ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Artık veri doğrudan sitedeki sayfa adresi (eski biçim de desteklenir)
        val pageUrl = if (data.startsWith("http", true)) {
            toSite(data)
        } else {
            val link = LinkData.decode(data) ?: return false
            findPage(link) ?: return false
        }
        val html0 = siteGet(pathOf(pageUrl))?.text
        if (html0 == null) {
            log("sayfa alınamadı $pageUrl")
            return false
        }
        val domHtml: String? = try { wvPageDom(pageUrl) } catch (e: java.util.concurrent.CancellationException) { throw e } catch (e: Exception) { null }
        val altsX = parseAlts(html0)
        val altsD = if (domHtml != null) parseAlts(domHtml) else emptyList()
        val ifrX = pageIframe(html0)
        val ifrD = if (domHtml != null) pageIframe(domHtml) else ""
        if (domHtml != null && (altsX.map { it.id } != altsD.map { it.id } || (ifrD.isNotBlank() && ifrX != ifrD))) {
            log("KAYNAK FARKI xhr=${altsX.map { it.id }} ifr=${ifrX.take(90)} | dom=${altsD.map { it.id }} ifr=${ifrD.take(90)}")
        }
        // Gerçek açılış görünümü tercih edilir; yoksa XHR ile alınan sayfa
        val useDom = domHtml != null && altsD.isNotEmpty()
        val altsUse = if (useDom) altsD else altsX
        val ifr = if (useDom && ifrD.isNotBlank()) ifrD else ifrX
        val html: String = if (useDom) domHtml!! else html0

        // Her oynatıcıdan yalnızca bir tane: Close bir, Rapid bir (aktif olan öncelikli)
        fun kindOf(n: String) = when {
            n.contains("rapid", true) -> "rapid"
            n.contains("close", true) -> "close"
            else -> n.lowercase().trim()
        }
        val picked = LinkedHashMap<String, Alt>()
        for (a in altsUse) {
            val k = a.lang.ifBlank { "dual" } + "|" + kindOf(a.name)
            val cur = picked[k]
            if (cur == null || (!cur.active && a.active)) picked[k] = a
        }
        // Sayfanın varsayılan (aktif) oynatıcısı iframe'de hazır gelir: AJAX yerine onu kullan
        val all = picked.values.map { a ->
            Player(a.name, a.lang.ifBlank { "dual" }, a.id, if (a.active && ifr.isNotBlank()) ifr else null)
        }

        // Yanlış film koruması: film yılı (adres / sayfa) ile yayın dosya adındaki yıl 1'den fazla farklıysa atla
        val pPath = pathOf(pageUrl)
        val isSeriesPage = pPath.startsWith("/dizi/") || pPath.contains("/sezon-")
        val filmYears = HashSet<Int>()
        if (!isSeriesPage) {
            Regex("""-((?:19|20)\d{2})(?=[-/]|$)""").findAll(pPath).forEach { m -> m.groupValues[1].toIntOrNull()?.let { filmYears.add(it) } }
            try {
                Jsoup.parse(html).selectFirst("[class*=year]")?.text()
                    ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value }?.toIntOrNull()?.let { filmYears.add(it) }
            } catch (e: Throwable) {
            }
        }
        fun yearBad(u: String): Boolean {
            if (filmYears.isEmpty()) return false
            val sy = Regex("""-((?:19|20)\d{2})-""").find(u.substringBefore("?"))?.groupValues?.get(1)?.toIntOrNull() ?: return false
            return filmYears.none { abs(it - sy) <= 1 }
        }
        try {
            val dd = Jsoup.parse(html)
            log("ALT baslik=${dd.title().take(80)} ifr=${ifr.take(140)}")
            all.forEach { log("ALT id=${it.id} ad=${it.name} dil=${it.lang}") }
            dumpLog("alts", dd.select("div.alternative-links, .video-container").outerHtml().take(6000))
            dd.select("script:not([src])").filter { sc ->
                val d = sc.data()
                d.contains("data-video") || d.contains("alternative") || d.contains("/video/")
            }.take(2).forEachIndexed { i, sc -> dumpLog("scr$i", sc.data().take(3000)) }
        } catch (e: Throwable) {
        }
        val dual = all.filter { it.lang == "dual" }
        val subbed = all.filter { it.lang != "dual" }
        log("sayfa len=${html.length} dual=${dual.size} altyazili=${subbed.size} iframe=${ifr.isNotBlank()} ${pageUrl.take(80)}")

        val provider = this.name
        val seenEmb = Collections.synchronizedSet(HashSet<String>())
        val seenUrl = Collections.synchronizedSet(HashSet<String>())
        val seenSub = Collections.synchronizedSet(HashSet<String>())
        val embList = Collections.synchronizedList(ArrayList<String>())
        val labelCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val labelOrd = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val gotExt = java.util.concurrent.atomic.AtomicBoolean(false)
        val trFound = java.util.concurrent.atomic.AtomicBoolean(false)
        val forcedRe = Regex("forced|zorunlu", IC)

        fun pushSub(label: String, url: String) {
            if (seenSub.add(url)) {
                val n = labelCount.merge(label, 1) { a, b -> a + b } ?: 1
                val shown = if (n <= 1) label else "$label $n"
                subtitleCallback.invoke(SubtitleFile(shown, url))
                if (label == "Türkçe") trFound.set(true)
            }
        }

        fun pushItem(s: SubItem) {
            if (!(s.lang in subLangs || s.lang == "xx")) return
            if (forcedRe.containsMatchIn(s.label + " " + s.url)) return
            val nameS = if (s.lang == "tr") "Türkçe" else if (s.lang == "en") "English" else s.label.ifBlank { "Altyazı" }
            pushSub(nameS, s.url)
        }

        // Bölüm sayfasının kendisinde de altyazı bağlantısı olabilir (.vtt / .srt / /vtt/ / /srt/)
        try {
            Decoder.extractSubs(html, siteBase()).forEach { pushItem(it) }
        } catch (e: Exception) {
        }

        // withVideo=false: yalnızca altyazı topla (akış ekleme)
        suspend fun runJobs(list: List<Player>, withVideo: Boolean) {
            list.take(maxPlayers).amap { job ->
                try {
                    val embedUrl = job.embed ?: run {
                        val rr = siteGet(
                            "/video/${job.id}/",
                            mapOf(
                                "Accept" to "application/json, text/plain, */*",
                                "X-Requested-With" to "fetch",
                                "Referer" to pageUrl
                            ),
                            pathOf(pageUrl)
                        )
                        log("VID id=${job.id} kod=${rr?.code} -> " + rr?.text.orEmpty().take(300).replace(Regex("\\s+"), " "))
                        iframeFromAjax(rr?.text.orEmpty()).ifBlank { if (job === list[0]) ifr else "" }
                    }
                    if (embedUrl.isBlank() || !seenEmb.add(embedUrl)) return@amap
                    embList.add(embedUrl)
                    log("P ${job.name}/${job.lang} video=$withVideo ${embedUrl.take(80)}")

                    val res: EmbedResult? = resolveEmbed(embedUrl, pageUrl, 0, job.id)
                    if (res == null) {
                        log("P ${job.name} çözülemedi")
                        // Yedek: hazır çıkarıcıları dene (ör. Close)
                        if (withVideo) {
                            try {
                                if (loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)) gotExt.set(true)
                            } catch (e: Exception) {
                            }
                        }
                        return@amap
                    }
                    log("P ${job.name} akis=${res.urls.size} altyazi=${res.subs.size} anahtar=${res.keys.size}")
                    if (res.urls.isNotEmpty() && res.urls.all { yearBad(it) }) {
                        log("YIL UYUMSUZ ${job.name} film=$filmYears yayin=${res.urls.firstOrNull()?.take(120)}")
                        return@amap
                    }

                    // Altyazılar (oynatıcı sayfasından; .mobi /vtt/ ve /srt/ bağlantıları dahil)
                    res.subs.forEach { pushItem(it) }

                    // Sayfada Türkçe altyazı bulunamadıysa /srt/<no>/<kod>_<ad>.vtt/ adreslerini türet
                    // (Turkish, Turkish2, Turkish1 ... ve English varyantları)
                    if (res.subs.none { it.lang == "tr" }) {
                        for ((no, code) in res.keys.take(3)) {
                            val want = ArrayList<Triple<String, String, Boolean>>()
                            trSrtNames.forEach { want.add(Triple(it, "Türkçe", true)) }
                            if ("en" in subLangs) enSrtNames.forEach { want.add(Triple(it, "English", false)) }
                            val checked = want.amap { (fn, lbl, isTr) ->
                                val su = siteBase() + "/srt/" + no + "/" + code + "_" + fn + ".vtt/"
                                if (seenSub.contains(su)) return@amap null
                                val (cc, ok) = checkSrt(su, pageUrl)
                                // Doğrulanamayan (engellenen / ağ hatası) ilk Türkçe adresi de ekle; 404 ise ekleme
                                val soft = fn == "Turkish" && (cc == 403 || cc == 503 || cc == -1)
                                if (ok || soft) Triple(lbl, su, isTr) else null
                            }.filterNotNull()
                            for ((lbl, su, _) in checked) pushSub(lbl, su)
                            if (checked.isNotEmpty()) break
                        }
                    }

                    if (!withVideo) return@amap

                    // Akışlar (çözülen / strict adresler önce)
                    val cands = res.urls
                        .filterNot { yearBad(it) }
                        .sortedWith(compareBy<String>({ if (res.strict.contains(it)) 0 else 1 }, { rank(it) }))
                        .take(6)
                    val verified: List<Pair<String, Verified>> = cands.amap { u -> Pair(u, verifyUrl(u, streamHeaderSets(res.base, u))) }

                    // Akışın içine gömülü (HLS) altyazı kanalları
                    verified.forEach { (mu, mv) ->
                        if (mv.ok) {
                            embeddedSubs(mv.text, mu).forEach { es -> pushSub(es.label, es.url) }
                        }
                    }
                    log("P ${job.name} aday=${cands.size} dogrulanan=${verified.count { it.second.ok }}")
                    var okList: List<Pair<String, Verified>> = verified.filter { it.second.ok }
                    if (okList.isEmpty()) {
                        // Doğrulanamadı: yine de en olası adresi ver (strict / çözülmüş adres öncelikli)
                        val st = verified.filter { res.strict.contains(it.first) }
                        val pick = if (st.isNotEmpty()) st.take(1) else verified.take(1)
                        okList = pick.map { Pair(it.first, Verified(false, streamHeaderSets(res.base, it.first)[0])) }
                        log("P ${job.name} dogrulanamadi, yine de veriliyor: ${pick.firstOrNull()?.first?.take(90)}")
                    }

                    val nm = when {
                        job.name.contains("rapid", true) -> "Rapid"
                        job.name.contains("close", true) -> "Close"
                        else -> job.name
                    }
                    val lg = if (job.lang == "dual") "DUAL" else if (job.lang.isNotBlank()) "Altyazılı" else "TR"

                    okList.forEachIndexed { i, pair: Pair<String, Verified> ->
                        val u = pair.first
                        val v = pair.second
                        if (!seenUrl.add(u)) return@forEachIndexed
                        val base = "$lg $nm"
                        val ord = if (i == 0) labelOrd.merge(base, 1) { a, b -> a + b } ?: 1 else 1
                        val label = (if (ord > 1) "$base $ord" else base) + (if (i > 0) " #${i + 1}" else "")
                        val linkType = when {
                            u.contains(".mp4", true) && !u.contains("master", true) -> ExtractorLinkType.VIDEO
                            u.contains(".m3u8", true) || u.contains("master", true) || u.contains("/hls", true) -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        }
                        callback.invoke(
                            newExtractorLink(
                                source = provider,
                                name = "$provider - $label",
                                url = u,
                                type = linkType
                            ) {
                                this.referer = v.headers["Referer"] ?: (siteBase() + "/")
                                this.quality = Qualities.Unknown.value
                                this.headers = v.headers
                            }
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 1) Önce dual (Türkçe dublaj) oynatıcılar; hiç yoksa altyazılı olanlar
        val first = if (showSubbed) all else dual
        val firstList = if (first.isNotEmpty()) first else subbed
        runJobs(firstList, true)

        // 2) Hiç akış çıkmadıysa kalan altyazılı oynatıcıları dene (yalnızca altyazılı yayınlanan filmler için)
        val rest = subbed.filter { s -> firstList.none { it.id == s.id } }
        if (seenUrl.isEmpty() && !gotExt.get()) runJobs(rest, true)

        // 3) Hâlâ yoksa sayfadaki doğrudan iframe
        if (seenUrl.isEmpty() && !gotExt.get() && ifr.isNotBlank()) {
            runJobs(listOf(Player("Player", "dual", null, ifr)), true)
        }

        // 4) Türkçe altyazı hiç bulunamadıysa altyazılı oynatıcılardan yalnızca altyazıyı topla
        if (!trFound.get() && rest.isNotEmpty()) runJobs(rest, false)

        // 5) Hâlâ Türkçe altyazı yoksa: oynatıcıyı WebView'de aç, .vtt / /srt/ isteğini yakala
        if (!trFound.get()) {
            for (eu in embList.toList().take(1)) {
                try {
                    val wv = app.get(
                        eu,
                        referer = siteBase() + "/",
                        interceptor = WebViewResolver(Regex("""\.vtt|/srt/\d|/vtt/\d"""), timeout = 15_000L)
                    )
                    val u = wv.url
                    if (Regex("""\.vtt|/srt/\d|/vtt/\d""", IC).containsMatchIn(u)) {
                        val lg = Decoder.subLang(u)
                        if (lg == "tr") pushSub("Türkçe", u)
                        else if (lg == "en") pushSub("English", u)
                    }
                } catch (e: Exception) {
                }
                if (trFound.get()) break
            }
        }

        log("bitti akis=${seenUrl.size} ext=${gotExt.get()} altyazi=${seenSub.size} tr=${trFound.get()}")
        return seenUrl.isNotEmpty() || gotExt.get()
    }

    // HLS ana listesindeki #EXT-X-MEDIA:TYPE=SUBTITLES kanalları (doğrudan .vtt/.srt olanlar)
    private fun embeddedSubs(master: String, base: String): List<SubItem> {
        val out = ArrayList<SubItem>()
        if (!master.contains("TYPE=SUBTITLES", true)) return out
        val dir = base.substringBefore("?").substringBeforeLast('/') + "/"
        for (line in master.lines()) {
            if (!line.startsWith("#EXT-X-MEDIA", true) || !line.contains("TYPE=SUBTITLES", true)) continue
            val uri = Regex("""URI="([^"]+)"""", IC).find(line)?.groupValues?.get(1) ?: continue
            if (!Regex("""\.(?:vtt|srt)(?:\?|#|/|$)""", IC).containsMatchIn(uri)) continue
            val nm = Regex("""NAME="([^"]*)"""", IC).find(line)?.groupValues?.get(1).orEmpty()
            val lg = Regex("""LANGUAGE="([^"]*)"""", IC).find(line)?.groupValues?.get(1).orEmpty().lowercase()
            if (Regex("forced|zorunlu", IC).containsMatchIn("$nm $uri")) continue
            val code = when {
                lg.startsWith("tr") || Regex("turk|türk", IC).containsMatchIn(nm) -> "tr"
                lg.startsWith("en") || Regex("english|ingiliz", IC).containsMatchIn(nm) -> "en"
                else -> lg.take(2)
            }
            if (code !in subLangs) continue
            val full = if (uri.startsWith("http", true)) uri else if (uri.startsWith("//")) "https:$uri" else dir + uri
            out.add(SubItem(full, if (code == "tr") "Türkçe" else "English", code))
        }
        return out
    }
}

// ================= Modeller =================
data class LinkData(
    val tmdbId: Int,
    val isTv: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    val imdb: String? = null,
    val year: Int? = null,
    val titles: List<String> = emptyList()
) {
    fun encode(): String {
        val j = JSONObject()
        j.put("tmdbId", tmdbId)
        j.put("isTv", isTv)
        val s = season
        val e = episode
        val i = imdb
        val y = year
        if (s != null) j.put("season", s)
        if (e != null) j.put("episode", e)
        if (i != null) j.put("imdb", i)
        if (y != null) j.put("year", y)
        val arr = JSONArray()
        titles.forEach { arr.put(it) }
        j.put("titles", arr)
        return j.toString()
    }

    companion object {
        fun decode(text: String): LinkData? {
            return try {
                val j = JSONObject(text)
                val list = ArrayList<String>()
                val arr = j.optJSONArray("titles")
                if (arr != null) {
                    for (k in 0 until arr.length()) list.add(arr.optString(k))
                }
                LinkData(
                    tmdbId = j.optInt("tmdbId"),
                    isTv = j.optBoolean("isTv"),
                    season = if (j.has("season")) j.optInt("season") else null,
                    episode = if (j.has("episode")) j.optInt("episode") else null,
                    imdb = if (j.has("imdb")) j.optString("imdb") else null,
                    year = if (j.has("year")) j.optInt("year") else null,
                    titles = list
                )
            } catch (ex: Exception) {
                null
            }
        }
    }
}

data class TmdbList(val results: List<TmdbItem>? = null)

data class TmdbItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val media_type: String? = null,
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val original_name: String? = null,
    val original_title: String? = null,
    val overview: String? = null
)

data class TmdbGenre(val name: String? = null)
data class TmdbExternalIds(val imdb_id: String? = null)

data class TmdbCast(
    val name: String? = null,
    val character: String? = null,
    val profile_path: String? = null
)

data class TmdbCredits(val cast: List<TmdbCast>? = null)

data class TmdbVideo(
    val site: String? = null,
    val type: String? = null,
    val key: String? = null
)

data class TmdbVideos(val results: List<TmdbVideo>? = null)

data class TmdbSeasonInfo(val season_number: Int? = null)

data class TmdbDetail(
    val id: Int? = null,
    val title: String? = null,
    val original_title: String? = null,
    val name: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val imdb_id: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre>? = null,
    val external_ids: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    val seasons: List<TmdbSeasonInfo>? = null
)

data class TmdbEpisode(
    val episode_number: Int? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val air_date: String? = null,
    val runtime: Int? = null
)

data class TmdbSeason(
    val season_number: Int? = null,
    val episodes: List<TmdbEpisode>? = null
)

data class SubItem(val url: String, val label: String, val lang: String)
data class Cand(val urls: List<String>, val strict: Set<String>)

// kn şeması: oynatıcı sayfası gerçek adresi POST .../kn/ ile veriyor (sayfadaki çözücüler yem adres üretiyor)
class KnInfo(
    val path: String,
    val nName: String, val jName: String,
    val n: String, val j: String,
    val fields: List<Pair<String, String>>,
    val k: String, val seed: Long, val rounds: Int
)

// ================= Decoder =================
object Decoder {
    private val IC = RegexOption.IGNORE_CASE
    private const val B64CH = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    // ---------- Metin yardımcıları ----------
    private fun fold(s: String): String {
        val t = s.lowercase()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    fun norm(s: String): String = fold(s).replace(Regex("[^a-z0-9]+"), "")

    fun slugify(s: String): String =
        fold(s).replace(Regex("[^a-z0-9]+"), "-").trim('-')

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

    // ---------- p.a.c.k.e.r çözücü (tek ve çift tırnaklı) ----------
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

    // Hoşgörülü base64 çözücü (URL-safe ve bozuk karakterleri atar), bayt dizisini Latin-1 metin olarak verir
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

    // Bilinen dönüşümlerin tüm kombinasyonları denenir (ters çevir / rot13 / base64 / konuma bağlı kaydırma)
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

    // İç içe base64 (base64 -> ters -> base64 ...): bağlantı çıkana kadar en çok 3 kat
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

    // ---------- Yeni şema (Rapid / Close): fn(["parça",...]) + anahtar + işlem dizisi ----------
    // Sayfadaki paketlenmiş (p.a.c.k.e.r) script şunu içeriyor:
    //   function f(a){var s=a.join('');var KEY="...";var OPS="...";  ... }  ve  var x=f(["...","...",...]);
    // Adımlar (her sayfada aynı, yalnızca KEY / OPS değişiyor; "length>4096" gibi koşullar yem):
    //   1) KEY'den (a,b) hesapla: a=(a*31+c)%251, b=(b^(c+i))&255  -> başlangıç durumu, adım, tohum
    //   2) OPS'u sondan başa uygula: 'b' = base64 çöz, 'v' = ters çevir, diğer harf = Sezar kaydırma (yalnızca harfler)
    //   3) Tohumdan (LCG: h=(h*75+74)%65537) karıştırma dizisi üret, elemanları takas et
    //   4) Akış XOR: x=(x+step)%256; çıktı=c^x; x=(x+c)%256
    private val nsFnRe = Regex("""function\s+(\w+)\s*\(\s*\w+\s*\)\s*\{""")
    private val nsStrRe = Regex("""var\s+\w+\s*=\s*"([^"]*)"""")
    private val nsCallRe = Regex("""(\w+)\(\s*\[((?:\s*"[^"]*"\s*,?)+)\s*\]\s*\)""")
    private val nsPartRe = Regex(""""([^"]*)"""")

    private fun nsRun(parts: List<String>, key: String, ops: String): String {
        var s = parts.joinToString("")
        var a = 0
        var b = 0
        for (i in key.indices) {
            val c = key[i].code
            a = (a * 31 + c) % 251
            b = (b xor (c + i)) and 255
        }
        val st0 = (a + b) % 256
        val step = (a % 13) + 3
        var h = ((a * 256 + b) % 65521) + 1

        for (i in ops.length - 1 downTo 0) {
            val ch = ops[i]
            when (ch) {
                'b' -> s = b64d(s)
                'v' -> s = s.reversed()
                else -> {
                    val r = (26 - ((ch.code - 64) % 26)) % 26
                    val sb = StringBuilder(s.length)
                    for (x in s) {
                        if (x in 'A'..'Z') sb.append((((x.code - 65 + r) % 26) + 65).toChar())
                        else if (x in 'a'..'z') sb.append((((x.code - 97 + r) % 26) + 97).toChar())
                        else sb.append(x)
                    }
                    s = sb.toString()
                }
            }
        }

        val n = s.length
        if (n == 0) return ""
        val k = IntArray(n)
        for (i in n - 1 downTo 1) {
            h = (h * 75 + 74) % 65537
            k[i] = h % (i + 1)
        }
        val arr = s.toCharArray()
        for (i in 1 until n) {
            val j = k[i]
            val t = arr[i]
            arr[i] = arr[j]
            arr[j] = t
        }

        var x = st0
        val out = StringBuilder(n)
        for (ch in arr) {
            val c = ch.code
            x = (x + step) % 256
            out.append((c xor x).toChar())
            x = (x + c) % 256
        }
        return out.toString()
    }

    private fun mostlyPrintable(t: String): Boolean {
        if (t.length < 8) return false
        var ok = 0
        for (ch in t) if (ch.code in 32..126 || ch == '\n' || ch == '\r' || ch == '\t') ok++
        return ok * 100 / t.length >= 95
    }

    // Tüm fn([...]) çağrılarını çözer: (return mu?, çözülen metin).
    // Aynı isimli birden fazla fonksiyon tanımı olabilir; hepsi denenir.
    fun nsDecodeDetailed(src: String, dbg: (String) -> Unit = {}): List<Pair<Boolean, String>> {
        if (!src.contains(".join(") || !src.contains("charCodeAt")) {
            dbg("NS join/charCodeAt yok")
            return emptyList()
        }
        val defs = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (m in nsFnRe.findAll(src)) {
            val st = m.range.last + 1
            val body = src.substring(st, minOf(src.length, st + 1500))
            if (!body.contains(".join(") || !body.contains("charCodeAt")) continue
            val strs = nsStrRe.findAll(body).map { it.groupValues[1] }.take(2).toList()
            if (strs.size == 2) defs.getOrPut(m.groupValues[1]) { ArrayList() }.add(Pair(strs[0], strs[1]))
        }
        dbg("NS defs=" + defs.map { it.key + ":" + it.value.size })
        val out = ArrayList<Pair<Boolean, String>>()
        for (m in nsCallRe.findAll(src)) {
            val dl = defs[m.groupValues[1]] ?: continue
            val pre = src.substring(maxOf(0, m.range.first - 16), m.range.first)
            val isRet = Regex("""return\s*$""").containsMatchIn(pre)
            val parts = nsPartRe.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            if (parts.isEmpty()) continue
            var got: String? = null
            loop@ for (d in dl) {
                for ((kk, oo) in listOf(d, Pair(d.second, d.first))) {
                    val r = try {
                        nsRun(parts, kk, oo)
                    } catch (e: Throwable) {
                        ""
                    }
                    if (mostlyPrintable(r)) {
                        got = r
                        if (r.contains("http", true)) break@loop
                    }
                }
            }
            dbg("NS call ${m.groupValues[1]} ret=$isRet parts=${parts.size} -> ${got?.take(200)}")
            if (got != null) out.add(Pair(isRet, got))
            if (out.size >= 20) break
        }
        return out
    }

    // Çözülen metinlerden akış adresleri. hints (sayfa / video kimliği) varsa onu taşıyan adres öne çıkar:
    // gerçek adres kimliği içerir, yem adresler (rastgele / sabit dosya adı) içermez.
    fun nsUrls(blobs: List<String>, hints: List<String> = emptyList(), dbg: (String) -> Unit = {}): List<String> {
        // Fonksiyon tanimlari paketlenmis scriptte, gercek cagri ise sayfanin duz metninde: hepsini birlestirip ara
        val joined = blobs.joinToString("\n")
        val dec = nsDecodeDetailed(joined, dbg)
        if (dec.isEmpty()) return emptyList()
        val all = ArrayList<Pair<Boolean, String>>() // (return mu, adres)
        for ((isRet, t) in dec) {
            for (m in urlRe.findAll(cleanBlob(t))) {
                val u = m.value.trimEnd(',', ';', '.', ']', ')')
                if (!badRe.containsMatchIn(u) && !junkUrl(u) && !subDirRe.containsMatchIn(u)) all.add(Pair(isRet, u))
            }
        }
        val hs = hints.filter { it.length >= 6 }
        val matched = all.filter { (_, u) -> hs.any { u.contains(it, true) } }.map { it.second }.distinct()
        if (matched.isNotEmpty()) return matched
        dbg("NS id eslesmedi hints=$hs urls=${all.map { it.second.take(100) }}")
        val rets = all.filter { it.first }.map { it.second }.distinct()
        return if (rets.isNotEmpty()) rets else all.map { it.second }.distinct()
    }

    // ---------- Genel "splice" ailesi (Close + yeni Rapid) ----------
    // Sayfadaki çözücü fonksiyon her sayfada farklı sabitlerle (37/53, 241/251, <<1/<<2, 97/89 ...) üretiliyor ve bazen
    // host adı + gizli data-xxx niteliği ile karışıyor. Sabitler fonksiyonun kendi gövdesinden okunur.
    // Çağrı biçimleri: fn(["a","b"])  ve  fn("a@b".split("@"))
    private class SpParams(
        val m1: Int, val base: Int, val m2: Int,
        val a: Int, val b: Int, val c: Int,
        val p0m: Int, val hcm: Int, val hca: Int, val fm: Int, val fmod: Int,
        val la: Int, val lb: Int, val lm: Int,
        val sm: Int, val atobCh: Char, val revCh: Char, val cb: Int,
        val oflag: Boolean, val app: List<Pair<String, String>>
    )

    private val spFnRe = Regex("""(?:function\s+(\w+)\s*\(\s*\w+\s*\)\s*\{|var\s+(\w+)\s*=\s*function\s*\(\s*\w+\s*\)\s*\{)""")
    private val spCallSplitRe = Regex("""(\w+)\(\s*"((?:[^"\\]|\\.)*)"\s*\.split\(\s*"([^"]*)"\s*\)\s*\)""")
    private val spCallArrRe = Regex("""(\w+)\(\s*\[((?:\s*"(?:[^"\\]|\\.)*"\s*,?)+)\s*\]\s*\)""")
    private val spStrRe = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val spP1 = Regex("""\w+=\w+\.length-2,\w+=\w+%(\d+),\w+=(\d+)\+\(\w+%(\d+)\)""")
    private val spP2 = Regex("""\w+=\(\w+\*(\d+)\+\w+\)%(\d+);\w+=\(\w+\+\(\(\w+<<(\d+)\)\^\w+\)\)&255""")
    private val spP3 = Regex("""\w+=\(\w+\*(\d+)\+\w+\)%256,\w+=\(\w+%(\d+)\)\+(\d+),\w+=\(\(\w+\*(\d+)\+\w+\)%(\d+)\)\+1""")
    private val spP4 = Regex("""=\(\w+\*(\d+)\+(\d+)\)%(\d+);\w+\[\w+\]=\w+%\(\w+\+1\)""")
    private val spP5 = Regex("""=\(\w+\*(\d+)\+\w+\)%256;\w+\+=String\.fromCharCode\(\w+\^\w+\)""")
    private val spAtobRe = Regex("""==='(.)'\)\{\w+=atob""")
    private val spRevRe = Regex("""==='(.)'\)\{\w+=\w+\.split\(''\)\.reverse""")
    private val spCbRe = Regex("""charCodeAt\(0\)-(\d+)\)%26""")
    private val spAppRe = Regex("""\w+\+=([^;]+);""")
    private val spCreateRe = Regex("""createElement\('(\w+)'\)""")
    private val spDataRe = Regex("""['"](data-[\w-]+)['"]""")

    private fun spParams(body: String): SpParams? {
        val bb = body.replace(wsRe, "").take(5000)
        val j = bb.indexOf(".join('')")
        val head = if (j > 0) bb.substring(0, j) else bb
        val m1 = spP1.find(bb) ?: return null
        val m2 = spP2.find(bb) ?: return null
        val m3 = spP3.find(bb) ?: return null
        val m4 = spP4.find(bb) ?: return null
        val m5 = spP5.find(bb) ?: return null
        val app = ArrayList<Pair<String, String>>()
        for (m in spAppRe.findAll(head)) {
            val e = m.groupValues[1]
            if (e.contains("hostname")) app.add(Pair("host", ""))
            else if (e.contains("tagName")) app.add(Pair("lit", (spCreateRe.find(e)?.groupValues?.get(1) ?: "div").uppercase()))
            else if (e.contains("data-")) app.add(Pair("attr", spDataRe.find(e)?.groupValues?.get(1) ?: ""))
        }
        return SpParams(
            m1.groupValues[1].toInt(), m1.groupValues[2].toInt(), m1.groupValues[3].toInt(),
            m2.groupValues[1].toInt(), m2.groupValues[2].toInt(), m2.groupValues[3].toInt(),
            m3.groupValues[1].toInt(), m3.groupValues[2].toInt(), m3.groupValues[3].toInt(), m3.groupValues[4].toInt(), m3.groupValues[5].toInt(),
            m4.groupValues[1].toInt(), m4.groupValues[2].toInt(), m4.groupValues[3].toInt(),
            m5.groupValues[1].toInt(),
            spAtobRe.find(bb)?.groupValues?.get(1)?.firstOrNull() ?: '7',
            spRevRe.find(bb)?.groupValues?.get(1)?.firstOrNull() ?: '3',
            spCbRe.find(bb)?.groupValues?.get(1)?.toIntOrNull() ?: 96,
            bb.contains("'?o='"),
            app
        )
    }

    private fun spRun(arr0: List<String>, host: String, html: String, P: SpParams): String {
        val arr = ArrayList(arr0)
        val f91 = arr.size - 2
        if (f91 < 1) return ""
        val oj = f91 % P.m1
        val b2 = P.base + (f91 % P.m2)
        if (b2 >= arr.size) return ""
        val ops = arr.removeAt(b2)
        if (oj >= arr.size) return ""
        val sb0 = StringBuilder(arr.removeAt(oj))
        for ((kind, v) in P.app) {
            when (kind) {
                "host" -> sb0.append(host)
                "lit" -> sb0.append(v)
                else -> {
                    val av = Regex("""\b""" + Regex.escape(v) + """\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1) ?: ""
                    sb0.append(av)
                }
            }
        }
        val sp = sb0.toString()
        var x = arr.joinToString("")
        var bs = 0
        var q = 0
        for (i in sp.indices) {
            val w = sp[i].code
            bs = (bs * P.a + w) % P.b
            q = (q + ((w shl P.c) xor i)) and 255
        }
        val p0 = (bs * P.p0m + q) % 256
        val hc = (q % P.hcm) + P.hca
        var f16 = ((q * P.fm + bs) % P.fmod) + 1
        for (i in ops.length - 1 downTo 0) {
            val ch = ops[i]
            if (ch == P.atobCh) {
                x = b64d(x)
            } else if (ch == P.revCh) {
                x = x.reversed()
            } else {
                val r = (26 - ((ch.code - P.cb) % 26)) % 26
                val sb = StringBuilder(x.length)
                for (c in x) {
                    if (c in 'A'..'Z') sb.append((((c.code - 65 + r) % 26) + 65).toChar())
                    else if (c in 'a'..'z') sb.append((((c.code - 97 + r) % 26) + 97).toChar())
                    else sb.append(c)
                }
                x = sb.toString()
            }
        }
        val n = x.length
        if (n == 0) return ""
        val e = IntArray(n)
        for (i in n - 1 downTo 1) {
            f16 = (f16 * P.la + P.lb) % P.lm
            e[i] = f16 % (i + 1)
        }
        val a = x.toCharArray()
        for (i in 1 until n) {
            val j = e[i]
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        var it = p0
        val out = StringBuilder(n)
        for (ch in a) {
            val w = ch.code
            it = (it * P.sm + hc) % 256
            out.append((w xor it).toChar())
            it = (it + w) % 256
        }
        return out.toString()
    }

    // joined: sayfa metni + çözülmüş (unpack) bloklar; html: ham sayfa (gizli data-xxx değerleri için); host: sayfanın host adı
    fun spUrls(joined: String, html: String, host: String, dbg: (String) -> Unit = {}): List<String> {
        if (!joined.contains("splice(")) return emptyList()
        val defs = HashMap<String, SpParams>()
        for (m in spFnRe.findAll(joined)) {
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val st = m.range.last + 1
            val body = joined.substring(st, minOf(joined.length, st + 7000))
            if (!body.contains("splice(") || !body.contains("charCodeAt")) continue
            val p = spParams(body) ?: continue
            defs[name] = p
        }
        dbg("SP defs=" + defs.keys)
        if (defs.isEmpty()) return emptyList()
        val rets = ArrayList<String>()
        val others = ArrayList<String>()
        fun handle(name: String, parts: List<String>, pos: Int) {
            val P = defs[name] ?: return
            val pre = joined.substring(maxOf(0, pos - 16), pos)
            val isRet = Regex("""return\s*$""").containsMatchIn(pre)
            val raw: String = try {
                spRun(parts, host, html, P)
            } catch (e: Throwable) {
                ""
            }
            val r = raw.trim()
            val ok = r.startsWith("http", true) && mostlyPrintable(r)
            dbg("SP call $name ret=$isRet parts=${parts.size} ok=$ok -> ${if (ok) r.take(160) else ""}")
            if (!ok) return
            val u = if (P.oflag) r + (if (r.contains("?")) "&o=255" else "?o=255") else r
            if (isRet) rets.add(u) else others.add(u)
        }
        for (m in spCallSplitRe.findAll(joined)) {
            if (!defs.containsKey(m.groupValues[1])) continue
            val str = m.groupValues[2].replace("\\/", "/").replace("\\\\", "\\")
            handle(m.groupValues[1], str.split(m.groupValues[3].ifEmpty { "*" }), m.range.first)
        }
        for (m in spCallArrRe.findAll(joined)) {
            if (!defs.containsKey(m.groupValues[1])) continue
            val parts = spStrRe.findAll(m.groupValues[2]).map { it.groupValues[1].replace("\\/", "/").replace("\\\\", "\\") }.toList()
            handle(m.groupValues[1], parts, m.range.first)
        }
        return (rets + others).distinct()
    }

    // ---------- kn şeması (Close + Rapid) ----------
    // Sayfada: (function(){var n="..",j="<uzun hex>",d=0; ... $.ajax({type:"POST",url:"/video/embed/<id>/kn/",data:{hash:"..",s:"g",n:n,j:j,p:p,d:d}...
    // p = knF(n), yanıt (hex) = knC(yanıt, n, p) -> ilk satır gerçek adres, sonrası altyazı JSON'u
    private val knInitRe = Regex("""\(function\(\)\s*\{\s*var\s+(\w+)\s*=\s*"([0-9a-fA-F]{8,})"\s*,\s*(\w+)\s*=\s*"([0-9a-fA-F]{32,})"""")
    private val knUrlRe = Regex("""url\s*:\s*"([^"]*/kn/?[^"]*)"""")
    private val knDataRe = Regex("""\bdata\s*:\s*\{([^{}]*)\}""")
    private val knFnRe = Regex("""window\.knF\s*=\s*function""")
    private val knKRe = Regex("""var\s+\w+\s*=\s*"([0-9a-fA-F]{8,64})"""")
    private val knSeedRe = Regex("""var\s+\w+\s*=\s*(\d{8,10})\s*,""")
    private val knRoundsRe = Regex("""\w+<(\d+);\w+\+\+\)\{for\(""")

    fun knParse(html: String, blobs: List<String>): KnInfo? {
        val init = knInitRe.find(html) ?: return null
        val tail = html.substring(init.range.last)
        val um = knUrlRe.find(tail) ?: return null
        val after = tail.substring(um.range.last)
        val dm = knDataRe.find(after) ?: return null
        val fields = ArrayList<Pair<String, String>>()
        for (part in dm.groupValues[1].split(",")) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val key = part.substring(0, i).trim().trim('"', '\'')
            val v = part.substring(i + 1).trim()
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) fields.add(Pair(key, v.substring(1, v.length - 1)))
            else fields.add(Pair(key, "@$v"))
        }
        val kb = blobs.firstOrNull { knFnRe.containsMatchIn(it) } ?: return null
        val k = knKRe.find(kb)?.groupValues?.get(1) ?: return null
        val flat = kb.replace(wsRe, "")
        val seed = knSeedRe.find(flat)?.groupValues?.get(1)?.toLongOrNull() ?: 2166136261L
        val rounds = knRoundsRe.find(flat)?.groupValues?.get(1)?.toIntOrNull() ?: 3
        return KnInfo(um.groupValues[1], init.groupValues[1], init.groupValues[3], init.groupValues[2], init.groupValues[4], fields, k, seed, rounds)
    }

    fun knF(s: String, seed: Long, rounds: Int): String {
        var h = seed.toInt()
        for (r in 0 until rounds) {
            for (ch in s) {
                h = h xor ch.code
                h = h + (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24)
            }
        }
        return Integer.toHexString(h).padStart(8, '0')
    }

    fun knC(x: String, n: String, p: String, seed: Long, rounds: Int): String {
        val bo = java.io.ByteArrayOutputStream()
        var ks = ""
        var i = 0
        while (i + 1 < x.length) {
            if (i % 8 == 0) ks = knF(n + "|" + (i / 8) + "|" + p, seed, rounds)
            val b = x.substring(i, i + 2).toInt(16) xor ks.substring(i % 8, i % 8 + 2).toInt(16)
            bo.write(b)
            i += 2
        }
        return String(bo.toByteArray(), Charsets.UTF_8)
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
        """cloudflareinsights|cloudflare\.com|googletagmanager|google-analytics|googlesyndication|gstatic|googleapis|doubleclick|facebook\.|twitter\.|jquery|cdnjs|jsdelivr|unpkg|bootstrapcdn|histats|yandex|adsterra|popads|beacon\.min\.js|/cdn-cgi/""",
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

    fun candFrom(blobs: List<String>): Cand {
        val urls = LinkedHashSet<String>()
        val strict = LinkedHashSet<String>()
        for (b in blobs) {
            val t = cleanBlob(b)
            for (m in urlRe.findAll(t)) {
                val u = m.value.trimEnd(',', ';', '.', ']', ')')
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u) && !junkUrl(u) && !u.contains("videosample", true)) {
                    urls.add(u)
                    strict.add(u)
                }
            }
        }
        // Akış desenine uyan adres yoksa: file:"..." / src="..." gibi anahtarlı adresleri aday yap (doğrulamadan geçerse kullanılır)
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
        return Cand(urls.toList(), strict)
    }

    // ---------- Altyazı anahtarları: /srt/<no>/<kod>_<ad>.vtt/ ----------
    private val srtKeyRe = Regex("""/srt/(\d{1,8})/([A-Za-z0-9]{6,32})_""", IC)
    private val streamKeyRe = Regex("""/hls\d?/\d+/(\d+)/([A-Za-z0-9]+)[_/.]""")
    private val looseKeyRe = Regex("""/(\d{4,6})/([A-Za-z0-9]{10,16})(?:[_/.]|$)""")

    // texts: sayfa metinleri veya adresler. loose=true yalnızca akış adresleri için (hls/<x>/<no>/<kod> dışı biçimler)
    fun keysFrom(texts: List<String>, loose: Boolean): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (raw in texts) {
            val t = raw.replace("\\/", "/")
            for (m in srtKeyRe.findAll(t)) out.add(Pair(m.groupValues[1], m.groupValues[2]))
            for (m in streamKeyRe.findAll(t)) out.add(Pair(m.groupValues[1], m.groupValues[2]))
        }
        if (loose && out.isEmpty()) {
            for (raw in texts) {
                val t = raw.replace("\\/", "/").substringBefore("?")
                for (m in looseKeyRe.findAll(t)) out.add(Pair(m.groupValues[1], m.groupValues[2]))
            }
        }
        return out.toList()
    }

    // ---------- Altyazılar ----------
    private val tagRe = Regex("""<track\b[^>]*>""", IC)
    private val objRe = Regex("""\{[^{}]{0,600}\}""")
    private val subExtRe = Regex("""\.(?:vtt|srt|ass|ssa|sub)(?:\?|/|$)|/(?:srt|sub|subs|subtitle|subtitles|vtt)/""", IC)
    private val anySubRe = Regex(
        """["'(=\s]((?:https?://|//|/)[^"'\s\\)<>]*?(?:\.vtt|\.srt|/srt/\d|/vtt/\d)[^"'\s\\)<>]*)["')\s<>]""",
        IC
    )
    private val otherLangRe = Regex(
        """fransiz|french|portekiz|portug|ispanyol|spanish|almanca|german|deutsch|italyan|italian|arapca|arabic|rusca|russian|hollanda|dutch|yunan|greek|arnavut|albanian|sirp|serbian|isvec|swedish|japon|korece|korean|cince|chinese|bulgar|rumen|romanian|macar|hungar|lehce|polish|czech|ibran|hebrew|farsca|persian|hintce|hindi|\b(?:fr|fre|pt|por|es|spa|de|ger|it|ita|ar|ara|ru|nl|el|ja|ko|zh)\b""",
        IC
    )

    private fun absUrl(u: String, origin: String): String = when {
        u.startsWith("http", true) -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> origin.trimEnd('/') + u
        else -> origin.trimEnd('/') + "/" + u
    }

    private fun langOf(label: String, code: String, url: String): String {
        val c = code.lowercase()
        if (c.startsWith("tr")) return "tr"
        if (c.startsWith("en")) return "en"
        val path = url.replace(Regex("^https?://[^/]+", IC), "")
        val f = fold("$label $path")
        return when {
            Regex("turk|turkce|\\btr\\b|\\btur\\b|/tr[._/-]").containsMatchIn(f) -> "tr"
            Regex("ingiliz|english|\\ben\\b|\\beng\\b|/en[._/-]").containsMatchIn(f) -> "en"
            otherLangRe.containsMatchIn(f) -> "other"
            c.isNotBlank() -> c.take(2)
            else -> "xx"
        }
    }

    // Dışarıdan: adresten dil tahmini ("tr" / "en" / "other" / "xx")
    fun subLang(url: String, label: String = ""): String = langOf(label, "", url)

    // <track src label srclang>, {file:"..", label:"..", kind:"captions"} (tırnaklı / tırnaksız anahtar) ve yalın .vtt/.srt adresleri
    private fun attrOf(tag: String, name: String): String {
        val re = Regex("[\\s{,\"']" + name + "[\"']?\\s*[=:]\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)')", IC)
        val m = re.find(tag) ?: return ""
        return if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
    }

    fun extractSubs(blob: String, origin: String): List<SubItem> {
        val t = blob.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").replace("&quot;", "\"")
        val out = ArrayList<SubItem>()
        val seen = HashSet<String>()

        fun push(url0: String, label0: String, kind: String, srclang: String) {
            val label = unesc(label0).trim()
            var url = unesc(url0).replace("\\", "")
            if (url.isBlank()) return
            if (Regex("thumb|sprite|storyboard", IC).containsMatchIn(url) || Regex("thumbnails", IC).containsMatchIn(kind)) return
            if (Regex("""\.(?:jpe?g|png|webp|gif|css|js|m3u8|mp4)(?:\?|$)""", IC).containsMatchIn(url)) return
            url = absUrl(url, origin)
            if (junkUrl(url) || !seen.add(url)) return
            out.add(SubItem(url, label, langOf(label, srclang, url)))
        }

        for (m in tagRe.findAll(t)) {
            val tag = " " + m.value
            val kd = attrOf(tag, "kind")
            if (Regex("metadata|chapters|descriptions", IC).containsMatchIn(kd)) continue
            var src = attrOf(tag, "src")
            if (src.isBlank()) src = attrOf(tag, "file")
            push(src, attrOf(tag, "label"), kd, attrOf(tag, "srclang"))
        }
        for (m in objRe.findAll(t)) {
            val o = m.value
            val kind = attrOf(o, "kind")
            var f = attrOf(o, "file")
            if (f.isBlank()) f = attrOf(o, "src")
            if (f.isBlank()) f = attrOf(o, "url")
            if (f.isBlank()) continue
            val isSub = subExtRe.containsMatchIn(f) || Regex("captions|subtitles", IC).containsMatchIn(kind)
            if (!isSub) continue
            var label = attrOf(o, "label")
            if (label.isBlank()) label = attrOf(o, "name")
            if (label.isBlank()) label = attrOf(o, "title")
            var lg = attrOf(o, "srclang")
            if (lg.isBlank()) lg = attrOf(o, "lang")
            if (lg.isBlank()) lg = attrOf(o, "language")
            push(f, label, kind, lg)
        }
        for (m in anySubRe.findAll(t)) {
            push(m.groupValues[1], "", "", "")
        }
        return out
    }
}
