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
import kotlin.math.abs

private data class SiteItem(
    val url: String,
    val title: String,
    val year: Int,
    val type: String,
    val poster: String? = null,
    val isPoster: Boolean = false
)
private data class Alt(val id: String, val name: String, val lang: String)
private data class Player(val name: String, val lang: String, val id: String?, val embed: String?)
private data class EmbedResult(
    val urls: List<String>,
    val subs: List<SubItem>,
    val strict: Set<String>,
    val base: String,
    val keys: List<Pair<String, String>> = emptyList()   // /srt/<no>/<code>_Turkish.vtt anahtarları
)
private data class Verified(val ok: Boolean, val headers: Map<String, String>, val text: String = "")
private class CfCooldown : RuntimeException("CF doğrulaması kısa süre önce başarısız oldu, bekleniyor")

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

    // Doğrulama başarısız olunca bir süre yeniden denenmez; böylece onlarca istek tek tek 20-30 sn
    // beklemez ve CloudStream'in 120 sn zaman aşımına takılmaz.
    @Volatile
    private var cfBlockedUntil = 0L
    private val cfCooldownMs = 60L * 1000

    private fun cfCoolingDown(): Boolean = System.currentTimeMillis() < cfBlockedUntil

    private fun cfFail(why: String) {
        cfBlockedUntil = System.currentTimeMillis() + cfCooldownMs
        log("CF doğrulaması geçilemedi ($why), ${cfCooldownMs / 1000} sn beklenecek")
    }

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
    // Doğrulama başarısız olursa cfCooldownMs boyunca CfCooldown fırlatılır (çağıranlar Exception yakalıyor).
    private suspend fun cfGet(url: String, headers: Map<String, String>, timeout: Long = 30L): NiceResponse {
        if (!cfWarm && cfCoolingDown()) throw CfCooldown()
        val k = cfKiller
        val r: NiceResponse = try {
            var x: NiceResponse = if (cfWarm) {
                app.get(url, headers = headers, interceptor = k, timeout = timeout)
            } else {
                cfLock.acquire()
                try {
                    if (!cfWarm && cfCoolingDown()) throw CfCooldown()
                    app.get(url, headers = headers, interceptor = cfKiller, timeout = timeout)
                } finally {
                    cfLock.release()
                }
            }
            if (x.code == 403 || x.code == 503) {
                log("CF 403: çözücü sıfırlanıyor ${url.take(70)}")
                cfLock.acquire()
                try {
                    if (!cfCoolingDown()) {
                        if (cfKiller === k) {
                            cfKiller = CloudflareKiller()
                            cfWarm = false
                            cfClear()
                        }
                        x = app.get(url, headers = headers, interceptor = cfKiller, timeout = timeout)
                    }
                } finally {
                    cfLock.release()
                }
            }
            x
        } catch (e: CfCooldown) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            cfFail("hata ${e.message?.take(60)}")
            throw e
        }
        if (r.code in 200..299) {
            cfWarm = true
            cfBlockedUntil = 0L
            cfSave()
        } else if (r.code == 403 || r.code == 503) {
            cfFail("kod=${r.code}")
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
        val html = try {
            siteGet(p)?.text.orEmpty()
        } catch (e: Exception) {
            ""
        }
        val items = parseCatalog(html, false).map { toSearchResponse(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
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
        h.putAll(extra)
        return h
    }

    private fun denied(r: NiceResponse?): Boolean {
        if (r == null || !r.isSuccessful) return true
        val t = r.text
        return t.length < 700 &&
            Regex("access denied|forbidden|attention required|just a moment|you have been blocked", IC).containsMatchIn(t)
    }

    private suspend fun siteGet(path: String, extra: Map<String, String> = emptyMap()): NiceResponse? {
        // 1) Cloudflare çerezi hazırsa (diskten yüklenmiş / daha önce çözülmüş) önce çözücüyle dene;
        //    düz istek 403 verecekse boşa beklemeyelim
        if (cfWarm) {
            val g = good ?: Pair(hosts[0], false)
            val r: NiceResponse? = try {
                cfGet(g.first + path, siteHeaders(g.first, g.second, extra), 15L)
            } catch (e: Exception) {
                null
            }
            if (!denied(r)) {
                good = g
                return r
            }
        }

        // 2) Düz istekler (adres + UA kombinasyonları)
        val tries = ArrayList<Pair<String, Boolean>>()
        good?.let { tries.add(it) }
        hosts.forEach { h ->
            listOf(false, true).forEach { dsk ->
                val p = Pair(h, dsk)
                if (p != good) tries.add(p)
            }
        }
        var cfSeen = false
        for ((host, desk) in tries) {
            val r: NiceResponse? = try {
                app.get(host + path, headers = siteHeaders(host, desk, extra), timeout = 10L)
            } catch (e: Exception) {
                null
            }
            if (!denied(r)) {
                good = Pair(host, desk)
                return r
            }
            // Cloudflare engeli: diğer kombinasyonları denemek yerine çözücüye geç
            if (r != null && (r.code == 403 || r.code == 503) &&
                r.headers["server"].orEmpty().contains("cloudflare", true)
            ) {
                cfSeen = true
                break
            }
        }

        // 3) Cloudflare engeli varsa WebView çözücü ile (çerez diske kaydedilir)
        if (cfSeen) {
            val cands = listOf(good ?: Pair(hosts[0], false), Pair(hosts[1], false)).distinct()
            for ((host, desk) in cands) {
                val r: NiceResponse? = try {
                    cfGet(host + path, siteHeaders(host, desk, extra), 25L)
                } catch (e: Exception) {
                    null
                }
                log("CF çözücü ${host.take(40)} kod=${r?.code}")
                if (!denied(r)) {
                    good = Pair(host, desk)
                    return r
                }
            }
        }
        return null
    }

    private fun siteBase(): String = good?.first ?: mainUrl

    private fun toSite(u0: String): String {
        val u = u0.replace("&amp;", "&").replace("\\/", "/")
        return when {
            Regex("^https?://", IC).containsMatchIn(u) ->
                u.replace(Regex("^https?://(?:www\\.)?hdfilmcehennemi\\.[a-z]+", IC), siteBase())
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

    private fun pageOk(r: NiceResponse?): Boolean =
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
        doc.select("div.alternative-links").forEach { div ->
            val lang = div.attr("data-lang")
            div.select("button[data-video]").forEach { b ->
                val id = b.attr("data-video")
                if (id.isBlank() || !seen.add(id)) return@forEach
                out.add(Alt(id, b.text().trim().ifBlank { "Player" }, lang.ifBlank { b.attr("data-lang") }))
            }
        }
        if (out.isNotEmpty()) return out

        // Yedek: kapsayıcı sınıf adı değişirse data-video taşıyan her öğeyi al, dili üst öğelerden bul
        doc.select("[data-video]").forEach { b ->
            val id = b.attr("data-video")
            if (id.isBlank() || !seen.add(id)) return@forEach
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
    private fun embedHeaders(ref: String): Map<String, String> = mapOf(
        "User-Agent" to uaMobile,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to ref
    )

    private fun embedVariants(u: String): List<String> {
        val out = ArrayList<String>()
        out.add(u)
        val id = Regex("""[?&]rapidrame_id=([^&#\x22\x27]+)""").find(u)?.groupValues?.get(1)
        if (id != null) {
            out.add(siteBase() + "/playerr/" + id)
            out.add(siteBase() + "/rplayer/" + id)
        }
        hosts.forEach { h ->
            if (u.startsWith(h, true)) return@forEach
            if (u.contains("hdfilmcehennemi.", true)) out.add(u.replace(Regex("^https?://[^/]+", IC), h))
        }
        return out.distinct().take(4)
    }

    private suspend fun resolveEmbed(embedUrl: String, pageUrl: String, depth: Int = 0): EmbedResult? {
        val refs = listOf(siteBase() + "/", pageUrl).distinct()
        val variants = if (depth > 0) listOf(embedUrl) else embedVariants(embedUrl)
        var cfTried = false // Cloudflare çözücüyü bu çağrıda en çok bir kez dene (gereksiz bekleme olmasın)

        for (v in variants) {
            for (rf in refs) {
                var r: NiceResponse? = try {
                    app.get(v, headers = embedHeaders(rf), timeout = 10L)
                } catch (e: Exception) {
                    null
                }
                if (r != null && (r.code == 403 || r.code == 503) && !cfTried &&
                    r.headers["server"].orEmpty().contains("cloudflare", true)
                ) {
                    cfTried = true
                    r = try {
                        cfGet(v, embedHeaders(rf), 20L)
                    } catch (e: Exception) {
                        r
                    }
                }
                if (r == null || r.code != 200) continue
                val html = r.text
                if (html.isEmpty()) continue

                val blobs = Decoder.collectBlobs(html)
                val c = Decoder.candFrom(blobs)
                val subs = ArrayList<SubItem>()
                val seenS = HashSet<String>()
                blobs.forEach { b ->
                    Decoder.extractSubs(b, originOf(v)).forEach { s -> if (seenS.add(s.url)) subs.add(s) }
                }
                // Close vb.: JSON-LD "contentUrl" doğrudan video adresi olabilir
                val ld = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                val allUrls = LinkedHashSet<String>()
                val allStrict = LinkedHashSet<String>(c.strict)
                if (ld != null && ld.startsWith("http", true)) {
                    allUrls.add(ld)
                    allStrict.add(ld)
                }
                allUrls.addAll(c.urls)

                // Altyazı anahtarları (/srt/<no>/<kod>_...): sayfa metinlerinden + akış adreslerinden
                val keySet = LinkedHashSet<Pair<String, String>>()
                keySet.addAll(Decoder.keysFrom(blobs + listOf(v), false))
                keySet.addAll(Decoder.keysFrom(allUrls.toList(), true))

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

        // Son çare: sayfayı WebView'de açıp m3u8 isteğini yakala
        try {
            val wv = app.get(
                embedUrl,
                referer = siteBase() + "/",
                interceptor = WebViewResolver(Regex("""\.m3u8|master\.txt|/hls\d?/|\.mp4(\?|$)"""))
            )
            val u = wv.url
            if (Regex("""\.m3u8|master\.txt|/hls\d?/|\.mp4(\?|$)""").containsMatchIn(u)) {
                return EmbedResult(
                    listOf(u), emptyList(), setOf(u), embedUrl,
                    Decoder.keysFrom(listOf(u), true)
                )
            }
        } catch (e: Exception) { }
        return null
    }

    // ---------------- Doğrulama ----------------
    private fun streamHeaderSets(embedUrl: String): List<Map<String, String>> {
        val org = originOf(embedUrl)
        return listOf(
            mapOf("User-Agent" to uaMobile, "Referer" to "$org/", "Origin" to org),
            mapOf("User-Agent" to uaMobile, "Referer" to embedUrl),
            mapOf("User-Agent" to uaMobile, "Referer" to siteBase() + "/", "Origin" to siteBase()),
            mapOf("User-Agent" to uaMobile)
        )
    }

    private suspend fun verifyUrl(u: String, sets0: List<Map<String, String>>): Verified {
        val own = originOf(u)
        val sets = listOf(mapOf("User-Agent" to uaMobile, "Referer" to "$own/", "Origin" to own)) + sets0
        if (Regex("""\.mp4(\?|$)""", IC).containsMatchIn(u)) return Verified(true, sets[0])
        for (h in sets) {
            val r: NiceResponse? = try {
                app.get(u, headers = h, timeout = 8L)
            } catch (e: Exception) {
                null
            }
            if (r != null && (r.code == 200 || r.code == 206) && r.text.trimStart().startsWith("#EXTM3U")) {
                return Verified(true, h, r.text)
            }
        }
        return Verified(false, sets[0])
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
                app.get(su, headers = embedHeaders(rf), timeout = 6L)
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
        val html = siteGet(pathOf(pageUrl))?.text
        if (html == null) {
            log("sayfa alınamadı $pageUrl")
            return false
        }
        val ifr = pageIframe(html)

        val all = parseAlts(html).map { a -> Player(a.name, a.lang.ifBlank { "dual" }, a.id, null) }
        val dual = all.filter { it.lang == "dual" }
        val subbed = all.filter { it.lang != "dual" }
        log("sayfa len=${html.length} dual=${dual.size} altyazili=${subbed.size} iframe=${ifr.isNotBlank()} ${pageUrl.take(80)}")

        val provider = this.name
        val seenEmb = Collections.synchronizedSet(HashSet<String>())
        val seenUrl = Collections.synchronizedSet(HashSet<String>())
        val seenSub = Collections.synchronizedSet(HashSet<String>())
        val embList = Collections.synchronizedList(ArrayList<String>())
        val labelCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
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
                            )
                        )
                        iframeFromAjax(rr?.text.orEmpty()).ifBlank { if (job === list[0]) ifr else "" }
                    }
                    if (embedUrl.isBlank() || !seenEmb.add(embedUrl)) return@amap
                    embList.add(embedUrl)
                    log("P ${job.name}/${job.lang} video=$withVideo ${embedUrl.take(80)}")

                    val res = resolveEmbed(embedUrl, pageUrl)
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
                    val sets = streamHeaderSets(res.base)

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

                    // Akışlar
                    val cands = res.urls
                        .sortedWith(compareBy<String>({ if (res.strict.contains(it)) 0 else 1 }, { rank(it) }))
                        .take(6)
                    val verified = cands.amap { u -> u to verifyUrl(u, sets) }

                    // Akışın içine gömülü (HLS) altyazı kanalları
                    verified.forEach { (mu, mv) ->
                        if (mv.ok) {
                            embeddedSubs(mv.text, mu).forEach { es -> pushSub(es.label, es.url) }
                        }
                    }
                    var okList = verified.filter { it.second.ok }
                    if (okList.isEmpty()) {
                        // Doğrulanamadı: yine de en olası adresi ver (Close gibi oynatıcılar için)
                        val st = verified.filter { res.strict.contains(it.first) }
                        val pick = if (st.isNotEmpty()) st.take(1) else emptyList<Pair<String, Verified>>()
                        okList = pick.map { Pair(it.first, Verified(false, sets[0])) }
                    }

                    val nm = when {
                        job.name.contains("rapid", true) -> "Rapid"
                        job.name.contains("close", true) -> "Close"
                        else -> job.name
                    }
                    val lg = if (job.lang == "dual") "DUAL" else if (job.lang.isNotBlank()) "Altyazılı" else "TR"

                    okList.forEachIndexed { i, pair ->
                        val u = pair.first
                        val v = pair.second
                        if (!seenUrl.add(u)) return@forEachIndexed
                        val label = "$lg $nm" + (if (i > 0) " #${i + 1}" else "")
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
            for (eu in embList.toList().take(2)) {
                try {
                    val wv = app.get(
                        eu,
                        referer = siteBase() + "/",
                        interceptor = WebViewResolver(Regex("""\.vtt|/srt/\d|/vtt/\d"""))
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
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u) && !junkUrl(u)) {
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
