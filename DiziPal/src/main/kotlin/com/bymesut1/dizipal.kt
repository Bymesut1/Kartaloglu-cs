package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1592.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val tag = "DiziPal"

    private val cf = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // Sitedeki aynı listeler iki kez geliyor (pop/popM, sitePop/sitePopM, epsBy.all/eps, classics/fPop):
    // her listeden yalnızca biri eklendi, tekrar yok.
    override val mainPage = mainPageOf(
        "home:trend" to "Bu Hafta Trend Diziler",
        "home:popM" to "Sitede Popüler",
        "home:sitePopM" to "Trend Diziler",
        "home:top" to "Şu An En Çok İzlenen",
        "home:eps" to "Güncel Bölümler",
        "home:epsBy:yerli" to "Yerli Güncel Bölümler",
        "home:epsBy:anime" to "Anime Güncel Bölümler",
        "home:epsBy:asya" to "Asya Güncel Bölümler",
        "home:fPop" to "Popüler Filmler",
        "home:fTrend" to "Trend Filmler",
        "home:fTop" to "En Çok İzlenen Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Tüm Diziler",
        "$mainUrl/film-izle" to "Tüm Filmler",
        "$mainUrl/trendler" to "Trendler"
    )

    // ---------------- Ortak yardımcılar ----------------

    private fun rethrow(e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
    }

    private fun isBlocked(code: Int, t: String): Boolean =
        code == 403 || code == 429 || code == 503 ||
            (t.length < 30_000 && (t.contains("Just a moment", true) || t.contains("cf-chl", true) ||
                t.contains("Attention Required", true)))

    // Site başka bir dizipal adresine yönlendirirse yeni adres kullanılır
    private fun trackDomain(finalUrl: String) {
        val o = originOf(finalUrl)
        if (o != mainUrl && Regex("""^https?://dizipal[0-9a-z.\-]*\.[a-z]+$""", RegexOption.IGNORE_CASE).matches(o)) {
            Log.d(tag, "alan adı değişti: $mainUrl -> $o")
            mainUrl = o
        }
    }

    // En fazla 3 deneme: 1) normal  2) Cloudflare aşıcı  3) normal (kısa beklemeyle)
    // Engel / hata / zaman aşımı olursa yeniden dener; engel yoksa gelen yanıtı (boş olsa da) döndürür.
    private suspend fun safeGet(url: String, referer: String? = null): String {
        val h = if (referer != null) headers + mapOf("Referer" to referer) else headers
        for (attempt in 1..3) {
            try {
                val r = if (attempt == 2) {
                    app.get(url, headers = h, interceptor = cf, timeout = 30L)
                } else {
                    app.get(url, headers = h, timeout = 25L)
                }
                trackDomain(r.url)
                if (!isBlocked(r.code, r.text)) return r.text
                Log.e(tag, "get engel kod=${r.code} len=${r.text.length} deneme=$attempt ${url.take(80)}")
            } catch (e: Exception) {
                rethrow(e)
                Log.e(tag, "get hata ${e.javaClass.simpleName} deneme=$attempt ${url.take(80)}")
            }
            if (attempt < 3) delay(600L * attempt)
        }
        return ""
    }

    private fun absU(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> u
    }

    private fun originOf(u: String): String =
        Regex("""^https?://[^/]+""").find(u)?.value ?: mainUrl

    private fun cleanText(s: String): String =
        s.replace("\u200B", "").replace("\u00A0", " ").trim()

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"").replace("&amp;", "&")

    private fun extractRsc(html: String): String {
        val sb = StringBuilder()
        val regex = Regex("""self\.__next_f\.push\(\[1,("(?:[^"\\]|\\.)*")\]\)""")
        regex.findAll(html).forEach { m ->
            try {
                val s = JSONTokener(m.groupValues[1]).nextValue() as String
                sb.append(s)
            } catch (_: Exception) {
            }
        }
        return sb.toString()
    }

    // Dizeleri dikkate alarak dengeli parantez bloğunu keser
    private fun balanced(text: String, start: Int, open: Char, close: Char): String? {
        if (text.getOrNull(start) != open) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
                continue
            }
            if (ch == '"') {
                inStr = true
                continue
            }
            if (ch == open) depth++
            else if (ch == close) {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    // "key": hemen ardından '[' gelen ilk geçerli diziyi alır ("top":0 gibi sahte eşleşmeleri atlar)
    private fun arrayAfterKey(text: String, key: String): JSONArray? {
        var from = 0
        while (true) {
            val idx = text.indexOf(key, from)
            if (idx < 0) return null
            var p = idx + key.length
            while (p < text.length && text[p].isWhitespace()) p++
            if (p < text.length && text[p] == '[') {
                val s = balanced(text, p, '[', ']')
                if (s != null) {
                    try {
                        return JSONArray(s)
                    } catch (_: Exception) {
                    }
                }
            }
            from = idx + 1
        }
    }

    // "key": ardından '[' gelen tüm geçerli diziler (en fazla 6)
    private fun arraysAfterKey(text: String, key: String): List<JSONArray> {
        val out = ArrayList<JSONArray>()
        var from = 0
        while (out.size < 6) {
            val idx = text.indexOf(key, from)
            if (idx < 0) break
            from = idx + key.length
            var p = from
            while (p < text.length && text[p].isWhitespace()) p++
            if (p < text.length && text[p] == '[') {
                val s = balanced(text, p, '[', ']')
                if (s != null) {
                    try {
                        out.add(JSONArray(s))
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return out
    }

    private fun objectAfterKey(text: String, key: String): JSONObject? {
        var from = 0
        while (true) {
            val idx = text.indexOf(key, from)
            if (idx < 0) return null
            var p = idx + key.length
            while (p < text.length && text[p].isWhitespace()) p++
            if (p < text.length && text[p] == '{') {
                val s = balanced(text, p, '{', '}')
                if (s != null) {
                    try {
                        return JSONObject(s)
                    } catch (_: Exception) {
                    }
                }
            }
            from = idx + 1
        }
    }

    // ---------------- Kartlar ----------------

    private fun itemToResponse(o: JSONObject): Pair<String, SearchResponse>? {
        val title = cleanText(o.optString("t", "").ifBlank { o.optString("title", "") })
        if (title.isBlank()) return null
        val href = o.optString("href", "")
        if (href.isBlank()) return null
        val img = o.optString("img", "").substringBefore("#").ifBlank { null }
        val meta = o.optString("meta", "")
        val year = o.optString("year", "").toIntOrNull()
            ?: Regex("""(?:^|\D)((?:19|20)\d{2})(?:\D|$)""").find(meta)?.groupValues?.get(1)?.toIntOrNull()
        val path = href.replace(Regex("""^https?://[^/]+"""), "")
        val full = if (href.startsWith("http")) href else "$mainUrl$href"
        return when {
            path.startsWith("/movies/") ->
                full to newMovieSearchResponse(title, full, TvType.Movie) {
                    this.posterUrl = img
                    this.year = year
                }
            path.startsWith("/series/") ->
                full to newTvSeriesSearchResponse(title, full, TvType.TvSeries) {
                    this.posterUrl = img
                    this.year = year
                }
            // bölüm kartı: tıklanınca load() dizi sayfasını bulur; aynı dizi tekrar eklenmesin diye başlığa göre tekilleştirilir
            path.startsWith("/bolum/") ->
                ("t:" + title.lowercase()) to newTvSeriesSearchResponse(title, full, TvType.TvSeries) {
                    this.posterUrl = img
                    this.year = year
                }
            else -> null
        }
    }

    private fun parseItems(arr: JSONArray): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val (k, r) = itemToResponse(o) ?: continue
            if (!out.containsKey(k)) out[k] = r
        }
        return out.values.toList()
    }

    // Liste sayfalarında (anahtarı bilinmeyen) dizi / film kartlarını RSC içinden toplar
    private fun collectItems(rsc: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val re = Regex("\"href\":\"(/(?:series|movies)/[^\"]+)\"")
        for (m in re.findAll(rsc)) {
            val pos = m.range.first
            var j = rsc.lastIndexOf('{', pos)
            var tries = 0
            while (j >= 0 && tries < 4) {
                val s = balanced(rsc, j, '{', '}')
                if (s != null && j + s.length > pos) {
                    val o = try {
                        JSONObject(s)
                    } catch (_: Exception) {
                        null
                    }
                    if (o != null) {
                        val p = itemToResponse(o)
                        if (p != null && !out.containsKey(p.first)) out[p.first] = p.second
                    }
                    break
                }
                j = if (j > 0) rsc.lastIndexOf('{', j - 1) else -1
                tries++
            }
        }
        return out.values.toList()
    }

    // ---------------- Ana sayfa ----------------

    @Volatile
    private var homeCache: Pair<Long, String>? = null
    private val firstKeys = ConcurrentHashMap<String, Set<String>>()

    private suspend fun homeRsc(): String {
        val c = homeCache
        val now = System.currentTimeMillis()
        if (c != null && c.second.isNotEmpty() && now - c.first < 60_000L) return c.second
        val rsc = extractRsc(safeGet("$mainUrl/", mainUrl))
        if (rsc.isNotEmpty()) homeCache = now to rsc
        return rsc
    }

    private fun homeArray(rsc: String, path: String): JSONArray? {
        return if (path.startsWith("epsBy:")) {
            objectAfterKey(rsc, "\"epsBy\":")?.optJSONArray(path.removePrefix("epsBy:"))
        } else {
            arrayAfterKey(rsc, "\"$path\":")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = request.data
        val empty = newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        return try {
            if (key.startsWith("home:")) {
                if (page > 1) return empty
                val arr = homeArray(homeRsc(), key.removePrefix("home:")) ?: return empty
                val items = parseItems(arr)
                Log.d(tag, "ana sayfa ${request.name}: ${items.size} öğe")
                newHomePageResponse(request.name, items, hasNext = false)
            } else {
                val url = if (page <= 1) key else key + (if (key.contains("?")) "&" else "?") + "page=$page"
                val items = collectItems(extractRsc(safeGet(url, mainUrl)))
                val ks = items.map { it.url }.toSet()
                if (page <= 1) {
                    firstKeys[key] = ks
                } else if (items.isEmpty() || firstKeys[key]?.containsAll(ks) == true) {
                    return empty
                }
                Log.d(tag, "liste ${request.name} sayfa=$page: ${items.size} öğe")
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
        } catch (e: Exception) {
            rethrow(e)
            Log.e(tag, "getMainPage hata: ${e.message}")
            empty
        }
    }

    // ---------------- Arama ----------------

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val body = JSONObject().apply {
                put("action", "searchContent")
                put("params", JSONArray().put(query))
            }

            val resp = app.post(
                "$mainUrl/api/mj",
                headers = headers + mapOf(
                    "Content-Type" to "application/json",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/kesfet"
                ),
                json = body
            ).text

            val root = JSONObject(resp)
            if (!root.optBoolean("ok", false)) {
                Log.e(tag, "search: ok=false")
                return emptyList()
            }
            val results = root.optJSONArray("result") ?: return emptyList()
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val node = results.optJSONObject(i) ?: continue
                val slug = node.optString("used_slug", "").ifBlank { continue }
                val type = node.optString("used_type", "")
                val title = cleanText(node.optString("object_name", "")).ifBlank { continue }
                val poster = node.optString("object_poster_url", "").ifBlank { null }
                val year = node.optInt("object_release_year", 0).takeIf { it > 0 }
                val imdb = node.optDouble("object_related_imdb_point", 0.0).takeIf { it > 0 }
                val fullUrl = if (slug.startsWith("http")) slug else "$mainUrl/${slug.trimStart('/')}"
                val isMovie = type.equals("Movies", ignoreCase = true) || slug.trimStart('/').startsWith("movies/")
                if (isMovie) {
                    items.add(newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                        this.score = imdb?.let { Score.from10(it) }
                    })
                } else {
                    items.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                        this.score = imdb?.let { Score.from10(it) }
                    })
                }
            }
            Log.d(tag, "search: ${items.size} sonuç")
            items.distinctBy { it.url }
        } catch (e: Exception) {
            rethrow(e)
            Log.e(tag, "search hata: ${e.message}")
            emptyList()
        }
    }

    // ---------------- Detay ----------------

    // /bolum/xxx-4x1(-c05) adresinden dizi sayfasını (/series/...) bulur
    private suspend fun resolveSeriesUrl(bolumUrl: String): String? {
        val slug = bolumUrl.substringBefore("?").trimEnd('/').substringAfterLast("/")
        val m = Regex("""^(.*?)-(\d+)x(\d+)(-c\d+)?$""").find(slug)
        val name0 = m?.groupValues?.get(1) ?: slug
        val suffix = m?.groupValues?.get(4).orEmpty()
        val html = safeGet(bolumUrl, mainUrl)
        val hay = html + "\n" + extractRsc(html)
        val found = Regex("""/series/([a-z0-9\-]+)""").findAll(hay).map { it.groupValues[1] }.distinct().toList()
        val exactRe = Regex("^" + Regex.escape(name0) + "(-c\\d+)?$")
        val pick = found.firstOrNull { it == name0 + suffix }
            ?: found.firstOrNull { it == name0 }
            ?: found.firstOrNull { exactRe.matches(it) }
            ?: found.firstOrNull { it.startsWith("$name0-") }
        return "$mainUrl/series/${pick ?: (name0 + suffix)}"
    }

    // Site başlıkları bazen "1. Bölüm", "S7:B1 1. Bölüm" gibi anlamsız / yinelenen geliyor: temizlenir.
    // Geriye anlamlı bir ad kalmazsa null döner (CloudStream kendi "Bölüm N" yazısını gösterir).
    // Not: Android regex motoru "[:" dizisini POSIX sınıfı sanıyor; bu yüzden köşeli parantez yerine (?:a|b) kullanıldı.
    private fun cleanEpName(raw: String): String? {
        var t = cleanText(raw)
        t = t.replace(Regex("""^S\d+\s*(?:\.|:)\s*B\d+\s*(?:-|–|:)?\s*"""), "")
        t = t.replace(Regex("""^\d+\s*(?:\.|\)|-)\s*"""), "")
        if (t.isBlank()) return null
        if (Regex("""(?i)^(\d+\s*\.?\s*)?(bölüm|bolum)(\s*\d+)?$""").matches(t)) return null
        if (Regex("""(?i)^\d*\.?\s*sezon\s*\d+\.?\s*\d*\.?\s*b[öo]l[üu]m.*$""").matches(t) && t.length < 30) return null
        return t
    }

    // Açılan sayfalar 10 dakika saklanır: tekrar açınca anında gelir, anlık ağ sorununda hata vermez
    private val loadCache = ConcurrentHashMap<String, Pair<Long, LoadResponse>>()

    override suspend fun load(url: String): LoadResponse? {
        val now = System.currentTimeMillis()
        val c = loadCache[url]
        if (c != null && now - c.first < 10 * 60_000L) return c.second
        val r = loadUncached(url)
        if (r != null) {
            if (loadCache.size > 60) loadCache.clear()
            loadCache[url] = now to r
        }
        return r
    }

    private suspend fun loadUncached(url: String): LoadResponse? {
        return try {
            var pageUrl = url
            if (pageUrl.contains("/bolum/")) {
                pageUrl = resolveSeriesUrl(pageUrl) ?: return null
                Log.d(tag, "bölüm -> dizi: $pageUrl")
            }
            val html = safeGet(pageUrl, mainUrl)
            val rsc = extractRsc(html)
            val data = extractDataObject(rsc)
                ?: run {
                    Log.e(tag, "load: data bulunamadı html=${html.length} rsc=${rsc.length} baş=${html.take(160).replace("\n", " ")}")
                    return null
                }

            val ldDescription = extractLdJsonDescription(html)
            val title = cleanText(data.optString("title", "")).ifBlank { return null }
            val slots = data.optJSONObject("slots")
            val poster = slots?.optString("mjPoster")?.ifBlank { null }
            val year = slots?.optString("mjYear")?.toIntOrNull()
            val imdb = slots?.optString("mjImdb")?.toDoubleOrNull()
            val genres = mutableListOf<String>()
            slots?.optJSONArray("mjGenres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val label = arr.optJSONObject(i)?.optString("label", "") ?: ""
                    if (label.isNotBlank()) genres.add(label)
                }
            }

            val isMovie = data.optString("page", "") == "Movies" || pageUrl.contains("/movies/")

            if (isMovie) {
                val duration = data.optInt("runtimeMin", 0).takeIf { it > 0 }
                newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = ldDescription
                    this.tags = genres.ifEmpty { null }
                    this.duration = duration
                    this.score = imdb?.let { Score.from10(it) }
                }
            } else {
                val seasons = data.optJSONArray("seasons") ?: return null
                val episodes = mutableListOf<Episode>()
                val hrefNum = Regex("""-(\d+)x(\d+)(?:-c\d+)?/?$""")
                for (si in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(si) ?: continue
                    val seasonNo = season.optInt("no", si + 1)
                    val eps = season.optJSONArray("episodes") ?: continue
                    for (ei in 0 until eps.length()) {
                        val ep = eps.optJSONObject(ei) ?: continue
                        val epHref = ep.optString("href", "").ifBlank { continue }
                        val epUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                        // sezon / bölüm numarası oynatılacak adresten alınır (…-5x1-c05); yoksa siteden gelen değer
                        val hm = hrefNum.find(epHref.substringBefore("?"))
                        val sNo = hm?.groupValues?.get(1)?.toIntOrNull() ?: seasonNo
                        val eNo = hm?.groupValues?.get(2)?.toIntOrNull() ?: ep.optInt("n", ei + 1)
                        val epName = cleanEpName(ep.optString("title", ""))
                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = sNo
                            this.episode = eNo
                            this.posterUrl = poster
                        })
                    }
                }
                Log.d(tag, "load dizi: $title, ${episodes.size} bölüm")
                newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = ldDescription
                    this.tags = genres.ifEmpty { null }
                    this.score = imdb?.let { Score.from10(it) }
                }
            }
        } catch (e: Exception) {
            rethrow(e)
            Log.e(tag, "load hata: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    // "data":{"id": ... } nesnesini bulur (yedek yol)
    private fun extractDataObject(rsc: String): JSONObject? {
        var from = 0
        val key = "\"data\":{"
        while (true) {
            val idx = rsc.indexOf(key, from)
            if (idx < 0) return null
            val s = balanced(rsc, idx + key.length - 1, '{', '}')
            if (s != null) {
                val o = try {
                    JSONObject(s)
                } catch (_: Exception) {
                    null
                }
                if (o != null && o.has("title") && (o.has("seasons") || o.has("slots") || o.has("page"))) return o
            }
            from = idx + 1
        }
    }

    // ---------------- Oynatma ----------------

    private val badHostRe = Regex("""(?i)google|gstatic|facebook|doubleclick|schema\.org|w3\.org|histats""")
    private val adHostRe = Regex("""(?i)googletagmanager|google-analytics|doubleclick|cdnhipter|blogger\.googleusercontent|rebrand\.ly|//t\.ly/|shortlink|gir\.gg|tinyurl|liverefer|operalink""")
    private val imgRe = Regex("""(?i)\.(?:png|jpe?g|gif|svg|webp|ico|css|js|woff2?|ttf)(?:\?|$)""")
    private val m3u8Re = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")
    private val mp4Re = Regex("""https?://[^"'\s\\<>]+\.mp4[^"'\s\\<>]*""")
    private val mphpRe = Regex("""https?://[^"'\s\\<>]+/m\.php\?v=[^"'\s\\<>]+""")
    private val nmphpRe = Regex("""https?://[^"'\s\\<>]+/nm\.php\?v=[^"'\s\\<>]+""")
    private val fileKeyRe = Regex(
        """["']?(file|src|source|master|hls|stream|m3u8|playlist)["']?\s*[=:]\s*["']((?:https?:)?//[^"']+|/[^"'\s]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val mediaHintRe = Regex("""(?i)\.m3u8|\.mp4|/nm\.php|/m\.php|master|playlist|/hls/""")
    private val subFileRe = Regex("""(?i)\.(?:vtt|srt)(?:\?|$)""")

    private fun withLang(u: String, l: String): String =
        if (u.contains("?")) "$u&lang=$l" else "$u?lang=$l"

    private fun isMediaUrl(u: String): Boolean =
        Regex("""(?i)\.(?:m3u8|mp4)(?:\?|$)""").containsMatchIn(u)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val base = data.trim()
            // Sayfa varsayılan dilde açılır; ayrıca dublaj / altyazı sürümlerine de bakılır (bazı içerikte oynatıcı yalnız orada var)
            val variants: List<Pair<String, String>> = if (base.contains("lang=")) {
                listOf(base to "")
            } else {
                listOf(base to "", withLang(base, "dublaj") to "Türkçe Dublaj", withLang(base, "altyazi") to "Türkçe Altyazı")
            }
            val pages = variants.amap { (u, lb) -> Triple(u, lb, safeGet(u, mainUrl)) }

            val seenFrames = HashSet<String>()
            val doneMedia = HashSet<String>()
            val doneSubs = HashSet<String>()
            var any = false

            for ((u, lb, html) in pages) {
                if (html.isBlank()) continue
                val frames = playerFrames(html)
                Log.d(tag, "loadLinks ${u.takeLast(60)} dil='$lb' oynatıcı=${frames.size}")
                for (f in frames) {
                    if (!seenFrames.add(f)) continue
                    val ok = try {
                        resolveFrame(f, u, lb, subtitleCallback, callback, doneMedia, doneSubs, 0)
                    } catch (e: Exception) {
                        rethrow(e)
                        Log.e(tag, "oynatıcı hata ${e.javaClass.simpleName} ${e.message}")
                        false
                    }
                    if (ok) any = true
                }
            }

            // hiçbir yoldan bulunamadıysa: oynatıcıyı gerçek WebView'da açıp video isteğini yakala
            if (!any) {
                val first = seenFrames.firstOrNull()
                if (first != null) {
                    any = try {
                        webviewFallback(first, base, doneMedia, callback)
                    } catch (e: Exception) {
                        rethrow(e)
                        Log.e(tag, "webview hata ${e.javaClass.simpleName}")
                        false
                    }
                }
            }
            if (!any) Log.e(tag, "loadLinks: kaynak bulunamadı ${base.takeLast(70)}")
            any
        } catch (e: Exception) {
            rethrow(e)
            Log.e(tag, "loadLinks hata: ${e.message}")
            false
        }
    }

    // Sayfadaki tüm oynatıcı adreslerini çıkarır (şifreli + düz + iframe)
    private fun playerFrames(html: String): List<String> {
        val rsc = extractRsc(html)
        val out = LinkedHashSet<String>()

        for (p in findPlayers(rsc)) {
            val enc = p.optJSONObject("enc")
            val key = p.optString("k", "").ifBlank { p.optString("key", "") }
            if (enc != null && key.isNotBlank()) {
                val dec = decryptPlayer(key, enc)
                if (dec != null) out.addAll(urlsFromDecrypted(dec))
                else Log.e(tag, "şifre çözülemedi")
            }
            val ks = p.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                if (k == "enc" || k == "k" || k == "key") continue
                val v = p.opt(k)
                if (v is String && (v.startsWith("http") || v.startsWith("//")) && !imgRe.containsMatchIn(v)) {
                    out.add(absU(v))
                }
            }
        }

        for (m in Regex("""(?i)<iframe[^>]+?src\s*=\s*["']([^"']+)["']""").findAll(html)) {
            val u = absU(m.groupValues[1])
            if (u.startsWith("http") && !adHostRe.containsMatchIn(u)) out.add(u)
        }

        if (out.isEmpty()) {
            val hint = Regex("""(?i)/embed|/player|iframe|/e/|/v/|\.m3u8|\.mp4""")
            Regex("""https?://[^"'\s\\<>]+""").findAll(unescape(rsc)).map { it.value }
                .filter { hint.containsMatchIn(it) && !adHostRe.containsMatchIn(it) && !imgRe.containsMatchIn(it) }
                .distinct().take(6).forEach { out.add(it) }
        }
        return out.filter { !adHostRe.containsMatchIn(it) }
    }

    // "player" / "players" / "embed(s)" nesneleri; olmazsa "enc" bloklarının yakınındaki "k" anahtarı
    private fun findPlayers(rsc: String): List<JSONObject> {
        val list = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        fun add(o: JSONObject) {
            val sig = o.optJSONObject("enc")?.optString("ciphertext", "") ?: o.toString().take(200)
            if (seen.add(sig)) list.add(o)
        }
        for (key in listOf("\"player\":", "\"players\":", "\"embed\":", "\"embeds\":")) {
            var from = 0
            while (true) {
                val idx = rsc.indexOf(key, from)
                if (idx < 0) break
                from = idx + key.length
                var p = from
                while (p < rsc.length && rsc[p].isWhitespace()) p++
                val c = rsc.getOrNull(p) ?: break
                if (c == '{') {
                    val s = balanced(rsc, p, '{', '}') ?: continue
                    try {
                        add(JSONObject(s))
                    } catch (_: Exception) {
                    }
                } else if (c == '[') {
                    val s = balanced(rsc, p, '[', ']') ?: continue
                    try {
                        val arr = JSONArray(s)
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (list.none { it.has("enc") }) {
            var from = 0
            val key = "\"enc\":{"
            while (true) {
                val idx = rsc.indexOf(key, from)
                if (idx < 0) break
                from = idx + 1
                val s = balanced(rsc, idx + key.length - 1, '{', '}') ?: continue
                val enc = try {
                    JSONObject(s)
                } catch (_: Exception) {
                    continue
                }
                val win = rsc.substring(maxOf(0, idx - 700), minOf(rsc.length, idx + s.length + 700))
                val k = Regex("\"k\":\"([^\"]+)\"").find(win)?.groupValues?.get(1) ?: continue
                add(JSONObject().put("k", k).put("enc", enc))
            }
        }
        return list
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(h.length / 2)
        var i = 0
        while (i < h.length) {
            out[i / 2] = ((Character.digit(h[i], 16) shl 4) + Character.digit(h[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private fun looksValid(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 20000) return false
        if (t.any { it.code < 9 || it.code in 14..31 }) return false
        return t.contains("http", true) || t.startsWith("/") || t.startsWith("{") ||
            t.startsWith("[") || t.contains("<iframe", true)
    }

    // PBKDF2 + AES-CBC; bilinen ayar önce, olmazsa diğer olasılıklar denenir
    private fun decryptPlayer(key: String, enc: JSONObject): String? {
        return try {
            val ctB64 = enc.optString("ciphertext", "").ifBlank { enc.optString("ct", "") }
                .ifBlank { enc.optString("data", "") }
            val ivHex = enc.optString("iv", "")
            val saltHex = enc.optString("salt", "")
            if (ctB64.isBlank() || ivHex.isBlank() || saltHex.isBlank()) return null
            val ct = Base64.decode(ctB64, Base64.DEFAULT)
            val iv = hexToBytes(ivHex)
            val salt = hexToBytes(saltHex)
            val hint = enc.optInt("iterations", enc.optInt("iter", 0))
            val iters = listOfNotNull(hint.takeIf { it > 0 }, 999, 1000, 10000).distinct()
            val algos = listOf("PBKDF2WithHmacSHA512", "PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1")
            for (algo in algos) {
                for (n in iters) {
                    try {
                        val spec = PBEKeySpec(key.toCharArray(), salt, n, 256)
                        val keyBytes = SecretKeyFactory.getInstance(algo).generateSecret(spec).encoded
                        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                        val s = String(cipher.doFinal(ct), Charsets.UTF_8)
                        if (looksValid(s)) return s
                    } catch (_: Exception) {
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(tag, "decryptPlayer hata: ${e.message}")
            null
        }
    }

    // Çözülen metin: düz adres, iframe etiketi ya da JSON olabilir
    private fun urlsFromDecrypted(s: String): List<String> {
        val t = s.trim()
        val out = LinkedHashSet<String>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    val ks = x.keys()
                    while (ks.hasNext()) walk(x.opt(ks.next()))
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
                is String -> if ((x.startsWith("http") || x.startsWith("//")) && !imgRe.containsMatchIn(x)) out.add(absU(x))
            }
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                walk(JSONTokener(t).nextValue())
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty() && t.contains("<iframe", true)) {
            Regex("""(?i)src\s*=\s*["']([^"']+)["']""").findAll(t).forEach { out.add(absU(it.groupValues[1])) }
        }
        if (out.isEmpty()) {
            if (t.startsWith("http") || t.startsWith("//") || t.startsWith("/")) {
                out.add(absU(t.lines().first().trim()))
            } else {
                Regex("""https?://[^"'\s<>\\]+""").findAll(t).forEach { out.add(it.value) }
            }
        }
        return out.toList()
    }

    // ---------------- Oynatıcı sayfasından video / altyazı ----------------

    private suspend fun resolveFrame(
        frameUrl: String,
        pageUrl: String,
        langLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        doneMedia: MutableSet<String>,
        doneSubs: MutableSet<String>,
        depth: Int
    ): Boolean {
        val origin = originOf(frameUrl)
        val label = if (langLabel.isBlank()) name else "$name - $langLabel"
        var ok = false

        if (isMediaUrl(frameUrl)) {
            return emitLink(frameUrl, "", label, origin, doneMedia, callback)
        }

        val html = safeGet(frameUrl, pageUrl)
        Log.d(tag, "oynatıcı d=$depth ${frameUrl.take(90)} len=${html.length}")
        if (html.isNotBlank()) {
            val texts = allTexts(html)

            // 1) yapılandırma blokları (NP_CONFIG ve benzerleri, "sources" / "subtitles" dizileri)
            val cfgs = configBlocks(texts)
            Log.d(tag, "yapılandırma bloğu=${cfgs.size}")
            for (cfg in cfgs) {
                if (emitConfig(cfg, origin, label, subtitleCallback, doneSubs, doneMedia, callback)) ok = true
            }

            // 2) metinlerde doğrudan m3u8 / mp4 / nm.php / m.php ara
            if (!ok && emitScanned(texts, origin, label, doneMedia, callback)) ok = true

            // 2b) "multiplayer" oynatıcı: liste (Response.playlist) sayfadan ayrı bir istekle geliyor
            if (!ok) {
                try {
                    if (emitPlaylistApi(texts, frameUrl, origin, label, subtitleCallback, doneSubs, doneMedia, callback)) ok = true
                } catch (e: Exception) {
                    rethrow(e)
                    Log.e(tag, "playlist hata ${e.javaClass.simpleName} ${e.message}")
                }
            }

            // 3) sayfa başka oynatıcılar (iframe / *.php?v=) içeriyorsa onlara in
            if (!ok && depth < 2) {
                val inner = innerPlayers(texts, frameUrl, origin).take(8)
                Log.d(tag, "iç oynatıcı=${inner.size} ${inner.take(3).joinToString(" | ") { it.take(80) }}")
                for (u in inner) {
                    val r = try {
                        resolveFrame(u, frameUrl, langLabel, subtitleCallback, callback, doneMedia, doneSubs, depth + 1)
                    } catch (e: Exception) {
                        rethrow(e)
                        false
                    }
                    if (r) ok = true
                }
            }

            if (!ok && depth == 0) {
                logResponseContext(texts)
                logPageSummary(html)
            }
        }

        // 4) hazır çıkarıcılar (bilinen oynatıcı siteleri)
        if (!ok) {
            try {
                loadExtractor(frameUrl, pageUrl, subtitleCallback) { l ->
                    callback.invoke(l)
                    ok = true
                }
            } catch (e: Exception) {
                rethrow(e)
                Log.e(tag, "loadExtractor hata ${e.javaClass.simpleName}")
            }
        }
        return ok
    }

    // ---- multiplayer.php: "var pFile = Response.playlist" -> Response bir istekten geliyor ----

    private fun vParam(u: String): String? =
        Regex("""[?&]v=([^&#]+)""").find(u)?.groupValues?.get(1)

    private fun absRel(u: String, frameUrl: String): String? {
        val s = u.trim()
        val dir = frameUrl.substringBefore("?").substringBeforeLast("/")
        return when {
            s.startsWith("http") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> originOf(frameUrl) + s
            s.startsWith("./") -> dir + "/" + s.removePrefix("./")
            s.contains(".php") -> "$dir/$s"
            else -> null
        }
    }

    private fun playlistFrom(txt: String): JSONArray? {
        val t = txt.trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return null
        return try {
            val x = JSONTokener(t).nextValue()
            val arr: JSONArray? = when (x) {
                is JSONArray -> x
                is JSONObject -> x.optJSONArray("playlist")
                    ?: x.optJSONObject("Response")?.optJSONArray("playlist")
                    ?: x.optJSONObject("response")?.optJSONArray("playlist")
                else -> null
            }
            if (arr != null && arr.length() > 0 && arr.optJSONObject(0)?.has("sources") == true) arr else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun emitPlaylistApi(
        texts: List<String>,
        frameUrl: String,
        origin: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        doneSubs: MutableSet<String>,
        doneMedia: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val src = texts.firstOrNull { it.contains("Response.playlist") } ?: return false
        val idx = src.indexOf("Response.playlist")
        val window = src.substring(maxOf(0, idx - 6000), idx)
        val v = vParam(frameUrl)

        val cands = LinkedHashSet<String>()
        for (m in Regex("""["'`]([^"'`\s]{3,300})["'`]""").findAll(window)) {
            var lit = m.groupValues[1]
            if (lit.contains("\${") || imgRe.containsMatchIn(lit) || adHostRe.containsMatchIn(lit) || badHostRe.containsMatchIn(lit)) continue
            if (lit == "m.php" || lit == "master.m3u8") continue
            if (!(lit.startsWith("/") || lit.startsWith("http") || lit.contains(".php") || lit.contains("api", true))) continue
            if (v != null && (lit.endsWith("=") || lit.endsWith("?"))) lit += v
            absRel(lit, frameUrl)?.let { cands.add(it) }
        }
        // sayfaya en yakın olan önce denenir
        val list = cands.toList().reversed().take(8)
        Log.d(tag, "playlist adayları=${list.size} ${list.take(4).joinToString(" | ") { it.take(90) }}")

        for (u in list) {
            val h = headers + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/plain, */*",
                "Origin" to origin
            )
            var txt = try {
                app.get(u, headers = h, referer = frameUrl, timeout = 15L).text
            } catch (e: Exception) {
                rethrow(e)
                ""
            }
            var arr = playlistFrom(txt)
            if (arr == null && v != null) {
                txt = try {
                    app.post(u, headers = h, referer = frameUrl, data = mapOf("v" to v), timeout = 15L).text
                } catch (e: Exception) {
                    rethrow(e)
                    ""
                }
                arr = playlistFrom(txt)
            }
            if (arr == null) {
                Log.d(tag, "playlist yok ${u.take(80)} len=${txt.length} baş=${txt.take(80).replace("\n", " ")}")
                continue
            }
            Log.d(tag, "playlist bulundu ${u.take(80)} öğe=${arr.length()}")
            val cfg = JSONObject().put("playlist", arr)
            if (emitConfig(cfg, origin, label, subtitleCallback, doneSubs, doneMedia, callback)) return true
        }
        return false
    }

    // Bulunamazsa: "Response.playlist" öncesindeki kod logcat'e yazılır (uzun base64 blokları kısaltılarak)
    private fun logResponseContext(texts: List<String>) {
        val src = texts.firstOrNull { it.contains("Response.playlist") } ?: return
        val idx = src.indexOf("Response.playlist")
        val w = src.substring(maxOf(0, idx - 2500), minOf(src.length, idx + 300))
            .replace(Regex("[A-Za-z0-9+/=_\\-]{100,}")) { "<b${it.value.length}:${it.value.take(24)}>" }
            .replace(Regex("\\s+"), " ")
        var i = 0
        var n = 0
        while (i < w.length && n < 8) {
            Log.d(tag, "RESP[$n] " + w.substring(i, minOf(w.length, i + 600)))
            i += 600
            n++
        }
    }

    private suspend fun emitLink(
        url: String,
        title: String,
        label: String,
        origin: String,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!done.add(url)) return false
        val nm = if (title.isBlank()) label else "$label - $title"
        val type = if (url.contains(".mp4", true) && !url.contains(".m3u8", true)) {
            ExtractorLinkType.VIDEO
        } else {
            ExtractorLinkType.M3U8
        }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = nm,
                url = url,
                type = type
            ) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = this@DiziPal.headers + mapOf("Referer" to "$origin/", "Origin" to origin)
            }
        )
        return true
    }

    private fun fixMaster(m: String, origin: String, hosts: List<String>): String {
        val u = m.trim()
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> (hosts.firstOrNull()?.trimEnd('/') ?: origin) + u
            else -> u
        }
    }

    // Sayfadaki yapılandırma blokları: "...CONFIG = {...}" ya da "...CONFIG": {...}
    // Bulunamazsa "sources" / "subtitles" / "tracks" / "captions" dizileri tek başına alınır.
    private fun configBlocks(texts: List<String>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<Int>()
        val re = Regex("""[A-Za-z_]*CONFIG\b["']?\s*[=:]\s*(?=\{)""")
        for (t in texts) {
            for (m in re.findAll(t)) {
                val st = m.range.last + 1
                val s = balanced(t, st, '{', '}') ?: continue
                if (!seen.add(s.hashCode())) continue
                try {
                    out.add(JSONObject(s))
                } catch (_: Exception) {
                }
            }
        }
        if (out.isEmpty()) {
            for (t in texts) {
                for (key in listOf("\"sources\":", "\"subtitles\":", "\"tracks\":", "\"captions\":")) {
                    for (arr in arraysAfterKey(t, key)) {
                        if (!seen.add(arr.toString().hashCode())) continue
                        out.add(JSONObject().put(key.removeSuffix(":").trim('"'), arr))
                    }
                }
            }
        }
        return out
    }

    private class CfgItem(val path: String, val key: String, val value: String, val title: String)

    // JSON içindeki tüm adres dizelerini (yol, anahtar, değer, en yakın başlık / etiket) ile toplar
    private fun walkCfg(x: Any?, path: String, key: String, title: String, out: MutableList<CfgItem>, depth: Int = 0) {
        if (depth > 10) return
        when (x) {
            is JSONObject -> {
                var t = title
                for (k in listOf("title", "label", "name", "lang", "language")) {
                    val v = x.opt(k)
                    if (v is String && v.isNotBlank()) {
                        t = v
                        break
                    }
                }
                if (x.optBoolean("forced", false) && !t.contains("forced", true)) t += " Forced"
                val ks = x.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    walkCfg(x.opt(k), "$path/$k", k, t, out, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until x.length()) walkCfg(x.opt(i), path, key, title, out, depth + 1)
            is String -> if (x.startsWith("http") || x.startsWith("//") || x.startsWith("/")) {
                out.add(CfgItem(path, key, x, title))
            }
        }
    }

    private suspend fun emitConfig(
        cfg: JSONObject,
        origin: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        doneSubs: MutableSet<String>,
        doneMedia: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hosts = ArrayList<String>()
        cfg.optJSONArray("hosts")?.let { a ->
            for (i in 0 until a.length()) a.optString(i, "").takeIf { it.startsWith("http") }?.let { hosts.add(it) }
        }
        val items = ArrayList<CfgItem>()
        walkCfg(cfg, "", "", "", items)
        val strongKeys = setOf("master", "hls", "m3u8", "playlist")
        val mediaKeys = setOf("master", "file", "url", "src", "hls", "m3u8", "link", "playlist", "stream", "source", "video")
        var ok = false
        for (item in items) {
            val v = item.value
            if (imgRe.containsMatchIn(v) && !subFileRe.containsMatchIn(v)) continue
            val isSub = subFileRe.containsMatchIn(v) ||
                item.path.contains("subtitle", true) || item.path.contains("caption", true) || item.path.contains("track", true)
            if (isSub) {
                if (!subFileRe.containsMatchIn(v) && item.key !in setOf("url", "file", "src")) continue
                val su = when {
                    v.startsWith("//") -> "https:$v"
                    v.startsWith("/") -> origin + v
                    else -> v
                }
                if (!doneSubs.add(su)) continue
                val lb = item.title.ifBlank { "Altyazı" }
                Log.d(tag, "altyazı: $lb -> ${su.take(80)}")
                subtitleCallback.invoke(newSubtitleFile(lb, su) {
                    this.headers = this@DiziPal.headers + mapOf("Referer" to "$origin/")
                })
                continue
            }
            val isMedia = item.key in strongKeys || v.contains(".m3u8", true) ||
                (item.key in mediaKeys && mediaHintRe.containsMatchIn(v))
            if (!isMedia) continue
            var master = fixMaster(v, origin, hosts)
            // m.php ise gerçekten liste mi diye bakılır; değilse master.m3u8 denenir
            if (master.contains("/m.php") && !master.contains(".m3u8", true)) {
                val txt = safeGet(master, "$origin/")
                if (!txt.trimStart().startsWith("#EXTM3U")) master = master.replace("/m.php", "/master.m3u8")
            }
            if (emitLink(master, item.title, label, origin, doneMedia, callback)) ok = true
        }
        return ok
    }

    // Yapılandırma bulunamazsa: sayfa metinlerinde (çözülmüş / paketlenmiş / base64 dahil) m3u8, mp4, nm.php, m.php ara
    private suspend fun emitScanned(
        texts: List<String>,
        origin: String,
        label: String,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val set = LinkedHashSet<String>()
        val strongKeys = setOf("master", "hls", "m3u8", "playlist")
        for (t in texts) {
            m3u8Re.findAll(t).forEach { set.add(it.value) }
            mp4Re.findAll(t).forEach { set.add(it.value) }
            nmphpRe.findAll(t).forEach { set.add(it.value) }
            mphpRe.findAll(t).forEach { set.add(it.value.replace("/m.php", "/master.m3u8")) }
            fileKeyRe.findAll(t).forEach { m ->
                val key = m.groupValues[1].lowercase()
                var u = m.groupValues[2]
                u = if (u.startsWith("/") && !u.startsWith("//")) origin + u else absU(u)
                if ((key in strongKeys && !imgRe.containsMatchIn(u)) || mediaHintRe.containsMatchIn(u)) set.add(u)
            }
        }
        var ok = false
        for (u in set.filter { !badHostRe.containsMatchIn(it) && !imgRe.containsMatchIn(it) }.take(10)) {
            if (emitLink(u, "", label, origin, done, callback)) ok = true
        }
        return ok
    }

    // Sayfa içindeki diğer oynatıcı adresleri (iframe / *.php?v=); medya listesi olan nm.php / m.php hariç
    private fun innerPlayers(texts: List<String>, frameUrl: String, origin: String): List<String> {
        val out = LinkedHashSet<String>()
        val abs1 = Regex("""(?:https?:)?//[A-Za-z0-9.-]+/[A-Za-z0-9_\-/]*\.php\?v=[A-Za-z0-9_%.=+/\-]+""")
        val rel1 = Regex("""["'`]((?:\./|/)?[A-Za-z0-9_\-/]*\.php\?v=[A-Za-z0-9_%.=+/\-]+)["'`]""")
        val ifr = Regex("""(?i)<iframe[^>]+?(?:src|data-src)\s*=\s*["']([^"']+)["']""")
        fun add(u0: String) {
            val s = u0.trim()
            val u = when {
                s.startsWith("http") -> s
                s.startsWith("//") -> "https:$s"
                s.startsWith("/") -> origin + s
                s.contains(".php?v=") -> origin + "/" + s.removePrefix("./")
                else -> return
            }
            if (u == frameUrl || adHostRe.containsMatchIn(u) || badHostRe.containsMatchIn(u)) return
            if (Regex("""(?i)/(?:nm|m)\.php""").containsMatchIn(u)) return
            out.add(u)
        }
        for (t in texts) {
            abs1.findAll(t).forEach { add(it.value) }
            rel1.findAll(t).forEach { add(it.groupValues[1]) }
            ifr.findAll(t).forEach { add(it.groupValues[1]) }
        }
        return out.toList()
    }

    // Hiçbir yolla bulunamazsa: sayfanın yapısı logcat'e yazılır (uzun base64 blokları kısaltılarak)
    private fun logPageSummary(html: String) {
        val masked = html.replace(Regex("[A-Za-z0-9+/=_\\-]{120,}")) { "<b${it.value.length}>" }
            .replace(Regex("\\s+"), " ")
        Log.d(tag, "SAYFA OZET len=${html.length} maskeli=${masked.length}")
        // sayfanın SONU (oynatıcı betiği genelde orada): son 2800 karakter
        val tail = masked.takeLast(2800)
        var i = 0
        var n = 0
        while (i < tail.length && n < 5) {
            Log.d(tag, "son[$n] " + tail.substring(i, minOf(tail.length, i + 600)))
            i += 600
            n++
        }
        for (key in listOf("sources", "NP_", "CONFIG", "m3u8", "nm.php", "player", "iframe")) {
            val idx = masked.indexOf(key)
            if (idx >= 0) {
                Log.d(tag, "baglam[$key@$idx] " + masked.substring(maxOf(0, idx - 200), minOf(masked.length, idx + 500)))
            }
        }
    }

    // Son çare: oynatıcıyı gerçek WebView'da çalıştırıp video isteğini yakalar
    private suspend fun webviewFallback(
        frameUrl: String,
        pageUrl: String,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val w = try {
            val r = app.get(
                frameUrl,
                referer = pageUrl,
                headers = headers,
                interceptor = WebViewResolver(
                    Regex("""(?i)\.m3u8|\.mp4(?:\?|$)|/nm\.php\?v=|/m\.php\?v=|master\.txt"""),
                    userAgent = headers["User-Agent"],
                    useOkhttp = false,
                    timeout = 20_000L
                ),
                timeout = 30L
            )
            r.url.takeIf { it != frameUrl }
        } catch (e: Exception) {
            rethrow(e)
            Log.e(tag, "webview istek hata ${e.javaClass.simpleName}")
            null
        }
        Log.d(tag, "webview yakalanan=${w?.take(120)}")
        if (w == null) return false
        val u = if (w.contains("/m.php")) w.replace("/m.php", "/master.m3u8") else w
        return emitLink(u, "", name, originOf(w), done, callback)
    }

    // HTML + paket çözümü + base64 / hex çözümü: gizlenmiş adresleri de yakalar
    private fun allTexts(html: String): List<String> {
        val base = unescape(html)
        val out = ArrayList<String>()
        out.add(base)
        try {
            out.addAll(unpackAll(base))
        } catch (_: Exception) {
        }
        try {
            out.addAll(deepTexts(out.toList()))
        } catch (_: Exception) {
        }
        return out.map { unescape(it) }.distinct()
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
        val useful = Regex("https?://|\\.m3u8|\\.mp4|\"file\"|sources|playlist|m\\.php", RegexOption.IGNORE_CASE)
        for (t in texts) {
            if (Regex("\\\\x[0-9a-fA-F]{2}|\\\\u[0-9a-fA-F]{4}").containsMatchIn(t)) {
                var dd = Regex("\\\\x([0-9a-fA-F]{2})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
                dd = Regex("\\\\u([0-9a-fA-F]{4})").replace(dd) { it.groupValues[1].toInt(16).toChar().toString() }
                out.add(dd)
            }
            // Uzun bloklar (sayfanın sonundaki asıl veri) kısa CSS / betik parçaları yüzünden atlanmasın:
            // önce uzun olanlar (sınırsız değil ama geniş), sonra kısalar
            val all = Regex("[A-Za-z0-9+/_\\-]{28,}={0,2}").findAll(t).toList()
            val picked = all.filter { it.value.length >= 120 }.take(80) + all.filter { it.value.length < 120 }.take(300)
            for (m in picked) {
                for (cand in listOf(m.value, m.value.reversed())) {
                    val bytes = b64bin(cand)
                    if (bytes.isEmpty()) continue
                    val dd = String(bytes, Charsets.ISO_8859_1)
                    if (dd.length > 12 && useful.containsMatchIn(dd)) {
                        out.add(dd)
                        break
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
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun extractLdJsonDescription(html: String): String? {
        val regex = Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        regex.findAll(html).forEach { m ->
            try {
                val json = JSONObject(m.groupValues[1].trim())
                val desc = json.optString("description", "")
                if (desc.isNotBlank()) return desc
            } catch (_: Exception) {
            }
        }
        return null
    }
}
