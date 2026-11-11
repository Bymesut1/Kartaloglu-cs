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
        private const val MIN_DURATION = 0

        // true: başlıkta tr dublaj / türkçe dublaj / izle / film izle / filmi izle ... YOKSA video listeye girmez
        private const val REQUIRE_TAG = false

        // true: açılan posteri olmayan kartlar listeden çıkarılır
        private const val REQUIRE_POSTER = false

        // Bir sayfa boş çıkarsa (süzgeçten geçen video yoksa) en fazla kaç ham istek daha atılsın
        private const val MAX_EMPTY_FETCHES = 4

        // Aramaya videoları dahil edilecek en fazla kanal/topluluk sayısı
        private const val MAX_CHANNELS = 10

        // Her kanaldan sayfa başına çekilecek video sayısı
        private const val CH_PAGE = 100

        // Aramada (1. sayfa) en fazla kaç sonuç gösterilsin (poster kontrolü yüzünden sınırlı)
        private const val MAX_RESULTS = 40

        // Aşağı kaydırınca her yeni sayfada en az kaç sonuç hedeflensin (süzgeçten geçen yoksa arkadan yeni ham sayfa çekilir)
        private const val PAGE_TARGET = 24

        // Bir sayfayı doldurmak için en fazla kaç ham sayfa çekilsin
        private const val MAX_PAGE_LOOPS = 8

        // 1. sayfada ilk 4 videodan sonra gösterilecek klip sayısı (sitedeki Klipler satırı gibi)
        private const val CLIP_MAX = 8

        // Aramada etiketli sorgulardan (ad + "Türkçe Dublaj", "izle" ...) en fazla kaçı denensin
        private const val TAGGED_QUERIES = 12

        // Yandex yardımı: "ad tr dublaj izle vkvideo.ru" tarzı aramalardan VK video numaraları toplanır
        private const val YANDEX = false
        private const val YANDEX_QUERIES = 12
        private const val YANDEX_MS = 9000L
        private const val YANDEX_VERIFY = 24

        // Yandex'in ilk kaç sonucu, süzgeç ne derse desin listenin en üstünde mutlaka gösterilsin
        private const val YANDEX_FORCE = 3

        // VK'nin kendi aramasından (site + video.search) adı uyan ilk kaç sonuç süzgeçten bağımsız gösterilsin
        private const val VK_FORCE = 3

        // video.search ile sayfa başına çekilecek video sayısı (en fazla 200)
        private const val CLASSIC_PAGE = 100

        // Yandex'ten gelen videolar için en kısa süre (saniye); normal MIN_DURATION'dan gevşek
        private const val YANDEX_MIN = 600

        // true: arama sonuçlarının başına teşhis kartı (API yanıt şekli, kanallar, Yandex durumu) eklenir
        private const val DEBUG_SEARCH = true

        // true: bağlantı bulunamazsa kaynak listesine DEBUG satırları eklenir (tıklayınca 2001 hatası verir). Normalde false.
        private const val DEBUG_LINKS = false

        // WebView'da oynatıcı kullanıcı dokunuşu beklediği için video kendiliğinden başlamaz; bu betik oynatmayı tetikler
        private const val PLAY_JS =
            "(function(){var n=0;var t=setInterval(function(){try{" +
                "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
                "var b=document.querySelector('[class*=\"play\" i],[aria-label*=\"Play\" i],[aria-label*=\"Воспр\" i],[class*=\"Play\"]');" +
                "if(b&&b.click)b.click();" +
                "var c=document.querySelector('video')||document.body;if(c&&c.click)c.click();" +
                "}catch(e){}if(++n>40)clearInterval(t);},400);})();"

        // DevTools -> Ağ -> "?act=get_anonym_token" isteği -> Yük (Payload) / Başlıklar
        private const val LOGIN = "https://login.vk.ru/?act=get_anonym_token"
        private const val CLIENT_SECRET = "o557NLIkAErNhakXrQ7A"
        private const val APP_ID = "6287487"
        private const val SCOPES = "audio_anonymous,video_anonymous,photos_anonymous,profile_anonymous"
        private const val DEVICE_ID = ""                    // tarayıcıdaki token isteğinde device_id yok; boş bırakılırsa gönderilmez

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

        // ExoPlayer'ın tanıdığı mp4 "brand" değerleri. Dosyanın başında bunlardan hiçbiri yoksa
        // oynatıcı "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" verir (tarayıcı yine de oynatabilir)
        private val KNOWN_BRANDS = listOf(
            "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso9", "avc1", "hvc1", "hev1", "av01",
            "mp41", "mp42", "mmp4", "3g2a", "3g2b", "3g2c", "3gr6", "3gs6", "3ge6", "3gg6", "3gp4",
            "3gp5", "3gp6", "3gp7", "M4V ", "M4A ", "f4v ", "kddi", "M4VP", "M4PH", "M4PC", "msnv",
            "MSNV", "dash", "msdh", "msix", "cmfc", "dby1", "isml", "piff", "qt  ", "XAVC"
        )

        // "ftyp" olmadan başlayıp yine de geçerli sayılan ilk kutular
        private val OK_FIRST_BOX = listOf("moov", "mdat", "free", "skip", "wide", "pnot", "uuid")
    }

    private val hdr = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Referer" to "https://vkvideo.ru/",
        "Origin" to "https://vkvideo.ru"
    )

    private var token: String? = null
    private var tokenExp = 0L          // jetonun bitiş zamanı (unix saniye), 0 = bilinmiyor
    private var tokenAt = 0L           // jetonun alındığı an (ms)

    // DEBUG kartına dokununca gösterilen tam teşhis metni
    private var lastDebug: String = ""

    // loadLinks aynı anda tek çalışsın (CloudStream aynı videoyu iki kez paralel isteyebiliyor; VK bunu kısıtlıyor)
    private val loadSem = java.util.concurrent.Semaphore(1)
    private var deviceId = ""                                   // boş: gönderilmez; yenileme turlarında rastgele üretilir
    private class CachedLinks(val at: Long, val lang: String, val list: List<Cand>)
    private val linkCache = HashMap<String, CachedLinks>()
    private val CACHE_MS = 10L * 60_000L

    private fun randomDeviceId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    override val mainPage = mainPageOf(
        "türkçe dublaj film" to "Türkçe Dublaj Filmler",
        "tr dublaj izle" to "TR Dublaj İzle",
        "yerli film izle" to "Yerli Filmler"
    )

    // ---------- API ----------

    private suspend fun getToken(): String {
        // Jeton süresi dolmak üzereyse ya da 25 dakikadan eskiyse yenilenir (eski / kısıtlanmış jeton
        // video bağlantılarını boş döndürebiliyor; eklentiyi yeniden kurmak bunu geçici çözüyordu)
        val cached = token
        if (cached != null) {
            val nowMs = System.currentTimeMillis()
            val expired = tokenExp > 0L && tokenExp - 120L <= nowMs / 1000L
            val old = nowMs - tokenAt > 25L * 60_000L
            if (!expired && !old) return cached
        }
        if (CLIENT_SECRET.isBlank()) {
            throw ErrorLoadingException("CLIENT_SECRET boş: VkVideo.kt içine yaz")
        }
        val text = app.post(
            LOGIN,
            headers = hdr,
            data = mutableMapOf(
                "client_secret" to CLIENT_SECRET,
                "client_id" to CLIENT_ID,
                "scopes" to SCOPES,
                "isApiOauthAnonymEnabled" to "false",
                "version" to "1",
                "app_id" to APP_ID
            ).also {
                val d = if (deviceId.isNotBlank()) deviceId else DEVICE_ID
                if (d.isNotBlank()) it["device_id"] = d
            }
        ).text
        val dataObj = try {
            JSONObject(text).optJSONObject("data")
        } catch (e: Exception) {
            null
        }
        val t = dataObj?.optString("access_token")
        if (t.isNullOrBlank()) throw ErrorLoadingException("Token alınamadı: $text")
        token = t
        tokenAt = System.currentTimeMillis()
        tokenExp = dataObj?.optLong("expired_at", 0L) ?: 0L
        return t
    }

    private suspend fun api(
        method: String,
        params: Map<String, String>,
        retry: Boolean = true
    ): Any? {
        var attempt = 0
        var lastText = ""
        while (true) {
            attempt++
            var text: String? = null
            try {
                text = app.post(
                    "$API/$method?v=$V&client_id=$CLIENT_ID",
                    headers = hdr,
                    data = params + ("access_token" to getToken())
                ).text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                lastText = e.message ?: ""
            }
            val json: JSONObject? = try {
                if (text != null) JSONObject(text) else null
            } catch (e: Exception) {
                lastText = text?.take(80) ?: ""
                null
            }
            if (json == null) {
                // JSON olmayan yanıt ("server ..." vb.) veya ağ hatası: bekleyip yeniden dene
                if (attempt < 4) {
                    Thread.sleep(700L * attempt)
                    continue
                }
                throw ErrorLoadingException("VK yanıtı okunamadı ($method): $lastText")
            }
            val err = json.optJSONObject("error")
            if (err != null) {
                val code = err.optInt("error_code")
                if (retry && code == 5) {
                    token = null
                    return api(method, params, false)
                }
                // 1 / 6 / 9 / 10: geçici sunucu ve çok sık istek hataları: bekleyip yeniden dene
                if ((code == 1 || code == 6 || code == 9 || code == 10) && attempt < 4) {
                    Thread.sleep(700L * attempt)
                    continue
                }
                throw ErrorLoadingException("VK hata $code ($method): ${err.optString("error_msg")}")
            }
            return json.opt("response")
        }
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

    // Teşhis: yanıtın iki seviye derinliğindeki anahtarlar
    private fun deepShape(r: Any?, depth: Int = 0): String {
        if (r !is JSONObject || depth > 1) return ""
        val sb = StringBuilder()
        val ks = r.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            val v = r.opt(k)
            sb.append(k)
            if (v is JSONObject) sb.append("{").append(v.keys().asSequence().take(30).joinToString(",")).append("}")
            else if (v is JSONArray) sb.append("[").append(v.length()).append("]")
            else if (v is String) sb.append("=").append(v.take(24))
            sb.append(' ')
        }
        return sb.toString().take(500)
    }

    // Boş "Original sound" kartları (açılmayan ses klipleri) listeye alınmaz
    private fun isPlaceholder(o: JSONObject): Boolean =
        o.optString("title").trim().equals("Original sound", ignoreCase = true)

    // Klip (dikey / short_video) mi? Sitede bunlar "Klipler" satırında ayrı gösterilir
    private fun isClipItem(o: JSONObject): Boolean {
        if (o.optString("type") == "short_video") return true
        val w = o.optInt("width")
        val h = o.optInt("height")
        return w > 0 && h > w
    }

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
    private fun rankVideo(o: JSONObject, names: List<String>, yandex: Boolean = false): Int? {
        val title = o.optString("title")
        if (Regex("[\\u0400-\\u04FF]").containsMatchIn(title)) return null           // Rusça (Kiril) başlık
        val dur = o.optInt("duration")
        if (dur < (if (yandex) YANDEX_MIN else MIN_DURATION)) return null
        val info = analyze(title)
        var li = langInfo(info)
        if (!li.ok) {
            val desc = o.optString("description")
            if (desc.isNotBlank()) {
                val l2 = langInfo(analyze(desc.take(300)))
                if (l2.ok && l2.strength >= 3) li = l2
            }
        }
        if (!li.ok && yandex &&
            !("altyazi" in info.tagset || "altyazili" in info.tagset || "sub" in info.tagset)
        ) {
            li = Lang(true, "Yandex", 1)
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
        yandexRank: Map<String, Int>
    ): List<JSONObject> {
        val out = ArrayList<Pair<JSONObject, Int>>()
        for (o in vids) {
            val yi = yandexRank[baseOf(o)]
            val s = rankVideo(o, names, yi != null) ?: continue
            out.add(o to (s + (if (yi != null) 40 - min(yi, 30) else 0)))
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
    private val shownMap = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val rawStart = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
    private suspend fun toResponses(
        videos: Collection<JSONObject>,
        tm: ExpandRes? = null,
        exempt: Set<String> = emptySet()
    ): List<SearchResponse> {
        val check = videos.size <= 60
        return videos.toList().filter { !isPlaceholder(it) }.amap { o ->
            var p = if (check) workingPoster(o) else posterCandidates(o).firstOrNull()
            if (p == null && tm?.poster != null && tm.tmNames.isNotEmpty() &&
                nameMatch(analyze(o.optString("title")), tm.tmNames) >= 0
            ) {
                p = tm.poster
            }
            val fromY = baseOf(o) in exempt
            if (p == null && fromY && tm?.poster != null) p = tm.poster
            if (p == null && REQUIRE_POSTER && !fromY) {
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
            var resp: Any? = try {
                api("catalog.getVideoSearchWeb2", params)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                if (cur == null) throw e          // ilk sayfa: hatayı göster
                break                             // sonraki sayfalar: imleç korunur, bir sonraki kaydırmada yeniden denenir
            }
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

    // Bing sonuç adresleri "u=a1<base64>" olarak sarılır; çözüp metne eklenir
    private fun decodeWrapped(h: String): String {
        val sb = StringBuilder()
        for (m in Regex("[?&;]u=a1([A-Za-z0-9_\\-]{16,})").findAll(h)) {
            try {
                var b = m.groupValues[1].replace('-', '+').replace('_', '/')
                while (b.length % 4 != 0) b += "="
                sb.append(' ').append(
                    String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), Charsets.UTF_8)
                )
            } catch (e: Exception) {
            }
        }
        return sb.toString()
    }

    // Sonuç sayfasındaki VK video numaraları: (erişimsiz anahtar, hash'li anahtar)
    private fun parseEngine(html: String): List<Pair<String, String>> {
        val h = vkNorm(html + " " + decodeWrapped(html))
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
        // "vkvideo.ru › video123_456" şeklinde gösterilen adresler
        for (m in Regex(
            "(?:vkvideo\\.ru|vk\\.com|vk\\.ru)\\s*(?:›|&rsaquo;|&#8250;|&gt;|>|/)\\s*(?:video|clip)(-?\\d+)_(\\d+)(?:_([0-9a-f]{8,}))?",
            ic
        ).findAll(h)) {
            add(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        // Son çare: adres gibi görünen her video<oid>_<id> (VK API zaten doğrular)
        for (m in Regex("(?<=[/=])(?:video|clip)(-?\\d{5,})_(\\d{5,})(?:_([0-9a-f]{8,}))?", ic).findAll(h)) {
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
            "https://yandex.com.tr/search/?text=$e",
            "https://www.bing.com/search?q=$e&setlang=tr&count=30",
            "https://html.duckduckgo.com/html/?q=$e&kl=tr-tr",
            "https://www.google.com/search?q=$e&hl=tr&num=20"
        )
        return if (qi < 2) all else listOf(all[0], all[4])
    }

    private class YandexRes(
        val found: LinkedHashMap<String, String>,
        val captcha: Int,
        val fetched: Int,
        val log: String
    )

    // "ad tr dublaj izle vkvideo.ru" tarzı sorgularla Yandex'ten VK video numaraları toplar (sıra = sonuç sırası)
    private suspend fun yandexCollect(names: List<String>): YandexRes {
        val found = LinkedHashMap<String, String>()
        var captcha = 0
        var fetched = 0
        val logs = ArrayList<String>()
        val cleaned = names.map { it.replace(Regex("[:!?,\"]+"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        val qs = ArrayList<String>()
        // Elle aranınca işe yarayan biçim en başta: "ad tr vk", "ad vk"
        cleaned.firstOrNull()?.let { qs.add("$it tr vk"); qs.add("$it vk") }
        for (n in cleaned.drop(1)) qs.add("$n tr vk")
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

        // Önce ana sayfadan çerez al (captcha ihtimalini azaltır)
        val warm: Map<String, String> = try {
            app.get("https://yandex.com.tr/", headers = eh, timeout = 4L).cookies
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            emptyMap()
        }
        logs.add("cerez=${warm.size}")

        val deadline = System.currentTimeMillis() + YANDEX_MS
        for (chunk in tasks.chunked(4)) {
            if (System.currentTimeMillis() > deadline) break
            val res = chunk.amap { u ->
                val host = hostOf(u).removePrefix("www.")
                val lbl = host + u.substringAfter(hostOf(u)).substringBefore("?").take(14)
                val pr: Pair<String, String?> = try {
                    val r = app.get(u, headers = eh, cookies = warm, timeout = 5L)
                    val t = if (r.code in 200..299) r.text else null
                    "$lbl ${r.code}" to t
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    "$lbl HATA ${(e.message ?: "").take(30)}" to null
                }
                pr
            }
            for ((lg, t) in res) {
                if (t == null) {
                    logs.add(lg)
                    continue
                }
                fetched++
                val ids = parseEngine(t)
                val cap = ids.isEmpty() && t.contains("captcha", true)
                if (cap) captcha++
                logs.add("$lg len=${t.length} id=${ids.size}${if (cap) " CAPTCHA" else ""}")
                for ((k, vid) in ids) {
                    val old = found[k]
                    if (old == null || (old.split("_").size < 3 && vid.split("_").size > 2)) found[k] = vid
                }
            }
        }
        return YandexRes(found, captcha, fetched, logs.take(16).joinToString("\n"))
    }

    // VK'nin klasik video.search yöntemi: site aramasından (catalog) ayrı bir dizin, ek kaynak
    private suspend fun vkApiSearch(names: List<String>): List<JSONObject> {
        val qs = ArrayList<String>()
        for (n in names.take(3)) {
            qs.add(n)
            qs.add("$n tr dublaj")
            qs.add("$n izle")
        }
        val res = qs.distinct().take(8).amap { t ->
            try {
                val r = api(
                    "video.search",
                    mapOf("q" to t, "count" to "50", "extended" to "1", "adult" to "1")
                )
                val m = LinkedHashMap<String, JSONObject>()
                collectVideos(r, m)
                m.values.toList()
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                emptyList<JSONObject>()
            }
        }
        return res.flatten()
    }

    // VK'nin kendi site arama sayfaları: video numaraları HTML'den okunur, VK API'den doğrulanır (sayfa JS ile çiziliyorsa boş dönebilir)
    private suspend fun vkSiteSearch(names: List<String>): List<JSONObject> {
        val urls = ArrayList<String>()
        for (n in names.take(2)) {
            val en = enc(n)
            urls.add("https://vkvideo.ru/?q=$en")
            urls.add("https://vk.com/video?q=$en")
        }
        val pages = urls.amap { u ->
            try {
                val r = app.get(u, headers = hdr, timeout = 6L)
                if (r.code in 200..299) r.text else ""
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ""
            }
        }
        val ids = LinkedHashMap<String, String>()
        for (t in pages) for ((k, v) in parseEngine(t)) if (!ids.containsKey(k)) ids[k] = v
        val out = ArrayList<JSONObject>()
        for (ch in ids.values.take(30).chunked(10)) {
            try {
                val r = api("video.getByIds", mapOf("videos" to ch.joinToString(",")))
                val m = LinkedHashMap<String, JSONObject>()
                collectVideos(r, m)
                out.addAll(m.values)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }
        return out
    }

    // VK'nin klasik video.search yöntemi: sitedeki "Tüm videolar" bloğunu (uzun filmler dahil) yakalar; offset ile sayfa sayfa devam eder
    private suspend fun vkClassicSearch(q: String, page: Int): Pair<List<JSONObject>, Boolean> {
        return try {
            val off = (maxOf(page, 1) - 1) * CLASSIC_PAGE
            val r = api(
                "video.search",
                mapOf(
                    "q" to q,
                    "count" to "$CLASSIC_PAGE",
                    "offset" to "$off",
                    "extended" to "1",
                    "adult" to "1"
                )
            )
            val m = LinkedHashMap<String, JSONObject>()
            collectVideos(r, m)
            val total = (r as? JSONObject)?.optInt("count") ?: 0
            val more = if (total > 0) total > off + CLASSIC_PAGE else m.size >= CLASSIC_PAGE
            m.values.toList() to more
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            emptyList<JSONObject>() to false
        }
    }

    private fun taggedQueries(names: List<String>): List<String> {
        val qs = LinkedHashSet<String>()
        for (tag in TAGS) for (n in names) qs.add("$n $tag".trim())
        return qs.filter { it.length >= 3 }.take(TAGGED_QUERIES)
    }

    // Aramanın tamamı: düz arama + kanallar + etiketli sorgular + Yandex; sonra etiket/ad süzgeci ve puan sıralaması
    private fun errorCard(msg: String): SearchResponse {
        lastDebug = "HATA: $msg"
        return newMovieSearchResponse("HATA (dokun): ${msg.take(120)}", "$mainUrl/video0_0", TvType.Movie)
    }

    private suspend fun safeSearch(q: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        return try {
            smartSearch(q, page)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            if (page > 1) return emptyList<SearchResponse>() to true     // kaydırırken hata: listeyi kesme, sonraki kaydırmada yeniden dene
            listOf(errorCard(e.message ?: e.toString())) to false
        }
    }

    private suspend fun smartSearch(q: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        // Aramaya doğrudan VK adresi yapıştırılırsa (vkvideo.ru/video248307654_168039297) o video açılır
        val direct = Regex("video(-?\\d+_\\d+(?:_[0-9a-zA-Z]+)?)").find(q)
        if (direct != null && q.contains("vk", true)) {
            if (page > 1) return emptyList<SearchResponse>() to false
            val o = fetchItem(direct.groupValues[1])
            val directPoster = workingPoster(o)
            val card = newMovieSearchResponse(
                o.optString("title"),
                "$mainUrl/video${videoKey(o)}",
                TvType.Movie
            ) {
                this.posterUrl = directPoster
            }
            return listOf<SearchResponse>(card) to false
        }
        // Yalın arama: yazılan metin VK'ya aynen gider, süzgeç yok; aşağı indikçe sayfa sayfa devam eder
        val sq = q.trim()
        if (page <= 1) {
            rawStart.keys.removeAll { it.startsWith("$sq|") }
            shownMap[sq] = java.util.Collections.synchronizedSet(HashSet<String>())
        }
        val shownKeys = shownMap.getOrPut(sq) { java.util.Collections.synchronizedSet(HashSet<String>()) }
        var r = if (page <= 1) 1 else (rawStart["$sq|$page"] ?: page)
        val out = ArrayList<SearchResponse>()
        var hasMore = true
        var loops = 0
        while (hasMore && out.size < PAGE_TARGET && loops < MAX_PAGE_LOOPS) {
            loops++
            val rr = r
            var cl: Pair<List<JSONObject>, Boolean> = emptyList<JSONObject>() to false
            var rawP = RawPage(LinkedHashMap(), false, "")
            // İki kaynak paralel: video.search (Tüm videolar, uzun filmler dahil) + katalog araması (klipler, kanallar)
            listOf(0, 1).amap { w ->
                if (w == 0) cl = vkClassicSearch(sq, rr) else rawP = searchRaw(sq, rr, true)
                0
            }
            r++
            hasMore = rawP.more || cl.second
            // Katalog sonucu (sitedeki sıra) önce, klasik arama sonra
            val all = (rawP.videos.values + cl.first).filter { shownKeys.add(baseOf(it)) }
            val regular = all.filter { !isClipItem(it) }
            // Klipler yalnızca ilk sayfada, ilk 4 videodan sonra gelir; sonraki sayfalarda sadece videolar
            val clips = if (page <= 1 && loops == 1) all.filter { isClipItem(it) }.take(CLIP_MAX) else emptyList()
            val ordered = if (clips.isEmpty()) regular
            else regular.take(4) + clips + regular.drop(4)
            out.addAll(toResponses(ordered))
        }
        rawStart["$sq|${page + 1}"] = r
        return out to hasMore
    }

    private suspend fun fetchItemRaw(key: String): JSONObject? {
        val r = try {
            api("video.getByIds", mapOf("videos" to key))
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            api("video.get", mapOf("videos" to key))
        }
        val found = LinkedHashMap<String, JSONObject>()
        collectVideos(r, found)
        return found.values.firstOrNull()
    }

    private suspend fun fetchItem(key: String): JSONObject {
        var err: Exception? = null
        // 1. deneme mevcut jetonla, 2. deneme taze jetonla (boş / hatalı yanıt jeton kaynaklı olabiliyor)
        for (attempt in 0..1) {
            if (attempt == 1) token = null
            try {
                fetchItemRaw(key)?.let { return it }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                err = e
            }
        }
        // Normal videolar yukarıda döner. Buraya yalnız bilgisi gelmeyen kayıtlar (kısa klipler) düşer.
        return try {
            fetchClipItem(key)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            throw (err ?: e)
        }
    }

    // Klip (kısa video) kayıtları video.getByIds'te boş dönebilir: başka metodlar denenir, olmazsa asgari kayıt üretilir
    private suspend fun fetchClipItem(key: String): JSONObject {
        val parts = key.split("_")
        val oid = parts.getOrNull(0) ?: ""
        val vid = parts.getOrNull(1) ?: ""
        if (oid.isBlank() || vid.isBlank()) throw ErrorLoadingException("Video bilgisi bulunamadı: $key")
        val tries = listOf(
            "video.getByIds" to mapOf("videos" to key, "extended" to "1", "adult" to "1"),
            "shortVideo.getByIds" to mapOf("videos" to key, "extended" to "1"),
            "shortVideo.get" to mapOf("owner_id" to oid, "video_id" to vid, "extended" to "1")
        )
        for ((m, p) in tries) {
            try {
                val found = LinkedHashMap<String, JSONObject>()
                collectVideos(api(m, p), found)
                val hit = found.values.firstOrNull { baseOf(it) == "${oid}_$vid" } ?: found.values.firstOrNull()
                if (hit != null) return hit
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }
        // Asgari kayıt: oynatma adımları (getForPlay, embed, WebView) bununla yine denenir
        return JSONObject()
            .put("owner_id", oid.toLongOrNull() ?: 0L)
            .put("id", vid.toLongOrNull() ?: 0L)
            .put("title", "Klip")
            .put("duration", 0)
            .put("type", "short_video")
            .put("synthetic", true)
            .also { o -> parts.getOrNull(2)?.let { o.put("access_key", it) } }
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
                val t = node.trim()
                if (t.startsWith("http")) out.add(hint to t)
                else if (t.startsWith("//") && t.length > 8) out.add(hint to ("https:" + t))
                else if ((t.startsWith("{") || t.startsWith("[")) && t.length > 20 && t.contains("http")) {
                    // JSON metin olarak gömülmüş olabilir: ayrıştırıp içini de tara
                    try {
                        scanUrls(if (t.startsWith("{")) JSONObject(t) else JSONArray(t), hint, out)
                    } catch (e: Exception) {
                    }
                }
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

    // Bağlantı adayı: hangi başlık takımının kabul edildiği (headers), ölü olup olmadığı (dead)
    // ve oynatıcının tanımadığı bir mp4 olup olmadığı (unsupported) test edilir
    private class Cand(val hint: String, val url: String) {
        var headers: Map<String, String>? = null
        var dead = false
        var unsupported = false
        var size = -1L
        var diag = ""
    }

    // Tür artık anahtar adına değil URL'ye göre belirlenir (dash_sep / dash_webm gibi anahtarlar düz mp4/webm döndürebilir)
    private fun isPlainFile(u: String) = Regex("(?i)\\.(mp4|webm|m4v|mov)(\\?|&|$)").containsMatchIn(u)
    private fun isHls(c: Cand) = c.url.contains(".m3u8") ||
        (c.hint.contains("hls", true) && !c.url.contains(".mpd") && !isPlainFile(c.url))
    private fun isDash(c: Cand) = c.url.contains(".mpd")
    // Kayıt (item) içindeki genel "url" anahtarı tam film değil, kısa önizleme / fragman (≈10 sn) dosyasıdır
    private fun isPreview(c: Cand) = c.hint.equals("url", true) && !isHls(c) && !isDash(c)
    private fun isWebm(c: Cand) = c.url.contains(".webm", true) || c.hint.contains("webm", true)

    // okcdn "?type=N" adreslerinde ct / ix / asubs parametreleri imzaya (sig) dahil değil. Oynatıcının (özellikle klipler)
    // istediği adres yalnızca başlık/ilk parçayı (birkaç KB) döndürür; ct=19 ve ix/asubs olmadan aynı adres tam mp4 verir.
    private fun normalizeOk(u: String): String {
        if (!u.contains("okcdn.ru/?") || !Regex("[?&]type=\\d").containsMatchIn(u)) return u
        var x = u.replace(Regex("&ix=\\d+"), "").replace(Regex("&asubs=[A-Za-z]+"), "")
        if (Regex("[?&]ct=\\d+").containsMatchIn(x)) x = x.replace(Regex("([?&])ct=\\d+"), "$1ct=19")
        return x
    }

    private fun addCand(list: MutableList<Cand>, seen: HashSet<String>, hint: String, raw: String): Boolean {
        // "&bytes=0-5355" gibi parça aralığı parametresi varsa kaldırılır (tam dosya istenir)
        val u = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
            .replace(Regex("&bytes=\\d+-\\d*"), "")
        val un = normalizeOk(u)
        var added = false
        if (un != u && isMedia(un) && seen.add(un)) {
            list.add(Cand(hint, un))
            added = true
        }
        if (!isMedia(u) || !seen.add(u)) return added
        list.add(Cand(hint, u))
        return true
    }

    // Sayfa yapısı/anahtar adı ne olursa olsun HTML içindeki medya adreslerini bulur
    private fun scanHtml(html: String, list: MutableList<Cand>, seen: HashSet<String>): Int {
        val t = html.replace("\\/", "/").replace("\\u0026", "&").replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\x2F", "/").replace("&quot;", "\"").replace("&amp;", "&")
        var n = 0
        // Yüzde kodlu adresler (https%3A%2F%2F...) çözülüp eklenir
        for (m in Regex("https?%3A%2F%2F[^\\s\"'<>\\\\]+", RegexOption.IGNORE_CASE).findAll(t)) {
            try {
                val d = java.net.URLDecoder.decode(m.value, "UTF-8")
                if (addCand(list, seen, "", d)) n++
            } catch (e: Exception) {
            }
        }
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
                val r = app.get(c.url, headers = h, timeout = 3L)
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

    // mp4 dosyasının ilk baytlarına bakar: oynatıcının (ExoPlayer) tanımadığı bir kapsayıcıysa c.unsupported = true.
    // Tarayıcı bu dosyaları oynatır ama CloudStream "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" verir.
    private suspend fun sniffMp4(c: Cand) {
        if (isHls(c) || isDash(c) || c.dead) return
        val hv = c.headers ?: hdrList(c.url).first()
        try {
            val h = hv.toMutableMap()
            h["Range"] = "bytes=0-95"
            val r = app.get(c.url, headers = h, timeout = 5L)
            val cr = r.headers["Content-Range"] ?: ""
            val total = Regex("/(\\d+)$").find(cr)?.groupValues?.get(1)?.toLongOrNull()
            val len = r.headers["Content-Length"]?.toLongOrNull() ?: -1L
            c.size = total ?: (if (r.code == 200) len else -1L)
            // Birkaç KB'lık "video" aslında yalnızca başlık / ilk parçadır: oynatıcı 3003 verir
            if (c.size in 1..100_000L) {
                c.unsupported = true
                c.diag += " kucuk=${c.size}"
                return
            }
            // Sunucu Range'i yok sayıp tüm dosyayı gönderirse gövde okunmaz (bellek / süre)
            if (!(r.code == 206 || (len in 1..4096))) return
            val bytes = r.body.bytes()
            if (bytes.size < 12) return
            val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1)
            val box = head.substring(4, 8)
            c.unsupported = when {
                box == "ftyp" -> KNOWN_BRANDS.none { head.contains(it) }
                box in OK_FIRST_BOX -> false
                else -> true
            }
            c.diag += " box=$box"
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
        }
    }

    private suspend fun emitCand(c: Cand, lang: String, callback: (ExtractorLink) -> Unit, allowUnknown: Boolean = false): Boolean {
        val ref = "$mainUrl/"
        val hdrs = c.headers ?: hdrList(c.url).first()
        val src = if (lang.isNotBlank()) "$name $lang" else name
        when {
            isHls(c) -> {
                try {
                    M3u8Helper.generateM3u8(src, c.url, ref, headers = hdrs).forEach(callback)
                    return true
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    callback(
                        newExtractorLink(src, "$src HLS", c.url, ExtractorLinkType.M3U8) {
                            this.referer = ref
                            this.headers = hdrs
                        }
                    )
                }
                return true
            }
            isDash(c) -> {
                callback(
                    newExtractorLink(src, "$src DASH", c.url, ExtractorLinkType.DASH) {
                        this.referer = ref
                        this.headers = hdrs
                    }
                )
                return true
            }
            else -> {
                val q = qualityOf(c.hint, c.url)
                val label = if (q != null) "${q}p" else c.hint.ifBlank { "MP4" }
                if (label.equals("MP4", ignoreCase = true) && !allowUnknown) return false   // kalitesi bilinmeyenler yalnızca başka kaynak yoksa gösterilir
                callback(
                    newExtractorLink(src, "$src $label", c.url) {
                        this.referer = ref
                        this.headers = hdrs
                        this.quality = q ?: Qualities.Unknown.value
                    }
                )
                return true
            }
        }
    }

    // ---------- CloudStream ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val raw = searchRaw(request.data, page, false)
            newHomePageResponse(request.name, toResponses(raw.videos.values), raw.more)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            newHomePageResponse(request.name, listOf(errorCard(e.message ?: e.toString())), false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, more) = safeSearch(query, page)
        return newSearchResponseList(items, more)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return safeSearch(query, 1).first
    }

    override suspend fun load(url: String): LoadResponse {
        val key = url.substringAfter("/video").substringBefore("?")
        if (key == "0_0") {
            return newMovieLoadResponse("DEBUG", url, TvType.Movie, "debug") {
                this.plot = lastDebug.ifBlank { "Henüz teşhis yok: önce bir arama yap, sonra DEBUG kartına dokun." }
            }
        }
        val item = fetchItem(key)
        val title = item.optString("title")
        val desc = item.optString("description")
        val durSec = item.optInt("duration")
        val vkPoster = workingPoster(item)

        // Başlık + yıl + süre uyuyorsa TMDB'deki gerçek film kaydından poster / özet / yıl al
        val tm = try {
            if (item.optBoolean("synthetic")) null else tmdbLookup(title, durSec)
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
        if (data == "debug") return false
        // Aynı anda tek istek; 20 sn içinde sıra gelmezse yine de devam eder
        val got = loadSem.tryAcquire(20, java.util.concurrent.TimeUnit.SECONDS)
        try {
            val c = synchronized(linkCache) { linkCache[data] }
            if (c != null && System.currentTimeMillis() - c.at < CACHE_MS) {
                var n = 0
                for (cand in c.list) if (emitCand(cand, c.lang, callback)) n++
                if (n == 0) for (cand in c.list) emitCand(cand, c.lang, callback, allowUnknown = true)
                return true
            }
            return loadLinksInner(data, callback)
        } finally {
            if (deviceId.isNotBlank()) { deviceId = ""; token = null }   // rastgele kimlikli jeton sonraki videolara kalmasın
            if (got) loadSem.release()
        }
    }

    private suspend fun loadLinksInner(
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var item = fetchItem(data)
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

        // 1) getByIds / get yanıtı içindeki linkler + 2) sitenin kendi oynatma metodları (paralel)
        suspend fun collectPrimary() {
            val urls1 = ArrayList<Pair<String, String>>()
            scanUrls(item, "", urls1)
            for ((k, u) in urls1) addCand(cands, seen, k, u)
            debug.add("item links=${cands.size}")
            android.util.Log.d(
                "VkVideo",
                "kayit anahtarlar=${item.keys().asSequence().joinToString(",").take(900)} link=${cands.size}"
            )

            val hashP = Regex("[?&]hash=([0-9a-zA-Z]+)").find(item.optString("player"))?.groupValues?.get(1)
                ?: item.optString("access_key")
            val playParams = mutableMapOf(
                "videos" to data,
                "video" to data,
                "owner_id" to oid,
                "oid" to oid,
                "video_id" to vid,
                "id" to vid
            ).also {
                if (hashP.isNotBlank()) {
                    it["hash"] = hashP
                    it["access_key"] = hashP
                }
            }
            // video.getEmbed: oynatıcı sayfasının (embed) akış bilgisini aldığı metod; DASH / HLS adresleri buradan gelebilir
            val playRes = listOf("video.getForPlay", "video.getPlayerConfig", "video.getEmbed").amap { m ->
                val list = ArrayList<Pair<String, String>>()
                try {
                    val r = api(m, playParams)
                    scanUrls(r, "", list)
                    debug.add("$m urls=${list.size} ${shape(r)} ${deepShape(r)}")
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    debug.add("$m: ${e.message?.take(90)}")
                }
                list
            }
            for (l in playRes) for ((k, u) in l) addCand(cands, seen, k, u)
        }

        collectPrimary()

        // 3) Yedek kaynak taraması: oynatıcı (embed) sayfası, farklı adresler, WebView.
        //    Hiç link yoksa VEYA bulunan linkler oynatıcının tanımadığı biçimdeyse (aşağıda) çalıştırılır.
        var player = item.optString("player")
        var fallbackDone = false
        // Toplam süre sınırı: sonsuz dönen yükleme ekranı yerine en geç ~50 sn'de sonuç (ya da "bağlantı yok") döner
        val deadline = System.currentTimeMillis() + 50_000L
        var allowWebView = true

        suspend fun runFallback() {
            if (fallbackDone) return
            fallbackDone = true
            val base = cands.size

            // 3a) oynatıcı (embed) sayfası — takılırsa 6 sn'de bırakılır
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

            // 3b) Farklı adresler / mobil sayfa (HTML'de oynatıcı verisi başka yerde olabilir)
            if (cands.size == base) {
                val hash = Regex("[?&]hash=([0-9a-zA-Z]+)").find(player)?.groupValues?.get(1)
                    ?: item.optString("access_key")
                val mobileHdr = hdr + mapOf("User-Agent" to MOBILE_UA)
                val isClip = item.optBoolean("synthetic") || item.optString("type") == "short_video"
                val alts = (if (isClip) listOf("$mainUrl/clip$data" to hdr, "$mainUrl/clip$data" to mobileHdr) else emptyList()) + listOf(
                    player to mobileHdr,
                    ("https://vk.com/video_ext.php?oid=$oid&id=$vid" + (if (hash.isNotBlank()) "&hash=$hash" else "")) to hdr,
                    ("https://m.vk.com/video${oid}_$vid" + (if (hash.isNotBlank()) "?access_key=$hash" else "")) to mobileHdr
                )
                for ((cu, ch) in alts) {
                    if (cands.size > base || System.currentTimeMillis() > deadline) break
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
            if (cands.size == base && allowWebView && System.currentTimeMillis() < deadline - 16_000L) {
                val clipWeb = if (item.optBoolean("synthetic") || item.optString("type") == "short_video") listOf("$mainUrl/clip$data") else emptyList()
                // Oynatıcı kullanıcı dokunuşu beklemesin: autoplay=1
                val webPlayer = player + (if (player.contains("?")) "&" else "?") + "autoplay=1"
                for (wu in listOf(webPlayer) + clipWeb) {
                    if (cands.size > base) break
                    try {
                        val r = app.get(
                            wu,
                            headers = hdr,
                            referer = "$mainUrl/",
                            allowRedirects = false,
                            interceptor = WebViewResolver(
                                Regex("""(?i)\.m3u8|\.mpd|(vkvd\d*|okcdn|mycdn)[^/\s]*/[^\s?]*\.mp4|okcdn\.ru/\?[^\s]*type=\d"""),
                                userAgent = null,
                                useOkhttp = false,
                                script = PLAY_JS,
                                timeout = 15_000L
                            ),
                            timeout = 25L
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
            android.util.Log.d("VkVideo", "yedek tarama: ${base} -> ${cands.size} aday")
        }

        // 4) Bağlantı testi: yeni adaylar için hangi başlık takımının çalıştığı bulunur, ölüler sona atılır,
        //    en iyi 2 mp4'ün kapsayıcısı oynatıcının tanıyacağı biçimde mi diye bakılır
        fun hasReal() = cands.any { !isPreview(it) && (isHls(it) || isDash(it) || qualityOf(it.hint, it.url) != null) }

        suspend fun evaluate() {
            if (hasReal()) {
                // Tam akış bulunduysa önizleme ve kalitesiz sahte adaylar elenir
                for (c in cands) if (isPreview(c) || (!isHls(c) && !isDash(c) && qualityOf(c.hint, c.url) == null)) c.dead = true
            }
            val fresh = cands.filter { it.diag.isEmpty() && !it.dead }
            val mp4s = fresh.filter { !isHls(it) && !isDash(it) }
                .sortedByDescending { qualityOf(it.hint, it.url) ?: 0 }
            val targets = mp4s.take(2) + fresh.filter { isHls(it) }.take(1)
            targets.amap { probe(it) }
            debug.add("probe " + targets.joinToString(" | ") { (if (isHls(it)) "hls:" else "mp4:") + it.diag })
            targets.filter { !isHls(it) && !isDash(it) && !it.dead }.take(2).amap { sniffMp4(it) }
            // Film uzun (>=60 sn) ama dosya çok küçükse (saniyede ~2.5 KB altı) bu kısa bir önizlemedir
            val durSec = item.optInt("duration")
            if (durSec >= 60) {
                for (c in targets) {
                    if (!isHls(c) && !isDash(c) && c.size > 0 && c.size * 8L / durSec < 20_000L) {
                        c.unsupported = true
                        c.diag += " onizleme"
                    }
                }
            }
            for (c in targets) {
                android.util.Log.d(
                    "VkVideo",
                    "aday hint='${c.hint}' ${c.url.take(90)} dead=${c.dead} unsupported=${c.unsupported} size=${c.size} diag=${c.diag}"
                )
            }
        }

        // Gerçek (HLS / DASH / kaliteli mp4) bağlantı var mı? "url" gibi kalitesiz tek aday sahte sayılır.

        // Hızlı yol: gerçek bağlantı yoksa (yalnızca sahte "url" adayı ya da hiç) doğrudan yedek taramaya geç; sahte adayı sınamak için vakit harcanmaz
        // Hiç bağlantı gelmediyse: jeton eskimiş / kısıtlanmış olabilir. Jeton yenilenir, kayıt yeniden çekilir, bir kez daha denenir.
        if (cands.isEmpty()) {
            token = null
            try {
                item = fetchItem(data)
                debug.add("jeton yenilendi, yeniden denendi")
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                debug.add("jeton yenileme: ${e.message?.take(80)}")
            }
            player = item.optString("player").ifBlank { player }
            collectPrimary()
        }

        if (!hasReal()) runFallback()

        // VK birkaç istekten sonra jetonu/IP'yi kısıp boş yanıt dönebiliyor: bekle, yeni kimlikle jeton al, yeniden dene
        var round = 0
        while (!hasReal() && round < 2 && System.currentTimeMillis() < deadline - 12_000L) {
            round++
            allowWebView = false          // yenileme turlarında 15 sn'lik WebView beklemesi yok
            Thread.sleep(1500L)
            token = null
            deviceId = randomDeviceId()
            try {
                item = fetchItem(data)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                debug.add("tur $round fetchItem: ${e.message?.take(60)}")
            }
            player = item.optString("player").ifBlank { player }
            collectPrimary()
            if (!hasReal()) {
                fallbackDone = false
                runFallback()
            }
            debug.add("tur $round -> aday=${cands.size} gercek=${hasReal()}")
        }

        if (cands.isNotEmpty()) {
            evaluate()
            // Bulunanların hiçbiri oynatılabilir değilse (ölü ya da tanınmayan mp4) yedek kaynakları da tara
            if (cands.none { !it.dead && !it.unsupported } && !fallbackDone) {
                val before = cands.size
                runFallback()
                if (cands.size > before) evaluate()
            }

            val noWebm = cands.filter { !isWebm(it) }
            val usable = noWebm.filter { !it.dead && !it.unsupported }
            // Oynatıcının tanımadığı (3003 hatası veren) kaynaklar ASLA verilmez
            val toEmit = if (usable.isNotEmpty()) usable else noWebm.filter { !it.dead && !it.unsupported }
            if (toEmit.isNotEmpty()) {
                var emitted = 0
                for (c in toEmit) if (emitCand(c, lang, callback)) emitted++
                // Hiçbiri etiketli çıkmadıysa kalitesi bilinmeyen düz mp4'ler de verilir
                if (emitted == 0) for (c in toEmit) emitCand(c, lang, callback, allowUnknown = true)
                synchronized(linkCache) { linkCache[data] = CachedLinks(System.currentTimeMillis(), lang, toEmit.toList()) }
                return true
            }

            // Son çare: test "ölü / tanınmıyor" dese bile gerçek görünümlü (HLS / DASH / kaliteli) adaylar yine de verilir.
            // Sınama (Range isteği, başlık okuma) yanılabilir; oynatıcı çoğu zaman yine de açar.
            val last = cands.filter { (isHls(it) || isDash(it) || qualityOf(it.hint, it.url) != null) && it.size !in 1..100_000L }
                .sortedWith(compareBy<Cand>({ isPreview(it) }, { -(qualityOf(it.hint, it.url) ?: 0) }))
                .take(4)
            if (last.isNotEmpty()) {
                var n = 0
                for (c in last) if (emitCand(c, lang, callback)) n++
                if (n == 0) for (c in last) emitCand(c, lang, callback, allowUnknown = true)
                return true
            }
        }

        // 5) Hiçbiri olmadıysa teşhis satırları göster (her satırın adresi benzersiz)
        debug.add("player=" + player.take(70))
        lastDebug = debug.joinToString("\n")
        android.util.Log.d("VkVideo", "BAGLANTI YOK: " + debug.joinToString(" | "))
        if (DEBUG_LINKS) {
            debug.forEachIndexed { i, d ->
                callback(
                    newExtractorLink(name, "DEBUG $d", "https://debug.invalid/$i") { }
                )
            }
            return true
        }
        return false
    }
}
