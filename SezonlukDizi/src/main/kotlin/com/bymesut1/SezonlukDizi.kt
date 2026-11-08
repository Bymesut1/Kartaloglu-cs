package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SezonlukDizi : MainAPI() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // CAPTCHA / Cloudflare "tarayıcı kontrolü" killer:
    // Site bir kontrol sayfası döndürürse otomatik geçer, çerezi saklar ve sonraki isteklerde kullanır.
    // Kontrol yoksa isteği olduğu gibi geçirir. Sitedeki TÜM isteklerde (liste, arama, ajax) kullanılıyor.
    private val cf = CloudflareKiller()

    // Hata ayıklama: true iken arama / oynatma başarısız olursa sebep ekranda hata mesajı olarak görünür.
    // Her şey düzelince false yap.
    private val debug = false
    private val dbg = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun d(m: String) { dbg.add(m.replace("\n", " ").take(300)) }
    private fun dbgText(): String = dbg.joinToString(" | ").take(1800)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val liste = "$mainUrl/diziler.asp"

    // ---------------- TMDB (Türkçe bilgiler) ----------------
    // TMDB API anahtarını buraya yaz. Boş ya da varsayılan bırakılırsa TMDB özellikleri kapalı kalır, eklenti eskisi gibi çalışır.
    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbOn: Boolean get() = tmdbKey.isNotBlank() && tmdbKey != "BURAYA_TMDB_API_KEY"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val reGenericEp = Regex("""(?iu)^\s*(bölüm|episode|ep\.?)\s*\d+\s*$""")

    private fun norm(t: String): String = t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private fun dateMs(s: String?): Long? = try {
        if (s.isNullOrBlank()) null else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    // Sitedeki bölüm bağlantı yazısını temizler: "9,5 1. Sezon 3. Bölüm The Krakken" -> "The Krakken"; "Cevapla" -> null
    private fun cleanLabel(raw: String): String? {
        var t = raw.trim()
        t = t.replace(Regex("""^\d+[.,]\d+\s*"""), "")
        t = t.replace(Regex("""(?iu)^\s*\d+\s*\.?\s*sezon\s*\d+\s*\.?\s*bölüm\s*"""), "")
        t = t.replace(Regex("""(?iu)^\s*\d+\s*\.?\s*bölüm\s*"""), "")
        t = t.trim(' ', '-', '–', ':', '.')
        if (t.isBlank() || t.equals("cevapla", true)) return null
        return t
    }

    private suspend fun tmdbSearch(q: String): List<TmdbTvResult> = try {
        app.get(
            "$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${URLEncoder.encode(q, "UTF-8")}",
            timeout = 15L
        ).parsedSafe<TmdbSearchResponse>()?.results.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    // Dizi ayrıntıları (Türkçe); özet Türkçe boşsa İngilizceden tamamlanır
    private suspend fun tmdbDetails(id: Int): TmdbTvDetails? {
        val tr = try {
            app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=tr-TR&append_to_response=credits", timeout = 15L)
                .parsedSafe<TmdbTvDetails>()
        } catch (e: Exception) {
            null
        } ?: return null
        if (!tr.overview.isNullOrBlank()) return tr
        val en = try {
            app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=en-US", timeout = 15L).parsedSafe<TmdbTvDetails>()
        } catch (e: Exception) {
            null
        }
        return if (en?.overview.isNullOrBlank()) tr else tr.copy(overview = en?.overview)
    }

    // Sitedeki diziyi TMDB'deki doğru diziyle eşler. Aynı adlı birden fazla dizi varsa (ör. Ben 10 2005 / 2016)
    // sitedeki sezon başına bölüm sayısına en yakın olan seçilir.
    private suspend fun tmdbMatch(title: String, siteCounts: Map<Int, Int>): TmdbTvDetails? {
        if (!tmdbOn) return null
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
            // Sitenin sezon düzeni TMDB'dekinden farklıysa (ör. site 5 sezon, TMDB tek sezon) toplam bölüm sayısına bakılır
            val covers = siteCounts.keys.filter { it > 0 }.all { cnt.containsKey(it) }
            if (!covers) {
                val tmTotal = cnt.filterKeys { it > 0 }.values.sum()
                score = kotlin.math.abs(siteCounts.filterKeys { it > 0 }.values.sum() - tmTotal)
            }
            score * 1000 - (d.popularity ?: 0.0).toInt().coerceAtMost(999)
        }
    }

    private suspend fun tmdbSeason(id: Int, sn: Int, lang: String): Map<Int, TmdbEpisode> = try {
        app.get("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=$lang", timeout = 15L)
            .parsedSafe<TmdbSeasonDetails>()?.episodes.orEmpty()
            .mapNotNull { e -> e.episode_number?.let { it to e } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    // Arama kartı için TMDB afişi (sadece adı birebir eşleşen dizi); bulunamazsa null (sitenin afişi kalır)
    // Hız için kısa zaman aşımı (2 sn): TMDB yavaşsa beklemeden sitenin afişi kullanılır; başarısız sonuç önbelleğe yazılmaz.
    private suspend fun tmdbPosterFor(title: String): String? {
        val key = norm(title)
        if (key.isEmpty()) return null
        posterCache[key]?.let { return it.ifBlank { null } }
        val res = try {
            app.get(
                "$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${URLEncoder.encode(title, "UTF-8")}",
                timeout = 1L
            ).parsedSafe<TmdbSearchResponse>()?.results.orEmpty()
        } catch (e: Exception) {
            return null
        }
        val best = res
            .filter {
                !it.poster_path.isNullOrBlank() &&
                    (norm(it.name.orEmpty()) == key || norm(it.original_name.orEmpty()) == key)
            }
            .maxByOrNull { it.popularity ?: 0.0 }
        val url = best?.poster_path?.let { "$tmdbImg/w500$it" }.orEmpty()
        posterCache[key] = url
        return url.ifBlank { null }
    }

    // data alanı: "soncikan" / "populer" (ana sayfa) ya da sayfalanabilir liste adresi (sonuna &s=<sayfa> eklenir)
    override val mainPage = mainPageOf(
        "soncikan" to "Son Çıkan Bölümler",
        "populer" to "Popüler Diziler",
        "$liste?dil=0&siralama_tipi=id&siralama_turu=desc" to "Türkçe Dublaj Diziler",
        "$liste?siralama_tipi=id&siralama_turu=desc" to "Yeni Eklenenler",
        "$liste?kat=1&siralama_tipi=imdb&siralama_turu=desc" to "Yabancı Diziler (IMDb)",
        "$liste?kat=2&siralama_tipi=imdb&siralama_turu=desc" to "Yerli Diziler",
        "$liste?kat=4&siralama_tipi=imdb&siralama_turu=desc" to "Animasyonlar",
        "$liste?kat=5&siralama_tipi=imdb&siralama_turu=desc" to "Animeler"
    )

    // ---------------- Yardımcılar ----------------

    private fun hdr(): Map<String, String> = mapOf("User-Agent" to ua)

    private fun ajaxHdr(referer: String): Map<String, String> = hdr() + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer
    )

    // Önce düz istek atılır (hızlı, WebView açmaz). Sadece Cloudflare kontrol sayfası gelirse CloudflareKiller devreye girer.
    // Eskiden her istek CloudflareKiller'dan geçiyordu; kontrol sayfasında WebView sonsuz bekleyip arama/oynatmayı donduruyordu.
    private fun isChallenge(code: Int, body: String): Boolean =
        code == 403 || code == 503 ||
            body.contains("<title>Just a moment", true) ||
            body.contains("cf-browser-verification", true) ||
            body.contains("/cdn-cgi/challenge-platform/h/b/orchestrate", true)

    // Site Cloudflare kontrolü istediyse bir sonraki istekler boşuna düz denenmeden doğrudan CloudflareKiller ile atılır
    @Volatile private var needCf = false

    private suspend fun get(url: String, referer: String = "$mainUrl/", t: Long = 8L) =
        try {
            if (needCf) throw Exception("cf")
            val r = app.get(url, headers = hdr(), referer = referer, timeout = t)
            if (isChallenge(r.code, r.text)) {
                needCf = true
                throw Exception("cf")
            }
            r
        } catch (e: Exception) {
            app.get(url, headers = hdr(), referer = referer, interceptor = cf, timeout = 20L)
        }

    // Daha önce çalışan kaynakların adı / sırası: sonraki bölümlerde önce onlar denenir (hız için)
    private val goodKeys: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    // Sayfadan ve ajax yanıtlarından gelen oturum çerezleri (ASP oturumu) sonraki ajax isteklerine eklenir
    @Volatile private var sessionCookies: Map<String, String> = emptyMap()

    private suspend fun post(url: String, form: Map<String, String>, referer: String, t: Long = 15L) =
        try {
            val r = app.post(url, data = form, headers = ajaxHdr(referer), cookies = sessionCookies, timeout = t)
            if (isChallenge(r.code, r.text)) throw Exception("cf")
            if (r.cookies.isNotEmpty()) sessionCookies = sessionCookies + r.cookies
            r
        } catch (e: Exception) {
            app.post(url, data = form, headers = ajaxHdr(referer), interceptor = cf, timeout = t)
        }

    private fun abs(u: String): String = if (u.startsWith("http")) u else fixUrl(u)

    // Site windows-1254 kullanıyor; Türkçe harfli aramalar için aynı kodlama denenir
    private fun encQ(q: String): String = try {
        URLEncoder.encode(q, "windows-1254")
    } catch (e: Exception) {
        URLEncoder.encode(q, "UTF-8")
    }

    // /diziler/mobland.html
    private val reSeries = Regex("""^(?:https?://sezonlukdizi\.[a-z]+)?(/diziler/[^/?#]+\.html)$""")

    // /mobland/2-sezon-5-bolum.html  veya  /mobland/dublaj/2-sezon-5-bolum.html
    private val reEp =
        Regex("""^(?:https?://sezonlukdizi\.[a-z]+)?/([A-Za-z0-9._~%-]+)/(?:dublaj/)?(\d+)-sezon-(\d+)-bolum\.html""")

    // 2-sezon-5-bolum.html (başında dizi adı olmayan göreli bağlantı)
    private val reEpBare = Regex("""^(?:\./)?(?:dublaj/)?(\d+)-sezon-(\d+)-bolum\.html""")

    private fun slugOf(seriesUrl: String): String =
        Regex("""/diziler/([^/?#]+)\.html""").find(seriesUrl)?.groupValues?.get(1)?.removeSuffix("-izle") ?: ""

    private fun cleanTitle(t: String): String =
        t.replace(Regex("""\s*(izle)?\s*[-|–]\s*Sezonluk\s*Dizi.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
            .trim()

    // Listelerde resim "data-src" içinde (src sadece boş yer tutucu); ana sayfada doğrudan src
    private fun posterOf(img: Element): String? {
        val ds = img.attr("data-src").trim()
        val s = img.attr("src").trim()
        val u = when {
            ds.isNotBlank() -> ds
            s.isNotBlank() && !s.startsWith("data:") -> s
            else -> return null
        }
        return abs(u)
    }

    // Sayfadaki dizi kartları (poster + bağlantı). Ana sayfadaki "bölüm" kartları bölüm adresiyle kalır,
    // gerçek dizi sayfası load() sırasında bölüm sayfasından bulunur.
    private fun cards(root: Element?): List<SearchResponse> {
        if (root == null) return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in root.select("a[href]")) {
            val href = a.attr("href").trim()
            val img = a.selectFirst("img") ?: continue
            val epm = reEp.find(href)
            val key: String
            val link: String
            when {
                reSeries.containsMatchIn(href) -> {
                    link = mainUrl + reSeries.find(href)!!.groupValues[1]
                    key = "s:" + slugOf(link)
                }
                epm != null -> {
                    link = abs(href)
                    key = "s:" + epm.groupValues[1]
                }
                else -> continue
            }
            if (out.containsKey(key)) continue
            val title = listOf(
                a.selectFirst(".box-title .title")?.text(),
                a.selectFirst(".description")?.text(),
                a.selectFirst(".title")?.text(),
                cleanTitle(a.attr("title")),
                img.attr("alt")
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: continue
            out[key] = newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = posterOf(img)
            }
        }
        return out.values.toList()
    }

    // Sayfalama çubuğundaki en büyük sayfa numarası
    private fun maxPage(doc: Document): Int =
        doc.select("#filtresayfalama a.item").mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull() ?: 1

    // ---------------- Ana sayfa ----------------

    private fun homeOf(title: String, list: List<SearchResponse>, hasNext: Boolean): HomePageResponse =
        newHomePageResponse(
            list = HomePageList(name = title, list = list, isHorizontalImages = false),
            hasNext = hasNext
        )

    // Ana sayfa ve liste sayfaları kısa süre önbellekte tutulur: iki ana sayfa satırı aynı sayfayı bir kez indirir, geri dönüşte anında açılır
    private val pageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Document>>()
    private val pageLock = Mutex()

    private suspend fun cachedDoc(url: String, ttlMs: Long = 180_000L): Document {
        pageCache[url]?.let { if (System.currentTimeMillis() - it.first < ttlMs) return it.second }
        return pageLock.withLock {
            pageCache[url]?.let { if (System.currentTimeMillis() - it.first < ttlMs) return@withLock it.second }
            val d = get(url).document
            if (pageCache.size > 40) pageCache.clear()
            pageCache[url] = System.currentTimeMillis() to d
            d
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data
        if (key == "soncikan" || key == "populer") {
            if (page > 1) return homeOf(request.name, emptyList(), false)
            val doc = cachedDoc(mainUrl)
            val list = if (key == "soncikan") {
                cards(doc.selectFirst("#soncikan"))
            } else {
                cards(doc.selectFirst("#enler .active.tab") ?: doc.selectFirst("#enler"))
            }
            return homeOf(request.name, list, false)
        }
        val doc = cachedDoc("$key&s=$page")
        val list = cards(doc.selectFirst(".afis") ?: doc.body())
        return homeOf(request.name, list, list.isNotEmpty() && page < maxPage(doc))
    }

    // ---------------- Arama ----------------

    // Aynı aramayı tekrar yapınca anında göstermek için önbellek
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, SearchResponseList>()

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.length < 2) return newSearchResponseList(emptyList(), false)   // site en az 2 karakter istiyor
        val cacheKey = q.lowercase() + "|" + page
        searchCache[cacheKey]?.let { return it }
        dbg.clear()
        val sfx = if (page > 1) "&s=$page" else ""
        val urls = listOf(
            "$liste?adi=${encQ(q)}$sfx",
            "$liste?adi=${URLEncoder.encode(q, "UTF-8")}$sfx"
        ).distinct()
        val responses = urls.amap { u ->
            try {
                get(u, t = 6L)
            } catch (e: Exception) {
                d("istek hatası ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
        for (r in responses) {
            if (r == null) continue
            val doc = r.document
            val list = cards(doc.selectFirst(".afis") ?: doc.body())
            d("kod=${r.code} uzunluk=${r.text.length} başlık='${doc.title()}' afis=${doc.select(".afis").size} kart=${list.size}")
            if (list.isNotEmpty()) {
                if (tmdbOn) {
                    // Sadece sitede bulunan diziler listelenir; afişleri TMDB'den alınır (bulunamazsa sitenin afişi kalır)
                    list.take(8).amap { c ->
                        val tp = try { tmdbPosterFor(c.name) } catch (e: Exception) { null }
                        if (!tp.isNullOrBlank()) c.posterUrl = tp
                        c
                    }
                }
                val out = newSearchResponseList(list, page < maxPage(doc))
                if (searchCache.size > 30) searchCache.clear()
                searchCache[cacheKey] = out
                return out
            }
        }
        if (debug) throw ErrorLoadingException("Arama boş: " + dbgText())
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    // ---------------- Dizi sayfası ----------------

    // Bölüm adresinden gerçek dizi sayfasını bulur (dizi adresi bölüm adresindeki addan farklı olabilir)
    private suspend fun resolveSeriesUrl(url: String): String {
        reSeries.find(url)?.let { return mainUrl + it.groupValues[1] }
        if (reEp.containsMatchIn(url)) {
            val doc = try { get(abs(url)).document } catch (e: Exception) { null }
            val href = doc?.selectFirst("h1 a[href*=\"/diziler/\"]")?.attr("href")
                ?: doc?.selectFirst("a[href^=\"/diziler/\"][href$=\".html\"]")?.attr("href")
            if (!href.isNullOrBlank()) reSeries.find(href)?.let { return mainUrl + it.groupValues[1] }
            val slug = reEp.find(url)!!.groupValues[1]
            return "$mainUrl/diziler/$slug.html"
        }
        return url
    }

    // Sayfadaki TAM bölüm bağlantılarının dizi adlarını sayar (gerçek adı bulmak için)
    private fun slugVotes(doc: Document, votes: MutableMap<String, Int>) {
        for (a in doc.select("a[href]")) {
            val m = reEp.find(a.attr("href").trim()) ?: continue
            val sl = m.groupValues[1]
            votes[sl] = (votes[sl] ?: 0) + 1
        }
    }

    // Bulunan bölüm bağlantısı sayısını döndürür (sezon dolu mu boş mu anlamak için)
    private fun parseEpisodes(doc: Document, into: MutableMap<String, Episode>, slugHint: String): Int {
        var matched = 0
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            val slug: String
            val s: Int
            val e: Int
            val m = reEp.find(href)
            if (m != null) {
                slug = m.groupValues[1]
                s = m.groupValues[2].toIntOrNull() ?: continue
                e = m.groupValues[3].toIntOrNull() ?: continue
            } else {
                val b = reEpBare.find(href) ?: continue
                slug = slugHint
                s = b.groupValues[1].toIntOrNull() ?: continue
                e = b.groupValues[2].toIntOrNull() ?: continue
            }
            matched++
            val key = "$s-$e"
            val epUrl = "$mainUrl/$slug/$s-sezon-$e-bolum.html"   // dublaj bağlantısı olsa da normal adrese çevrilir
            val label = cleanLabel(a.text().trim().ifBlank { a.attr("title").trim() })
            val old = into[key]
            // Aynı bölüme giden birden fazla bağlantı olabilir ("Cevapla" gibi); ilk iyi yazıyı koru
            if (old != null && !(old.name.isNullOrBlank() && label != null)) continue
            into[key] = newEpisode(epUrl) {
                this.season = s
                this.episode = e
                this.name = label
            }
        }
        return matched
    }

    override suspend fun load(url: String): LoadResponse? {
        val seriesUrl = resolveSeriesUrl(url)
        val res = get(seriesUrl)
        val html = res.text
        val doc = res.document
        val hint = slugOf(seriesUrl)

        val title = (doc.selectFirst("h1")?.text()?.let { cleanTitle(it) }?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanTitle(it) }
            ?: cleanTitle(doc.title())).ifBlank { return null }

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[src*=\"/i/dizi/\"]")?.attr("src")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val eps = LinkedHashMap<String, Episode>()
        parseEpisodes(doc, eps, hint)
        val votes = HashMap<String, Int>()
        slugVotes(doc, votes)

        // Dizi numarası (did): sayfadaki did özelliği ya da poster adresinden (/i/dizi/y/b/2595.jpg)
        val did = Regex("""did\s*[=:]\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""/i/dizi/(?:[a-z]/)+(\d+)\.jpg""").find(html)?.groupValues?.get(1)

        if (did != null) {
            val endpoint = Regex("""/ajax/dataIzleBolumler\w*\.asp""").find(html)?.value
                ?: "/ajax/dataIzleBolumler.asp"

            suspend fun season(sno: Int): Document? = try {
                post(mainUrl + endpoint, mapOf("did" to did, "sno" to sno.toString()), seriesUrl).document
            } catch (e: Exception) {
                null
            }

            // Sezonlar 8'erli gruplar halinde paralel denenir; grubun son sezonu boşsa durulur
            var start = 1
            while (start <= 40) {
                val chunk = (start until start + 8).toList()
                val docs = chunk.amap { season(it) }
                var lastCount = 0
                docs.forEachIndexed { i, d ->
                    if (d != null) {
                        slugVotes(d, votes)
                        val c = parseEpisodes(d, eps, hint)
                        if (i == chunk.lastIndex) lastCount = c
                    }
                }
                if (lastCount == 0) break
                start += 8
            }
        }

        // Bölüm adresindeki dizi adı, dizi sayfası adresindekinden farklı olabilir
        // (örn. /diziler/yu-gi-oh-izle.html -> /yu-gi-oh-duel-monsters/1-sezon-1-bolum.html).
        // Dizi sayfasındaki, sezon yanıtlarındaki ve /bolumler/ sayfasındaki TAM bağlantılardan gerçek ad bulunur;
        // göreli bağlantılardan türetilen yanlış adlı adresler düzeltilir.
        try {
            try {
                val bol = get("$mainUrl/bolumler/" + seriesUrl.substringAfterLast("/"), seriesUrl)
                if (bol.code in 200..299) slugVotes(bol.document, votes)
            } catch (e: Exception) {
            }
            var real = votes.maxByOrNull { it.value }?.key
            if (real == null) {
                // Hiç TAM bağlantı yok (hepsi göreli): gerçek ad dizi sayfası dosya adı (örn. yu-gi-oh-izle) ya da -izle'siz ad olabilir.
                // Adaylar gerçek bir bölüm sayfası açılarak doğrulanır.
                val fileSlug = seriesUrl.substringAfterLast("/").removeSuffix(".html")
                val sample = eps.values.minWithOrNull(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                if (sample != null) {
                    for (c in listOf(hint, fileSlug).distinct()) {
                        val u = sample.data.replace("/$hint/", "/$c/")
                        val ok = try {
                            val r = get(u)
                            r.code in 200..299 && r.document.selectFirst("#dilsec") != null
                        } catch (e: Exception) {
                            false
                        }
                        if (ok) {
                            real = c
                            break
                        }
                    }
                }
            }
            if (real != null && real != hint) {
                for (k in eps.keys.toList()) {
                    val old = eps[k] ?: continue
                    if (old.data.contains("/$hint/")) {
                        eps[k] = newEpisode(old.data.replace("/$hint/", "/$real/")) {
                            this.season = old.season
                            this.episode = old.episode
                            this.name = old.name
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }

        // Liste SADECE sitede yayınlanmış bölümlerden oluşur; yeni bölüm sitede çıkınca listeye kendiliğinden eklenir.
        val siteEps = eps.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        var tm: TmdbTvDetails? = null
        var trMap: Map<Int, Map<Int, TmdbEpisode>> = emptyMap()
        var enMap: Map<Int, Map<Int, TmdbEpisode>> = emptyMap()
        if (tmdbOn && siteEps.isNotEmpty()) {
            try {
                val counts = siteEps.groupBy { it.season ?: 0 }.mapValues { it.value.size }
                tm = tmdbMatch(title, counts)
                val tmv = tm
                val tid = tmv?.id
                if (tid != null) {
                    val tmNums = tmv?.seasons.orEmpty().mapNotNull { it.season_number }.filter { it > 0 }.sorted()
                    val siteNums = counts.keys.filter { it > 0 }
                    if (tmNums.isEmpty() || siteNums.all { it in tmNums }) {
                        // Sezon düzeni aynı: sezon sezon eşle
                        val rows = siteNums.amap { sn ->
                            Triple(sn, tmdbSeason(tid, sn, "tr-TR"), tmdbSeason(tid, sn, "en-US"))
                        }
                        trMap = rows.associate { it.first to it.second }
                        enMap = rows.associate { it.first to it.third }
                    } else {
                        // Site sezonları TMDB'dekinden farklı bölünmüş (ör. site 5 sezon, TMDB tek sezon 224 bölüm):
                        // TMDB bölümleri tek sıra yapılır, sitedeki bölümler sırayla bunlara eşlenir.
                        val rows = tmNums.amap { sn ->
                            Triple(sn, tmdbSeason(tid, sn, "tr-TR"), tmdbSeason(tid, sn, "en-US"))
                        }.sortedBy { it.first }
                        val flat = ArrayList<Pair<TmdbEpisode?, TmdbEpisode?>>()
                        for ((_, trS, enS) in rows) {
                            for (n in (trS.keys + enS.keys).toSortedSet()) flat.add(Pair(trS[n], enS[n]))
                        }
                        val trM = HashMap<Int, HashMap<Int, TmdbEpisode>>()
                        val enM = HashMap<Int, HashMap<Int, TmdbEpisode>>()
                        siteEps.forEachIndexed { i, e ->
                            val p = flat.getOrNull(i) ?: return@forEachIndexed
                            val sn = e.season ?: 0
                            val en = e.episode ?: 0
                            p.first?.let { trM.getOrPut(sn) { HashMap() }[en] = it }
                            p.second?.let { enM.getOrPut(sn) { HashMap() }[en] = it }
                        }
                        trMap = trM
                        enMap = enM
                    }
                }
            } catch (e: Exception) {
            }
        }

        // Henüz yayınlanmamış bölümler (TMDB yayın tarihi bugünden sonra ya da belli değil) listeden çıkarılır
        val now = System.currentTimeMillis()
        val lastSeason = siteEps.maxOfOrNull { it.season ?: 0 } ?: 0
        val list0 = siteEps.mapNotNull { old ->
            val sn = old.season ?: 0
            val en = old.episode ?: 0
            val tr = trMap[sn]?.get(en)
            val us = enMap[sn]?.get(en)
            // Türkçe ad yoksa ("Bölüm 3" gibi) İngilizce ad, o da yoksa sitenin temizlenmiş yazısı
            val epName = tr?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: us?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                ?: old.name
            val overview = tr?.overview?.takeIf { it.isNotBlank() } ?: us?.overview?.takeIf { it.isNotBlank() }
            val still = (tr?.still_path ?: us?.still_path)?.let { "$tmdbImg/w500$it" }
            val runtime = tr?.runtime ?: us?.runtime
            val airMs = dateMs(tr?.air_date ?: us?.air_date)
            // Bölüm TMDB'de varsa: tarihi gelecekteyse gizle; tarihi hiç yoksa sadece son sezonda gizle
            if (tm != null && (tr != null || us != null)) {
                if (airMs != null && airMs > now) return@mapNotNull null
                if (airMs == null && sn == lastSeason) return@mapNotNull null
            }
            if (tm == null) old else newEpisode(old.data) {
                this.season = old.season
                this.episode = old.episode
                this.name = epName
                this.description = overview
                this.posterUrl = still
                if (runtime != null && runtime > 0) this.runTime = runtime
                if (airMs != null) this.date = airMs
            }
        }
        // Güvenlik: hepsi elenirse (TMDB hatalıysa) sitedeki tüm bölümler gösterilir
        val list = list0.ifEmpty { siteEps }

        val t = tm
        val tPoster = t?.poster_path?.let { "$tmdbImg/w500$it" }
        val tBack = t?.backdrop_path?.let { "$tmdbImg/w1280$it" }
        val tPlot = t?.overview?.takeIf { it.isNotBlank() }
        val tYear = t?.first_air_date?.take(4)?.toIntOrNull()
        val tTags = t?.genres.orEmpty().mapNotNull { it.name }.takeIf { it.isNotEmpty() }
        val tActors = t?.credits?.cast.orEmpty().filter { !it.name.isNullOrBlank() }.take(15)
            .map { c -> Actor(c.name.orEmpty(), c.profile_path?.let { p -> "$tmdbImg/w185$p" }) }

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, list) {
            this.posterUrl = tPoster ?: poster?.let { abs(it) }
            this.backgroundPosterUrl = tBack
            this.plot = tPlot ?: plot
            if (tYear != null) this.year = tYear
            if (tTags != null) this.tags = tTags
            // addActors yeni CloudStream sürümlerinde kaldırıldı; actors doğrudan atanır
            if (tActors.isNotEmpty()) this.actors = tActors.map { ActorData(actor = it) }
        }
    }

    // ---------------- Oynatma ----------------

    private val reIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private var altEndpoints: List<String>? = null
    private var embedEndpoints: List<String>? = null

    // Sitenin ajax servis adları zamanla değişiyor (dataAlternatif3, dataAlternatif22 ...).
    // Adları sayfadaki ve site.min.js içindeki kodlardan okuyup kullanıyoruz.
    private suspend fun discoverEndpoints(doc: Document, html: String) {
        if (altEndpoints != null && embedEndpoints != null) return
        val sb = StringBuilder(html)
        doc.select("script[src]").map { it.attr("src") }.filter { it.contains("site") }.take(3).forEach { src ->
            try {
                sb.append('\n').append(get(abs(src)).text)
            } catch (e: Exception) {
            }
        }
        val all = sb.toString()
        altEndpoints = Regex("""/ajax/dataAlternatif\w*\.asp""").findAll(all).map { it.value }.distinct().toList()
            .ifEmpty { listOf("/ajax/dataAlternatif3.asp") }
        embedEndpoints = Regex("""/ajax/dataEmbed\w*\.asp""").findAll(all).map { it.value }.distinct().toList()
            .ifEmpty { listOf("/ajax/dataEmbed.asp") }
    }

    // Alternatif listesi: JSON ({"data":[{"id":..,"baslik":..}]}) ya da HTML (data-id'li öğeler)
    private fun altList(txt: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val t = txt.trim()
        try {
            val arr: JSONArray? = when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    o.optJSONArray("data") ?: o.optJSONArray("veri") ?: o.optJSONArray("list")
                }
                else -> null
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val nm = o.optString("baslik").ifBlank { o.optString("name") }
                    if (id.isNotBlank()) out.add(id to nm)
                }
            }
        } catch (e: Exception) {
        }
        if (out.isEmpty()) {
            Regex("""data-id=["'](\d+)["'][^>]*>([^<]*)<""").findAll(t)
                .forEach { out.add(it.groupValues[1] to it.groupValues[2].trim()) }
        }
        return out
    }

    private val reAsset = Regex(""".*\.(js|css|png|jpe?g|gif|svg|ico|woff2?)(\?.*)?$""", RegexOption.IGNORE_CASE)

    private fun embedOf(txt0: String): String? {
        val txt = txt0.replace("\\/", "/")
        reIframe.find(txt)?.let { return it.groupValues[1] }
        // Dzen: yanıt iframe değil, "<script>var vid = 'oVUYjD9YIAAA'; ..." şeklinde bir betik; oynatıcı adresi
        // https://dzen.ru/embed/<vid> olarak kurulur. Betikte tam adres açıkça yazıyorsa o kullanılır.
        Regex("""(?:https?:)?//(?:www\.)?dzen\.ru/embed/[A-Za-z0-9_-]+""").find(txt)?.let { return it.value }
        Regex("""\bvar\s+vid\s*=\s*['"]([A-Za-z0-9_-]{6,})['"]""").find(txt)
            ?.let { return "https://dzen.ru/embed/" + it.groupValues[1] }
        // iframe yoksa src / data-src / file gibi alanlardaki adresler (göreli olanlar dahil)
        Regex("""(?:src|data-src|file|source|url)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(txt).map { it.groupValues[1] }
            .firstOrNull { (it.startsWith("http") || it.startsWith("/")) && !reAsset.matches(it) }
            ?.let { return it }
        return Regex("""(?:https?:)?//[^"'\\\s<>]+""").findAll(txt)
            .map { it.value }.firstOrNull { !it.contains("sezonlukdizi") && !reAsset.matches(it) }
    }

    // WebView yedeği yavaş olduğu için bir bölümde en fazla bu kadar kaynakta denenir
    private val webviewLeft = AtomicInteger(0)

    // Dzen'in KENDİ WebView hakkı: Pixel gibi yavaş kaynaklar webviewLeft'i bitirince Dzen'in yedeği çalışmıyordu
    private val dzenWebviewLeft = AtomicInteger(0)

    // Byse'ye ek olarak Vidmoly bağlantısı da verilsin (site bu kaynağı Turnstile kapısının arkasında tutuyor).
    // İstemezsen false yap; o zaman Vidmoly sadece Byse çalışmazsa yedek olarak denenir.
    private val vidmolyExtra = true

    // Turnstile kapısı: doğrulanmış oturum çerezleri bir süre saklanır, çözülemezse kısa bir süre tekrar denenmez
    @Volatile private var gateCookies: Map<String, String> = emptyMap()
    @Volatile private var gateOkUntil = 0L
    @Volatile private var gateFailUntil = 0L

    // Captcha kapı sayfası bölüm başına yalnızca bir kez incelenir (hız için)
    private val gateProbed = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.get(1) ?: mainUrl

    // https://filemoon.sx/e/abc -> "Filemoon"
    private fun hostLabel(u: String): String {
        val h = Regex("""^https?://(?:www\.)?([^./]+)""").find(u)?.groupValues?.get(1) ?: return "Player"
        return h.replaceFirstChar { it.uppercase() }
    }

    // Dil adını kaynak adının önüne ekler: "Dublaj - Filemoon (1080p)"
    private suspend fun relabel(l: ExtractorLink, label: String): ExtractorLink =
        newExtractorLink(l.source, "$label - ${l.name}", l.url, l.type) {
            this.referer = l.referer
            this.quality = l.quality
            this.headers = l.headers
        }

    // CDN bağlantıları (SprintCDN vb.) Referer/Origin olmadan, süresi dolunca ya da başka IP'den açılınca
    // 404 verir. Bu yüzden oynatıcıya gömülü sayfanın Referer ve Origin'i ile verilir.
    private suspend fun emitM3u8(
        m3u8: String,
        label: String,
        embed: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(embed)
        callback(
            newExtractorLink(name, "$label - ${hostLabel(embed)}", m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$origin/",
                    "Origin" to origin
                )
            }
        )
    }

    // ---- bysejikuar.com (Byse) oynatıcısı: API şifreli (AES-256-GCM) JSON döndürür ----
    private fun b64u(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return android.util.Base64.decode(t, android.util.Base64.DEFAULT)
    }

    private suspend fun extractBysejikuar(
        embed: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(embed)
        val id = embed.substringAfterLast("/e/").substringBefore("?").substringBefore("/")
        if (id.isBlank() || !embed.contains("/e/")) {
            d("byse id bulunamadı: $embed")
            return false
        }

        // Çalışan eklentideki gibi: sadece UA + Referer
        val h = mapOf("User-Agent" to ua, "Referer" to embed)
        val txt = try {
            app.get("$origin/api/videos/$id", headers = h, timeout = 15L).text
        } catch (e: Exception) {
            d("byse api hata: ${e.message}")
            return false
        }
        val p = try {
            JSONObject(txt).optJSONObject("playback")
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            d("byse playback yok: ${txt.take(120)}")
            return false
        }

        val arr = p.optJSONArray("key_parts")
        if (arr == null || arr.length() < 1) {
            d("byse key_parts yok, alanlar=${p.keys().asSequence().toList()}")
            return false
        }
        val parts = (0 until arr.length()).map { b64u(arr.getString(it)) }
        d("byse version=${p.optInt("version")} parça=${parts.size}")

        // Anahtar: 30 parçadan sadece ikisi gerçek anahtar (iki adet 16 baytlık parça, toplam 32 bayt = AES-256).
        // Loglarda gözlenen: parts[version-1] ve parts[n-version]. Emin olmak için geçerli boy veren tüm ikili
        // kombinasyonlar denenir; GCM etiketi doğru anahtarı kendiliğinden doğrular.
        val v = p.optInt("version")
        val n = parts.size
        val valid = intArrayOf(16, 24, 32)
        val cands = ArrayList<ByteArray>()
        if (v in 1..n && (n - v) in 0 until n && v - 1 != n - v) cands.add(parts[v - 1] + parts[n - v])
        for (i in parts.indices) for (j in parts.indices) {
            if (i != j && (parts[i].size + parts[j].size) in valid) cands.add(parts[i] + parts[j])
        }
        parts.forEach { if (it.size in valid) cands.add(it) }
        val keys = cands.distinctBy { it.toList() }
        if (keys.isEmpty()) {
            d("byse geçerli anahtar boyu yok")
            return false
        }

        val ivEnc = try {
            Pair(b64u(p.getString("iv")), b64u(p.getString("payload")))
        } catch (e: Exception) {
            d("byse iv/payload yok: ${e.message}")
            return false
        }
        val iv = ivEnc.first
        val enc = ivEnc.second

        var json: JSONObject? = null
        var failed = 0
        for (key in keys) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                val o = JSONObject(String(cipher.doFinal(enc), Charsets.UTF_8))
                if (o.optJSONArray("sources") != null) {
                    json = o
                    break
                }
            } catch (e: Exception) {
                failed++
            }
        }
        val srcs = json?.optJSONArray("sources")
        if (srcs == null) {
            d("byse çözülemedi, denenen anahtar=${keys.size} hatalı=$failed")
            return false
        }

        val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        var ok = false
        for (i in 0 until srcs.length()) {
            val o = srcs.optJSONObject(i) ?: continue
            val url = o.optString("url").replace("\\/", "/")
            if (!url.startsWith("http")) continue
            val ql = o.optString("label").ifBlank { "1080p" }
            val tag = "$label ($ql)"
            val mime = o.optString("mime_type")
            if (url.contains(".m3u8") || mime.contains("mpegurl", true)) {
                val links = try {
                    M3u8Helper.generateM3u8(name, url, "$origin/", headers = headers)
                } catch (e: Exception) {
                    emptyList()
                }
                if (links.isNotEmpty()) links.forEach { callback(relabel(it, tag)) }
                else emitM3u8(url, tag, embed, callback)
            } else {
                callback(
                    newExtractorLink(name, "$tag - Byse", url, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = getQualityFromName(ql)
                        this.headers = headers
                    }
                )
            }
            ok = true
        }
        d("byse kaynak sayısı=${srcs.length()} ok=$ok")
        return ok
    }

    // ---- Vidmoly (vidmoly.biz / .to / .me / .net): oynatıcı sayfasında açık .m3u8 var ----
    // Örnek adres: https://vidmoly.biz/embed-m90baavtu9m6.html
    private suspend fun extractVidmoly(
        embed: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host0 = Regex("""^https?://([^/]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val id = Regex("""(?:embed-|/w/|/e/|/embed/)([A-Za-z0-9]{6,})""").find(embed)?.groupValues?.get(1)
        // Alan adı sık değişir: önce gelen adres, olmazsa aynı kimlikle diğer alan adları denenir
        val hosts = (listOf(host0) + listOf("vidmoly.biz", "vidmoly.to", "vidmoly.me", "vidmoly.net")).distinct()

        for (host in hosts) {
            val url = when {
                host == host0 -> embed
                id != null -> "https://$host/embed-$id.html"
                else -> continue
            }
            val origin = "https://$host"
            val html = try {
                app.get(
                    url,
                    referer = "$mainUrl/",
                    headers = mapOf("User-Agent" to ua, "Sec-Fetch-Dest" to "iframe"),
                    timeout = 12L
                ).text
            } catch (e: Exception) {
                d("vidmoly $host hata: ${e.javaClass.simpleName}")
                continue
            }
            val clean = html.replace("\\/", "/")
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(clean)?.groupValues?.get(1)
                ?: Regex("""["'](https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)["']""").find(clean)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) {
                d("vidmoly $host m3u8 yok uzunluk=${html.length} '${html.replace(Regex("""\s+"""), " ").take(60)}'")
                continue
            }
            val link = m3u8.replace("&amp;", "&")
            val headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
            // Sayfada: label: "720p HD"
            val q = Regex("""label\s*:\s*["'](\d{3,4})p""").find(clean)?.groupValues?.get(1)
            val tag = if (q != null) "$label ${q}p" else label

            // Ana (master) liste: oynatıcı kaliteyi kendi seçer
            emitM3u8(link, tag, "$origin/", callback)
            // Kalite adlarını (720p vb.) görebilmek için alt listeler de eklenir
            try {
                M3u8Helper.generateM3u8(name, link, "$origin/", headers = headers)
                    .forEach { callback(relabel(it, label)) }
            } catch (e: Exception) {
            }
            d("vidmoly $host tamam")
            return true
        }
        return false
    }

    // ---- Dzen (dzen.ru/embed/<id>): oynatıcı sayfasında akış adresleri açık yazılı ----
    // Örnek: https://dzen.ru/embed/oVUYjD9YIAAA
    // Oynatıcı sayfası (Referer: sezonlukdizi) içinde Yandex'in VMAP reklam adresleri ile birlikte gerçek video
    // adresleri de bulunur: okcdn.ru (OK.ru CDN) üzerinden DASH bildirimi (...&dzen_dash=dash) ve MP4
    // (...&type=2...), bazen strm.yandex.ru HLS (.m3u8). Reklam (yandex.ru/ads) adresleri atlanır; reklam
    // oynatılmadan doğrudan video verilir. CDN adresleri imzalıdır (expires + srcIp): bu yüzden oynatıcı
    // sayfası, videoyu oynatacak cihazla AYNI ağdan istenir. Referer/Origin dzen.ru olmalı.
    private fun dzUnescape(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\/", "/")
            .replace("&amp;", "&")

    private suspend fun extractDzen(
        embed: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = "https://dzen.ru"
        val hdrs = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
        val seenUrls = HashSet<String>()
        var m3u8Cnt = 0
        var dashCnt = 0
        var mp4Cnt = 0

        // Adresi türüne göre (HLS / DASH / MP4) bağlantı olarak verir
        suspend fun add(u0: String): Boolean {
            val u = u0.replace(Regex("""&bytes=[^&]*"""), "")   // aralık (bytes=a-b) parçası değil, tüm dosya
            if (!seenUrls.add(u)) return false
            return when {
                u.contains(".m3u8") -> {
                    if (m3u8Cnt >= 2) return false
                    m3u8Cnt++
                    emitM3u8(u, label, "$origin/", callback)
                    true
                }
                u.contains(".mpd") || u.contains("dzen_dash") -> {
                    if (dashCnt >= 1) return false
                    dashCnt++
                    callback(
                        newExtractorLink(name, "$label - Dzen DASH", u, ExtractorLinkType.DASH) {
                            this.referer = "$origin/"
                            this.quality = Qualities.Unknown.value
                            this.headers = hdrs
                        }
                    )
                    true
                }
                else -> {
                    if (mp4Cnt >= 2) return false
                    mp4Cnt++
                    val nm = if (mp4Cnt == 1) "$label - Dzen MP4" else "$label - Dzen MP4 $mp4Cnt"
                    callback(
                        newExtractorLink(name, nm, u, ExtractorLinkType.VIDEO) {
                            this.referer = "$origin/"
                            this.quality = Qualities.Unknown.value
                            this.headers = hdrs
                        }
                    )
                    true
                }
            }
        }

        // Metindeki medya adreslerini bulur (JSON kaçışları \u002F, \/, \u0026 ve &amp; çözülür)
        fun media(txt: String): List<String> =
            Regex("""https?://[^"'\s<>\\]+""").findAll(dzUnescape(txt))
                .map { it.value.trimEnd(',', ';', ')') }
                .filter { u ->
                    !u.contains("/ads/") && !u.contains("yandex.ru/ads") && !u.contains("adfox") &&
                        (u.contains(".m3u8") || u.contains(".mpd") || u.contains("dzen_dash") ||
                            Regex("""(?:okcdn\.ru|mycdn\.me|vkuser\.net)/\?""").containsMatchIn(u) ||
                            (u.contains("strm.yandex") && !u.contains("/ads")) ||
                            Regex("""\.mp4(\?|$)""").containsMatchIn(u))
                }
                .toList()

        // Oynatıcı sayfasını birkaç farklı başlıkla dener (Referer sezonlukdizi / dzen.ru); ilk medya adresi bulunan kazanır
        var html = ""
        var found: List<String> = emptyList()
        val tries = listOf(
            Triple("sd", "$mainUrl/", ua),
            Triple("dz", "$origin/", ua),
            Triple("mb", "$mainUrl/", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        )
        for ((tag, ref, agent) in tries) {
            try {
                val r = app.get(
                    embed,
                    referer = ref,
                    headers = mapOf(
                        "User-Agent" to agent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "ru-RU,ru;q=0.9,tr;q=0.8,en;q=0.7",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "cross-site"
                    ),
                    timeout = 15L
                )
                html = r.text
                found = media(html)
                val cap = html.contains("showcaptcha", true) || html.contains("smartcaptcha", true) ||
                    r.url.contains("captcha", true)
                d("dzen[$tag] kod=${r.code} url=${r.url.take(70)} uzunluk=${html.length} adres=${found.size} captcha=$cap '${html.replace(Regex("""\s+"""), " ").take(70)}'")
                if (found.isNotEmpty()) break
            } catch (e: Exception) {
                d("dzen[$tag] hata ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (found.isEmpty() && html.isNotEmpty()) {
            // Sayfada adres yoksa sayfanın hangi anahtar sözcükleri içerdiğini kaydet (teşhis için)
            val kw = listOf("okcdn", "m3u8", "mpd", "mp4", "strm.yandex", "streams", "videos", "vmap", "captcha")
                .filter { html.contains(it, true) }
            d("dzen sayfada anahtarlar=$kw")
        }
        // Önce HLS ve DASH, sonra MP4 (DASH tüm kaliteleri içerir; MP4 yedek)
        var ok = false
        for (u in found.sortedBy { if (it.contains(".m3u8")) 0 else if (it.contains(".mpd") || it.contains("dzen_dash")) 1 else 2 }) {
            if (add(u)) ok = true
        }
        if (ok) {
            d("dzen tamam hls=$m3u8Cnt dash=$dashCnt mp4=$mp4Cnt")
            return true
        }

        // Yedek: sayfa adresleri açık yazmıyorsa görünmez WebView'de çalıştırıp ağdan geçen ilk medya isteğini yakala.
        // useOkhttp = false: sayfa WebView'in kendi ağ yığınıyla açılır (gerçek tarayıcı gibi), kendi UA'sı kullanılır.
        if (dzenWebviewLeft.getAndDecrement() > 0) {
            try {
                val r = app.get(
                    embed,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(
                        Regex("""(?:okcdn\.ru|mycdn\.me|vkuser\.net)/\?|\.m3u8|\.mpd|strm\.yandex"""),
                        userAgent = null,
                        useOkhttp = false,
                        timeout = 25_000L
                    )
                )
                val hit = media(r.url).firstOrNull()
                d("dzen webview url=${r.url.take(140)} -> ${hit != null}")
                if (hit != null && add(hit)) return true
            } catch (e: Exception) {
                d("dzen webview ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            d("dzen webview hakkı bitti")
        }
        return false
    }

    // Bir gömülü oynatıcı adresini çözer. Sırayla:
    // 1) CloudStream'in hazır çözücüleri (Filemoon, Vidmoly, Ok.ru ...)
    // 2) Sayfayı CloudflareKiller ile açıp içindeki .m3u8 adresini bulmak
    // 3) Görünmez WebView ile sayfayı çalıştırıp ağdan geçen .m3u8'i yakalamak
    private suspend fun emitEmbed(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val u = when {
            embed.startsWith("//") -> "https:$embed"
            embed.startsWith("/") -> mainUrl + embed
            else -> embed
        }
        if (!u.startsWith("http")) return false

        // 0) Byse (bysejikuar.com): CloudStream'de hazır çözücü yok, kendi çözücümüz
        if (u.contains("byse", ignoreCase = true)) {
            val okByse = try {
                extractBysejikuar(u, label, callback)
            } catch (e: Exception) {
                d("byse istisna ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (okByse) return true
        }

        // 0b) Vidmoly: kendi çözücümüz; olmazsa aşağıdaki hazır çözücüye düşer
        if (u.contains("vidmoly", ignoreCase = true)) {
            val okVm = try {
                extractVidmoly(u, label, callback)
            } catch (e: Exception) {
                d("vidmoly istisna ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (okVm) return true
        }

        // 0c) Dzen (dzen.ru/embed/<id>): kendi çözücümüz
        if (u.contains("dzen.ru", ignoreCase = true) && u.contains("/embed/", ignoreCase = true)) {
            return try {
                extractDzen(u, label, callback)
            } catch (e: Exception) {
                d("dzen istisna ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        // 1) hazır çözücüler
        val tmp = ArrayList<ExtractorLink>()
        try {
            loadExtractor(u, "$mainUrl/", subtitleCallback) { synchronized(tmp) { tmp.add(it) } }
        } catch (e: Exception) {
        }
        if (tmp.isNotEmpty()) {
            tmp.forEach { callback(relabel(it, label)) }
            return true
        }

        // 2) CloudflareKiller ile sayfayı aç, .m3u8 ara
        try {
            val page = app.get(u, referer = "$mainUrl/", timeout = 10L)
                .text.replace("\\/", "/")
            val m = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""").find(page)
            if (m != null) {
                emitM3u8(m.value.replace("&amp;", "&"), label, u, callback)
                return true
            }
        } catch (e: Exception) {
        }

        // 3) WebView ile yakala
        if (webviewLeft.getAndDecrement() > 0) {
            try {
                val r = app.get(
                    u,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(Regex("""\.m3u8"""), timeout = 15_000L)
                )
                if (r.url.contains(".m3u8")) {
                    emitM3u8(r.url, label, u, callback)
                    return true
                }
            } catch (e: Exception) {
            }
        }
        return false
    }

    // Sayfadaki oynatıcı adresleri (öncelik sırasıyla). iframe src yerine data-src de olabilir;
    // hiç gerçek oynatıcı yoksa sayfa kaynağında Byse adresi ve diğer iframe'ler de aranır.
    private fun pageEmbeds(doc: Document, html: String): List<String> {
        val out = LinkedHashSet<String>()
        for (e in doc.select("iframe#hs, #embed iframe")) {
            listOf(e.attr("src"), e.attr("data-src")).forEach { if (it.isNotBlank()) out.add(it.trim()) }
        }
        if (out.none { !it.contains("reCAPTCHA", true) }) {
            Regex("""(?:https?:)?//[^"'\\\s<>]*byse[^"'\\\s<>]*/e/[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(html.replace("\\/", "/")).forEach { out.add(it.value) }
            for (e in doc.select("iframe")) {
                val u = listOf(e.attr("src"), e.attr("data-src")).firstOrNull { it.isNotBlank() }?.trim() ?: continue
                if (u.startsWith("about:")) continue
                if (listOf("google", "facebook", "youtube", "doubleclick", "histats").any { u.contains(it, true) }) continue
                out.add(u)
            }
        }
        return out.toList().take(3)
    }

    // Kapı sayfası (/ajax/reCAPTCHADATA.asp) Cloudflare Turnstile yükler; token çözülünce
    // $.post("/ajax/reCAPTCHA.asp", {'cf-turnstile-response': token}) ile oturumu doğrular.
    // Kapı adresi (dataEmbed'in döndürdüğü tam adres) görünmez WebView'de açılır ve kendi kendine çözmesi için süre tanınır.
    // ÖNEMLİ: useOkhttp = false. WebView kendi ağ yığınını ve çerez yöneticisini kullanır; böylece Turnstile
    // gerçek tarayıcı gibi çalışır ve doğrulama çerezi WebView'in CookieManager'ına yazılır.
    // Oturum çerezleri WebView'e verilir, sonra WebView'in çerezleri geri alınır. Sonucu çağıran taraf,
    // aynı kaynağı yeniden isteyerek doğrular.
    private suspend fun solveGate(ref: String, gateUrl: String? = null) {
        val gate = gateUrl?.let { g ->
            when {
                g.startsWith("//") -> "https:$g"
                g.startsWith("/") -> mainUrl + g
                else -> g
            }
        } ?: "$mainUrl/ajax/reCAPTCHADATA.asp"
        val cm = try { android.webkit.CookieManager.getInstance() } catch (e: Throwable) { null }
        try {
            cm?.setAcceptCookie(true)
            sessionCookies.forEach { (k, v) -> cm?.setCookie(mainUrl, "$k=$v; Path=/") }
            cm?.flush()
        } catch (e: Throwable) {
            d("captcha çerez hata ${e.javaClass.simpleName}")
        }
        d("captcha kapı=${gate.take(140)}")
        val t0 = System.currentTimeMillis()
        try {
            app.get(
                gate,
                headers = hdr(),
                referer = ref,
                cookies = sessionCookies,
                interceptor = WebViewResolver(
                    Regex("""zz-asla-eslesmez-zz"""),
                    userAgent = null,      // eskiden: ua
                    useOkhttp = false,
                    timeout = 12_000L      // eskiden: 18_000L
                )
            )
        } catch (e: Exception) {
            d("captcha webview ${e.javaClass.simpleName}")
        }
        try {
            val before = sessionCookies.size
            cm?.getCookie(mainUrl)?.split(";")?.forEach { c ->
                val kv = c.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) sessionCookies = sessionCookies + (kv[0] to kv[1])
            }
            d("captcha çözüm denendi süre=${System.currentTimeMillis() - t0}ms çerez $before->${sessionCookies.size} adlar=${sessionCookies.keys.joinToString(",").take(80)}")
        } catch (e: Throwable) {
            d("captcha çerez geri alma hata ${e.javaClass.simpleName}")
        }
    }

    // reCAPTCHA kapısı dönen adresin içinde ya da (oturum çerezleriyle açılan) sayfasında gerçek oynatıcı adresi var mı bakar
    private suspend fun captchaInner(u: String, ref: String, force: Boolean = false): String? {
        val full = when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl + u
            else -> u
        }
        val dec = try { java.net.URLDecoder.decode(full, "UTF-8") } catch (e: Exception) { full }
        Regex("""https?://[^\s"'&<>]+""").findAll(dec.substringAfter("?", "")).map { it.value }
            .firstOrNull { !it.contains("sezonlukdizi", true) && !it.contains("recaptcha", true) }
            ?.let { return it }
        // Kapı sayfasını bölüm başına BİR kez açıp yapısını hata ayıklamaya yazar; gerçek oynatıcı adresi
        // sayfada açıkça yazıyorsa (Vidmoly vb.) onu kullanır.
        if (force || gateProbed.compareAndSet(false, true)) {
            try {
                val h = app.get(full, headers = hdr(), referer = ref, cookies = sessionCookies, timeout = 10L).text
                val ajax = Regex("""/ajax/[A-Za-z0-9_]+\.asp""").findAll(h).map { it.value }.distinct().take(6).toList()
                val key = Regex("""sitekey["']?\s*[:=]\s*["']([^"']+)""").find(h)?.groupValues?.get(1)
                val post = Regex("""\$\.(?:post|ajax)\([^;]{0,150}""").find(h)?.value?.replace(Regex("""\s+"""), " ")
                d("captcha sayfa uzunluk=${h.length} ajax=$ajax sitekey=$key post=$post")
                d("captcha sayfa başı='${h.replace(Regex("""\s+"""), " ").take(110)}'")
                Regex("""https?://[^\s"'<>\\]*(?:vidmoly|embed-)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(h.replace("\\/", "/"))?.value?.let { return it }
            } catch (e: Exception) {
                d("captcha sayfa hata ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    // Genel çözücülerden (Ok.ru vb.) gelen bağlantı gerçekten açılıyor mu? İmzasız okcdn adresleri 400 döndürüyor ve
    // bozuk bağlantı o dilin yerini kapatıp sıradaki (Dzen gibi) çalışan kaynağın denenmesini engelliyordu.
    // Sadece açık HTTP hatası (400-599) bozuk sayılır; zaman aşımı / istisna durumunda bağlantı korunur.
    // Byse / Filemoon / Vidmoly / Dzen kendi çözücüleriyle çalıştığı için kontrol edilmez.
    private suspend fun linkAlive(buf: List<ExtractorLink>, embed: String): Boolean {
        if (buf.isEmpty()) return false
        if (listOf("byse", "filemoon", "vidmoly", "dzen.ru").any { embed.contains(it, true) }) return true
        val probe = buf.distinctBy { it.url }.take(2)
        var anyOk = false
        var bad = 0
        for (l in probe) {
            try {
                val r = app.get(
                    l.url,
                    headers = l.headers + mapOf("Range" to "bytes=0-1023"),
                    referer = l.referer,
                    timeout = 4L
                )
                if (r.code in 400..599) {
                    bad++
                    d("bağlantı bozuk kod=${r.code} ${l.url.take(80)}")
                } else anyOk = true
            } catch (e: Exception) {
                anyOk = true
            }
        }
        return anyOk || bad == 0
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        webviewLeft.set(2)
        dzenWebviewLeft.set(2)
        gateProbed.set(false)
        dbg.clear()
        sessionCookies = if (System.currentTimeMillis() < gateOkUntil) gateCookies else emptyMap()

        fun dilName(x: String) = if (x == "0") "Dublaj" else "Altyazı"
        var any = false
        val seen = HashSet<String>()
        val captchaSeen = HashSet<String>()
        val got = HashSet<String>()            // kaynağı bulunan diller: "0" = dublaj, "1" = altyazı
        val retryQ = ArrayList<Triple<String, String, String>>()   // captcha / boş dönenler: (dil, id, referer)
        val emitted = HashSet<String>()        // bağlantısı listeye verilen diller (dil başına tek bağlantı)
        val okCnt = HashMap<String, Int>()     // dil başına çalışan kaynak sayısı
        val srcLog = ArrayList<String>()       // kaynak başına kısa sonuç (hata ayıklama)
        val pageLog = ArrayList<String>()

        fun short(x: String?, n: Int = 50): String =
            (x ?: "").replace(Regex("""\s+"""), " ").trim().take(n)

        // Bir kaynağın topladığımız bağlantılarından dil başına TEK, temiz adlı bağlantı verir:
        // "Türkçe Dublaj (720p)" / "Türkçe Altyazı (720p)". Dublaj üstte çıksın diye kalitesi biraz yüksek tutulur.
        suspend fun flush(dil: String, buf: List<ExtractorLink>): Boolean {
            if (buf.isEmpty()) return false
            if (dil in emitted) return true
            val pick = buf.firstOrNull { it.url.contains("master.m3u8", true) }
                ?: buf.firstOrNull { it.name.contains("Dzen") && it.url.contains(".m3u8") }
                ?: buf.firstOrNull { it.name.contains("Dzen DASH") }
                ?: buf.maxByOrNull { it.quality }
                ?: buf.first()
            val res = buf.mapNotNull { Regex("""(\d{3,4})p""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull()
            val linkName = "Türkçe ${dilName(dil)}" + (res?.let { " (${it}p)" } ?: "")
            callback(
                newExtractorLink(pick.source, linkName, pick.url, pick.type) {
                    this.referer = pick.referer
                    this.quality = if (dil == "0") Qualities.P1080.value else Qualities.P720.value
                    this.headers = pick.headers
                }
            )
            emitted.add(dil)
            // Dzen: seçilen bağlantıya ek olarak diğer biçimler (MP4 / DASH / HLS) de ayrı bağlantı olarak verilir
            buf.filter { it !== pick && it.name.contains("Dzen") }.forEach { l ->
                callback(
                    newExtractorLink(l.source, "Türkçe ${dilName(dil)} - " + l.name.substringAfter(" - "), l.url, l.type) {
                        this.referer = l.referer
                        this.quality = l.quality
                        this.headers = l.headers
                    }
                )
            }
            return true
        }

        // Bir gömülü oynatıcıyı verilen dil etiketiyle yayınlar (captcha kapısıysa içindeki adresi arar)
        suspend fun emit(dil: String, embed0: String): Boolean {
            var embed = embed0
            if (embed.isBlank()) return false
            if (embed.contains("reCAPTCHA", true)) {
                if (!captchaSeen.add(embed)) return false
                val inner = try { captchaInner(embed, "$mainUrl/") } catch (e: Exception) { null }
                d("captcha iç=$inner")
                if (inner.isNullOrBlank()) return false
                embed = inner
            }
            if (!seen.add(embed)) return false
            val buf = ArrayList<ExtractorLink>()
            val collect: (ExtractorLink) -> Unit = { l -> synchronized(buf) { buf.add(l) } }
            val ok: Boolean
            // Doğrudan .m3u8 adresi verilmişse hazır çözücüye gerek yok
            if (Regex("""\.m3u8(\?|$)""").containsMatchIn(embed)) {
                val full = abs(embed)
                emitM3u8(full, dilName(dil), full, collect)
                ok = linkAlive(buf, full) && flush(dil, buf)
            } else {
                val r = emitEmbed(embed, dilName(dil), subtitleCallback, collect)
                ok = r && linkAlive(buf, embed) && flush(dil, buf)
            }
            d("kaynak ${dilName(dil)} $embed -> $ok")
            if (ok) {
                any = true
                got.add(dil)
                okCnt[dil] = (okCnt[dil] ?: 0) + 1
            }
            return ok
        }

        // Cloudflare hız sınırı ("error code: 1015") yanıtı mı?
        fun rl(x: String) = x.contains("1015") || x.contains("rate limit", true) || x.contains("too many", true)

        // Hız sınırında beklemek için toplam süre bütçesi (ms)
        var waitBudget = 40_000L

        // Kaynak id'sinden gömülü oynatıcı adresini ister: (adres, ham yanıt).
        // Cloudflare 1015 dönerse bekleyip aynı kaynağı tekrar dener.
        suspend fun fetchEmbed(id: String, ref: String): Pair<String?, String> {
            var found: String? = null
            var raw = ""
            for (attempt in 0..2) {
                for (ep in embedEndpoints.orEmpty()) {
                    val r = try {
                        post(mainUrl + ep, mapOf("id" to id), ref).text
                    } catch (e: Exception) {
                        "HATA ${e.javaClass.simpleName}"
                    }
                    raw = r
                    found = embedOf(r)
                    if (found != null) break
                }
                if (found != null || !rl(raw) || waitBudget <= 0) break
                val w = 5000L + attempt * 3000L
                waitBudget -= w
                d("hız sınırı id=$id bekle=${w}ms")
                try { Thread.sleep(w) } catch (e: Exception) { }
            }
            return Pair(found, raw)
        }

        // Bir kaynağı dener ve sonucunu kısa bir satır olarak kaydeder
        var gatedSeen = false
        var lastGateUrl: String? = null
        suspend fun tryId(dil: String, id: String, ref: String, tagPrefix: String, nm: String = "", idx: Int = -1) {
            val (f, raw) = fetchEmbed(id, ref)
            val tag = "$tagPrefix$dil:$id"
            if (f.isNullOrBlank()) {
                srcLog.add("$tag BOŞ[${short(raw)}]")
                retryQ.add(Triple(dil, id, ref))
                return
            }
            val cap = f.contains("reCAPTCHA", true)
            val host = if (cap) "CAPTCHA" else (Regex("""^(?:https?:)?//([^/]+)""").find(f)?.groupValues?.get(1)
                ?: short(f, 25))
            if (cap) {
                gatedSeen = true
                lastGateUrl = f
            }
            val ok = emit(dil, f)
            if (cap && !ok) retryQ.add(Triple(dil, id, ref))
            if (ok) {
                if (nm.isNotBlank()) goodKeys.add("n:${nm.trim().lowercase()}")
                if (idx >= 0) goodKeys.add("i:$dil:$idx")
            }
            srcLog.add("$tag $host ${if (ok) "OK" else "yok"}")
        }

        // 1) Hem normal (altyazı) hem /dublaj/ bölüm sayfası açılır; her ikisindeki açık oynatıcı kendi diliyle eklenir.
        val m = reEp.find(data)
        val pageUrls = if (m != null) {
            val slug = m.groupValues[1]
            val s = m.groupValues[2]
            val e = m.groupValues[3]
            listOf(
                "$mainUrl/$slug/$s-sezon-$e-bolum.html",
                "$mainUrl/$slug/dublaj/$s-sezon-$e-bolum.html"
            )
        } else listOf(data)

        val pages = ArrayList<Triple<String, String, String>>()   // (sayfa adresi, bid, aktif dil)

        // Verilen bölüm sayfalarını (normal + /dublaj/) açar; bid ve açık oynatıcıları toplar
        suspend fun processPages(urls: List<String>) {
            // İki sayfa aynı anda istenir (hız için)
            val fetched = urls.amap { pu ->
                try {
                    Pair(pu, get(pu))
                } catch (e: Exception) {
                    d("sayfa hata ${pu.takeLast(40)} ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
            for (item in fetched) {
                if (item == null) {
                    pageLog.add("HATA")
                    continue
                }
                val pu = item.first
                val res = item.second
                if (res.code !in 200..299) {
                    d("sayfa kod=${res.code} $pu")
                    pageLog.add("kod=${res.code}")
                    continue
                }
                if (res.cookies.isNotEmpty()) sessionCookies = sessionCookies + res.cookies
                val doc = res.document
                val bid = doc.selectFirst("#dilsec")?.attr("data-id").orEmpty()
                val activeDil = doc.selectFirst("#dilsec .item.active")?.attr("data-dil")
                    ?.takeIf { it == "0" || it == "1" }
                    ?: if (res.url.contains("/dublaj/")) "0" else "1"
                discoverEndpoints(doc, res.text)
                val embeds = pageEmbeds(doc, res.text)
                d("sayfa kod=${res.code} bid='$bid' dil=$activeDil açık=${embeds.size}")
                pageLog.add("dil=$activeDil bid=$bid açık=${embeds.size} çerez=${sessionCookies.size}")
                pages.add(Triple(pu, bid, activeDil))
                for (f in embeds) emit(activeDil, f)
                try { Thread.sleep(250) } catch (e: Exception) { }
            }
        }

        processPages(pageUrls)

        // Bölüm adresindeki dizi adı yanlışsa (sayfa 404 / bid yok): gerçek adı dizi sayfasından ve aramadan bulup yeniden dene
        if (m != null && pages.none { it.second.isNotBlank() }) {
            val slug0 = m.groupValues[1]
            val sNo = m.groupValues[2]
            val eNo = m.groupValues[3]
            val serieCands = LinkedHashSet<String>()
            serieCands.add("$mainUrl/diziler/$slug0-izle.html")
            serieCands.add("$mainUrl/diziler/$slug0.html")
            try {
                val txt = post("$mainUrl/ajax/arama.asp", mapOf("q" to slug0.replace('-', ' ')), "$mainUrl/").text
                Regex("""["'](/diziler/[^"'\\\s]+\.html)["']""").findAll(txt.replace("\\/", "/"))
                    .map { mainUrl + it.groupValues[1] }.take(3).forEach { serieCands.add(it) }
            } catch (e: Exception) {
                d("arama hata ${e.message}")
            }
            var realUrls: List<String> = emptyList()
            for (su in serieCands) {
                val docs = ArrayList<Document>()
                try {
                    val r1 = get(su)
                    if (r1.code in 200..299) docs.add(r1.document)
                } catch (e: Exception) {
                }
                try {
                    val r2 = get("$mainUrl/bolumler/" + su.substringAfterLast("/"), su)
                    if (r2.code in 200..299) docs.add(r2.document)
                } catch (e: Exception) {
                }
                var found: String? = null
                for (dc in docs) {
                    for (a in dc.select("a[href]")) {
                        val mm = reEp.find(a.attr("href").trim()) ?: continue
                        if (mm.groupValues[2] == sNo && mm.groupValues[3] == eNo) {
                            found = mm.groupValues[1]
                            break
                        }
                    }
                    if (found != null) break
                }
                // Bağlantılar göreli ("1-sezon-1-bolum.html") ise dizi sayfası adresindeki ad kullanılır (örn. yu-gi-oh-izle)
                if (found == null && docs.isNotEmpty()) {
                    val fileSlug = su.substringAfterLast("/").removeSuffix(".html")
                    val hasBare = docs.any { dc ->
                        dc.select("a[href]").any { a ->
                            val b = reEpBare.find(a.attr("href").trim())
                            b != null && b.groupValues[1] == sNo && b.groupValues[2] == eNo
                        }
                    }
                    if (hasBare) found = fileSlug
                }
                if (found != null && found != slug0) {
                    d("gerçek ad bulundu: $found (eski $slug0)")
                    realUrls = listOf(
                        "$mainUrl/$found/$sNo-sezon-$eNo-bolum.html",
                        "$mainUrl/$found/dublaj/$sNo-sezon-$eNo-bolum.html"
                    )
                    break
                }
            }
            if (realUrls.isNotEmpty()) processPages(realUrls)

            // Hâlâ yoksa doğrudan olası adlar denenir: ad-izle ve -izle'siz ad
            if (pages.none { it.second.isNotBlank() }) {
                for (cand in listOf("$slug0-izle", slug0.removeSuffix("-izle")).distinct().filter { it != slug0 }) {
                    processPages(
                        listOf(
                            "$mainUrl/$cand/$sNo-sezon-$eNo-bolum.html",
                            "$mainUrl/$cand/dublaj/$sNo-sezon-$eNo-bolum.html"
                        )
                    )
                    if (pages.any { it.second.isNotBlank() }) {
                        d("doğrudan ad bulundu: $cand")
                        break
                    }
                }
            }
        }

        // 2) Alternatif kaynak listeleri alınır. Çalışan kaynaklar (Byse / daha önce çalışanlar) öne alınır,
        //    sonra iki dil sırayla (altyazı, dublaj, altyazı, dublaj ...) denenir; bir dilde kaynak bulununca o dil durur.
        //    Böylece iki dil de çabuk gelir ve Cloudflare hız sınırına takılmaz.
        fun prio(dil: String, idx: Int, nm: String): Int {
            val n = nm.trim().lowercase()
            return when {
                n.contains("byse") || n.contains("filemoon") || n.contains("dzen") -> 0   // eski dosyadaki sıra: Dzen, Filemoon ile aynı öncelik
                n.contains("okru") || n.contains("ok.ru") -> 4
                "i:$dil:$idx" in goodKeys -> 1
                n.isNotBlank() && "n:$n" in goodKeys -> 2
                else -> 3
            }
        }

        val queues = HashMap<String, List<Triple<String, String, Int>>>()
        val refs = HashMap<String, String>()
        for (dil in listOf("0", "1")) {
            val refUrl = pages.map { it.first }.firstOrNull { (dil == "0") == it.contains("/dublaj/") }
                ?: pages.firstOrNull()?.first ?: data
            val bid = pages.firstOrNull { it.first == refUrl }?.second?.takeIf { it.isNotBlank() }
                ?: pages.map { it.second }.firstOrNull { it.isNotBlank() }
                ?: continue

            var alts: List<Pair<String, String>> = emptyList()
            for (ep in altEndpoints.orEmpty()) {
                var txt = ""
                for (att in 0..2) {
                    txt = try {
                        post(mainUrl + ep, mapOf("bid" to bid, "dil" to dil), refUrl).text
                    } catch (e: Exception) {
                        ""
                    }
                    if (!rl(txt) || waitBudget <= 0) break
                    waitBudget -= 5000L
                    try { Thread.sleep(5000L) } catch (e: Exception) { }
                }
                alts = altList(txt)
                if (alts.isEmpty()) d("alt boş dil=$dil yanıt='${short(txt, 60)}'")
                if (alts.isNotEmpty()) break
            }
            d("bid=$bid dil=$dil alt=${alts.size} adlar=${alts.joinToString(",") { short(it.second, 12) }}")
            srcLog.add("$dil: alt=${alts.size} adlar=${alts.joinToString(",") { short(it.second, 12) }}")
            refs[dil] = refUrl
            queues[dil] = alts.mapIndexed { i, a -> Triple(a.first, a.second, i) }
                .sortedWith(compareBy({ prio(dil, it.third, it.second) }, { it.third }))
        }

        val pos = HashMap<String, Int>()
        var more = true
        while (more) {
            more = false
            for (dil in listOf("0", "1")) {
                if (dil in got) continue
                val q = queues[dil] ?: continue
                val i = pos[dil] ?: 0
                if (i >= q.size) continue
                pos[dil] = i + 1
                more = true
                val cand = q[i]
                tryId(dil, cand.first, refs[dil] ?: data, "", cand.second, cand.third)
                try { Thread.sleep(400) } catch (e: Exception) { }
            }
        }

        // 3) Hâlâ kaynağı çıkmayan dil varsa captcha'ya takılan / boş dönen kaynaklar bekleyerek yeniden denenir
        if (gatedSeen && listOf("0", "1").any { it !in got } && System.currentTimeMillis() >= gateFailUntil) {
            solveGate(refs.values.firstOrNull() ?: data, lastGateUrl)
        }
        for (dil in listOf("0", "1")) {
            if (dil in got) continue
            val todo = retryQ.filter { it.first == dil }.take(4)
            for (attempt in 1..2) {
                if (dil in got) break
                for ((_, id, ref) in todo) {
                    if (dil in got) break
                    try { Thread.sleep(1500L * attempt) } catch (e: Exception) { }
                    tryId(dil, id, ref, "R$attempt ")
                }
            }
        }

        // 4) Vidmoly: Byse'ye ek bağlantı. Site kaynağı Turnstile kapısının arkasında tutuyorsa:
        //    (a) kapı adresinin içinde / kapı sayfasında gerçek adres aranır,
        //    (b) olmazsa kapının tam adresi WebView'de açılıp çözülür (useOkhttp = false), adres yeniden istenir.
        if (vidmolyExtra) {
            for (dil in listOf("0", "1")) {
                val cand = queues[dil]?.firstOrNull { it.second.contains("vidmoly", true) } ?: continue
                val ref = refs[dil] ?: data
                var f = fetchEmbed(cand.first, ref).first
                if (f != null && f.contains("reCAPTCHA", true)) {
                    val gateUrl: String = f
                    d("vidmoly kapı url=${short(gateUrl, 170)}")
                    // (a) adresin içinde / sayfasında gerçek adres var mı
                    var inner: String? = try { captchaInner(gateUrl, ref, true) } catch (e: Exception) { null }
                    if (inner.isNullOrBlank()) {
                        if (System.currentTimeMillis() < gateFailUntil) {
                            d("vidmoly dil=$dil kapı yakın zamanda çözülemedi, atlandı")
                            continue
                        }
                        // (b) kapıyı WebView'de çöz, adresi yeniden iste
                        solveGate(ref, gateUrl)
                        val f2 = fetchEmbed(cand.first, ref).first
                        d("vidmoly kapı sonrası url=${short(f2, 170)}")
                        inner = if (f2 != null && !f2.contains("reCAPTCHA", true)) f2
                        else try { captchaInner(f2 ?: gateUrl, ref, true) } catch (e: Exception) { null }
                        if (inner.isNullOrBlank()) {
                            gateFailUntil = System.currentTimeMillis() + 90_000L
                            d("vidmoly dil=$dil kapı çözülemedi")
                            continue
                        }
                        gateCookies = sessionCookies
                        gateOkUntil = System.currentTimeMillis() + 15 * 60_000L
                        d("vidmoly dil=$dil kapı çözüldü")
                    } else {
                        d("vidmoly dil=$dil gerçek adres kapıdan çıkarıldı")
                    }
                    f = inner
                }
                if (f.isNullOrBlank() || f.contains("reCAPTCHA", true) || f in seen) continue
                val buf = ArrayList<ExtractorLink>()
                val ok = try {
                    emitEmbed(abs(f), "Türkçe ${dilName(dil)}", subtitleCallback) { l -> synchronized(buf) { buf.add(l) } }
                } catch (e: Exception) {
                    false
                }
                d("vidmoly dil=$dil ${short(f, 40)} -> $ok")
                if (ok && buf.isNotEmpty()) {
                    val pick = buf.firstOrNull { it.url.contains("master.m3u8", true) }
                        ?: buf.maxByOrNull { it.quality }
                        ?: buf.first()
                    val res = buf.mapNotNull { Regex("""(\d{3,4})p""").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                        .maxOrNull()
                    callback(
                        newExtractorLink(pick.source, "Türkçe ${dilName(dil)} - Vidmoly" + (res?.let { " (${it}p)" } ?: ""), pick.url, pick.type) {
                            this.referer = pick.referer
                            this.quality = Qualities.P720.value
                            this.headers = pick.headers
                        }
                    )
                    any = true
                }
            }
        }

        val missing = listOf("0", "1").filter { it !in got }
        d("özet dublaj=${"0" in got} altyazı=${"1" in got}")

        // Hata ayıklama: bir dil eksik kalırsa sebebi kaynak listesinde kısa "DEBUG" satırları olarak gösterilir
        if (debug && any && missing.isNotEmpty()) {
            val eksik = missing.joinToString("+") { dilName(it) }
            val lines = listOf(
                "sayfalar: " + pageLog.joinToString(" | "),
                "dublaj: " + srcLog.filter { it.contains("0:") && !it.contains("1:") }.joinToString(" ; "),
                "altyazı: " + srcLog.filter { it.contains("1:") }.joinToString(" ; "),
                "byse: " + dbg.filter { it.startsWith("byse") }.takeLast(4).joinToString(" / "),
                "kapı: " + dbg.filter { it.startsWith("captcha") || it.startsWith("vidmoly") }.takeLast(10).joinToString(" / ")
            )
            for ((i, line) in lines.withIndex()) {
                callback(
                    newExtractorLink(
                        name,
                        "DEBUG ${i + 1} [$eksik yok] " + line.take(520),
                        "https://debug.invalid/$i",
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Hâlâ kaynak yoksa bölüm adresindeki dizi adının -izle'li / -izle'siz biçimleri bir kez denenir
        if (!any && m != null && !data.contains("#alt")) {
            val slug0 = m.groupValues[1]
            val alts = listOf("$slug0-izle", slug0.removeSuffix("-izle")).distinct().filter { it != slug0 }
            for (a in alts) {
                val r = try {
                    loadLinks(
                        "$mainUrl/$a/${m.groupValues[2]}-sezon-${m.groupValues[3]}-bolum.html#alt",
                        isCasting, subtitleCallback, callback
                    )
                } catch (e: Exception) {
                    false
                }
                if (r) return true
            }
        }

        if (!any) {
            if (debug) throw ErrorLoadingException("Kaynak yok: " + dbgText())
            throw ErrorLoadingException(
                "Kaynak yok: " + pageLog.joinToString(" | ").take(120) + " || " + srcLog.joinToString(" ; ").take(300) +
                    " || " + dbg.filter { it.startsWith("dzen") }.joinToString(" / ").take(700)
            )
        }
        return any
    }
}

// ---------------- TMDB JSON veri sınıfları ----------------
// Alan adları TMDB'nin JSON adlarıyla birebir aynıdır (JsonProperty kullanılmaz).
data class TmdbTvResult(
    val id: Int? = null,
    val name: String? = null,
    val original_name: String? = null,
    val first_air_date: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val overview: String? = null,
    val popularity: Double? = null
)

data class TmdbSearchResponse(
    val results: List<TmdbTvResult>? = null
)

data class TmdbGenre(
    val id: Int? = null,
    val name: String? = null
)

data class TmdbSeasonInfo(
    val season_number: Int? = null,
    val episode_count: Int? = null
)

data class TmdbCast(
    val name: String? = null,
    val character: String? = null,
    val profile_path: String? = null
)

data class TmdbCredits(
    val cast: List<TmdbCast>? = null
)

data class TmdbTvDetails(
    val id: Int? = null,
    val name: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val popularity: Double? = null,
    val genres: List<TmdbGenre>? = null,
    val seasons: List<TmdbSeasonInfo>? = null,
    val credits: TmdbCredits? = null
)

data class TmdbEpisode(
    val episode_number: Int? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val runtime: Int? = null,
    val air_date: String? = null,
    val vote_average: Double? = null
)

data class TmdbSeasonDetails(
    val episodes: List<TmdbEpisode>? = null
)
