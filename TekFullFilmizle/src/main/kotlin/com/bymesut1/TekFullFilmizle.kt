package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TekFullFilmizle : MainAPI() {
    override var mainUrl = "https://www.tekfullfilmizle6.com"
    override var name = "TekFullFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/en-cok-izlenenler" to "En Çok İzlenenler",
        "$mainUrl/imdb-top-250-filmleri" to "IMDb Top 250",
        "$mainUrl/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/animasyon-filmleri-izle" to "Animasyon",
        "$mainUrl/aile-filmleri" to "Aile",
        "$mainUrl/bilimkurgu-filmleri-izle" to "Bilim Kurgu",
        "$mainUrl/dram-filmleri" to "Dram",
        "$mainUrl/fantastik-film-izle" to "Fantastik",
        "$mainUrl/gerilim-filmleri" to "Gerilim",
        "$mainUrl/komedi-filmleri" to "Komedi",
        "$mainUrl/korku-filmleri" to "Korku",
        "$mainUrl/macera-filmleri-izle" to "Macera",
        "$mainUrl/romantik-filmler" to "Romantik",
        "$mainUrl/savas-filmleri" to "Savaş",
        "$mainUrl/suc-filmleri" to "Suç"
    )

    private val fallbackPass = "NaNeACxYoEBKS6WtFPLVvG52CQrmbmJsfdsD5Mh7Pn8TQ5A0db"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val tag = "TekFull"

    private fun log(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(tag, msg, e) else Log.d(tag, msg)
    }

    private val baseHeaders get() = mapOf("User-Agent" to userAgent)

    // ---------------------------------------------------------------- Ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val document = app.get(url, headers = baseHeaders).document
        val home = document.select("div.moviefilm").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("(?i)\\s*(full\\s+)?t[üu]rk[çc]e\\s+dublaj(\\s+hd)?\\s+izle"), "")
            .replace(Regex("(?i)\\s+izle\\s*$"), "")
            .replace("&#8217;", "’")
            .trim()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("div.movief a") ?: selectFirst("a") ?: return null
        val rawTitle = a.text().trim().ifBlank { a.attr("title").trim() }
        if (rawTitle.isBlank()) return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        )
        return newMovieSearchResponse(cleanTitle(rawTitle), href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------- Arama

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val document = app.get("$mainUrl/search/$q", headers = baseHeaders).document
        return document.select("div.moviefilm").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------- İçerik sayfası

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("div.filmcontent h1")?.ownText()
            ?: return null
        val title = cleanTitle(rawTitle)

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.filmaltiimg img")?.attr("src")
        )

        val plot = (document.selectFirst("div.konuozet")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content"))
            ?.replace(Regex("^.*?yorumlarınızı bekliyoruz\\.\\s*"), "")
            ?.trim()

        val infos = document.select("div.filmaltiaciklama p")
        fun info(key: String): String? = infos
            .firstOrNull { it.selectFirst("span")?.text()?.trim() == key }
            ?.text()?.substringAfter(":")?.trim()

        val year = Regex("(19|20)\\d{2}").find(info("Yapım") ?: "")?.value?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

        val tagList = document.select("div.filmaltiaciklama a[rel=category tag]").map { it.text().trim() }
        val actorList = document.select("div.filmaltiaciklama a[href*=/search/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .map { ActorData(Actor(it)) }

        val recommendationList = document.select("div.moviefilm").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tagList
            this.recommendations = recommendationList
            this.actors = actorList
        }
    }

    // ---------------------------------------------------------------- Linkler

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = data.substringBefore("#").substringBefore("?").trimEnd('/')
        val noHtml = clean.removeSuffix(".html")
        val slug = noHtml.substringAfterLast("/")
        log("loadLinks data=$data")

        // label -> aday sayfa adresleri (sırayla denenir)
        val candidates = linkedMapOf<String, MutableList<String>>()
        fun add(label: String, url: String) {
            candidates.getOrPut(label) { mutableListOf() }.let { if (url !in it) it.add(url) }
        }

        // 1) Ana sayfa (Platin)
        add("Platin", clean)

        // 2) Sayfadaki gerçek kaynak butonlarını bul (…/2, …/3 vb.)
        try {
            val doc = app.get(clean, referer = "$mainUrl/", headers = baseHeaders).document
            doc.select("a[href]").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                if (!href.contains(slug)) return@forEach
                val n = Regex("/(\\d)(?:\\.html)?/?$").find(href)?.groupValues?.get(1) ?: return@forEach
                val label = when (n) {
                    "1" -> "Platin"
                    "2" -> "Gold"
                    "3" -> "Silver"
                    else -> "Kaynak $n"
                }
                add(label, href)
            }
        } catch (e: Exception) {
            log("ana sayfa okunamadı: $clean", e)
        }

        // 3) Bulunamazsa tahmin et (.html olsun / olmasın)
        add("Gold", "$noHtml/2")
        add("Gold", "$clean/2")
        add("Gold", "$noHtml/2.html")

        log("adaylar: $candidates")

        var found = false
        for ((label, urls) in candidates) {
            for (pageUrl in urls) {
                reached = false
                val ok = try {
                    tryPage(label, pageUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    log("$label hata: $pageUrl", e)
                    false
                }
                if (ok) found = true
                if (ok || reached) break // sayfa çözüldüyse diğer tahminlere gerek yok
            }
        }
        log("loadLinks sonuç found=$found")
        return found
    }

    @Volatile private var reached = false

    private suspend fun tryPage(
        label: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(pageUrl, referer = "$mainUrl/", headers = baseHeaders)
        if (resp.code !in 200..299) {
            log("$label HTTP ${resp.code}: $pageUrl")
            return false
        }
        val html = resp.text
        val inner = decryptFromDocument(resp.document, html)
        log("$label decrypted=${inner != null} len=${html.length}: $pageUrl")
        if (inner != null) reached = true

        val sources = extractSources(inner ?: html)
        log("$label kaynaklar=$sources")

        var any = false
        for (src in sources) {
            try {
                if (handleSource(label, src, pageUrl, subtitleCallback, callback)) any = true
            } catch (e: Exception) {
                log("$label kaynak hatası: $src", e)
            }
        }
        return any
    }

    /** #encrypted-content alanını AES-CBC ile çözer. */
    private fun decryptFromDocument(document: Document, html: String): String? {
        val el = document.selectFirst("#encrypted-content") ?: return null
        val enc = el.attr("data-enc")
        val iv = el.attr("data-iv")
        if (enc.isBlank() || iv.isBlank()) return null

        val pagePass = Regex("""pass\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        val passes = listOfNotNull(pagePass, fallbackPass).distinct()
        for (p in passes) {
            decrypt(enc, iv, p)?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    private fun b64(s: String): ByteArray = try {
        Base64.decode(s, Base64.DEFAULT)
    } catch (_: Exception) {
        Base64.decode(s, Base64.URL_SAFE)
    }

    private fun decrypt(enc: String, iv: String, pass: String): String? {
        return try {
            val key = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(b64(iv)))
            String(cipher.doFinal(b64(enc)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private val ignoredHosts = listOf(
        "google", "facebook", "youtube", "doubleclick", "histats", "twitter", "instagram"
    )

    /** HTML'den iframe/video/kaynak linklerini toplar. */
    private fun extractSources(html: String): List<String> {
        val out = linkedSetOf<String>()
        val doc = Jsoup.parse(html)
        doc.select("iframe[src], iframe[data-src], video[src], source[src]").forEach {
            val s = it.attr("src").ifBlank { it.attr("data-src") }
            if (s.isNotBlank()) out.add(normalizeUrl(s))
        }
        if (out.isEmpty()) {
            Regex("""https?://[^\s"'<>\\]+""")
                .findAll(html.replace("\\/", "/"))
                .map { it.value }
                .filter { u -> u.contains("m3u8") || u.contains(".mp4") || u.contains("loadvid") || u.contains("embed") || u.contains("player") }
                .forEach { out.add(it) }
        }
        return out.filter { u -> ignoredHosts.none { u.contains(it) } }
    }

    private fun normalizeUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> u
    }

    private suspend fun handleSource(
        label: String,
        url: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            url.contains("loadvid.com") -> loadvid(label, url, subtitleCallback, callback, pageUrl)
            url.contains("myvideoplayer") -> myvideo(label, url, pageUrl, subtitleCallback, callback)
            else -> generic(label, url, pageUrl, subtitleCallback, callback)
        }
    }


    // ---- myvideoplayer.top (Platin/Gold/Silver artık hepsi bunu kullanıyor)
    private suspend fun myvideo(
        label: String,
        url: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = try { "https://" + URI(url).host } catch (_: Exception) { "https://www.myvideoplayer.top" }
        // Doğrudan Abyss iç sayfası (myvideoplayer.link) geldiyse
        if (url.contains("myvideoplayer.link")) {
            return abyss(label, url, "$mainUrl/", subtitleCallback, callback)
        }
        val resp = app.get(url, referer = "$mainUrl/", headers = baseHeaders)
        val html = resp.text
        log("myvideo code=${resp.code} len=${html.length} url=$url")

        // Teşhis: sayfanın script kısımlarını logla (sonraki adımda buna göre extractor yazılır)
        val doc = Jsoup.parse(html)
        doc.select("script").forEachIndexed { i, sc ->
            val body = sc.data().ifBlank { sc.attr("src") }.trim()
            if (body.isNotBlank()) log("myvideo script[$i]=" + body.take(1500))
        }
        log("myvideo head=" + html.take(600))

        // 2) Açık link / packed JS / base64 içinde ara
        var text = html.replace("\\/", "/")
        try { getAndUnpack(html).takeIf { it.isNotBlank() }?.let { text += "\n" + it } } catch (_: Exception) {}
        var decodedBlobs = ""
        Regex("""atob\(\s*["']([A-Za-z0-9+/=_-]{20,})["']\s*\)""").findAll(html).forEach { m ->
            try { decodedBlobs += "\n" + String(b64(m.groupValues[1]), Charsets.UTF_8) } catch (_: Exception) {}
        }
        decodedBlobs = decodedBlobs.replace("\\/", "/")
        text = (text + "\n" + decodedBlobs).replace("\\/", "/")

        // Sayfa aslında bir sarmalayıcı: atob içindeki JS bir iframe oluşturuyor.
        val frameUrls = Regex("""src=\\*["'](https?://[^"'\\\s]+)""")
            .findAll(decodedBlobs).map { it.groupValues[1] }.distinct().toList()
        log("myvideo iframe=$frameUrls")
        for (frame in frameUrls) {
            val ok = try {
                if (frame.contains("loadvid.com")) loadvid(label, frame, subtitleCallback, callback, url)
                else if (frame.contains("myvideoplayer.link")) abyss(label, frame, "$origin/", subtitleCallback, callback)
                else generic(label, frame, url, subtitleCallback, callback)
            } catch (e: Exception) {
                log("myvideo iframe hatası: $frame", e)
                false
            }
            if (ok) return true
        }

        findMedia(text)?.let { (link, type) ->
            addLink(label, link, "$origin/", type, callback, origin)
            return true
        }

        // 3) İçeride iframe varsa onu da dene
        for (frame in doc.select("iframe[src]").map { normalizeUrl(it.attr("src")) }) {
            if (frame != url && generic(label, frame, url, subtitleCallback, callback)) return true
        }
        return try { loadExtractor(url, pageUrl, subtitleCallback, callback) } catch (_: Exception) { false }
    }


    // =====================================================================
    // Abyss (myvideoplayer.link): iframe HTML'indeki `datas` (base64 JSON)
    //  -> media alanı AES-CTR (seed = user_id:slug:md5_id)
    //  -> mp4 kaynakları: https://{domain}/sora/{size}/{token}
    //     token = b64(b64(AES-CTR("/mp4/{md5_id}/{res_id}/{size}?v={slug}", seed=size)))
    // =====================================================================
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun asciiSeed(s: String) = s.toByteArray(Charsets.UTF_8)

    /** JS md5 kütüphanesi sayıyı toString yapıp karakteri sayı olarak okur: "345" -> [3,4,5]. */
    private fun digitSeed(s: String) = ByteArray(s.length) { i ->
        val c = s[i]
        (if (c in '0'..'9') c - '0' else 0).toByte()
    }

    private fun abyssCrypt(mode: Int, data: ByteArray, seed: ByteArray): ByteArray {
        val key = hex(MessageDigest.getInstance("MD5").digest(seed)).toByteArray(Charsets.UTF_8) // 32 bayt
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        return cipher.doFinal(data)
    }

    private fun abyssToken(path: String, seed: ByteArray): String {
        val enc = abyssCrypt(Cipher.ENCRYPT_MODE, path.toByteArray(Charsets.UTF_8), seed)
        val first = Base64.encodeToString(enc, Base64.NO_WRAP).replace("=", "")
        return Base64.encodeToString(first.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP).replace("=", "")
    }

    private suspend fun abyssReachable(u: String, referer: String): Boolean = try {
        val r = app.get(
            u,
            headers = mapOf("User-Agent" to userAgent, "Referer" to referer, "Range" to "bytes=0-1")
        )
        log("abyss test ${r.code}: $u")
        r.code in 200..299
    } catch (e: Exception) {
        log("abyss test hata: $u", e)
        false
    }

    private suspend fun abyss(
        label: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(url, referer = referer, headers = baseHeaders).text
        val datas = Regex("""datas\s*=\s*["']([A-Za-z0-9+/=_-]{40,})["']""").find(html)?.groupValues?.get(1)
        if (datas == null) {
            log("abyss: datas bulunamadı len=${html.length} url=$url")
            return false
        }
        val info = JSONObject(String(b64(datas), Charsets.ISO_8859_1))
        val userId = info.get("user_id").toString()
        val slug = info.getString("slug")
        val md5Id = info.get("md5_id").toString()
        log("abyss slug=$slug md5_id=$md5Id user_id=$userId")

        val mediaRaw = info.get("media")
        val media: JSONObject = if (mediaRaw is JSONObject) mediaRaw else {
            val plain = abyssCrypt(
                Cipher.DECRYPT_MODE,
                mediaRaw.toString().toByteArray(Charsets.ISO_8859_1),
                asciiSeed("$userId:$slug:$md5Id")
            )
            JSONObject(String(plain, Charsets.UTF_8))
        }

        // Altyazılar
        try {
            val subs = info.optJSONObject("config")?.optJSONArray("subtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val sb = subs.getJSONObject(i)
                    val lang = java.net.URLDecoder.decode(sb.optString("lang", "Altyazı"), "UTF-8")
                    subtitleCallback(
                        SubtitleFile(
                            lang,
                            "https://cdn.iamcdn.net/subtitle/$md5Id/${sb.getString("slug")}.${sb.getString("type")}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log("abyss altyazı hatası", e)
        }

        val mp4 = media.optJSONObject("mp4")
        if (mp4 == null) {
            log("abyss: mp4 yok (sadece hls?) keys=${media.keys().asSequence().toList()}")
            return false
        }
        return abyssMp4(label, mp4, md5Id, slug, callback)
    }

    private suspend fun abyssMp4(
        label: String,
        mp4: JSONObject,
        md5Id: String,
        slug: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = mp4.optJSONArray("sources") ?: JSONArray()
        val domains = mp4.optJSONArray("domains") ?: JSONArray()
        val innerReferer = "https://myvideoplayer.link/"
        val innerOrigin = "https://myvideoplayer.link"

        // etikete göre grupla (AV1 ve 1440p/2160p atlanır)
        val groups = linkedMapOf<String, MutableList<JSONObject>>()
        val all = (0 until sources.length()).map { sources.getJSONObject(it) }
            .sortedBy { it.optLong("size", 0L) }
        for (o in all) {
            if (o.optString("codec") == "av1") continue
            val q = o.optString("label")
            if (q == "1440p" || q == "2160p") continue
            groups.getOrPut(q) { mutableListOf() }.add(o)
        }
        log("abyss etiketler=${groups.keys} domains=$domains")

        var any = false
        for ((q, list) in groups) {
            val o = list.first()
            val quality = Regex("\\d+").find(q)?.value?.toIntOrNull() ?: Qualities.Unknown.value
            val sizeAny = o.opt("size")
            val sizeStr = sizeAny?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }

            if (sizeStr == null) {
                val f = o.optString("file")
                if (f.isNotBlank()) {
                    addLink("$label $q", f, innerReferer, ExtractorLinkType.VIDEO, callback, innerOrigin, quality)
                    any = true
                }
                continue
            }

            val sub = o.optString("sub")
            var host: String? = null
            for (i in 0 until domains.length()) {
                val d = domains.optString(i)
                if (d.isNotBlank() && d.contains(sub)) { host = d; break }
            }
            if (host == null) {
                log("abyss host yok sub=$sub")
                continue
            }

            val path = "/mp4/$md5Id/${o.get("res_id")}/$sizeStr?v=$slug"
            // JSON'da sayı ise JS tarafı digit-seed kullanır, string ise ASCII. Emin olmak için ikisini de dene.
            val seeds = if (sizeAny is Number) listOf(digitSeed(sizeStr), asciiSeed(sizeStr))
            else listOf(asciiSeed(sizeStr), digitSeed(sizeStr))
            val urls = seeds.map { "https://$host/sora/$sizeStr/${abyssToken(path, it)}" }

            var chosen: String? = null
            for (u in urls) {
                if (abyssReachable(u, innerReferer)) { chosen = u; break }
            }
            val finalUrl = chosen ?: urls.first()
            log("abyss $q -> $finalUrl (doğrulandı=${chosen != null})")
            addLink("$label $q", finalUrl, innerReferer, ExtractorLinkType.VIDEO, callback, innerOrigin, quality)
            any = true
        }
        return any
    }

    // ---- Platin: cdn.loadvid.com (POST /videos/resolve-token -> m3u8)
    private suspend fun loadvid(
        label: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        pageUrl: String
    ): Boolean {
        val origin = "https://cdn.loadvid.com"
        val pageResp = app.get(url, referer = "$mainUrl/", headers = baseHeaders)
        val html = pageResp.text
        val csrf = pageResp.document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        val hash = Regex("""videoHash:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: url.substringAfterLast("/")
        val token = Regex("""videoToken:\s*'([^']*)'""").find(html)?.groupValues?.get(1)
        val thumb = Regex("""thumbnailUrl:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
        val baseDir = thumb?.substringBeforeLast("/")?.plus("/")
        log("loadvid hash=$hash tokenLen=${token?.length} baseDir=$baseDir")

        // 1) Token çözme
        if (!token.isNullOrBlank()) {
            try {
                val body = JSONObject()
                    .put("token", token)
                    .put("hash", hash)
                    .toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())

                val resp = app.post(
                    "$origin/videos/resolve-token",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "X-CSRF-TOKEN" to csrf,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/vnd.apple.mpegurl,*/*",
                        "Origin" to origin
                    ),
                    referer = url,
                    cookies = pageResp.cookies,
                    requestBody = body
                )
                val text = resp.text
                log("resolve-token code=${resp.code} head=${text.take(60)}")
                if (text.contains("#EXTM3U")) {
                    val fixed = absolutize(text, baseDir)

                    if (fixed.contains("#EXT-X-STREAM-INF")) {
                        val variants = fixed.lines().map { it.trim() }.filter { it.startsWith("http") }
                        if (variants.isNotEmpty()) {
                            variants.forEach {
                                addLink(label, it, "$origin/", ExtractorLinkType.M3U8, callback, origin)
                            }
                            return true
                        }
                    }

                    // Cronet "data:" şemasını desteklemiyor (ERR_UNKNOWN_URL_SCHEME);
                    // playlist'i 127.0.0.1 üzerinde küçük bir sunucudan gerçek URL olarak veriyoruz.
                    val localUrl = try {
                        LocalPlaylistServer.serve(fixed)
                    } catch (e: Exception) {
                        log("yerel sunucu başlamadı", e)
                        null
                    }
                    log("playlist url=$localUrl")
                    val finalUrl = localUrl ?: ("data:application/vnd.apple.mpegurl;base64," +
                            Base64.encodeToString(fixed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                    addLink(label, finalUrl, "$origin/", ExtractorLinkType.M3U8, callback, origin)
                    return true
                }
            } catch (e: Exception) {
                log("resolve-token hatası", e)
            }
        }

        // 2) Yedek: sayfada doğrudan m3u8 / mp4
        findMedia(html.replace("\\/", "/"))?.let { (link, type) ->
            addLink(label, link, "$origin/", type, callback, origin)
            return true
        }

        // 3) Yedek: thumbnail klasöründen tahmin
        if (baseDir != null) {
            for (n in listOf("master.m3u8", "index.m3u8", "playlist.m3u8", "video.m3u8", "hls.m3u8")) {
                val cand = "$baseDir$n"
                val ok = try {
                    app.get(cand, referer = "$origin/", headers = baseHeaders).text.trimStart().startsWith("#EXTM3U")
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    addLink(label, cand, "$origin/", ExtractorLinkType.M3U8, callback, origin)
                    return true
                }
            }
        }

        // 4) Son çare: hazır extractor'lar
        return loadExtractor(url, pageUrl, subtitleCallback, callback)
    }

    /** m3u8 içindeki göreli adresleri mutlak adrese çevirir. */
    private fun absolutize(text: String, base: String?): String {
        if (base == null) return text
        fun abs(u: String): String {
            if (u.startsWith("http") || u.startsWith("data:")) return u
            return try {
                URI(base).resolve(u).toString()
            } catch (_: Exception) {
                u
            }
        }
        return text.lines().joinToString("\n") { line ->
            val l = line.trim()
            when {
                l.isEmpty() -> line
                l.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { m ->
                    "URI=\"" + abs(m.groupValues[1]) + "\""
                }
                else -> abs(l)
            }
        }
    }

    // ---- Gold ve diğerleri
    private suspend fun generic(
        label: String,
        url: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = "$mainUrl/"
        if (url.contains(".m3u8")) {
            addLink(label, url, ref, ExtractorLinkType.M3U8, callback)
            return true
        }
        if (url.contains(".mp4")) {
            addLink(label, url, ref, ExtractorLinkType.VIDEO, callback)
            return true
        }

        val text = try {
            app.get(url, referer = ref, headers = baseHeaders).text
        } catch (e: Exception) {
            log("generic get hatası: $url", e)
            ""
        }
        log("generic url=$url len=${text.length}")
        if (text.trimStart().startsWith("<")) {
            Jsoup.parse(text).select("script").forEachIndexed { i, sc ->
                val body = sc.data().ifBlank { sc.attr("src") }.trim()
                if (body.isNotBlank()) log("generic script[$i]=" + body.take(1800))
            }
            // JW Player tarzı: file: "https://..." / "file":"https://..."
            val unescaped = text.replace("\\/", "/")
            Regex("""["']?(?:file|src|source|hls|url)["']?\s*[:=]\s*["'](https?://[^"'\s]+)["']""")
                .findAll(unescaped).map { it.groupValues[1] }
                .firstOrNull { u -> ignoredHosts.none { u.contains(it) } && !u.endsWith(".js") && !u.endsWith(".css") && !u.contains("jwpcdn") && !u.endsWith(".png") && !u.endsWith(".jpg") }
                ?.let { u ->
                    log("generic file=$u")
                    val t = if (u.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    addLink(label, u, ref, t, callback)
                    return true
                }
        }
        if (text.trimStart().startsWith("#EXTM3U")) {
            addLink(label, url, ref, ExtractorLinkType.M3U8, callback)
            return true
        }
        findMedia(text.replace("\\/", "/"))?.let { (link, type) ->
            addLink(label, link, ref, type, callback)
            return true
        }
        return loadExtractor(url, pageUrl, subtitleCallback, callback)
    }

    private fun findMedia(text: String): Pair<String, ExtractorLinkType>? {
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(text)?.let {
            return it.value to ExtractorLinkType.M3U8
        }
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").find(text)?.let {
            return it.value to ExtractorLinkType.VIDEO
        }
        return null
    }

    private suspend fun addLink(
        label: String,
        url: String,
        referer: String,
        type: ExtractorLinkType,
        callback: (ExtractorLink) -> Unit,
        origin: String? = null,
        quality: Int = Qualities.Unknown.value
    ) {
        callback(
            newExtractorLink(
                source = "$name $label",
                name = "$name $label",
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = quality
                val h = mutableMapOf("User-Agent" to userAgent, "Referer" to referer)
                if (origin != null) h["Origin"] = origin
                this.headers = h
            }
        )
    }
}

/** Media playlist'i http://127.0.0.1:PORT/ID.m3u8 olarak sunan minik sunucu. */
private object LocalPlaylistServer {
    private val playlists = ConcurrentHashMap<String, ByteArray>()
    private var server: ServerSocket? = null

    @Synchronized
    fun serve(content: String): String {
        if (playlists.size > 30) playlists.clear()
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        playlists[id] = content.toByteArray(Charsets.UTF_8)
        val s = ensureStarted()
        return "http://127.0.0.1:${s.localPort}/$id.m3u8"
    }

    private fun ensureStarted(): ServerSocket {
        server?.let { if (!it.isClosed) return it }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true, name = "TekFullPlaylistServer") {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                } catch (_: Exception) {
                }
            }
        }
        return s
    }

    private fun handle(c: Socket) {
        try {
            c.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val first = reader.readLine() ?: return
                while (true) {
                    val l = reader.readLine()
                    if (l.isNullOrEmpty()) break
                }
                val parts = first.split(" ")
                val isHead = parts.getOrNull(0) == "HEAD"
                val id = parts.getOrNull(1)?.trim('/')?.removeSuffix(".m3u8")
                val body = id?.let { playlists[it] }
                val out = sock.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray())
                    if (!isHead) out.write(body)
                }
                out.flush()
            }
        } catch (_: Exception) {
        }
    }
}
