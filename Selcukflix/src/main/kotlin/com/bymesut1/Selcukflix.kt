package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Selcukflix : MainAPI() {
    override var mainUrl = "https://selcukflix.app"
    override var name = "Selcukflix"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    // true: arama / bağlantı bulunamazsa listeye "DEBUG ..." satırı ekler (ekran görüntüsü için)
    private val debug = true

    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"

    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/dizi-izle" to "Diziler",
        "$mainUrl/film-izle" to "Filmler",
        "$mainUrl/trend" to "Trend"
    )

    private fun d(m: String) {
        Log.d("SelcukDbg", m)
    }

    private fun dLong(tag: String, s: String) {
        var i = 0
        var n = 0
        while (i < s.length && n < 8) {
            d("$tag[$n] " + s.substring(i, minOf(s.length, i + 700)))
            i += 700
            n++
        }
    }

    private fun rethrow(e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
    }

    // ---------------- Yardımcılar ----------------

    private fun isCf(code: Int, t: String): Boolean =
        code == 403 || code == 503 ||
            (t.length < 30_000 && (t.contains("Just a moment", true) || t.contains("cf-chl", true)))

    private suspend fun fetchFull(url: String, referer: String? = null): Pair<Int, String> {
        val h = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9")
        try {
            val r = app.get(url, headers = h, referer = referer)
            if (!isCf(r.code, r.text)) return r.code to r.text
            d("cf engeli ${r.code} $url")
        } catch (e: Exception) {
            rethrow(e)
            d("fetch hata ${e.javaClass.simpleName} $url")
        }
        return try {
            val r2 = app.get(url, headers = h, referer = referer, interceptor = cf)
            r2.code to r2.text
        } catch (e: Exception) {
            rethrow(e)
            d("cf fetch hata ${e.javaClass.simpleName} $url")
            0 to ""
        }
    }

    private suspend fun fetch(url: String, referer: String? = null): String = fetchFull(url, referer).second

    private fun abs(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> "$mainUrl/$u"
    }

    private fun cleanImg(u: String?): String? =
        u?.substringBefore("#")?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { abs(it) }

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"").replace("&amp;", "&")

    private fun humanize(slug: String): String =
        slug.replace(Regex("""-(?:\d{4}-\d+|c\d+|\d+)$"""), "").split("-").filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }

    private fun slugify(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace("ı", "i").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun firstStr(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank() && v != "null") return v
        }
        return null
    }

    // ---------------- Kartlar (ana sayfa / kategori / arama) ----------------

    private val diziPathRe = Regex("""^/dizi/([^/?#]+)""")
    private val filmPathRe = Regex("""^/film/([^/?#]+)""")
    private val seriPathRe = Regex("""^/seri-filmler/([^/?#]+)""")

    private class Card(val url: String, val title: String, val poster: String?, val movie: Boolean, val score: Int)

    private fun toResponse(c: Card): SearchResponse =
        if (c.movie) newMovieSearchResponse(c.title, c.url, TvType.Movie) { this.posterUrl = c.poster }
        else newTvSeriesSearchResponse(c.title, c.url, TvType.TvSeries) { this.posterUrl = c.poster }

    private fun cardsOf(doc: Document): List<SearchResponse> {
        val best = LinkedHashMap<String, Card>()
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href")
            val path = raw.substringBefore("?").substringBefore("#").removePrefix(mainUrl)
            val dm = diziPathRe.find(path)
            val fm = filmPathRe.find(path)
            val sm = seriPathRe.find(path)
            val key: String
            val url: String
            val slug: String
            val movie: Boolean
            if (dm != null) {
                slug = dm.groupValues[1]
                key = "d/$slug"
                url = "$mainUrl/dizi/$slug"
                movie = false
            } else if (fm != null) {
                slug = fm.groupValues[1]
                key = "f/$slug"
                url = mainUrl + path.trimEnd('/')
                movie = true
            } else if (sm != null) {
                slug = sm.groupValues[1]
                key = "s/$slug"
                url = "$mainUrl/seri-filmler/$slug"
                movie = false
            } else continue
            val img = a.selectFirst("img")
            val src = cleanImg(img?.attr("src")) ?: cleanImg(img?.attr("data-src"))
            var title = a.attr("aria-label").substringBefore(",").trim()
            if (title.isBlank()) title = a.attr("title").trim()
            if (title.isBlank()) title = img?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) title = a.selectFirst("h1,h2,h3,h4,h5,[class*=title]")?.text()?.trim().orEmpty()
            val real = title.isNotBlank()
            if (!real) title = humanize(slug)
            val score = (if (src != null) 2 else 0) + (if (real) 1 else 0)
            val old = best[key]
            if (old == null || score > old.score) best[key] = Card(url, title, src ?: old?.poster, movie, score)
        }
        return best.values.map { toResponse(it) }
    }

    private val firstPageKeys = ConcurrentHashMap<String, Set<String>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data
        val url = if (page <= 1) base else base + (if (base.contains("?")) "&" else "?") + "page=$page"
        val list = cardsOf(org.jsoup.Jsoup.parse(fetch(url), mainUrl))
        val keys = list.map { it.url }.toSet()
        if (page <= 1) {
            firstPageKeys[base] = keys
        } else if (list.isEmpty() || firstPageKeys[base]?.containsAll(keys) == true) {
            return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        }
        d("anasayfa ${request.name} sayfa=$page kart=${list.size}")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = base != "$mainUrl/"
        )
    }

    // ---------------- Arama ----------------
    // Site aramayı sayfa içinde (JS ile) yapıyor. Önce /mobil-arama sayfası ve script'lerinden uç nokta bulunur,
    // olmazsa sık kullanılan adresler denenir, o da olmazsa TMDB + adres tahmini ile aranır.

    private var searchEndpoints: List<String>? = null
    private var goodTemplate: String? = null

    private suspend fun discoverSearch(): List<String> {
        searchEndpoints?.let { return it }
        val out = LinkedHashSet<String>()
        try {
            val html = fetch("$mainUrl/mobil-arama")
            val scripts = Regex("""(?i)<script[^>]+src\s*=\s*["']([^"']+\.js[^"']*)["']""").findAll(html)
                .map { abs(it.groupValues[1]) }.filter { it.startsWith(mainUrl) }.distinct().take(14).toList()
            val texts = ArrayList<String>()
            texts.add(unescape(html))
            texts.addAll(scripts.amap { fetch(it) }.map { unescape(it) })
            val apiRe = Regex("""["'`](/api/[A-Za-z0-9_\-/.]*(?:search|arama|suggest|find|query)[A-Za-z0-9_\-/.]*)(\?[A-Za-z_]+=)?""", RegexOption.IGNORE_CASE)
            val pathRe = Regex("""["'`](/[A-Za-z0-9_\-/.]*(?:search|arama|suggest)[A-Za-z0-9_\-/.]*)\?([A-Za-z_]+)=""", RegexOption.IGNORE_CASE)
            for (t in texts) {
                for (m in apiRe.findAll(t)) {
                    val p = m.groupValues[1]
                    val prm = m.groupValues[2].removePrefix("?").removeSuffix("=")
                    if (prm.isNotBlank()) out.add("$mainUrl$p?$prm={q}")
                    else {
                        out.add("$mainUrl$p?q={q}")
                        out.add("$mainUrl$p?query={q}")
                        out.add("$mainUrl$p?searchTerm={q}")
                    }
                }
                for (m in pathRe.findAll(t)) out.add("$mainUrl${m.groupValues[1]}?${m.groupValues[2]}={q}")
            }
            d("arama uc noktalari kesfedildi=${out.size} ${out.take(6).joinToString(" | ")}")
        } catch (e: Exception) {
            rethrow(e)
            d("arama kesif hata ${e.javaClass.simpleName}")
        }
        val res = out.toList().take(12)
        searchEndpoints = res
        return res
    }

    private fun jsonCards(x: Any?, out: LinkedHashMap<String, SearchResponse>, depth: Int = 0) {
        if (depth > 8) return
        when (x) {
            is JSONObject -> {
                val poster0 = firstStr(x, "poster", "image", "cover", "thumbnail", "img", "posterUrl", "poster_url")
                val tmdbPoster = firstStr(x, "poster_path")
                val link = firstStr(x, "url", "link", "path", "href", "permalink")
                    ?: (if (poster0 != null || tmdbPoster != null || x.length() >= 4) firstStr(x, "slug") else null)
                val title = firstStr(x, "title", "name", "baslik", "original_title")
                if (link != null && title != null) {
                    val typeS = (firstStr(x, "type", "kind", "contentType", "content_type", "mediaType", "media_type") ?: "").lowercase()
                    var movie = typeS.contains("film") || typeS.contains("movie") || link.contains("/film/")
                    val path = when {
                        link.startsWith("http") -> link.removePrefix(mainUrl)
                        link.startsWith("/") -> link
                        else -> if (movie) "/film/$link/izle" else "/dizi/$link"
                    }
                    val dm = diziPathRe.find(path)
                    val fm = filmPathRe.find(path)
                    if (dm != null || fm != null) {
                        movie = fm != null
                        val url = if (dm != null) "$mainUrl/dizi/${dm.groupValues[1]}" else mainUrl + path.substringBefore("?").trimEnd('/')
                        val poster = cleanImg(poster0) ?: tmdbPoster?.let { "https://image.tmdb.org/t/p/w342$it" }
                        if (!out.containsKey(url)) {
                            out[url] = toResponse(Card(url, title, poster, movie, 0))
                        }
                    }
                }
                val ks = x.keys()
                while (ks.hasNext()) jsonCards(x.opt(ks.next()), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until x.length()) jsonCards(x.opt(i), out, depth + 1)
        }
    }

    private fun parseSearch(body: String, query: String): List<SearchResponse> {
        val t = body.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("{") || t.startsWith("[")) {
            val root: Any? = try {
                JSONTokener(t).nextValue()
            } catch (e: Exception) {
                null
            }
            val m = LinkedHashMap<String, SearchResponse>()
            jsonCards(root, m)
            return m.values.toList()
        }
        // HTML: site sorgu adresini yok sayıp ana listeyi döndürebilir; sorguyla eşleşenleri al
        val nq = normT(query)
        return cardsOf(org.jsoup.Jsoup.parse(body, mainUrl))
            .filter { normT(it.name).contains(nq) || normT(it.url).contains(nq) }
    }

    private suspend fun tmdbSearchFallback(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val res = tmdbGet("/search/multi?query=$q&include_adult=false")?.optJSONArray("results") ?: return emptyList()
        class Cand(val movie: Boolean, val slug: String, val title: String, val poster: String?)
        val cands = ArrayList<Cand>()
        for (i in 0 until minOf(res.length(), 8)) {
            val o = res.optJSONObject(i) ?: continue
            val mt = o.optString("media_type")
            if (mt != "tv" && mt != "movie") continue
            val title = jStr(o, "name") ?: jStr(o, "title") ?: continue
            val orig = jStr(o, "original_name") ?: jStr(o, "original_title") ?: title
            val poster = jStr(o, "poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" }
            for (s in listOf(slugify(orig), slugify(title)).distinct()) {
                if (s.isNotBlank()) cands.add(Cand(mt == "movie", s, title, poster))
            }
        }
        val checked = cands.take(14).amap { c ->
            val url = if (c.movie) "$mainUrl/film/${c.slug}/izle" else "$mainUrl/dizi/${c.slug}"
            val r = fetchFull(url)
            val ok = r.first == 200 && (c.movie || r.second.contains("sezon", true))
            if (ok) c to url else null
        }.filterNotNull()
        val seen = HashSet<String>()
        return checked.filter { seen.add(it.second) }.map { (c, url) ->
            toResponse(Card(url, c.title, c.poster, c.movie, 0))
        }
    }

    private var lastDebug = ""

    // Sitenin kendi arama sayfasını (/mobil-arama) gerçek WebView'da açar, kutuya sorguyu yazar,
    // çıkan sonuç bağlantılarını toplayıp sitenin KENDİ alan adındaki /__selcukres adresine gönderir; adres yakalanır.
    @Volatile
    private var webOk = false

    private val searchJs = """
(function(){
  var q = %Q%;
  function post(o){
    try { fetch(location.origin + '/__selcukres?d=' + encodeURIComponent(JSON.stringify(o))).catch(function(){}); } catch(e) {}
  }
  function findInput(){
    return document.querySelector('input[type=search]') || document.querySelector('input[type=text]') ||
      document.querySelector('input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=submit])');
  }
  var key = (q.toLowerCase().split(/\s+/)[0] || '');
  function hits(as){
    return as.filter(function(a){ return (a.innerText || '').toLowerCase().indexOf(key) > -1; });
  }
  var waited = 0;
  var w = setInterval(function(){
    waited++;
    var inp = findInput();
    if (!inp && waited < 24) return;
    clearInterval(w);
    if (!inp) { post({l: [], x: 'input yok'}); return; }
    inp.focus();
    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(inp, q);
    inp.dispatchEvent(new Event('input', {bubbles: true}));
    inp.dispatchEvent(new Event('change', {bubbles: true}));
    inp.dispatchEvent(new KeyboardEvent('keyup', {bubbles: true, key: 'a'}));
    var tries = 0, last = -1;
    var iv = setInterval(function(){
      tries++;
      var as = [].slice.call(document.querySelectorAll('a[href]')).filter(function(a){
        return /\/(dizi|film|seri-filmler)\//.test(a.getAttribute('href') || '');
      });
      var h = hits(as);
      var stable = (h.length === last);
      last = h.length;
      if ((h.length > 0 && stable && tries >= 3) || tries >= 16) {
        clearInterval(iv);
        var out = h.slice(0, 24).map(function(a){
          var im = a.querySelector('img');
          return {h: a.getAttribute('href'), t: (a.innerText || '').trim().slice(0, 120), i: im ? (im.getAttribute('src') || im.getAttribute('data-src') || '') : ''};
        });
        post({l: out, x: out.length ? '' : 'sonuc yok'});
      }
    }, 500);
  }, 500);
  return 'ok';
})();
"""

    private suspend fun webSearch(query: String, dbg: StringBuilder): List<SearchResponse> {
        webOk = false
        val js = searchJs.replace("%Q%", JSONObject.quote(query))
        val finalUrl = try {
            val r = app.get(
                "$mainUrl/mobil-arama",
                referer = "$mainUrl/",
                interceptor = WebViewResolver(
                    Regex("""__selcukres"""),
                    userAgent = ua,
                    useOkhttp = false,
                    script = js,
                    scriptCallback = { },
                    timeout = 25_000L
                ),
                timeout = 35L
            )
            r.url
        } catch (e: Exception) {
            rethrow(e)
            dbg.append("webview hata ${e.javaClass.simpleName} ${e.message}\n")
            ""
        }
        if (!finalUrl.contains("__selcukres")) {
            dbg.append("webview yakalama yok url=${finalUrl.take(90)}\n")
            return emptyList()
        }
        val payload = try {
            URLDecoder.decode(finalUrl.substringAfter("d="), "UTF-8")
        } catch (e: Exception) {
            ""
        }
        dbg.append("webview veri=${payload.take(700)}\n")
        val root = (try {
            JSONObject(payload)
        } catch (e: Exception) {
            null
        }) ?: return emptyList()
        // kutu bulunamadıysa sitenin aramasından cevap alınamadı: yedek yollar denensin
        if (root.optString("x") == "input yok") return emptyList()
        webOk = true
        val arr = root.optJSONArray("l") ?: return emptyList()
        val out = LinkedHashMap<String, SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("h").substringBefore("?").substringBefore("#").removePrefix(mainUrl)
            val dm = diziPathRe.find(path)
            val fm = filmPathRe.find(path)
            val sm = seriPathRe.find(path)
            val url: String
            val slug: String
            val movie: Boolean
            if (dm != null) {
                slug = dm.groupValues[1]
                url = "$mainUrl/dizi/$slug"
                movie = false
            } else if (fm != null) {
                slug = fm.groupValues[1]
                url = mainUrl + path.trimEnd('/')
                movie = true
            } else if (sm != null) {
                slug = sm.groupValues[1]
                url = "$mainUrl/seri-filmler/$slug"
                movie = false
            } else continue
            val lines = o.optString("t").split("\n").map { it.trim() }.filter { it.isNotBlank() }
            var title = lines.firstOrNull() ?: ""
            if (title.isBlank()) title = humanize(slug)
            if (!out.containsKey(url)) out[url] = toResponse(Card(url, title, cleanImg(o.optString("i")), movie, 0))
        }
        return out.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val dbg = StringBuilder()
        var result: List<SearchResponse> = try {
            webSearch(query, dbg)
        } catch (e: Exception) {
            rethrow(e)
            emptyList()
        }
        d("arama webview sonuc=${result.size} ok=$webOk")

        // Sitenin kendi araması çalıştıysa (sonuç boş olsa bile) yavaş yedek yollara gerek yok
        if (result.isEmpty() && !webOk) {
            val q = URLEncoder.encode(query, "UTF-8")
            val templates = LinkedHashSet<String>()
            goodTemplate?.let { templates.add(it) }
            // /api/bg/ uç noktaları "izinsiz erisim algilandi" döndürüyor: denenmez
            templates.addAll(discoverSearch().filter { !it.contains("/api/bg/") })
            for (p in listOf("api/search?q={q}", "api/search?query={q}", "api/arama?q={q}",
                "mobil-arama?q={q}", "mobil-arama?query={q}", "arama?q={q}", "ara?q={q}", "search?q={q}")) {
                templates.add("$mainUrl/$p")
            }
            for (batch in templates.toList().chunked(6)) {
                val bodies = batch.amap { t -> t to fetchFull(t.replace("{q}", q), "$mainUrl/mobil-arama").second }
                for ((t, b) in bodies) {
                    if (b.contains("izinsiz erisim", true)) continue
                    val r = parseSearch(b, query)
                    dbg.append("api ${t.removePrefix(mainUrl)} len=${b.length} bas=${b.trim().take(70)} sonuc=${r.size}\n")
                    if (r.isNotEmpty()) {
                        result = r
                        goodTemplate = t
                        break
                    }
                }
                if (result.isNotEmpty()) break
            }
        }
        if (result.isEmpty() && !webOk) {
            result = tmdbSearchFallback(query)
            dbg.append("tmdb yedek sonuc=${result.size}\n")
        }
        if (result.isEmpty() && !webOk && debug) {
            lastDebug = dbg.toString()
            d("arama debug: $lastDebug")
            return listOf(
                newTvSeriesSearchResponse("DEBUG arama bulunamadi (detay icin ac)", "$mainUrl/debug-arama", TvType.TvSeries) {}
            )
        }
        return result
    }

    // ---------------- TMDB ----------------

    private fun tmdbOn(): Boolean = tmdbKey.isNotBlank() && !tmdbKey.startsWith("BURAYA")

    private class Tm(
        val id: Int,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val genres: List<String>,
        val seasons: List<Int>,
        val runtime: Int?
    )

    private class TSeason(val eps: Map<Int, JSONObject>, val poster: String?)

    private fun jStr(o: JSONObject?, k: String): String? {
        if (o == null) return null
        val s = o.optString(k, "")
        return if (s.isBlank() || s == "null") null else s
    }

    private suspend fun tmdbGet(path: String, language: String = "tr-TR"): JSONObject? {
        if (!tmdbOn()) return null
        return try {
            val sep = if (path.contains("?")) "&" else "?"
            val t = app.get(
                "https://api.themoviedb.org/3$path${sep}api_key=$tmdbKey&language=$language",
                headers = mapOf("User-Agent" to ua)
            ).text
            JSONObject(t)
        } catch (e: Exception) {
            rethrow(e)
            null
        }
    }

    private fun normT(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9ğüşıöç]"), "")

    private suspend fun tmdbFind(title: String, year: Int?, movie: Boolean): Tm? {
        if (!tmdbOn() || title.isBlank()) return null
        val kind = if (movie) "movie" else "tv"
        val nameKey = if (movie) "title" else "name"
        val origKey = if (movie) "original_title" else "original_name"
        val dateKey = if (movie) "release_date" else "first_air_date"
        val q = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val res = tmdbGet("/search/$kind?query=$q&include_adult=false")?.optJSONArray("results") ?: return null
        if (res.length() == 0) return null
        val nt = normT(title)
        var pick: JSONObject? = null
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            val same = normT(o.optString(nameKey, "")) == nt || normT(o.optString(origKey, "")) == nt
            if (!same) continue
            val y = jStr(o, dateKey)?.take(4)?.toIntOrNull()
            if (year == null || y == year) {
                pick = o
                break
            }
            if (pick == null) pick = o
        }
        if (pick == null) pick = res.optJSONObject(0)
        val p = pick ?: return null
        val id = p.optInt("id", 0)
        if (id == 0) return null
        val det = tmdbGet("/$kind/$id") ?: p
        var overview = jStr(det, "overview")
        if (overview == null) overview = jStr(tmdbGet("/$kind/$id", "en-US"), "overview")
        val genres = ArrayList<String>()
        det.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) jStr(g.optJSONObject(i), "name")?.let { genres.add(it) }
        }
        val seasons = ArrayList<Int>()
        det.optJSONArray("seasons")?.let { s ->
            for (i in 0 until s.length()) {
                val n = s.optJSONObject(i)?.optInt("season_number", 0) ?: 0
                if (n > 0) seasons.add(n)
            }
        }
        return Tm(
            id,
            overview,
            jStr(det, "poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            jStr(det, "backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
            jStr(det, dateKey)?.take(4)?.toIntOrNull(),
            genres,
            seasons,
            det.optInt("runtime", 0).takeIf { it > 0 }
        )
    }

    private suspend fun tmdbSeason(id: Int, season: Int): TSeason? {
        val tr = tmdbGet("/tv/$id/season/$season") ?: return null
        val arr = tr.optJSONArray("episodes") ?: return null
        val map = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.optInt("episode_number", -1)] = o
        }
        val needEn = map.values.any { jStr(it, "overview") == null }
        if (needEn) {
            val en = tmdbGet("/tv/$id/season/$season", "en-US")?.optJSONArray("episodes")
            if (en != null) {
                for (i in 0 until en.length()) {
                    val eo = en.optJSONObject(i) ?: continue
                    val tgt = map[eo.optInt("episode_number", -1)] ?: continue
                    if (jStr(tgt, "overview") == null) jStr(eo, "overview")?.let { tgt.put("overview", it) }
                    if (jStr(tgt, "still_path") == null) jStr(eo, "still_path")?.let { tgt.put("still_path", it) }
                }
            }
        }
        return TSeason(map, jStr(tr, "poster_path")?.let { "https://image.tmdb.org/t/p/w300$it" })
    }

    // ---------------- Dizi / Film sayfası ----------------

    private fun cleanTitle(t: String): String =
        t.substringBefore(" | ")
            .replace(Regex("""(?i)\s*[-|–]\s*Sel[cç]uk.*$"""), "")
            .replace(Regex("""\s+[iİıI]zle\b.*$""", RegexOption.IGNORE_CASE), "")
            .trim()

    override suspend fun load(url: String): LoadResponse? {
        val u = abs(url).substringBefore("?").substringBefore("#").trimEnd('/')
        if (u.endsWith("/debug-arama")) {
            return newMovieLoadResponse("DEBUG arama", u, TvType.Movie, u) {
                this.plot = lastDebug.ifBlank { "bos" }
            }
        }
        if (u.contains("/film/")) return loadMovie(u)
        if (u.contains("/seri-filmler/")) return loadCollection(u)
        val s = loadSeries(u)
        // Sonuç dizi sanılmış ama bölüm yoksa aynı adlı film sayfasını dene
        if (s is TvSeriesLoadResponse && s.episodes.isEmpty()) {
            val slug = u.substringAfter("/dizi/").substringBefore("/")
            val m = loadMovie("$mainUrl/film/$slug/izle")
            if (m != null) return m
        }
        return s
    }

    // Seri film koleksiyonu (örn. Harry Potter Serisi): içindeki filmler bölüm olarak listelenir
    private suspend fun loadCollection(pageUrl: String): LoadResponse? {
        val raw = fetch(pageUrl)
        if (raw.isBlank()) return null
        val doc = org.jsoup.Jsoup.parse(raw, mainUrl)
        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()
        val title = cleanTitle(meta("og:title") ?: doc.selectFirst("title")?.text() ?: humanize(pageUrl.substringAfter("/seri-filmler/")))
        val films = cardsOf(doc).filter { it.url.contains("/film/") }
        val episodes = ArrayList<Episode>()
        var n = 0
        for (f in films) {
            n++
            val fn = f.name
            val fp = f.posterUrl
            val idx = n
            episodes.add(
                newEpisode(f.url) {
                    this.season = 1
                    this.episode = idx
                    this.name = fn
                    this.posterUrl = fp
                }
            )
        }
        d("koleksiyon $title film=${episodes.size}")
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = cleanImg(meta("og:image")) ?: films.firstOrNull()?.posterUrl
            this.plot = meta("og:description")
        }
    }

    private suspend fun loadMovie(pageUrl: String): LoadResponse? {
        val pg = fetchFull(pageUrl)
        val raw = pg.second
        if (raw.isBlank() || pg.first == 404) return null
        val doc = org.jsoup.Jsoup.parse(raw, mainUrl)
        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()
        val title = cleanTitle(meta("og:title") ?: doc.selectFirst("title")?.text() ?: humanize(pageUrl.substringAfter("/film/").substringBefore("/")))
        val sitePoster = cleanImg(meta("og:image"))
        val sitePlot = meta("og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val tm = tmdbFind(title, null, true)
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = tm?.poster ?: sitePoster
            this.backgroundPosterUrl = tm?.backdrop
            this.plot = tm?.overview ?: sitePlot
            this.year = tm?.year
            if (tm != null && tm.genres.isNotEmpty()) this.tags = tm.genres
            if (tm?.runtime != null) this.duration = tm.runtime
        }
    }

    private suspend fun loadSeries(url: String): LoadResponse? {
        val pageUrl = Regex("""^(https?://[^/]+/dizi/[^/]+)""").find(url)?.groupValues?.get(1) ?: url
        val slug = pageUrl.substringAfter("/dizi/")
        val raw = fetch(pageUrl)
        if (raw.isBlank()) return null
        val html = unescape(raw)
        val doc = org.jsoup.Jsoup.parse(raw, mainUrl)
        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()

        val title = cleanTitle(meta("og:title") ?: doc.selectFirst("title")?.text() ?: humanize(slug))
        val sitePoster = cleanImg(meta("og:image"))
        val sitePlot = meta("og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val isAnime = Regex("""(?i)"(?:origin|type|kind)"\s*:\s*"anime"""").containsMatchIn(html)

        val tm = tmdbFind(title, null, false)

        val epRe = Regex("""/dizi/${Regex.escape(slug)}/sezon-(\d+)/bolum-(\d+)""")
        val seaRe = Regex("""/dizi/${Regex.escape(slug)}/sezon-(\d+)(?![\d]|/bolum)""")
        val found = LinkedHashSet<Pair<Int, Int>>()
        val seasonSet = LinkedHashSet<Int>()
        fun scan(t: String) {
            epRe.findAll(t).forEach {
                val s = it.groupValues[1].toInt()
                found.add(s to it.groupValues[2].toInt())
                seasonSet.add(s)
            }
            seaRe.findAll(t).forEach { seasonSet.add(it.groupValues[1].toInt()) }
        }
        scan(html)
        seasonSet.add(1)
        tm?.seasons?.let { seasonSet.addAll(it) }
        val pages = seasonSet.toList().sorted().take(40).amap { sn -> fetch("$pageUrl/sezon-$sn") }
        for (t in pages) if (t.isNotBlank()) scan(unescape(t))
        d("dizi=$slug site bolum=${found.size} sezon=${seasonSet.size} tmdb=${tm?.id}")

        val fromTmdb = found.isEmpty()
        val neededSeasons = (if (fromTmdb) (tm?.seasons ?: emptyList()) else found.map { it.first }.distinct()).sorted()
        val seasonMap = HashMap<Int, TSeason>()
        if (tm != null) {
            val got = neededSeasons.amap { sn -> sn to tmdbSeason(tm.id, sn) }
            for ((sn, ts) in got) if (ts != null) seasonMap[sn] = ts
        }
        val pairs: List<Pair<Int, Int>> = if (!fromTmdb) {
            found.sortedWith(compareBy({ it.first }, { it.second }))
        } else {
            val l = ArrayList<Pair<Int, Int>>()
            for ((sn, ts) in seasonMap.toSortedMap()) for (en in ts.eps.keys.sorted()) if (en > 0) l.add(sn to en)
            l
        }

        val today = Date().time
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val episodes = ArrayList<Episode>()
        for ((s, e) in pairs) {
            val ts = seasonMap[s]
            val j = ts?.eps?.get(e)
            val air = jStr(j, "air_date")
            val airMs: Long? = air?.let {
                try {
                    df.parse(it)?.time
                } catch (ex: Exception) {
                    null
                }
            }
            if (fromTmdb && airMs != null && airMs > today) continue
            val still = jStr(j, "still_path")?.let { "https://image.tmdb.org/t/p/w300$it" }
            val epName = jStr(j, "name")
            val epDesc = jStr(j, "overview")
            val epRun = j?.optInt("runtime", 0)?.takeIf { it > 0 }
            val dataUrl = "$pageUrl/sezon-$s/bolum-$e"
            episodes.add(
                newEpisode(dataUrl) {
                    this.season = s
                    this.episode = e
                    if (epName != null) this.name = epName
                    if (epDesc != null) this.description = epDesc
                    this.posterUrl = still ?: ts?.poster
                    if (epRun != null) this.runTime = epRun
                    if (airMs != null) this.date = airMs
                }
            )
        }
        d("toplam bolum=${episodes.size}")

        return newTvSeriesLoadResponse(title, pageUrl, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            this.posterUrl = tm?.poster ?: sitePoster
            this.backgroundPosterUrl = tm?.backdrop
            this.plot = tm?.overview ?: sitePlot
            this.year = tm?.year
            if (tm != null && tm.genres.isNotEmpty()) this.tags = tm.genres
        }
    }

    // ---------------- Oynatma ----------------

    private val iframeRe = Regex("""https?://[A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9_%.=-]+""")
    private val lphpRe = Regex("""https?://[A-Za-z0-9.-]+/l\.php\?v=[A-Za-z0-9_%.=+/-]+""")
    private val trailerRe =
        Regex("""(https?://[A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9_%.=-]+)"[^}]{0,300}?"trailer"\s*:\s*(true|false)""")
    private val ifrTagRe = Regex("""(?i)<iframe[^>]+?(?:src|data-src)\s*=\s*["']([^"']+)["']""")
    private val quotedUrlRe = Regex("[\"'](https?://[^\"'\\s<>\\\\]+|//[^\"'\\s<>\\\\]+)[\"']")
    private val playerKeyRe = Regex(
        """(?i)iframe|/embed|/player|/v/|/e/|/f/|vidmoly|filemoon|streamtape|dood|voe\.|mixdrop|sibnet|ok\.ru|rapidvid|vidmoxy|closeload|upstream|fembed|pixeldrain|uqload|streamwish|vidhide|hdplayer|peacemaker|pichive|cdn"""
    )
    private val m3u8Re = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")
    private val mp4Re = Regex("""https?://[^"'\s\\<>]+\.mp4[^"'\s\\<>]*""")
    private val subRe = Regex("""https?://[^"'\s\\<>]+\.(?:vtt|srt)[^"'\s\\<>]*""")
    private val assetRe = Regex("""(?i)\.(?:js|css|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|vtt|srt|html?|php)(?:\?|$)""")
    private val badHostRe = Regex("""(?i)google|gstatic|facebook|twitter|cloudflare|doubleclick|histats|yandex|jsdelivr|cdnjs|jquery|bootstrap|schema\.org|w3\.org""")
    private val keyRe = Regex("""(?i)m3u|master|playlist|index|hls|manifest|/f/|stream|video|cdn""")

    private fun originOf(u: String) = Regex("""^https?://[^/]+""").find(u)?.value ?: mainUrl

    private fun fixProto(u: String): String = if (u.startsWith("//")) "https:$u" else u

    // ---------------- Altyazı ----------------

    private fun subLang(u: String, label: String = ""): String {
        val base = subLang0(u, label)
        val forced = label.contains("forced", true) || label.contains("zorunlu", true)
        return if (forced && !base.contains("Forced")) "$base Forced" else base
    }

    private fun subLang0(u: String, label: String = ""): String {
        val name = u.substringBefore("?").substringAfterLast("/").lowercase()
        val m = Regex("""(?:^|[._\-])(tr|tur|turkish|turkce|türkçe|en|eng|english)(?:[._\-]|$)""")
            .find(name)?.groupValues?.get(1)
        when (m) {
            "tr", "tur", "turkish", "turkce", "türkçe" -> return "Türkçe"
            "en", "eng", "english" -> return "İngilizce"
        }
        val l = label.lowercase()
        if (l.contains("türk") || l.contains("turk") || l == "tr" || l == "tur") return "Türkçe"
        if (l.contains("ingiliz") || l.contains("engl") || l == "en" || l == "eng") return "İngilizce"
        if (label.isNotBlank()) return label
        return "Altyazı"
    }

    private val labelRe = Regex("(?:label|lang|language|srclang)[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val trackTagRe = Regex("""(?is)<track\b[^>]*>""")
    private val kindRe = Regex("""(?i)kind["']?\s*[:=]\s*["'](?:captions|subtitles)["']""")

    private fun findSubs(texts: List<String>, origin: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        fun absU(s: String): String? = when {
            s.startsWith("http", true) -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> origin + s
            else -> null
        }
        for (t in texts) {
            for (m in subRe.findAll(t)) {
                val u = m.value
                if (Regex("thumb|sprite|chapter", RegexOption.IGNORE_CASE).containsMatchIn(u)) continue
                if (out.containsKey(u)) continue
                val ctx = t.substring(maxOf(0, m.range.first - 200), minOf(t.length, m.range.last + 200))
                out[u] = labelRe.find(ctx)?.groupValues?.get(1) ?: ""
            }
            for (m in trackTagRe.findAll(t)) {
                val tag = m.value
                val src = Regex("""(?i)src\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1) ?: continue
                val u = absU(src) ?: continue
                val lb = Regex("""(?i)(?:label|srclang)\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1) ?: ""
                if (!out.containsKey(u)) out[u] = lb
            }
            for (k in kindRe.findAll(t)) {
                val ctx = t.substring(maxOf(0, k.range.first - 300), minOf(t.length, k.range.last + 300))
                val fu = Regex("""(?i)(?:file|src|url)["']?\s*[:=]\s*["']([^"']+)["']""").find(ctx)?.groupValues?.get(1) ?: continue
                val u = absU(fu) ?: continue
                if (!out.containsKey(u)) out[u] = labelRe.find(ctx)?.groupValues?.get(1) ?: ""
            }
        }
        return out
    }

    private fun toVtt(t0: String): String? {
        val t = t0.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (t.startsWith("WEBVTT")) return t
        if (Regex("""^\d+\s*\r?\n\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->""").containsMatchIn(t)) {
            return "WEBVTT\n\n" + t.replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
        }
        return null
    }

    private suspend fun fetchSub(u: String, frameUrl: String): ByteArray? {
        val fo = originOf(frameUrl)
        val sets = listOf(
            mapOf("User-Agent" to ua, "Referer" to frameUrl, "Origin" to fo, "Accept" to "*/*"),
            mapOf("User-Agent" to ua, "Referer" to "$fo/", "Origin" to fo, "Accept" to "*/*"),
            mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Accept" to "*/*"),
            mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "*/*"),
            mapOf("Accept" to "*/*")
        )
        for ((i, h) in sets.withIndex()) {
            try {
                val r = app.get(u, headers = h)
                val v = if (r.code == 200) toVtt(r.text) else null
                d("altyazi deneme ${i + 1} kod=${r.code} vtt=${v != null}")
                if (v != null) return v.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                rethrow(e)
            }
        }
        try {
            val r = app.get(u, headers = sets[0], interceptor = cf)
            val v = if (r.code == 200) toVtt(r.text) else null
            if (v != null) return v.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            rethrow(e)
        }
        return null
    }

    private val subCount = HashMap<String, Int>()

    private suspend fun emitSub(
        u: String,
        label: String,
        frameUrl: String,
        subSent: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        if (!subSent.add(u)) return false
        val strict = !subRe.containsMatchIn(u)
        val bytes = fetchSub(u, frameUrl)
        if (bytes == null && strict) {
            d("altyazi atlandi ${u.takeLast(70)}")
            return false
        }
        val lang = subLang(u, label)
        val n = subCount[lang] ?: 0
        subCount[lang] = n + 1
        val shown = if (n == 0) lang else "$lang ${n + 1}"
        val local = if (bytes != null) SelcukSubProxy.put(bytes) else null
        d("altyazi $shown ${if (local != null) "yerel sunucu" else "dogrudan"} ${u.takeLast(70)}")
        subtitleCallback(SubtitleFile(shown, local ?: u))
        return local != null
    }

    // ---------------- Video adresleri ----------------

    private fun allTexts(html: String): List<String> {
        val base = unescape(html)
        val out = ArrayList<String>()
        out.add(base)
        try {
            out.addAll(unpackAll(base))
        } catch (e: Exception) {
        }
        try {
            out.addAll(deepTexts(out.toList()))
        } catch (e: Exception) {
        }
        return out.map { unescape(it) }.distinct()
    }

    // Bölüm / film sayfasındaki olası oynatıcılar: önce iframe.php, sonra l.php, sonra diğerleri
    private fun playerCandidates(texts: List<String>, trailers: Set<String>): List<String> {
        val strong = LinkedHashSet<String>()
        val lphp = LinkedHashSet<String>()
        val weak = LinkedHashSet<String>()
        val selfHost = originOf(mainUrl)
        for (t in texts) {
            iframeRe.findAll(t).forEach { strong.add(it.value) }
            lphpRe.findAll(t).forEach { lphp.add(it.value) }
            ifrTagRe.findAll(t).forEach { m ->
                val u = fixProto(m.groupValues[1])
                if (u.startsWith("http")) strong.add(u)
            }
            quotedUrlRe.findAll(t).forEach { m ->
                val u = fixProto(m.groupValues[1])
                if (weak.size < 40 && playerKeyRe.containsMatchIn(u)) weak.add(u)
            }
        }
        val out = ArrayList<String>()
        for (u in strong) if (u !in trailers && !badHostRe.containsMatchIn(u)) out.add(u)
        for (u in lphp) if (u !in out) out.add(u)
        for (u in weak) {
            if (u in out || u in trailers) continue
            if (u.startsWith(selfHost)) continue
            if (badHostRe.containsMatchIn(u) || assetRe.containsMatchIn(u)) continue
            if (u.contains("themoviedb", true) || u.contains("image.tmdb", true)) continue
            out.add(u)
        }
        return out.distinct().take(12)
    }

    private fun mediaCandidates(texts: List<String>): List<String> {
        val set = LinkedHashSet<String>()
        val quoted = Regex("[\"'](https?://[^\"'\\s<>\\\\]+|//[^\"'\\s<>\\\\]+)[\"']")
        for (t in texts) {
            m3u8Re.findAll(t).forEach { set.add(it.value) }
            mp4Re.findAll(t).forEach { set.add(it.value) }
            quoted.findAll(t).forEach { set.add(fixProto(it.groupValues[1])) }
        }
        return set.filter { !badHostRe.containsMatchIn(it) && (!assetRe.containsMatchIn(it)) }
            .sortedBy {
                when {
                    it.contains(".m3u8", true) -> 0
                    it.contains(".mp4", true) -> 1
                    keyRe.containsMatchIn(it) -> 2
                    else -> 9
                }
            }
            .filter {
                it.contains(".m3u8", true) || it.contains(".mp4", true) || keyRe.containsMatchIn(it)
            }
    }

    private suspend fun emitLink(
        url: String,
        type: ExtractorLinkType,
        label: String,
        origin: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(name, label, url, type) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
            }
        )
    }

    private suspend fun emitCandidates(
        cands: List<String>,
        origin: String,
        label: String,
        sent: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        var verified = 0
        for (u in cands.take(14)) {
            if (u in sent) continue
            when {
                u.contains(".m3u8", true) -> {
                    sent.add(u)
                    emitLink(u, ExtractorLinkType.M3U8, label, origin, callback)
                    ok = true
                }
                u.contains(".mp4", true) -> {
                    sent.add(u)
                    emitLink(u, ExtractorLinkType.VIDEO, label, origin, callback)
                    ok = true
                }
                verified < 6 -> {
                    verified++
                    val body = try {
                        app.get(
                            u,
                            headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
                        ).text
                    } catch (e: Exception) {
                        rethrow(e)
                        ""
                    }
                    if (body.trimStart().startsWith("#EXTM3U")) {
                        sent.add(u)
                        emitLink(u, ExtractorLinkType.M3U8, label, origin, callback)
                        ok = true
                    }
                }
            }
        }
        return ok
    }

    private var webCount = 0
    private var curFrame = ""
    private var videoOk = true
    private var npDone = false
    private var curHosts: List<String> = emptyList()
    private var srcCount = 0
    private val styleRe = Regex("""(?is)<style\b[^>]*>.*?</style>""")

    private val mphpRe = Regex("""https?://[^"'\s\\<>]+/m\.php\?v=[^"'\s\\<>]+""")
    private val titleRe = Regex("""["']title["']\s*:\s*["']([^"']{1,40})["']""")
    private val imgRe = Regex("""(?i)\.(?:png|jpe?g|gif|svg|webp|ico|css|js|woff2?|ttf)(?:\?|$)""")
    private val phpAbsRe = Regex("""(?:https?:)?//[A-Za-z0-9.-]+/(?:[A-Za-z0-9_\-/]*/)?[A-Za-z0-9_\-]+\.php\?[A-Za-z0-9_%=&.+/\-]+""")
    private val phpRelRe = Regex("""["'`]((?:\./|/)?(?:[A-Za-z0-9_\-/]*/)?[A-Za-z0-9_\-]+\.php\?[A-Za-z0-9_%=&.+/\-]*)["'`]""")
    private val apiSkipRe = Regex("""(?i)/(?:iframe|m|l)\.php|/master\.m3u8""")

    private suspend fun emitHls(
        url: String,
        title: String,
        label: String,
        origin: String,
        sent: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!videoOk || !sent.add(url)) return false
        val nm = if (title.isBlank()) label else "$label $title"
        emitLink(url, ExtractorLinkType.M3U8, nm, origin, callback)
        return true
    }

    private suspend fun emitMedia(
        u: String,
        title: String,
        label: String,
        origin: String,
        sent: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val nm = if (title.isBlank()) label else "$label $title"
        if (u.contains(".mp4", true)) {
            if (!sent.add(u)) return false
            emitLink(u, ExtractorLinkType.VIDEO, nm, origin, callback)
            return true
        }
        val alt = if (u.contains("/m.php")) u.replace("/m.php", "/master.m3u8") else u
        return emitHls(alt, title, label, origin, sent, callback)
    }

    private fun hostsOf(texts: List<String>): List<String> {
        for (t in texts) {
            val m = Regex("\"hosts\"\\s*:\\s*\\[([^\\]]*)\\]").find(t) ?: continue
            return Regex("\"(https?:[^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1].replace("\\/", "/") }.toList()
        }
        return emptyList()
    }

    // window.NP_CONFIG = {...}  (sources[].master, hosts, subtitles[].url)
    private fun npConfig(t: String): JSONObject? {
        val i = t.indexOf("NP_CONFIG")
        if (i < 0) return null
        val st = t.indexOf('{', i)
        if (st < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (j in st until t.length) {
            val c = t[j]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else {
                if (c == '"') inStr = true
                else if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return try {
                            JSONObject(t.substring(st, j + 1))
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun emitPlaylist(
        texts: List<String>,
        origin: String,
        label: String,
        sent: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        for (t in texts) {
            for (m in mphpRe.findAll(t)) {
                val after = t.substring(m.range.last + 1, minOf(t.length, m.range.last + 1 + 200)).substringBefore("m.php")
                val before = t.substring(maxOf(0, m.range.first - 200), m.range.first).substringAfterLast("m.php")
                val title = titleRe.find(after)?.groupValues?.get(1)
                    ?: titleRe.findAll(before).lastOrNull()?.groupValues?.get(1) ?: ""
                if (emitMedia(m.value, title, label, origin, sent, callback)) ok = true
            }
        }
        return ok
    }

    private fun apiEndpoints(f: String, origin: String, texts: List<String>): List<String> {
        val vid = Regex("""[?&]v=([A-Za-z0-9_%.=-]+)""").find(f)?.groupValues?.get(1) ?: ""
        val out = LinkedHashSet<String>()
        val js = styleRe.replace(texts.joinToString("\n"), " ")
        for (m in phpAbsRe.findAll(js)) out.add(fixProto(m.value))
        for (m in phpRelRe.findAll(js)) {
            val r = m.groupValues[1]
            out.add(if (r.startsWith("/")) origin + r else "$origin/${r.removePrefix("./")}")
        }
        if (vid.isNotBlank()) {
            for (g in listOf("track.php", "nsub.php", "source2.php")) out.add("$origin/$g?v=$vid")
        }
        return out.map { if (it.endsWith("=") && vid.isNotBlank()) it + vid else it }
            .filter { !apiSkipRe.containsMatchIn(it) && !badHostRe.containsMatchIn(it) && it.length < 4000 }
            .distinct().take(10)
    }

    private suspend fun getAny(u: String, h: Map<String, String>): String {
        try {
            val r = app.get(u, headers = h)
            if (r.code == 200 && !isCf(r.code, r.text)) return r.text
            if (r.code != 403 && r.code != 503) return ""
        } catch (e: Exception) {
            rethrow(e)
        }
        return try {
            val r = app.get(u, headers = h, interceptor = cf)
            if (r.code == 200) r.text else ""
        } catch (e: Exception) {
            rethrow(e)
            ""
        }
    }

    private fun walkJson(x: Any?, path: String, out: MutableList<Triple<String, String, String>>, depth: Int = 0) {
        if (depth > 8) return
        when (x) {
            is JSONObject -> {
                var url: String? = null
                for (k in listOf("file", "url", "src", "link", "path", "vtt", "srt", "source")) {
                    val v = x.opt(k)
                    if (v is String && v.isNotBlank()) {
                        url = v
                        break
                    }
                }
                if (url != null) {
                    var lb = ""
                    for (k in listOf("label", "lang", "language", "srclang", "name", "title", "kind")) {
                        val v = x.opt(k)
                        if (v is String && v.isNotBlank()) {
                            lb = v
                            break
                        }
                    }
                    out.add(Triple(path, url, lb))
                }
                val ks = x.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    walkJson(x.opt(k), "$path/$k", out, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until x.length()) walkJson(x.opt(i), path, out, depth + 1)
            is String -> if (x.startsWith("http") || x.startsWith("//")) out.add(Triple(path, x, ""))
        }
    }

    private suspend fun harvestApi(
        f: String,
        origin: String,
        texts: List<String>,
        label: String,
        sent: MutableSet<String>,
        subSent: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Pair<Boolean, Int> {
        val eps = apiEndpoints(f, origin, texts).filter { it !in subSent && it !in sent }
        if (eps.isEmpty()) return false to 0
        d("api uc noktalari=${eps.size} " + eps.joinToString(" | ") { it.takeLast(70) })
        val hdr = mapOf(
            "User-Agent" to ua, "Referer" to f, "Origin" to origin,
            "X-Requested-With" to "XMLHttpRequest", "Accept" to "*/*"
        )
        val bodies = eps.amap { u -> u to getAny(u, hdr) }
        var gotVideo = false
        var subs = 0
        val subWord = Regex("(?i)sub|track|caption|altyaz")
        for ((u, b) in bodies) {
            if (b.isBlank()) continue
            val tag = u.substringBefore("?").substringAfterLast("/").lowercase()
            d("api $tag len=${b.length}: " + b.take(220).replace(Regex("\\s+"), " "))
            val subEp = subWord.containsMatchIn(tag)
            val direct = toVtt(b)
            if (direct != null) {
                if (subSent.add(u)) {
                    val local = SelcukSubProxy.put(direct.toByteArray(Charsets.UTF_8))
                    subtitleCallback(SubtitleFile(subLang(u, ""), local ?: u))
                    subs++
                }
                continue
            }
            if (b.trimStart().startsWith("#EXTM3U")) {
                if (emitHls(u, "", label, origin, sent, callback)) gotVideo = true
                continue
            }
            val found = ArrayList<Triple<String, String, String>>()
            val root: Any? = try {
                JSONTokener(b.trim()).nextValue()
            } catch (e: Exception) {
                null
            }
            if (root is JSONObject || root is JSONArray) {
                walkJson(root, tag, found)
            } else {
                val t = unescape(b)
                for (m in m3u8Re.findAll(t)) found.add(Triple(tag, m.value, ""))
                for (m in mphpRe.findAll(t)) found.add(Triple(tag, m.value, ""))
                for ((su, sl) in findSubs(listOf(t), origin)) found.add(Triple("sub", su, sl))
            }
            val seenU = HashSet<String>()
            for ((path, raw, lb) in found) {
                var u0 = fixProto(raw.trim())
                if (u0.startsWith("/")) u0 = origin + u0
                if (!u0.startsWith("http") || !seenU.add(u0)) continue
                if (imgRe.containsMatchIn(u0) || badHostRe.containsMatchIn(u0) || u0.contains("tmdb", true)) continue
                val isSub = subRe.containsMatchIn(u0) || subEp || subWord.containsMatchIn(path)
                if (isSub) {
                    if (emitSub(u0, lb, f, subSent, subtitleCallback)) subs++
                } else if (u0.contains(".m3u8", true) || u0.contains(".mp4", true) || u0.contains("/m.php") || u0.contains("master")) {
                    if (emitMedia(u0, lb, label, origin, sent, callback)) gotVideo = true
                }
            }
        }
        d("api sonuc video=$gotVideo altyazi=$subs")
        return gotVideo to subs
    }

    // Tek bir oynatıcı adresinden (iframe.php / l.php ...) video + altyazı çıkarır
    private suspend fun resolveFrame(
        f: String,
        label: String,
        sent: MutableSet<String>,
        subSent: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        info: StringBuilder,
        depth: Int = 0
    ): Boolean {
        val origin = originOf(f)

        val rawIfr = fetch(f, "$mainUrl/")
        val texts = allTexts(rawIfr)
        val ifr = texts.firstOrNull() ?: ""
        val cfErr = ifr.contains("cf-error", true) || ifr.contains("cf.errors", true) ||
            ifr.contains("Attention Required", true)
        d("$label iframe len=${rawIfr.length} cfHata=$cfErr url=${f.take(70)}")

        val subMap = findSubs(texts, origin)
        var subOk = 0
        for ((su, sl) in subMap) {
            if (emitSub(su, sl, f, subSent, subtitleCallback)) subOk++
        }

        var got = false
        curFrame = f
        videoOk = true
        curHosts = hostsOf(listOf(rawIfr))
        try {
            val np = npConfig(rawIfr)
            if (np != null) {
                val srcs = np.optJSONArray("sources")
                if (srcs != null) {
                    for (i in 0 until srcs.length()) {
                        val so = srcs.optJSONObject(i) ?: continue
                        val master = so.optString("master", "")
                        if (master.isBlank()) continue
                        if (emitHls(master, so.optString("title", ""), label, origin, sent, callback)) got = true
                    }
                }
                val sb = np.optJSONArray("subtitles")
                if (sb != null) {
                    for (i in 0 until sb.length()) {
                        val so = sb.optJSONObject(i) ?: continue
                        val su = so.optString("url", "")
                        if (su.isBlank()) continue
                        var lb = so.optString("label", "")
                        if (lb.isBlank()) lb = so.optString("lang", "")
                        if (so.optBoolean("forced", false) && !lb.contains("forced", true)) lb += " Forced"
                        if (emitSub(su, lb, f, subSent, subtitleCallback)) subOk++
                    }
                }
                d("np config kaynak=${srcs?.length() ?: 0} altyazi=${sb?.length() ?: 0} ok=$got/$subOk")
            }
        } catch (e: Exception) {
            rethrow(e)
            d("np hata ${e.javaClass.simpleName} ${e.message}")
        }
        videoOk = false
        if (got) npDone = true

        // l.php: içinden gerçek oynatıcı (iframe.php) çıkabilir; onu çöz
        if (!got && depth < 2 && f.contains("/l.php")) {
            val inner = LinkedHashSet<String>()
            for (t in texts) {
                iframeRe.findAll(t).forEach { inner.add(it.value) }
                ifrTagRe.findAll(t).forEach { m ->
                    val u = fixProto(m.groupValues[1])
                    if (u.startsWith("http")) inner.add(u)
                }
            }
            inner.remove(f)
            d("$label l.php ic oynatici=${inner.size}")
            for (iu in inner.take(3)) {
                if (badHostRe.containsMatchIn(iu)) continue
                val r = try {
                    resolveFrame(iu, label, sent, subSent, subtitleCallback, callback, info, depth + 1)
                } catch (e: Exception) {
                    rethrow(e)
                    false
                }
                if (r) return true
            }
        }

        // HIZLI YOL: NP_CONFIG yoksa CloudStream'in hazır çıkarıcısı hemen denenir; link gelirse yavaş adımlar atlanır
        if (!got) {
            val fast = ArrayList<ExtractorLink>()
            try {
                loadExtractor(f, "$mainUrl/", subtitleCallback) { l -> fast.add(l) }
            } catch (e: Exception) {
                rethrow(e)
                d("hizli cikarici hata ${e.javaClass.simpleName}")
            }
            d("$label hizli cikarici link=${fast.size}")
            if (fast.isNotEmpty()) {
                for (l in fast) callback(l)
                info.append("[${origin.removePrefix("https://").removePrefix("http://").take(22)} hizli=${fast.size}] ")
                return true
            }
        }

        try {
            if (emitPlaylist(texts, origin, label, sent, callback)) got = true
        } catch (e: Exception) {
            rethrow(e)
        }
        try {
            val r = harvestApi(f, origin, texts, label, sent, subSent, subtitleCallback, callback)
            if (r.first) got = true
            subOk += r.second
        } catch (e: Exception) {
            rethrow(e)
            d("api hata ${e.javaClass.simpleName}")
        }

        // açık / gizlenmiş video adresleri
        val cands = mediaCandidates(texts)
        d("$label aday=${cands.size} ${cands.take(4).joinToString(" | ") { it.take(90) }}")
        if (!got && emitCandidates(cands, origin, label, sent, callback)) got = true

        // sayfadaki api / player uç noktalarını dene
        if (!got && rawIfr.isNotBlank() && !cfErr) {
            val ends = LinkedHashSet<String>()
            Regex("[\"'](/[A-Za-z0-9_\\-/]*(?:api|source|player|video|stream|ajax|embed|get)[A-Za-z0-9_\\-/.?=&%]*)[\"']", RegexOption.IGNORE_CASE)
                .findAll(ifr).forEach { ends.add(it.groupValues[1]) }
            for (ep in ends.take(4)) {
                val r = try {
                    app.get(
                        origin + ep,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to f,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "*/*"
                        )
                    ).text
                } catch (e: Exception) {
                    rethrow(e)
                    ""
                }
                if (r.isBlank()) continue
                val rt = allTexts(r)
                val c2 = mediaCandidates(rt)
                for ((su, sl) in findSubs(rt, origin)) {
                    if (emitSub(su, sl, f, subSent, subtitleCallback)) subOk++
                }
                if (emitPlaylist(rt, origin, label, sent, callback)) got = true
                if (emitCandidates(c2, origin, label, sent, callback)) got = true
                if (got) break
            }
        }

        // son çare: WebView ile ağdan video adresi yakala (en fazla 2 kez)
        if (!got && sent.isEmpty() && webCount < 2 && !cfErr) {
            webCount++
            val w = try {
                val r = app.get(
                    f,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(
                        Regex("""(?i)\.(?:m3u8|mp4)(?:\?|$)|master\.txt|cf-master|/m\.php\?"""),
                        userAgent = ua,
                        useOkhttp = true,
                        timeout = 15_000L
                    ),
                    timeout = 25L
                )
                r.url.takeIf { it != f && Regex("""(?i)\.(?:m3u8|mp4|txt)|/m\.php""").containsMatchIn(it) }
            } catch (e: Exception) {
                rethrow(e)
                d("webview hata ${e.javaClass.simpleName}")
                null
            }
            d("$label webview=${w?.take(90)}")
            if (w != null && emitMedia(w, "", label, origin, sent, callback)) got = true
        }

        if (!got) {
            val scripts = Regex("(?is)<script[^>]*>(.*?)</script>").findAll(ifr).map { it.groupValues[1].trim() }
                .filter { it.length > 20 }.joinToString(" ¶ ")
            d("$label BULUNAMADI. iframe ozet:")
            dLong("ifr", (if (scripts.isNotBlank()) scripts else ifr).replace(Regex("\\s+"), " ").take(5000))
        }
        info.append("[${origin.removePrefix("https://").removePrefix("http://").take(22)} len=${rawIfr.length} cf=$cfErr aday=${cands.size} altyazi=${subMap.size}/$subOk ok=$got] ")
        return got
    }

    private suspend fun debugLink(callback: (ExtractorLink) -> Unit, text: String) {
        callback(
            newExtractorLink(name, "DEBUG $text".take(600), "https://debug.invalid/x.m3u8", ExtractorLinkType.M3U8) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun processPage(
        pageUrl: String,
        sent: MutableSet<String>,
        subSent: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        info: StringBuilder
    ): Boolean {
        var ok = false
        val pg = fetchFull(pageUrl, "$mainUrl/")
        val html = unescape(pg.second)
        info.append("sayfa=${pg.first}/${pg.second.length} ")
        val pageTexts = allTexts(pg.second)
        val trailers = trailerRe.findAll(html).filter { it.groupValues[2] == "true" }.map { it.groupValues[1] }.toSet()
        val frames = playerCandidates(pageTexts, trailers)
        d("oynatici adayi=${frames.size} ${frames.take(5).joinToString(" | ") { it.take(80) }}")
        info.append("oynatici=${frames.size} ")

        try {
            for ((su, sl) in findSubs(pageTexts, originOf(mainUrl))) emitSub(su, sl, pageUrl, subSent, subtitleCallback)
        } catch (e: Exception) {
            rethrow(e)
        }

        for (f in frames) {
            // iframe.php ile kaynak bulunduysa aynı oynatıcının l.php adresi tekrar çözülmez
            if (ok && f.contains("/l.php")) continue
            srcCount++
            val origin = originOf(f)
            val label = "Kaynak $srcCount (${origin.removePrefix("https://").removePrefix("http://")})"
            val got = try {
                resolveFrame(f, label, sent, subSent, subtitleCallback, callback, info)
            } catch (e: Exception) {
                rethrow(e)
                d("$label hata ${e.javaClass.simpleName} ${e.message}")
                info.append("[hata ${e.javaClass.simpleName}] ")
                false
            }
            if (got) {
                ok = true
                if (npDone) break
            }
        }

        if (!ok) {
            val scripts = Regex("(?is)<script[^>]*>(.*?)</script>").findAll(pg.second).map { it.groupValues[1].trim() }
                .filter { it.length > 20 }.joinToString(" ¶ ")
            d("BULUNAMADI sayfa=${pg.first} len=${pg.second.length}. sayfa ozet:")
            dLong("pg", (if (scripts.isNotBlank()) scripts else pg.second).replace(Regex("\\s+"), " ").take(5000))
        }
        return ok
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        webCount = 0
        npDone = false
        srcCount = 0
        subCount.clear()
        val info = StringBuilder()
        val sent = HashSet<String>()
        val subSent = HashSet<String>()
        var ok = false

        val pageUrl = data.split("|").map { it.trim() }.firstOrNull { it.startsWith("http") } ?: data
        try {
            ok = processPage(pageUrl, sent, subSent, subtitleCallback, callback, info)
        } catch (e: Exception) {
            rethrow(e)
            d("sayfa hata ${e.javaClass.simpleName} ${e.message}")
        }

        // hiçbiri çıkmadıysa: sayfayı gerçek WebView'da aç, oynatıcı / video isteğini yakala
        if (!ok) {
            val w = try {
                val r = app.get(
                    pageUrl,
                    referer = "$mainUrl/",
                    interceptor = WebViewResolver(
                        Regex("""(?i)iframe\.php\?v=|l\.php\?v=|\.m3u8|\.mp4|/embed/|master\.txt"""),
                        userAgent = ua,
                        useOkhttp = false,
                        timeout = 25_000L
                    ),
                    timeout = 35L
                )
                r.url.takeIf { it != pageUrl }
            } catch (e: Exception) {
                rethrow(e)
                d("sayfa webview hata ${e.javaClass.simpleName}")
                null
            }
            d("sayfa webview=${w?.take(100)}")
            info.append("webview=${if (w != null) "var" else "yok"} ")
            if (w != null) {
                val origin = originOf(w)
                srcCount++
                val label = "Kaynak $srcCount (${origin.removePrefix("https://").removePrefix("http://")})"
                if (Regex("""(?i)\.(?:m3u8|mp4)(?:\?|$)""").containsMatchIn(w)) {
                    if (sent.add(w)) {
                        emitLink(
                            w,
                            if (w.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                            label,
                            origin,
                            callback
                        )
                        ok = true
                    }
                } else {
                    val got = try {
                        resolveFrame(w, label, sent, subSent, subtitleCallback, callback, info)
                    } catch (e: Exception) {
                        rethrow(e)
                        false
                    }
                    if (got) ok = true
                }
            }
        }

        if (!ok && debug) {
            debugLink(callback, info.toString())
            return true
        }
        return ok
    }

    private fun b64bin(s: String): ByteArray {
        var c = s.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        while (c.length % 4 != 0) c += "="
        return try {
            Base64.decode(c, Base64.DEFAULT)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun deepTexts(texts: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in texts) {
            if (Regex("\\\\x[0-9a-fA-F]{2}|\\\\u[0-9a-fA-F]{4}").containsMatchIn(t)) {
                var dd = Regex("\\\\x([0-9a-fA-F]{2})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
                dd = Regex("\\\\u([0-9a-fA-F]{4})").replace(dd) { it.groupValues[1].toInt(16).toChar().toString() }
                out.add(dd)
            }
            if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(t)) {
                try {
                    out.add(URLDecoder.decode(t.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"), "UTF-8"))
                } catch (e: Exception) {
                }
            }
            Regex("[A-Za-z0-9+/_\\-]{28,}={0,2}").findAll(t).take(400).forEach { m ->
                val bytes = b64bin(m.value)
                if (bytes.isNotEmpty()) {
                    val dd = String(bytes, Charsets.ISO_8859_1)
                    if (dd.length > 12 && Regex("https?://|\\.m3u8|\\.mp4|\"file\"|sources", RegexOption.IGNORE_CASE).containsMatchIn(dd)) {
                        out.add(dd)
                    }
                }
            }
        }
        return out.toList()
    }

    // Dean Edwards packer çözücü
    private fun unpackAll(text: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("""\}\('((?:\\.|[^'\\])*)',\s*(\d+),\s*(\d+),\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)""")
        re.findAll(text).forEach { m ->
            try {
                var p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                val a = m.groupValues[2].toInt()
                var c = m.groupValues[3].toInt()
                val k = m.groupValues[4].split("|")
                fun enc(n: Int): String {
                    val hi = if (n < a) "" else enc(n / a)
                    val r = n % a
                    return hi + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
                }
                while (c-- > 0) {
                    if (c < k.size && k[c].isNotEmpty()) {
                        val word = k[c]
                        p = Regex("\\b" + Regex.escape(enc(c)) + "\\b").replace(p) { word }
                    }
                }
                out.add(p)
            } catch (e: Exception) {
            }
        }
        return out
    }
}

// ----------------------------------------------------------------------
// Yerel altyazı sunucusu (127.0.0.1): çektiğimiz altyazıyı oynatıcıya verir
// ----------------------------------------------------------------------
private object SelcukSubProxy {
    private val store = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port = 0

    @Synchronized
    fun ensure(): Boolean {
        val cur = server
        if (cur != null && !cur.isClosed) return true
        return try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            val t = Thread {
                while (!s.isClosed) {
                    try {
                        val c = s.accept()
                        val w = Thread { serve(c) }
                        w.isDaemon = true
                        w.start()
                    } catch (e: Exception) {
                        if (s.isClosed) break
                    }
                }
            }
            t.isDaemon = true
            t.name = "selcuk-sub-proxy"
            t.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun put(bytes: ByteArray): String? {
        if (!ensure()) return null
        if (store.size > 80) store.clear()
        val id = java.lang.Long.toString(System.nanoTime(), 36)
        store[id] = bytes
        return "http://127.0.0.1:$port/s/$id.vtt"
    }

    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 15_000
            val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val reqLine = inp.readLine() ?: return
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
            }
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            val id = path.removePrefix("/s/").substringBefore("?").removeSuffix(".vtt")
            val os = sock.getOutputStream()
            val body = store[id]
            if (body == null) {
                os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                os.flush()
                return
            }
            val head = "HTTP/1.1 200 OK\r\nContent-Type: text/vtt; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            os.write(head.toByteArray())
            if (method != "HEAD") os.write(body)
            os.flush()
        } catch (ex: Exception) {
        } finally {
            try {
                sock.close()
            } catch (ex: Exception) {
            }
        }
    }
}
