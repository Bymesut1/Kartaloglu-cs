package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    private suspend fun safeGet(url: String, referer: String? = null): String {
        return try {
            val h = if (referer != null) headers + mapOf("Referer" to referer) else headers
            app.get(url, headers = h).text
        } catch (e: Exception) {
            rethrow(e)
            Log.e(tag, "get hata ${e.javaClass.simpleName} ${url.take(90)}")
            ""
        }
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

    override suspend fun load(url: String): LoadResponse? {
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
                    Log.e(tag, "load: data bulunamadı")
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
                for (si in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(si) ?: continue
                    val seasonNo = season.optInt("no", si + 1)
                    val eps = season.optJSONArray("episodes") ?: continue
                    for (ei in 0 until eps.length()) {
                        val ep = eps.optJSONObject(ei) ?: continue
                        val epNo = ep.optInt("n", ei + 1)
                        val epTitle = ep.optString("title", "").ifBlank { "$seasonNo. Sezon $epNo. Bölüm" }
                        val epHref = ep.optString("href", "").ifBlank { continue }
                        val epUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                        episodes.add(newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = seasonNo
                            this.episode = epNo
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
            Log.e(tag, "load hata: ${e.message}")
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
    private val fileKeyRe = Regex("""["']?(?:file|src|source|master|hls|stream)["']?\s*[:=]\s*["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)

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
                        resolveFrame(f, u, lb, subtitleCallback, callback, doneMedia, doneSubs)
                    } catch (e: Exception) {
                        rethrow(e)
                        Log.e(tag, "oynatıcı hata ${e.javaClass.simpleName} ${e.message}")
                        false
                    }
                    if (ok) any = true
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
        doneSubs: MutableSet<String>
    ): Boolean {
        val origin = originOf(frameUrl)
        val label = if (langLabel.isBlank()) name else "$name - $langLabel"
        var ok = false

        if (isMediaUrl(frameUrl)) {
            return emitLink(frameUrl, "", label, origin, doneMedia, callback)
        }

        val html = safeGet(frameUrl, pageUrl)
        Log.d(tag, "oynatıcı ${frameUrl.take(80)} len=${html.length}")
        if (html.isNotBlank()) {
            val np = npConfig(html)
            if (np != null) {
                emitNpSubs(np, frameUrl, origin, subtitleCallback, doneSubs)
                if (emitNpSources(np, frameUrl, origin, label, doneMedia, callback)) ok = true
            }
            if (!ok) {
                if (emitScanned(allTexts(html), frameUrl, origin, label, doneMedia, callback)) ok = true
            }
        }

        // hazır çıkarıcılar (bilinen oynatıcı siteleri)
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

    // window.NP_CONFIG = {...}  (dizelere duyarlı dengeli parantez; ";" olması gerekmez)
    private fun npConfig(t: String): JSONObject? {
        var from = 0
        while (true) {
            val i = t.indexOf("NP_CONFIG", from)
            if (i < 0) return null
            from = i + 9
            val st = t.indexOf('{', i)
            if (st < 0) return null
            val s = balanced(t, st, '{', '}') ?: continue
            try {
                return JSONObject(s)
            } catch (_: Exception) {
            }
        }
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

    private suspend fun emitNpSources(
        np: JSONObject,
        frameUrl: String,
        origin: String,
        label: String,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hosts = ArrayList<String>()
        np.optJSONArray("hosts")?.let { a ->
            for (i in 0 until a.length()) a.optString(i, "").takeIf { it.startsWith("http") }?.let { hosts.add(it) }
        }
        val srcs = np.optJSONArray("sources") ?: return false
        var ok = false
        for (i in 0 until srcs.length()) {
            val so = srcs.optJSONObject(i) ?: continue
            var master = so.optString("master", "").ifBlank { so.optString("file", "") }
                .ifBlank { so.optString("url", "") }.ifBlank { so.optString("src", "") }
            if (master.isBlank()) continue
            master = fixMaster(master, origin, hosts)
            // uzantısız adreslerde gerçekten m3u8 mi diye bakılır; m.php ise master.m3u8 denenir
            if (!master.contains(".m3u8", true) && !master.contains(".mp4", true)) {
                val txt = safeGet(master, frameUrl)
                if (!txt.trimStart().startsWith("#EXTM3U") && master.contains("/m.php")) {
                    master = master.replace("/m.php", "/master.m3u8")
                }
            }
            val title = so.optString("title", "").ifBlank { so.optString("label", "") }
            if (emitLink(master, title, label, origin, done, callback)) ok = true
        }
        return ok
    }

    private suspend fun emitNpSubs(
        np: JSONObject,
        frameUrl: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        doneSubs: MutableSet<String>
    ) {
        val subs = np.optJSONArray("subtitles") ?: return
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            var label = sub.optString("label", "").ifBlank { sub.optString("lang", "Altyazı") }
            var subUrl = sub.optString("url", "")
            if (subUrl.isBlank()) continue
            if (subUrl.startsWith("//")) subUrl = "https:$subUrl"
            else if (subUrl.startsWith("/")) subUrl = origin + subUrl
            if (sub.optBoolean("forced", false) && !label.contains("forced", true)) label += " Forced"
            if (!doneSubs.add(subUrl)) continue
            Log.d(tag, "altyazı: $label -> ${subUrl.take(80)}")
            subtitleCallback.invoke(newSubtitleFile(label, subUrl) {
                this.headers = this@DiziPal.headers + mapOf("Referer" to frameUrl)
            })
        }
    }

    // NP_CONFIG yoksa: sayfa metinlerinde (çözülmüş / paketlenmiş dahil) m3u8, mp4, m.php ara
    private suspend fun emitScanned(
        texts: List<String>,
        frameUrl: String,
        origin: String,
        label: String,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val set = LinkedHashSet<String>()
        for (t in texts) {
            m3u8Re.findAll(t).forEach { set.add(it.value) }
            mp4Re.findAll(t).forEach { set.add(it.value) }
            mphpRe.findAll(t).forEach { set.add(it.value.replace("/m.php", "/master.m3u8")) }
            fileKeyRe.findAll(t).forEach { m ->
                val u = absU(m.groupValues[1])
                if (Regex("""(?i)m3u8|\.mp4|master|playlist""").containsMatchIn(u)) set.add(u)
            }
        }
        var ok = false
        for (u in set.filter { !badHostRe.containsMatchIn(it) && !imgRe.containsMatchIn(it) }.take(8)) {
            if (emitLink(u, "", label, origin, done, callback)) ok = true
        }
        return ok
    }

    private fun allTexts(html: String): List<String> {
        val base = unescape(html)
        val out = ArrayList<String>()
        out.add(base)
        try {
            out.addAll(unpackAll(base))
        } catch (_: Exception) {
        }
        return out.map { unescape(it) }.distinct()
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
