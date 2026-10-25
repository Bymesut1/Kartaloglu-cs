package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.min

class VkVideo : MainAPI() {
    override var mainUrl = "https://vkvideo.ru"
    override var name = "VK Video"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val API = "https://api.vkvideo.ru/method"
        private const val V = "5.289"
        private const val CLIENT_ID = "52461373"

        // Bu süreden kısa videolar (klip vb.) listeden elenir (saniye). 0 = hiç eleme
        private const val MIN_DURATION = 1200

        // true: başlıkta tr dublaj / türkçe dublaj / izle / film izle / filmi izle ... YOKSA video listeye girmez
        private const val REQUIRE_TAG = true

        // true: açılan posteri olmayan kartlar listeden çıkarılır
        private const val REQUIRE_POSTER = true

        // Bir sayfa boş çıkarsa (süzgeçten geçen video yoksa) en fazla kaç ham istek daha atılsın
        private const val MAX_EMPTY_FETCHES = 4

        // Aramaya videoları dahil edilecek en fazla kanal/topluluk sayısı
        private const val MAX_CHANNELS = 6

        // Her kanaldan sayfa başına çekilecek video sayısı
        private const val CH_PAGE = 30

        // Aramada (1. sayfa) en fazla kaç sonuç gösterilsin (poster kontrolü yüzünden sınırlı)
        private const val MAX_RESULTS = 40

        // Aramada etiketli sorgulardan (ad + "Türkçe Dublaj", "izle" ...) en fazla kaçı denensin
        private const val TAGGED_QUERIES = 12

        // Yandex yardımı: "ad tr dublaj izle vkvideo.ru" tarzı aramalardan VK video numaraları toplanır
        private const val YANDEX = true
        private const val YANDEX_QUERIES = 9
        private const val YANDEX_MS = 7000L
        private const val YANDEX_VERIFY = 24

        // true: arama sonuçlarının başına teşhis kartı (API yanıt şekli, kanallar, Yandex durumu) eklenir
        private const val DEBUG_SEARCH = true

        // DevTools -> Ağ -> "?act=get_anonym_token" isteği -> Yük (Payload) / Başlıklar
        private const val LOGIN = "https://login.vk.ru/?act=get_anonym_token"
        private const val CLIENT_SECRET = ""
        private const val DEVICE_ID = ""

        private const val TMDB_KEY = "000316508321ce461cf81e7c6815eec7"

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        private const val UA_WIN =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // VK Video'nun kendi aramasında ad yanına eklenen etiketler (öncelik sırasıyla)
        private val TAGS = listOf(
            "Türkçe Dublaj", "TR Dublaj", "izle", "film izle", "filmi izle",
            "tr izle", "tr dublaj izle", ""
        )

        // Yandex'te ad yanına eklenen etiketler
        private val YANDEX_TAGS = listOf(
            "türkçe dublaj izle vk", "tr dublaj izle vkvideo.ru", "Türkçe Dublaj film izle vkvideo.ru",
            "filmi izle vkvideo.ru", "film izle vkvideo.ru"
        )
    }

    private val hdr = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Referer" to "https://vkvideo.ru/",
        "Origin" to "https://vkvideo.ru"
    )

    private var token: String? = null

    override val mainPage = mainPageOf(
        "türkçe dublaj film" to "Türkçe Dublaj Filmler",
        "tr dublaj izle" to "TR Dublaj İzle",
        "yerli film izle" to "Yerli Filmler"
    )

    // ---------- API ----------

    private suspend fun getToken(): String {
        token?.let { return it }
        val text = app.post(
            LOGIN,
            headers = hdr,
            data = mapOf(
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
                "app_id" to CLIENT_ID,
                "version" to "1",
                "device_id" to DEVICE_ID
            )
        ).text
        val t = JSONObject(text).optJSONObject("data")?.optString("access_token")
        if (t.isNullOrBlank()) throw ErrorLoadingException("Token alınamadı: $text")
        token = t
        return t
    }

    private suspend fun api(
        method: String,
        params: Map<String, String>,
        retry: Boolean = true
    ): Any? {
        val text = app.post(
            "$API/$method?v=$V&client_id=$CLIENT_ID",
            headers = hdr,
            data = params + ("access_token" to getToken())
        ).text
        val json = JSONObject(text)
        val err = json.optJSONObject("error")
        if (err != null) {
            val code = err.optInt("error_code")
            if (retry && code == 5) {
                token = null
                return api(method, params, false)
            }
            throw ErrorLoadingException("VK hata $code ($method): ${err.optString("error_msg")}")
        }
        return json.opt("response")
    }

    // ---------- Video listesi yardımcıları ----------

    // Sadece gerçek videoları toplar (albüm/oynatma listesi gibi nesneler elenir)
    private fun collectVideos(node: Any?, out: LinkedHashMap<String, JSONObject>) {
        when (node) {
            is JSONObject -> {
                if (node.has("owner_id") && node.has("id") && node.has("title") &&
                    node.has("duration") && node.optLong("id") != 0L &&
                    node.optLong("owner_id") != 0L
                ) {
                    out.putIfAbsent(videoKey(node), node)
                }
                val ks = node.keys()
                while (ks.hasNext()) collectVideos(node.opt(ks.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectVideos(node.opt(i), out)
            }
        }
    }

    private fun videoKey(o: JSONObject): String {
        val k = "${o.optLong("owner_id")}_${o.optLong("id")}"
        val ak = o.optString("access_key")
        return if (ak.isNotBlank()) "${k}_$ak" else k
    }

    // Erişim anahtarsız anahtar: aynı video iki kaynaktan gelirse tek sayılsın
    private fun baseOf(o: JSONObject): String = "${o.optLong("owner_id")}_${o.optLong("id")}"

    private fun shape(r: Any?): String = when (r) {
        is JSONObject -> "obj{" + r.keys().asSequence().joinToString(",").take(110) + "}"
        is JSONArray -> "arr[${r.length()}]"
        else -> r.toString().take(60)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- Başlık analizi (JS sürümüyle aynı mantık) ----------

    private fun asciiLower(s: String): String = s.replace('İ', 'i').replace('I', 'i').lowercase()
        .replace("\u0307", "")
        .replace('ı', 'i').replace('ç', 'c').replace('ğ', 'g').replace('ö', 'o')
        .replace('ş', 's').replace('ü', 'u').replace('â', 'a').replace('î', 'i').replace('û', 'u')

    private fun norm(s: String): String = asciiLower(s).replace(Regex("[^a-z0-9]"), "")

    private val STOP = setOf("the", "a", "an", "of", "and", "ve", "ile", "film", "filmi", "izle", "movie")

    private val ROMAN = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6,
        "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10
    )

    private val NOISE = setOf(
        "tr", "turkce", "turkish", "trk", "turk", "dublaj", "dublajli", "dub", "trdub", "trdublaj",
        "izle", "film", "filmi", "filmler", "full", "hd", "fhd", "uhd", "bluray", "brrip", "bdrip",
        "webrip", "webdl", "web", "dl", "hdrip", "hdtv", "dvdrip", "dvd", "x264", "x265", "h264",
        "hevc", "aac", "ac3", "dts", "dual", "cift", "tek", "parca", "part", "kisim", "bolum",
        "hdfilm", "filmizle", "fullizle", "yerli", "yabanci", "mp4", "mkv", "avi", "mov", "com",
        "ru", "org", "net", "www", "vk", "vkvideo", "video", "izlesene", "altyazi", "altyazili",
        "sub", "subs", "subtitle", "subtitles", "en", "eng", "english", "ingilizce", "imax",
        "extended", "remastered", "nf", "amzn", "sansursuz", "tamami", "sinema", "yeni"
    )

    private class Info(
        val years: List<Int>,
        val res: Int,
        val words: List<String>,
        val nums: List<Int>,
        val bag: List<String>,
        val tagset: Set<String>
    ) {
        val joined: String = bag.joinToString("")
    }

    // "m1080ptr" -> 1080p tr | "filmizle" -> film izle | "2004tr" -> 2004 tr | "720pizle" -> 720p izle
    private fun splitTok(t: String): List<String> {
        if (Regex("^\\d{5,}$").matches(t)) return emptyList()
        var m = Regex("^m?(2160|1080|720|480|360)p(tr|dual|dublaj|turkce|izle|hd|sub)?$").find(t)
        if (m != null) {
            return if (m.groupValues[2].isNotEmpty()) listOf(m.groupValues[1] + "p", m.groupValues[2])
            else listOf(m.groupValues[1] + "p")
        }
        m = Regex("^((?:19|20)\\d{2})(tr|dublaj|izle|turkce|dual|hd)$").find(t)
        if (m != null) return listOf(m.groupValues[1], m.groupValues[2])
        m = Regex("^(tr|trdub|trdublaj|dublaj|dublajli|dual|turkce)(izle|hd|film|filmi)$").find(t)
            ?: Regex("^(film|filmi|hd|full|tek|parca|bolum|tr|dublaj|turkce|\\d{3,4}p)(izle)$").find(t)
            ?: Regex("^(izle)(tr|dublaj|turkce|hd)$").find(t)
        if (m != null) return listOf(m.groupValues[1], m.groupValues[2])
        m = Regex("^(tr|en|eng)(sub|subs|altyazi)$").find(t)
        if (m != null) return listOf(m.groupValues[1], "sub")
        return listOf(t)
    }

    private fun analyze(title: String): Info {
        val raw = title
            .replace(Regex("^\\s*\\d{1,3}\\s*[.)\\-–:]\\s+"), "")
            .replace(Regex("\\s*[(\\[]\\s*\\d\\s*[)\\]]\\s*$"), "")
            .replace(Regex("(^|[^0-9])[257][.,][01](?![0-9])"), "\$1")
        val toks = ArrayList<String>()
        for (t in asciiLower(raw).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }) {
            toks.addAll(splitTok(t))
        }
        val years = ArrayList<Int>()
        val words = ArrayList<String>()
        val nums = ArrayList<Int>()
        var res = 0
        val tagset = HashSet<String>()
        for (t in toks) {
            tagset.add(t)
            if (Regex("^(19|20)\\d{2}$").matches(t)) { years.add(t.toInt()); continue }
            val rm = Regex("^(2160|1080|720|480|360)p$").find(t)
            if (rm != null) { res = maxOf(res, rm.groupValues[1].toInt()); continue }
            if (t == "4k") { res = maxOf(res, 2160); continue }
            if (t in NOISE) continue
            if (Regex("^\\d{1,2}$").matches(t)) { nums.add(t.toInt()); continue }
            val rn = ROMAN[t]
            if (rn != null) { nums.add(rn); continue }
            if (t in STOP) continue
            words.add(t)
        }
        return Info(years, res, words, nums, toks, tagset)
    }

    // Aranan addan anlamlı kelimeler ve sayılar. stripNoise: sorgudaki etiket kelimelerini (tr, dublaj, izle ...) at
    private fun sigTokens(s: String, stripNoise: Boolean): Pair<List<String>, List<Int>> {
        val words = ArrayList<String>()
        val nums = ArrayList<Int>()
        for (t in asciiLower(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }) {
            if (Regex("^\\d{1,2}$").matches(t)) { nums.add(t.toInt()); continue }
            val rn = ROMAN[t]
            if (rn != null) { nums.add(rn); continue }
            if (t in STOP) continue
            if (stripNoise && t in NOISE) continue
            words.add(t)
        }
        return words to nums
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1)
                )
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun looseEq(a: String, b: String): Boolean {
        if (a == b) return true
        return a.length >= 5 && b.length >= 5 && abs(a.length - b.length) <= 1 && lev(a, b) <= 1
    }

    private val TR_SUF = Regex(
        "^(i|u|a|e|s|n|si|su|in|un|an|en|ya|ye|yi|yu|da|de|ta|te|ler|lar|leri|lari|nin|nun|dan|den|tan|ten|nda|nde|ndan|nden|ni|na|ne|ndaki)$"
    )

    // Türkçe ek toleransı: "Bourneun" ~ "Bourne", "Yolcu" ~ "Yolcusu"
    private fun sufEq(a: String, b: String): Boolean {
        val s = if (a.length <= b.length) a else b
        val l = if (a.length <= b.length) b else a
        return s.length >= 4 && l.length > s.length && l.length - s.length <= 5 &&
            l.startsWith(s) && TR_SUF.matches(l.substring(s.length))
    }

    private fun tokEq(w: String, b: String): Boolean = looseEq(w, b) || sufEq(w, b)

    // Adlardan biri başlıkta tam geçiyorsa fazladan kaç kelime olduğunu döndürür, geçmiyorsa -1
    private fun nameMatch(info: Info, names: List<String>): Int {
        var best = -1
        for (n in names) {
            val clean = n.replace("'", "").replace("’", "").replace("`", "")
            val (ww, nums) = sigTokens(clean, true)
            if (ww.isEmpty()) continue
            var covered = ww.all { w -> info.bag.any { b -> tokEq(w, b) } }
            if (!covered) {
                val wn = norm(clean)                                   // bitişik yazım: "YapayZeka" ~ "Yapay Zeka"
                if (wn.length >= 6 && info.joined.contains(wn)) covered = true
            }
            if (!covered) continue
            if (ww.size == 1 && ww[0].length <= 2 && info.words.size > 3) continue
            if (nums.isNotEmpty() && !nums.all { it in info.nums }) continue
            val extra = info.words.count { b -> ww.none { w -> tokEq(w, b) } }
            if (best < 0 || extra < best) best = extra
        }
        return best
    }

    private class Lang(val ok: Boolean, val label: String, val strength: Int)

    // TR dublaj / Türkçe dublaj / TR / izle ... etiketi (altyazılı ve yabancı dil etiketliler kabul edilmez)
    private fun langInfo(info: Info): Lang {
        val t = info.tagset
        fun has(vararg k: String): Boolean = k.any { it in t }
        val dub = has("dublaj", "dublajli", "dub", "trdub", "trdublaj")
        val tr = has("tr", "turkce", "turkish", "trk", "turk", "turkiye", "trdub", "trdublaj")
        val sub = has("altyazi", "altyazili", "sub", "subs", "subtitle", "subtitles")
        val dual = has("dual", "cift")
        val foreign = has(
            "en", "eng", "english", "ingilizce", "rus", "russian", "rusca", "ger", "german",
            "fre", "french", "vostfr", "arabic", "arapca", "hindi", "multi"
        )
        val izle = has("izle")
        if (dub && !(foreign && !tr)) return Lang(true, if (tr) "TR Dublaj" else "Dublaj", if (tr) 4 else 3)
        if (tr && dual) return Lang(true, "TR Dual", 2)
        if (tr && !sub && !foreign) return Lang(true, "TR", 2)
        if (izle && !tr && !sub && !foreign) return Lang(true, "TR İzle", 1)
        return Lang(false, if (sub) "Altyazı" else "?", 0)
    }

    // Puan: null = ele. names boşsa ad kontrolü yapılmaz (ana sayfa kategorileri)
    private fun rankVideo(o: JSONObject, names: List<String>): Int? {
        val title = o.optString("title")
        if (Regex("[\\u0400-\\u04FF]").containsMatchIn(title)) return null           // Rusça (Kiril) başlık
        val dur = o.optInt("duration")
        if (dur < MIN_DURATION) return null
        val info = analyze(title)
        var li = langInfo(info)
        if (!li.ok) {
            val desc = o.optString("description")
            if (desc.isNotBlank()) {
                val l2 = langInfo(analyze(desc.take(300)))
                if (l2.ok && l2.strength >= 3) li = l2
            }
        }
        if (!li.ok) {
            if (REQUIRE_TAG) return null                                              // etiket YOKSA alma
            if ("altyazi" in info.tagset || "altyazili" in info.tagset || "sub" in info.tagset) return null
            li = Lang(true, "Dil ?", 0)
        }
        var extra = 0
        if (names.isNotEmpty()) {
            extra = nameMatch(info, names)
            if (extra < 0) return null
        }
        var score = 50 + li.strength * 5
        if (extra == 0) score += 10
        score -= min(15, extra * 3)
        if (info.res >= 1080) score += 3 else if (info.res >= 720) score += 2
        return score
    }

    private fun rankAll(
        vids: Collection<JSONObject>,
        names: List<String>,
        yandexKeys: Set<String>
    ): List<JSONObject> {
        val out = ArrayList<Pair<JSONObject, Int>>()
        for (o in vids) {
            val s = rankVideo(o, names) ?: continue
            out.add(o to (s + (if (baseOf(o) in yandexKeys) 1 else 0)))
        }
        return out.sortedByDescending { it.second }.map { it.first }
    }

    // ---------- Poster ----------

    private fun posterCandidates(o: JSONObject): List<String> {
        val raw = ArrayList<Pair<String, Int>>()
        for (k in listOf("image", "first_frame")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val u = e.optString("url")
                if (u.isNotBlank()) raw.add(u to e.optInt("width"))
            }
        }
        for (k in listOf("photo_800", "photo_640", "photo_320", "photo_130")) {
            val u = o.optString(k)
            if (u.isNotBlank()) raw.add(u to k.removePrefix("photo_").toInt())
        }
        // okcdn önizlemeleri sona, ~640px genişliğe en yakın olan öne
        val sorted = raw.sortedWith(
            compareBy<Pair<String, Int>>(
                { it.first.contains("okcdn.ru") },
                { abs(it.second - 640) }
            )
        )
        val out = LinkedHashSet<String>()
        for ((u, _) in sorted) {
            if (u.contains("userapi.com")) out.add(u.replace("userapi.com", "vkuserphoto.ru"))
            out.add(u)
        }
        return out.toList()
    }

    private suspend fun imageOk(u: String): Boolean {
        return try {
            val r = app.get(
                u,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "image/*",
                    "Range" to "bytes=0-1023"
                ),
                timeout = 5L
            )
            val ct = r.headers["Content-Type"] ?: ""
            r.code in 200..206 && (ct.isEmpty() || ct.startsWith("image"))
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            false
        }
    }

    // Gerçekten açılan ilk posteri döndürür, hiçbiri açılmazsa null
    private suspend fun workingPoster(o: JSONObject): String? {
        for (u in posterCandidates(o).take(4)) {
            if (imageOk(u)) return u
        }
        return null
    }

    // ---------- TMDB: ad -> gerçek film (poster, özet, yıl) ----------

    // "Zafere Kaçış 1981 Sylvester Stallon Türkçe Dublaj" -> "Zafere Kaçış Sylvester Stallon"
    private fun cleanTitle(title: String): String {
        val out = ArrayList<String>()
        for (tok in title.split(Regex("[\\s._|\\-–—()\\[\\]]+")).filter { it.isNotEmpty() }) {
            val parts = splitTok(asciiLower(tok))
            val junk = parts.isEmpty() || parts.all {
                it in NOISE || it in STOP ||
                    Regex("^(19|20)\\d{2}$").matches(it) ||
                    Regex("^m?(2160|1080|720|480|360)p$").matches(it)
            }
            if (!junk) out.add(tok)
        }
        return out.joinToString(" ").trim()
    }

    // Başlık + yıl + süre uyuyorsa TMDB kaydı (poster/özet için), yoksa null
    private suspend fun tmdbLookup(title: String, durSec: Int): JSONObject? {
        val clean = cleanTitle(title)
        if (clean.length < 2) return null
        val year = analyze(title).years.firstOrNull()
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_KEY&language=tr-TR&query=${enc(clean)}" +
            (if (year != null) "&year=$year" else "")
        val arr = JSONObject(app.get(url, timeout = 6L).text).optJSONArray("results") ?: return null
        for (i in 0 until min(arr.length(), 5)) {
            val r = arr.optJSONObject(i) ?: continue
            val ry = r.optString("release_date").take(4).toIntOrNull()
            if (year != null && ry != null && abs(ry - year) > 1) continue
            val ok = nameMatch(analyze(r.optString("title")), listOf(clean)) >= 0 ||
                nameMatch(analyze(r.optString("original_title")), listOf(clean)) >= 0
            if (!ok) continue
            val det = JSONObject(
                app.get(
                    "https://api.themoviedb.org/3/movie/${r.optInt("id")}?api_key=$TMDB_KEY&language=tr-TR",
                    timeout = 6L
                ).text
            )
            val rt = det.optInt("runtime")
            if (rt > 0 && durSec > 0) {
                val ratio = durSec / (rt * 60.0)
                if (ratio < 0.6 || ratio > 1.7) continue                              // süre bambaşka: başka içerik
            }
            return det
        }
        return null
    }

    private class ExpandRes(val names: List<String>, val tmNames: List<String>, val poster: String?)

    // Yazılan etiketleri (türkçe dublaj, izle, tr ...) atar: "zathura türkçe dublaj izle" -> "zathura"
    private fun cleanQuery(q: String): String {
        val out = ArrayList<String>()
        for (tok in q.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val a = asciiLower(tok).replace(Regex("[^a-z0-9]"), "")
            if (a.isEmpty() || a in NOISE) continue
            out.add(tok)
        }
        val base = out.joinToString(" ").trim()
        return if (base.length >= 2) base else q.trim()
    }

    // Arama anahtarı: (VK'ya gidecek temiz sorgu, sadece etiket mi yazıldı?)
    private fun baseQuery(q: String): Pair<String, Boolean> {
        val qc = cleanQuery(q)
        val noName = sigTokens(qc, true).first.isEmpty()
        return (if (noName) q.trim() else qc) to noName
    }

    // Aranan ad Türkçe de olsa İngilizce de olsa her ikisiyle aranır: TMDB'den TR + EN + orijinal ad (+ poster)
    private suspend fun expandNames(q: String): ExpandRes {
        val out = LinkedHashSet<String>()
        out.add(q.trim())
        val tmNames = ArrayList<String>()
        var poster: String? = null
        try {
            val txt = app.get(
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_KEY&language=tr-TR&query=${enc(q)}",
                timeout = 6L
            ).text
            val r = JSONObject(txt).optJSONArray("results")?.optJSONObject(0)
            if (r != null) {
                val tt = r.optString("title")
                val ot = r.optString("original_title")
                val related = nameMatch(analyze(tt), listOf(q)) >= 0 || nameMatch(analyze(ot), listOf(q)) >= 0
                if (related) {
                    if (tt.isNotBlank()) { out.add(tt); tmNames.add(tt) }
                    if (ot.isNotBlank()) { out.add(ot); tmNames.add(ot) }
                    val pp = r.optString("poster_path")
                    if (pp.isNotBlank() && pp != "null") poster = "https://image.tmdb.org/t/p/w500$pp"
                    try {
                        val en = JSONObject(
                            app.get(
                                "https://api.themoviedb.org/3/movie/${r.optInt("id")}?api_key=$TMDB_KEY&language=en-US",
                                timeout = 6L
                            ).text
                        ).optString("title")
                        if (en.isNotBlank()) { out.add(en); tmNames.add(en) }
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
        }
        return ExpandRes(out.toList(), tmNames, poster)
    }

    // ---------- Arama (sayfalı + kanallar) ----------

    private class Cur(val next: String, val section: String?)

    // "anahtar|sayfa" -> o sayfayı çekmek için gereken imleç
    private val cursors = java.util.concurrent.ConcurrentHashMap<String, Cur>()
    private val seenMap = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val chanMap = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<Long, String>>()
    private val namesCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val tmCache = java.util.concurrent.ConcurrentHashMap<String, ExpandRes>()

    private fun findSectionId(root: Any?): String? {
        val o = root as? JSONObject ?: return null
        val s = o.optJSONObject("catalog")?.optJSONArray("sections")
            ?.optJSONObject(0)?.optString("id")
        return if (s.isNullOrBlank()) null else s
    }

    // Yanıttaki "next_from" imleçlerinden en çok video içereni seçer
    private fun findCursor(root: Any?): Cur? {
        var best: Cur? = null
        var bestCount = -1
        val sec = findSectionId(root)
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val nf = n.optString("next_from")
                    if (nf.isNotBlank()) {
                        val tmp = LinkedHashMap<String, JSONObject>()
                        collectVideos(n, tmp)
                        if (tmp.size > bestCount) {
                            bestCount = tmp.size
                            best = Cur(nf, n.optString("section_id").ifBlank { sec })
                        }
                    }
                    val ks = n.keys()
                    while (ks.hasNext()) walk(n.opt(ks.next()))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        return best
    }

    // Yanıttaki kanal/topluluk listesini toplar (extended=1 ile "groups" dizisi gelir)
    private fun collectGroups(node: Any?, out: LinkedHashMap<Long, String>) {
        when (node) {
            is JSONObject -> {
                val g = node.optJSONArray("groups")
                if (g != null) {
                    for (i in 0 until g.length()) {
                        val o = g.optJSONObject(i) ?: continue
                        val id = abs(o.optLong("id"))
                        val nm = o.optString("name")
                        if (id != 0L && nm.isNotBlank()) out.putIfAbsent(id, nm)
                    }
                }
                val ks = node.keys()
                while (ks.hasNext()) collectGroups(node.opt(ks.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectGroups(node.opt(i), out)
        }
    }

    private fun fold(s: String): String = s.lowercase()
        .replace("\u0307", "")
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    // Kanal adı aramayla eşleşiyor mu? ("Filmizle" ~ "Film izle")
    private fun channelMatches(q: String, name: String): Boolean {
        val fq = fold(q)
        val fn = fold(name)
        if (fn.replace(" ", "").contains(fq.replace(" ", ""))) return true
        val tokens = fq.split(" ").filter { it.length >= 2 }
        return tokens.isNotEmpty() && tokens.all { fn.contains(it) }
    }

    private suspend fun channelVideos(gid: Long, offset: Int): Pair<List<JSONObject>, Boolean> {
        return try {
            val r = api(
                "video.get",
                mapOf(
                    "owner_id" to "-$gid",
                    "count" to "$CH_PAGE",
                    "offset" to "$offset",
                    "extended" to "1"
                )
            )
            val m = LinkedHashMap<String, JSONObject>()
            collectVideos(r, m)
            val total = (r as? JSONObject)?.optInt("count") ?: 0
            val more = if (total > 0) total > offset + CH_PAGE else m.size >= CH_PAGE
            m.values.toList() to more
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            emptyList<JSONObject>() to false
        }
    }

    // VK önizlemesi açılmazsa ve başlık TMDB'deki filmle uyuşuyorsa TMDB posteri kullanılır; ikisi de yoksa (REQUIRE_POSTER) kart düşer
    private suspend fun toResponses(videos: Collection<JSONObject>, tm: ExpandRes? = null): List<SearchResponse> {
        return videos.toList().amap { o ->
            var p = workingPoster(o)
            if (p == null && tm?.poster != null && tm.tmNames.isNotEmpty() &&
                nameMatch(analyze(o.optString("title")), tm.tmNames) >= 0
            ) {
                p = tm.poster
            }
            if (p == null && REQUIRE_POSTER) {
                null
            } else {
                newMovieSearchResponse(
                    o.optString("title"),
                    "$mainUrl/video${videoKey(o)}",
                    TvType.Movie
                ) {
                    this.posterUrl = p
                }
            }
        }.filterNotNull()
    }

    private class RawPage(
        val videos: LinkedHashMap<String, JSONObject>,
        val more: Boolean,
        val debug: String
    )

    // Bir sayfa HAM video döndürür (süre süzgeci uygulanmış). Sıralama/etiket süzgeci çağıranda
    private suspend fun searchRaw(q: String, page: Int, withChannels: Boolean): RawPage {
        val ck = (if (withChannels) "S:" else "H:") + q
        val seen = seenMap.getOrPut(ck) { java.util.Collections.synchronizedSet(HashSet()) }
        if (page <= 1) {
            seen.clear()
            chanMap.remove(ck)
            cursors.keys.removeAll { it.startsWith("$ck|") }
        }
        val chans = chanMap.getOrPut(ck) { LinkedHashMap() }

        var cur: Cur? = if (page <= 1) null else cursors["$ck|$page"]
        var searchMore = page <= 1 || cur != null
        val fresh = LinkedHashMap<String, JSONObject>()
        var firstShape: String? = null
        var tries = 0

        while (searchMore && tries < MAX_EMPTY_FETCHES) {
            tries++
            val params = mutableMapOf("q" to q, "count" to "30", "extended" to "1")
            if (cur != null) params["start_from"] = cur.next
            var resp: Any? = api("catalog.getVideoSearchWeb2", params)
            var vids = LinkedHashMap<String, JSONObject>()
            collectVideos(resp, vids)

            // Arama imleci işe yaramadıysa bölüm (section) imleciyle dene
            if (cur != null && cur.section != null && vids.keys.none { it !in seen }) {
                try {
                    val r2 = api(
                        "catalog.getSection",
                        mapOf(
                            "section_id" to cur.section,
                            "start_from" to cur.next,
                            "count" to "30",
                            "extended" to "1"
                        )
                    )
                    val v2 = LinkedHashMap<String, JSONObject>()
                    collectVideos(r2, v2)
                    if (v2.keys.any { it !in seen }) {
                        resp = r2
                        vids = v2
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                }
            }

            if (firstShape == null) firstShape = shape(resp)
            if (withChannels) collectGroups(resp, chans)

            val nxt = findCursor(resp)
            cur = if (nxt != null && nxt.next != cur?.next) nxt else null

            for ((k, v) in vids) {
                if (v.optInt("duration") >= MIN_DURATION && seen.add(k)) fresh[k] = v
            }
            searchMore = cur != null
            if (fresh.isNotEmpty()) break
        }
        cur?.let { cursors["$ck|${page + 1}"] = it }

        // Kanallar / topluluklar: adı aramayla eşleşenlerin videoları
        var chMore = false
        var matched: List<Long> = emptyList()
        if (withChannels) {
            matched = chans.filter { channelMatches(q, it.value) }.keys.take(MAX_CHANNELS)
            val res = matched.amap { gid -> channelVideos(gid, (maxOf(page, 1) - 1) * CH_PAGE) }
            for ((list, more) in res) {
                if (more) chMore = true
                for (v in list) {
                    if (v.optInt("duration") >= MIN_DURATION && seen.add(videoKey(v))) {
                        fresh[videoKey(v)] = v
                    }
                }
            }
        }
        val dbg = "${firstShape ?: "-"} groups=${chans.size} matched=${matched.size} names=${chans.values.take(6)}"
        return RawPage(fresh, searchMore || chMore, dbg)
    }

    // ---------- Yandex yardımı ----------

    private fun vkNorm(h: String): String = h
        .replace("%252F", "/", true).replace("%2F", "/", true).replace("%3A", ":", true)
        .replace("%3D", "=", true).replace("%3F", "?", true).replace("%26", "&", true)
        .replace("&amp;", "&").replace("\\u002F", "/", true).replace("\\u0026", "&", true)
        .replace("\\/", "/")

    // Sonuç sayfasındaki VK video numaraları: (erişimsiz anahtar, hash'li anahtar)
    private fun parseEngine(html: String): List<Pair<String, String>> {
        val h = vkNorm(html)
        val out = LinkedHashMap<String, String>()
        fun add(oid: String, id: String, hash: String) {
            if (oid.toLongOrNull() == null || id.toLongOrNull() == null) return
            if (oid.toLong() == 0L || id.toLong() == 0L) return
            val k = "${oid}_$id"
            if (!out.containsKey(k)) out[k] = if (hash.isNotEmpty()) "${k}_$hash" else k
        }
        val ic = RegexOption.IGNORE_CASE
        for (m in Regex("(?:vkvideo\\.ru|vk\\.com|vk\\.ru)/(?:video|clip)(-?\\d+)_(\\d+)(?:_([0-9a-f]{8,}))?", ic).findAll(h)) {
            add(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        for (m in Regex("[?&]z=(?:video|clip)(-?\\d+)_(\\d+)", ic).findAll(h)) {
            add(m.groupValues[1], m.groupValues[2], "")
        }
        for (m in Regex(
            "video_ext\\.php\\?[^\"'\\s<>\\\\]*?oid=(-?\\d+)[^\"'\\s<>\\\\]*?&id=(\\d+)(?:[^\"'\\s<>\\\\]*?hash=([0-9a-f]{8,}))?",
            ic
        ).findAll(h)) {
            add(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        return out.entries.map { it.key to it.value }
    }

    private fun yandexUrls(q: String, qi: Int): List<String> {
        val e = enc(q)
        val all = listOf(
            "https://yandex.com.tr/search/touch/?text=${e.replace("%20", "+")}&lr=11508",
            "https://yandex.com.tr/video/search?text=$e",
            "https://yandex.com/video/search?text=$e",
            "https://yandex.com.tr/search/?text=$e"
        )
        return if (qi < 2) all else all.take(2)
    }

    private class YandexRes(val found: LinkedHashMap<String, String>, val captcha: Int, val fetched: Int)

    // "ad tr dublaj izle vkvideo.ru" tarzı sorgularla Yandex'ten VK video numaraları toplar
    private suspend fun yandexCollect(names: List<String>): YandexRes {
        val found = LinkedHashMap<String, String>()
        var captcha = 0
        var fetched = 0
        val cleaned = names.map { it.replace(Regex("[:!?,\"]+"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        val qs = ArrayList<String>()
        for (tag in YANDEX_TAGS) for (n in cleaned) qs.add("$n $tag")
        for (n in cleaned) qs.add("site:vkvideo.ru $n izle")
        val queries = qs.distinct().take(YANDEX_QUERIES)
        val tasks = ArrayList<String>()
        queries.forEachIndexed { qi, q -> tasks.addAll(yandexUrls(q, qi)) }

        val eh = mapOf(
            "User-Agent" to MOBILE_UA,
            "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to "https://yandex.com.tr/"
        )
        val deadline = System.currentTimeMillis() + YANDEX_MS
        for (chunk in tasks.chunked(4)) {
            if (System.currentTimeMillis() > deadline) break
            val texts = chunk.amap { u ->
                try {
                    val r = app.get(u, headers = eh, timeout = 5L)
                    if (r.code in 200..299) r.text else null
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    null
                }
            }
            for (t in texts) {
                if (t == null) continue
                fetched++
                val ids = parseEngine(t)
                if (ids.isEmpty() && t.contains("captcha", true)) captcha++
                for ((k, vid) in ids) {
                    val old = found[k]
                    if (old == null || (old.split("_").size < 3 && vid.split("_").size > 2)) found[k] = vid
                }
            }
        }
        return YandexRes(found, captcha, fetched)
    }

    private fun taggedQueries(names: List<String>): List<String> {
        val qs = LinkedHashSet<String>()
        for (tag in TAGS) for (n in names) qs.add("$n $tag".trim())
        return qs.filter { it.length >= 3 }.take(TAGGED_QUERIES)
    }

    // Aramanın tamamı: düz arama + kanallar + etiketli sorgular + Yandex; sonra etiket/ad süzgeci ve puan sıralaması
    private suspend fun smartSearch(q: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val (sq, noName) = baseQuery(q)                          // yazılan etiketler atılmış temiz sorgu
        if (page > 1) {
            val raw = searchRaw(sq, page, true)
            val names = namesCache[q] ?: (if (noName) emptyList() else listOf(sq))
            val ranked = rankAll(raw.videos.values, names, emptySet())
            return toResponses(ranked, tmCache[q]) to raw.more
        }
        val ex = if (noName) ExpandRes(emptyList(), emptyList(), null) else expandNames(sq)
        val names = ex.names                                      // ad süzgeci (boş = sadece etiket süzgeci)
        val qNames = if (noName) listOf(sq) else names            // VK / Yandex sorgularında kullanılacak adlar
        namesCache[q] = names
        tmCache[q] = ex

        val merged = LinkedHashMap<String, JSONObject>()
        val yKeys = HashSet<String>()
        var more = false
        var dbgTxt = ""
        var yRes: YandexRes? = null

        // 0: Yandex toplama, 1: VK aramaları (düz + kanallar + etiketli sorgular) — ikisi paralel
        listOf(0, 1).amap { which ->
            if (which == 0) {
                if (YANDEX) {
                    try {
                        yRes = yandexCollect(qNames)
                    } catch (e: Exception) {
                        if (e is java.util.concurrent.CancellationException) throw e
                    }
                }
            } else {
                val base = searchRaw(sq, 1, true)
                more = base.more
                dbgTxt = base.debug
                for (o in base.videos.values) merged.putIfAbsent(baseOf(o), o)

                for (ch in taggedQueries(qNames).chunked(4)) {
                    val raws = ch.amap { t ->
                        try {
                            searchRaw(t, 1, false)
                        } catch (e: Exception) {
                            if (e is java.util.concurrent.CancellationException) throw e
                            RawPage(LinkedHashMap(), false, "")
                        }
                    }
                    for (rp in raws) for (o in rp.videos.values) merged.putIfAbsent(baseOf(o), o)
                }
            }
            0
        }

        var yStat = "yandex kapalı"
        val y = yRes
        if (y != null) {
            // Yandex'ten gelen numaralar VK API'den doğrulanır (gerçek başlık + süre), 10'arlı toplu istek
            val need = y.found.entries.filter { it.key !in merged }.take(YANDEX_VERIFY)
            val objs = need.chunked(10).amap { ch ->
                try {
                    val r = api("video.getByIds", mapOf("videos" to ch.joinToString(",") { it.value }))
                    val m = LinkedHashMap<String, JSONObject>()
                    collectVideos(r, m)
                    m.values.toList()
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    emptyList<JSONObject>()
                }
            }.flatten()
            for (o in objs) {
                if (o.optString("access_key").isBlank()) {                       // API erişim anahtarı vermediyse Yandex adresindeki hash
                    val h = y.found[baseOf(o)]?.split("_")?.getOrNull(2)
                    if (!h.isNullOrBlank()) o.put("access_key", h)
                }
                if (o.optInt("duration") >= MIN_DURATION) {
                    yKeys.add(baseOf(o))
                    merged.putIfAbsent(baseOf(o), o)
                }
            }
            yStat = "yandex sayfa=${y.fetched} captcha=${y.captcha} numara=${y.found.size} dogrulanan=${objs.size}"
        }

        val ranked = rankAll(merged.values, names, yKeys).take(MAX_RESULTS)
        val items = ArrayList<SearchResponse>()
        if (DEBUG_SEARCH) {
            items.add(
                newMovieSearchResponse(
                    "DEBUG sorgu=$sq adlar=$names toplam=${merged.size} aday=${ranked.size} tmdbPoster=${ex.poster != null} $yStat | $dbgTxt",
                    "$mainUrl/video0_0",
                    TvType.Movie
                )
            )
        }
        items.addAll(toResponses(ranked, ex))
        return items to more
    }

    private suspend fun fetchItem(key: String): JSONObject {
        val r = try {
            api("video.getByIds", mapOf("videos" to key))
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            api("video.get", mapOf("videos" to key))
        }
        val found = LinkedHashMap<String, JSONObject>()
        collectVideos(r, found)
        return found.values.firstOrNull()
            ?: throw ErrorLoadingException("Video bilgisi bulunamadı: $key")
    }

    // ---------- Link yardımcıları ----------

    private fun isImage(u: String): Boolean {
        if (u.contains(".mp4") || u.contains(".m3u8") || u.contains(".mpd")) return false
        return u.contains(".jpg") || u.contains(".jpeg") || u.contains(".png") ||
            u.contains(".webp") || u.contains("getVideoPreview") ||
            u.contains("userapi.com") || u.contains("vkuserphoto")
    }

    private fun hostOf(u: String): String =
        Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1) ?: ""

    private fun isMedia(u: String): Boolean {
        if (isImage(u)) return false
        if (u.contains("video_ext.php")) return false
        val host = hostOf(u)
        if (Regex("(^|\\.)(vkvideo\\.ru|vk\\.com|vk\\.ru|m\\.vk\\.com)$", RegexOption.IGNORE_CASE).containsMatchIn(host)) return false
        return u.contains(".m3u8") || u.contains(".mpd") || u.contains(".mp4") ||
            u.contains("okcdn.ru") ||
            Regex("(mycdn\\.me|vkuservideo\\.(net|ru)|vkvd\\d*\\.)", RegexOption.IGNORE_CASE).containsMatchIn(host) ||
            Regex("[?&]type=\\d").containsMatchIn(u)
    }

    // JSON içinde nerede olursa olsun http ile başlayan metinleri bulur
    private fun scanUrls(node: Any?, hint: String, out: MutableList<Pair<String, String>>) {
        when (node) {
            is JSONObject -> {
                val ks = node.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    if (k == "image" || k == "first_frame" || k.startsWith("photo_")) continue
                    scanUrls(node.opt(k), k, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) scanUrls(node.opt(i), hint, out)
            }
            is String -> {
                if (node.startsWith("http")) out.add(hint to node)
            }
        }
    }

    private fun qualityOf(hint: String, u: String): Int? {
        Regex("(\\d{3,4})").find(hint)?.value?.toIntOrNull()?.let { return it }
        val t = Regex("[?&]type=(\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull()
        return when (t) {
            0 -> 144
            1 -> 240
            2 -> 360
            3 -> 480
            4 -> 720
            5 -> 1080
            6 -> 1440
            7 -> 2160
            else -> null
        }
    }

    // Bağlantı adayı: hangi başlık takımının kabul edildiği (headers) ve ölü olup olmadığı (dead) test edilir
    private class Cand(val hint: String, val url: String) {
        var headers: Map<String, String>? = null
        var dead = false
        var diag = ""
    }

    private fun isHls(c: Cand) = c.url.contains(".m3u8") || c.hint.contains("hls", true)
    private fun isDash(c: Cand) = c.url.contains(".mpd") || c.hint.contains("dash", true)

    private fun addCand(list: MutableList<Cand>, seen: HashSet<String>, hint: String, raw: String): Boolean {
        val u = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        if (!isMedia(u) || !seen.add(u)) return false
        list.add(Cand(hint, u))
        return true
    }

    // Sayfa yapısı/anahtar adı ne olursa olsun HTML içindeki medya adreslerini bulur
    private fun scanHtml(html: String, list: MutableList<Cand>, seen: HashSet<String>): Int {
        val t = html.replace("\\/", "/").replace("\\u0026", "&").replace("&quot;", "\"").replace("&amp;", "&")
        var n = 0
        for (m in Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"(https?://[^\"\\s]+)\"").findAll(t)) {
            if (addCand(list, seen, m.groupValues[1], m.groupValues[2])) n++
        }
        for (m in Regex("https?://[^\\s\"'<>\\\\]+").findAll(t)) {
            if (addCand(list, seen, "", m.value)) n++
        }
        return n
    }

    // Yönlendirmeleri elle takip eder, çerezleri taşır (döngüyü kırar). budgetMs dolunca null döner
    private suspend fun fetchFollow(url: String, h: Map<String, String> = hdr, budgetMs: Long = 8000L): String? {
        val end = System.currentTimeMillis() + budgetMs
        var u = url
        var cookies = emptyMap<String, String>()
        repeat(10) {
            if (System.currentTimeMillis() > end) return null
            val r = app.get(u, headers = h, cookies = cookies, allowRedirects = false, timeout = 5L)
            cookies = cookies + r.cookies
            val loc = r.headers["Location"]
            if (r.code in 300..399 && !loc.isNullOrBlank()) {
                u = java.net.URI(u).resolve(loc).toString()
            } else {
                return r.text
            }
        }
        return null
    }

    // Adreste srcAg=CHROME_LINUX gibi bir değer varsa adres o tarayıcı kimliğine bağlıdır: önce ona uyan UA denenir
    private fun uaForUrl(u: String): String {
        val m = Regex("[?&]srcAg=([^&]+)").find(u) ?: return ""
        val a = m.groupValues[1].uppercase()
        return when {
            Regex("ANDROID|MOBILE").containsMatchIn(a) -> MOBILE_UA
            a.contains("WINDOWS") -> UA_WIN
            a.contains("LINUX") -> UA
            else -> ""
        }
    }

    private fun hdrList(url: String): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        fun add(h: Map<String, String>) {
            if (h !in out) out.add(h)
        }
        val ref = "$mainUrl/"
        val ua = uaForUrl(url)
        if (ua.isNotEmpty()) {
            add(mapOf("User-Agent" to ua, "Referer" to ref, "Origin" to mainUrl))
            add(mapOf("User-Agent" to ua))
        }
        add(mapOf("User-Agent" to UA, "Referer" to ref, "Origin" to mainUrl))
        add(mapOf("User-Agent" to MOBILE_UA, "Referer" to ref, "Origin" to mainUrl))
        add(mapOf("User-Agent" to UA))
        add(emptyMap())
        add(mapOf("User-Agent" to UA, "Referer" to ref))
        add(mapOf("User-Agent" to MOBILE_UA))
        return out
    }

    // Her bağlantı ayrı denenir: önce ilk 3 başlık takımı, tutmazsa kalanlar. Kabul edilen takım c.headers, kesin ölü ise c.dead
    private suspend fun probe(c: Cand) {
        val isH = isHls(c)
        val hv = hdrList(c.url)

        // 1 = çalışıyor, -1 = çalışmıyor, 0 = belirsiz (ağ hatası)
        suspend fun one(i: Int): Pair<Int, Int> {
            val h = hv[i].toMutableMap()
            if (!isH) h["Range"] = "bytes=0-1"
            return try {
                val r = app.get(c.url, headers = h, timeout = 4L)
                val ct = r.headers["Content-Type"] ?: ""
                val st = if (isH) {
                    if (r.code in 200..299 && r.text.trimStart().startsWith("#EXTM3U")) 1 else -1
                } else {
                    if ((r.code == 200 || r.code == 206) &&
                        !Regex("(?i)text/html|json").containsMatchIn(ct)
                    ) 1 else -1
                }
                st to r.code
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                0 to 0
            }
        }

        val first = (0 until min(3, hv.size)).toList().amap { one(it) }
        val all = if (first.any { it.first == 1 } || hv.size <= 3) first
        else first + (3 until hv.size).toList().amap { one(it) }

        val okIdx = all.indexOfFirst { it.first == 1 }
        val unknown = all.any { it.first == 0 }
        c.diag = all.mapIndexed { i, p -> "V$i:${p.second}" }.joinToString(" ")
        if (okIdx >= 0) c.headers = hv[okIdx]
        else if (!unknown) c.dead = true
    }

    private suspend fun emitCand(c: Cand, lang: String, callback: (ExtractorLink) -> Unit) {
        val ref = "$mainUrl/"
        val hdrs = c.headers ?: hdrList(c.url).first()
        val src = if (lang.isNotBlank()) "$name $lang" else name
        when {
            isHls(c) -> {
                try {
                    M3u8Helper.generateM3u8(src, c.url, ref, headers = hdrs).forEach(callback)
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    callback(
                        newExtractorLink(src, "$src HLS", c.url, ExtractorLinkType.M3U8) {
                            this.referer = ref
                            this.headers = hdrs
                        }
                    )
                }
            }
            isDash(c) -> {
                callback(
                    newExtractorLink(src, "$src DASH", c.url, ExtractorLinkType.DASH) {
                        this.referer = ref
                        this.headers = hdrs
                    }
                )
            }
            else -> {
                val q = qualityOf(c.hint, c.url)
                val label = if (q != null) "${q}p" else c.hint.ifBlank { "MP4" }
                callback(
                    newExtractorLink(src, "$src $label", c.url) {
                        this.referer = ref
                        this.headers = hdrs
                        this.quality = q ?: Qualities.Unknown.value
                    }
                )
            }
        }
    }

    // ---------- CloudStream ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val raw = searchRaw(request.data, page, false)
        val ranked = rankAll(raw.videos.values, emptyList(), emptySet())
        return newHomePageResponse(request.name, toResponses(ranked), raw.more)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, more) = smartSearch(query, page)
        return newSearchResponseList(items, more)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return smartSearch(query, 1).first
    }

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfter("/video").substringBefore("?")
        val item = fetchItem(key)
        val title = item.optString("title")
        val desc = item.optString("description")
        val durSec = item.optInt("duration")
        val vkPoster = workingPoster(item)

        // Başlık + yıl + süre uyuyorsa TMDB'deki gerçek film kaydından poster / özet / yıl al
        val tm = try {
            tmdbLookup(title, durSec)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }
        val tmPoster = tm?.optString("poster_path")?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
        val tmBack = tm?.optString("backdrop_path")?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val tmPlot = tm?.optString("overview")?.takeIf { it.isNotBlank() }
        val tmYear = tm?.optString("release_date")?.take(4)?.toIntOrNull()
        val tmTags = ArrayList<String>()
        tm?.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) {
                val n = g.optJSONObject(i)?.optString("name")
                if (!n.isNullOrBlank()) tmTags.add(n)
            }
        }

        return newMovieLoadResponse(tm?.optString("title")?.takeIf { it.isNotBlank() } ?: title, url, TvType.Movie, key) {
            this.posterUrl = tmPoster ?: vkPoster
            this.backgroundPosterUrl = tmBack
            this.plot = tmPlot ?: desc.ifBlank { null }
            this.year = tmYear
            this.duration = durSec / 60
            if (tmTags.isNotEmpty()) this.tags = tmTags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = fetchItem(data)
        val seen = HashSet<String>()
        val cands = ArrayList<Cand>()
        val debug: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())

        val lang = langInfo(analyze(item.optString("title"))).let { if (it.ok) it.label else "" }

        val parts = data.split("_")
        val oid = parts.getOrNull(0) ?: ""
        val vid = parts.getOrNull(1) ?: ""

        debug.add(
            "info type=${item.optString("type")} srv=${item.optString("server")} " +
                "ovp=${item.optString("ov_provider_id")} h=${item.optInt("height")}"
        )

        // 1) getByIds / get yanıtı içindeki linkler
        val urls1 = ArrayList<Pair<String, String>>()
        scanUrls(item, "", urls1)
        for ((k, u) in urls1) addCand(cands, seen, k, u)
        debug.add("item links=${cands.size}")

        // 2) Sitenin kendi oynatma metodları (paralel)
        val playParams = mapOf(
            "videos" to data,
            "video" to data,
            "owner_id" to oid,
            "video_id" to vid,
            "id" to vid
        )
        val playRes = listOf("video.getForPlay", "video.getPlayerConfig").amap { m ->
            val list = ArrayList<Pair<String, String>>()
            try {
                val r = api(m, playParams)
                scanUrls(r, "", list)
                debug.add("$m urls=${list.size} ${shape(r)}")
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                debug.add("$m: ${e.message?.take(90)}")
            }
            list
        }
        for (l in playRes) for ((k, u) in l) addCand(cands, seen, k, u)

        // 3) Yedek: oynatıcı (embed) sayfası — takılırsa 6 sn'de bırakılır
        var player = item.optString("player")
        if (cands.isEmpty()) {
            if (player.isBlank()) {
                val ak = item.optString("access_key")
                player = "$mainUrl/video_ext.php?oid=$oid&id=$vid" +
                    (if (ak.isNotBlank()) "&hash=$ak" else "")
            }
            try {
                val html = fetchFollow(player, hdr, 6000L)
                if (html == null) {
                    debug.add("embed: yönlendirme döngüsü / zaman aşımı")
                } else {
                    val n = scanHtml(html, cands, seen)
                    debug.add(
                        "embed len=${html.length} links=$n head=" + html.take(80).replace("\n", " ")
                    )
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                debug.add("embed: ${e.message?.take(90)}")
            }
        }

        // 3b) Farklı adresler / mobil sayfa (HTML'de oynatıcı verisi başka yerde olabilir)
        if (cands.isEmpty()) {
            val hash = Regex("[?&]hash=([0-9a-zA-Z]+)").find(player)?.groupValues?.get(1)
                ?: item.optString("access_key")
            val mobileHdr = hdr + mapOf("User-Agent" to MOBILE_UA)
            val alts = listOf(
                player to mobileHdr,
                ("https://vk.com/video_ext.php?oid=$oid&id=$vid" + (if (hash.isNotBlank()) "&hash=$hash" else "")) to hdr,
                ("https://m.vk.com/video${oid}_$vid" + (if (hash.isNotBlank()) "?access_key=$hash" else "")) to mobileHdr
            )
            for ((cu, ch) in alts) {
                if (cands.isNotEmpty()) break
                try {
                    val html = fetchFollow(cu, ch, 5000L) ?: continue
                    val n = scanHtml(html, cands, seen)
                    debug.add("alt ${cu.take(45)} len=${html.length} links=$n")
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    debug.add("alt: ${e.message?.take(70)}")
                }
            }
        }

        // 3c) Son çare: sayfayı WebView'da açıp oynatıcının istediği medya adresini yakala
        if (cands.isEmpty()) {
            for (wu in listOf(player, "$mainUrl/video$data")) {
                if (cands.isNotEmpty()) break
                try {
                    val r = app.get(
                        wu,
                        headers = hdr,
                        referer = "$mainUrl/",
                        interceptor = WebViewResolver(
                            Regex("""(?i)\.m3u8|\.mpd|(vkvd|okcdn|mycdn)[^/\s]*/[^\s?]*\.mp4"""),
                            userAgent = null,
                            useOkhttp = false,
                            timeout = 20_000L
                        ),
                        timeout = 30L
                    )
                    val cu = r.url
                    debug.add("webview ${cu.take(70)}")
                    if (cu != wu) addCand(cands, seen, "", cu)
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    debug.add("webview: ${e.message?.take(70)}")
                }
            }
        }

        // 4) Bağlantı testi: en iyi 4 mp4 + 1 HLS için hangi başlık takımının çalıştığı bulunur, ölüler sona atılır
        if (cands.isNotEmpty()) {
            val mp4s = cands.filter { !isHls(it) && !isDash(it) }
                .sortedByDescending { qualityOf(it.hint, it.url) ?: 0 }
            val targets = mp4s.take(4) + cands.filter { isHls(it) }.take(1)
            targets.amap { probe(it) }
            debug.add("probe " + targets.joinToString(" | ") { (if (isHls(it)) "hls:" else "mp4:") + it.diag })

            val alive = cands.filter { !it.dead }
            val toEmit = if (alive.isNotEmpty()) alive else cands            // hepsi ölüyse yine de ver
            for (c in toEmit) emitCand(c, lang, callback)
            return true
        }

        // 5) Hiçbiri olmadıysa teşhis satırları göster (her satırın adresi benzersiz)
        debug.add("player=" + player.take(70))
        debug.forEachIndexed { i, d ->
            callback(
                newExtractorLink(name, "DEBUG $d", "https://debug.invalid/$i") { }
            )
        }
        return true
    }
}
