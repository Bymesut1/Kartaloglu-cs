package com.bymesut1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * fullhdfilmizlesene.now
 *
 * filmabisi2.js (Nuvio) kazıyıcısının CloudStream'e birebir aktarımı:
 *  - sitenin "scx" jetonları (rot13 -> base64) ve eski "loadSource" düğmeleri
 *  - RapidVid (av('..') çözücü, packed açıcı, brute çözücü, mobil/masaüstü UA denemesi)
 *  - Vidmoly (vidmoly.biz yedeği), Ok.ru (hls + mp4 kaliteleri), bilinmeyen kaynaklar (RapidVid yöntemi)
 * Hiçbir kaynak/akış elenmez; sıra sayfadaki sıradır.
 */
class FullHDFilmizlesene : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    override var name = "fullhdfilmizlesene"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val cf = CloudflareKiller()

    private val androidUa =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Tanı kayıtları logcat'e "FHDDbg" etiketiyle yazılır
    private fun d(m: String) {
        try { android.util.Log.d("FHDDbg", m.replace("\n", " ").take(900)) } catch (e: Throwable) { }
    }

    private data class Found(
        val url: String,
        val type: String,                 // "hls" | "mp4"
        val quality: String = "Auto",
        val headers: Map<String, String>? = null
    )

    private data class Src(val url: String, val label: String, val group: String)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler" to "Yeni Filmler",
        "$mainUrl/trend-filmler" to "Trend Filmler",
        "$mainUrl/populer-filmler" to "Popüler Filmler",
        "$mainUrl/en-cok-izlenen-filmler" to "En Çok İzlenenler",
        "$mainUrl/filmizle/turkce-dublaj-filmler-1" to "Türkçe Dublaj",
        "$mainUrl/filmizle/turkce-altyazili-filmler-1" to "Türkçe Altyazılı",
        "$mainUrl/filmizle/dual-filmler-1" to "Dual Filmler",
        "$mainUrl/filmizle/1080p-filmler-2" to "1080p Filmler",
        "$mainUrl/filmizle/4k-filmler" to "4K Filmler",
        "$mainUrl/filmizle/yerli-filmler" to "Yerli Filmler",
        "$mainUrl/yil/2026-filmleri-izle" to "2026 Filmleri",
        "$mainUrl/yil/2025-filmler-izle" to "2025 Filmleri",
        "$mainUrl/yil/2024-filmleri-izle-1" to "2024 Filmleri",
        "$mainUrl/filmizle/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/filmizle/komedi-filmleri" to "Komedi",
        "$mainUrl/filmizle/korku-filmleri" to "Korku",
        "$mainUrl/filmizle/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "$mainUrl/filmizle/dram-filmler-izle" to "Dram",
        "$mainUrl/filmizle/gerilim-filmleri" to "Gerilim",
        "$mainUrl/filmizle/animasyon-filmleri" to "Animasyon",
        "$mainUrl/filmizle/aile-filmleri" to "Aile",
        "$mainUrl/filmizle/macera-filmleri-izle-1" to "Macera",
        "$mainUrl/filmizle/fantastik-filmler" to "Fantastik",
        "$mainUrl/filmizle/romantik-filmler" to "Romantik",
        "$mainUrl/filmizle/savas-filmleri" to "Savaş",
        "$mainUrl/filmizle/suc-filmleri" to "Suç",
        "$mainUrl/filmizle/gizem-filmleri" to "Gizem",
        "$mainUrl/filmizle/belgesel-filmleri" to "Belgesel",
        "$mainUrl/filmizle/tarih-filmleri" to "Tarih",
        "$mainUrl/filmizle/western-filmler" to "Western"
    )

    // ============================================================
    //  Ağ yardımcıları
    // ============================================================

    private fun pageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to androidUa,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9",
        "Referer" to "$mainUrl/"
    )

    // JS getText karşılığı: başarısızsa (0/403/429/503) site adresleri masaüstü UA ile yeniden denenir.
    // Başarılıysa gövde, değilse "" döner.
    private suspend fun getText(url: String, headers: Map<String, String>? = null, timeout: Long = 12L): String {
        suspend fun once(h: Map<String, String>): Pair<Int, String> =
            try {
                val r = app.get(url, headers = h, timeout = timeout)
                r.code to r.text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                0 to ""
            }

        val h = headers ?: pageHeaders()
        val isSite = url.contains("fullhdfilmizlesene")
        var r = once(h)
        if (r.first !in 200..299 && isSite && r.first in listOf(0, 403, 429, 503)) {
            val h2 = h.toMutableMap()
            h2["User-Agent"] = desktopUa
            val r2 = once(h2)
            if (r2.first in 200..299) r = r2
        }
        if (r.first !in 200..299 && isSite) {
            // Cloudflare kontrolü varsa son çare
            try {
                val r3 = app.get(url, headers = h, interceptor = cf, timeout = 20L)
                if (r3.code in 200..299) r = r3.code to r3.text
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
            }
        }
        return if (r.first in 200..299) r.second else ""
    }

    // ============================================================
    //  Küçük çözücüler (JS ile birebir)
    // ============================================================

    private fun decodeHtml(s: String): String =
        s.replace("&quot;", "\"").replace(Regex("&#0?39;"), "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun originOf(u: String): String =
        Regex("""^https?://[^/]+""").find(u)?.value ?: ""

    private val b64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    // base64 (kendi uygulamamız: -/_ kabul eder, hatalı karakterleri atar, dolgu gerekmez)
    private fun b64ToBytes(input: String): IntArray {
        val s = input.replace('-', '+').replace('_', '/').replace(Regex("[^A-Za-z0-9+/]"), "")
        val out = ArrayList<Int>()
        var buf = 0
        var bits = 0
        for (ch in s) {
            buf = (buf shl 6) or b64Chars.indexOf(ch)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((buf shr bits) and 255)
                buf = buf and ((1 shl bits) - 1)
            }
        }
        return out.toIntArray()
    }

    private fun bytesToStr(b: IntArray): String {
        val sb = StringBuilder(b.size)
        for (x in b) sb.append((x and 255).toChar())
        return sb.toString()
    }

    private fun bytesToUtf8(b: IntArray): String =
        String(ByteArray(b.size) { b[it].toByte() }, Charsets.UTF_8)

    private fun rot13(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in 'A'..'Z' -> sb.append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                in 'a'..'z' -> sb.append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val hexRe = Regex("""\\x([0-9a-fA-F]{2})""")
    private val uniRe = Regex("""\\u([0-9a-fA-F]{4})""")

    private fun hexUnescape(s: String): String =
        s.replace(hexRe) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(uniRe) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\/", "/")

    // Sitenin scx jetonu: rot13 -> base64 -> URL
    private fun decodeToken(t: String): String {
        val u = bytesToStr(b64ToBytes(rot13(t)))
        return if (Regex("""^https?://""").containsMatchIn(u)) u else ""
    }

    // RapidVid av('...') çözücü: ters çevir -> b64 -> "K9L" anahtarıyla kaydır -> b64
    private fun decodeSecret(input: String): String {
        val rev = input.reversed()
        val bytes = b64ToBytes(rev)
        val key = "K9L"
        val out = IntArray(bytes.size)
        for (i in bytes.indices) {
            val off = (key[i % key.length].code % 5) + 1
            out[i] = (bytes[i] - off) and 255
        }
        val inner = bytesToStr(out)
        if (Regex("""https?://""").containsMatchIn(inner)) return inner
        return bytesToStr(b64ToBytes(inner))
    }

    // eval(function(p,a,c,k,e,d)...) açıcı
    private val packedRe =
        Regex("""eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""")
    private val wordRe = Regex("""\b\w+\b""")

    private fun unpackPacked(src: String): String {
        val m = packedRe.find(src) ?: return ""
        val p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val a = m.groupValues[2].toInt()
        val c0 = m.groupValues[3].toInt()
        val k = m.groupValues[4].split("|")
        if (a <= 0) return ""

        fun enc(n0: Int): String {
            val pre = if (n0 < a) "" else enc(n0 / a)
            val n = n0 % a
            val ch = if (n > 35) (n + 29).toChar().toString() else n.toString(36)
            return pre + ch
        }

        val dict = HashMap<String, String>()
        var i = c0
        while (i > 0) {
            i--
            val key = enc(i)
            val v = k.getOrNull(i)
            dict[key] = if (v.isNullOrEmpty()) key else v
        }
        return wordRe.replace(p) { dict[it.value] ?: it.value }
    }

    private fun unpackAll(text: String): List<String> {
        val list = ArrayList<String>()
        list.add(text)
        var cur = text
        for (i in 0 until 3) {
            val u = unpackPacked(cur)
            if (u.isEmpty()) break
            list.add(u)
            cur = u
        }
        return list
    }

    private val badExt = Regex("""\.(vtt|srt|jpg|jpeg|png|webp|gif|css|js|ico|svg)(\?|$)""", RegexOption.IGNORE_CASE)
    private val cdnHost = Regex(
        """^https?://[^/]*(?:\.shop|pictabox\.[a-z]+|cdnimgs?\d*\.[a-z]+|static\d+\.[a-z]+)(?:[/:?]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val m3u8Re = Regex("""\.m3u8""", RegexOption.IGNORE_CASE)
    private val masterRe = Regex("""master""", RegexOption.IGNORE_CASE)
    private val urlRe = Regex("""https?://[^\s\x22\x27<>\\]+""")
    private val fileMp4Re = Regex("""file\s*[\x22\x27]?\s*:\s*[\x22\x27]([^\x22\x27]+\.mp4[^\x22\x27]*)[\x22\x27]""")

    private fun findStreamUrl(text0: String): Found? {
        val text = hexUnescape(text0).replace("&amp;", "&")
        val urls = urlRe.findAll(text).map { it.value }.toList()
        for (u in urls) {
            if (m3u8Re.containsMatchIn(u) && !badExt.containsMatchIn(u)) {
                var best = u
                for (v in urls) {
                    if (m3u8Re.containsMatchIn(v) && masterRe.containsMatchIn(v)) { best = v; break }
                }
                return Found(best, "hls")
            }
        }
        for (u in urls) {
            if (cdnHost.containsMatchIn(u) && !badExt.containsMatchIn(u)) return Found(u, "hls")
        }
        val f = fileMp4Re.find(text)
        if (f != null) return Found(f.groupValues[1], "mp4")
        return null
    }

    // ---- RapidVid yedek çözücü: anahtar/format değişse de dener ----

    private val hasHttpRe = Regex("""https?://[A-Za-z0-9]""")
    private fun hasHttp(x: String) = hasHttpRe.containsMatchIn(x)
    private val b64OnlyRe = Regex("""^[A-Za-z0-9+/=_\-]+$""")

    private fun tryDecodeString(str: String): String {
        val variants = listOf(str, str.reversed(), rot13(str), rot13(str).reversed())
        for (v in variants) {
            val bytes = b64ToBytes(v)
            if (bytes.size < 12) continue
            val plain = bytesToStr(bytes)
            if (hasHttp(plain)) return plain
            val inner0 = bytesToStr(b64ToBytes(plain))
            if (hasHttp(inner0)) return inner0
            for (a in 0..5) for (b in 0..5) for (c in 0..5) {
                if (a == 0 && b == 0 && c == 0) continue
                val off = intArrayOf(a, b, c)
                val out = IntArray(bytes.size)
                for (i in bytes.indices) out[i] = (bytes[i] - off[i % 3]) and 255
                val s2 = bytesToStr(out)
                if (hasHttp(s2)) return s2
                if (b64OnlyRe.containsMatchIn(s2)) {
                    val s3 = bytesToStr(b64ToBytes(s2))
                    if (hasHttp(s3)) return s3
                }
            }
        }
        return ""
    }

    private val bruteCandRe = Regex("""[\x22\x27]([A-Za-z0-9+/=_\-]{30,6000})[\x22\x27]""")

    private fun bruteFindStream(texts: List<String>): Found? {
        val cands = ArrayList<String>()
        for (t in texts) {
            for (m in bruteCandRe.findAll(t)) {
                if (m.groupValues[1] !in cands) cands.add(m.groupValues[1])
            }
        }
        val top = cands.sortedByDescending { it.length }.take(8)
        for (cnd in top) {
            val dec = tryDecodeString(cnd)
            if (dec.isNotEmpty()) {
                val f = findStreamUrl(dec)
                if (f != null) return f
                val u = urlRe.find(dec)?.value
                if (u != null && !badExt.containsMatchIn(u)) {
                    return Found(u, if (Regex("""\.mp4""", RegexOption.IGNORE_CASE).containsMatchIn(u)) "mp4" else "hls")
                }
            }
        }
        return null
    }

    private val sourceTagRe =
        Regex("""<(?:source|video)[^>]*\ssrc=[\x22\x27](https?://[^\x22\x27]+)[\x22\x27]""", RegexOption.IGNORE_CASE)
    private val avRe = Regex("""av\(\s*[\x27\x22]([^\x27\x22]+)[\x27\x22]\s*\)""")
    private val fileRe = Regex("""\x22?file\x22?\s*:\s*\x22([^\x22]+)\x22""")
    private val mp4Re = Regex("""\.mp4""", RegexOption.IGNORE_CASE)
    private val mp4EndRe = Regex("""\.mp4(\?|$)""", RegexOption.IGNORE_CASE)
    private val httpStartRe = Regex("""^https?://""")
    private val httpOnlyRe = Regex("""^https?://\S+$""")

    private fun extractRapid(html: String): Found? {
        val texts = unpackAll(html)

        // Düz <video><source src="..."> / <video src="..."> (uzantısız link de olabilir)
        val sm = sourceTagRe.find(html)
        if (sm != null && !badExt.containsMatchIn(sm.groupValues[1])) {
            val u = decodeHtml(sm.groupValues[1])
            return Found(u, if (mp4EndRe.containsMatchIn(sm.groupValues[1])) "mp4" else "hls")
        }

        var found: Found? = null
        for (t in texts) {
            val av = avRe.find(t)
            if (av != null) {
                try {
                    val dd = decodeSecret(av.groupValues[1])
                    found = findStreamUrl(dd)
                        ?: if (httpOnlyRe.containsMatchIn(dd)) Found(dd, "hls") else null
                } catch (e: Exception) {
                }
            }
            if (found != null) break

            for (m in fileRe.findAll(t)) {
                val v = hexUnescape(m.groupValues[1])
                if (httpStartRe.containsMatchIn(v) && !badExt.containsMatchIn(v)) {
                    found = Found(v, if (mp4Re.containsMatchIn(v)) "mp4" else "hls")
                    break
                }
            }
            if (found != null) break

            found = findStreamUrl(t)
            if (found != null) break
        }
        if (found == null) {
            try { found = bruteFindStream(texts) } catch (e: Exception) { d("brute hata ${e.message}") }
        }
        return found
    }

    // ============================================================
    //  Kaynak çözücüler
    // ============================================================

    private suspend fun resolveRapid(embedUrl: String, pageUrl: String): Found? {
        val origin = originOf(embedUrl)
        suspend fun fetchEmbed(ua: String): String = getText(
            embedUrl,
            mapOf(
                "User-Agent" to ua,
                "Accept" to "text/html,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9",
                "Referer" to pageUrl
            )
        )

        var ua = androidUa
        val h1 = fetchEmbed(androidUa)
        var found = if (h1.isNotEmpty()) extractRapid(h1) else null
        if (found == null) {
            // Mobil UA ile bulunamadıysa masaüstü UA ile dene (site farklı sayfa sunabiliyor)
            val h2 = fetchEmbed(desktopUa)
            val f2 = if (h2.isNotEmpty()) extractRapid(h2) else null
            if (f2 != null) {
                found = f2
                ua = desktopUa
            }
        }
        if (found == null) return null
        return found.copy(headers = mapOf("User-Agent" to ua, "Referer" to "$origin/"))
    }

    private suspend fun resolveVidmoly(embedUrl: String): Found? {
        val clean = embedUrl.substringBefore("?")
        val tries = ArrayList<String>()
        tries.add(clean)
        val biz = clean.replace(Regex("""^https?://[^/]+"""), "https://vidmoly.biz")
        if (biz != clean) tries.add(biz)

        for (t in tries) {
            val html = getText(
                t,
                mapOf(
                    "User-Agent" to androidUa,
                    "Accept" to "text/html,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9",
                    "Referer" to "$mainUrl/"
                )
            )
            if (html.isEmpty()) continue
            val texts = unpackAll(html)
            var found: Found? = null
            for (tx in texts) {
                found = findStreamUrl(tx)
                if (found != null) break
            }
            if (found != null) {
                val ref = if (Regex("""vidmoly\.""").containsMatchIn(t)) "https://vidmoly.biz/" else originOf(t) + "/"
                return found.copy(headers = mapOf("User-Agent" to androidUa, "Referer" to ref))
            }
        }
        return null
    }

    private suspend fun resolveOk(embedUrl: String): Found? {
        val html = getText(
            embedUrl.substringBefore("?"),
            mapOf("User-Agent" to androidUa, "Referer" to "$mainUrl/")
        )
        if (html.isEmpty()) return null
        val m = Regex("""data-options=\x22([^\x22]+)\x22""").find(html) ?: return null
        try {
            val opts = JSONObject(decodeHtml(m.groupValues[1]))
            val fv = opts.optJSONObject("flashvars") ?: return null
            val metaAny = fv.opt("metadata")
            val meta: JSONObject = when (metaAny) {
                is JSONObject -> metaAny
                is String -> JSONObject(metaAny)
                else -> return null
            }
            val hls = listOf("hlsManifestUrl", "ondemandHls", "hlsMasterPlaylistUrl")
                .map { meta.optString(it, "") }.firstOrNull { it.isNotBlank() }
            val hdr = mapOf("User-Agent" to androidUa, "Referer" to "https://ok.ru/")
            if (hls != null) return Found(hls, "hls", "Auto", hdr)

            val order = listOf("full", "hd", "sd", "low", "lowest", "mobile")
            val q = mapOf(
                "full" to "1080p", "hd" to "720p", "sd" to "480p",
                "low" to "360p", "lowest" to "240p", "mobile" to "144p"
            )
            val vids = meta.optJSONArray("videos")
            if (vids != null) {
                for (o in order) {
                    for (j in 0 until vids.length()) {
                        val v = vids.optJSONObject(j) ?: continue
                        val url = v.optString("url", "")
                        if (v.optString("name", "") == o && url.isNotBlank()) {
                            return Found(url, "mp4", q[o] ?: "Auto", hdr)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
        }
        return null
    }

    // vidmoxy (Fastly) ve bilinmeyen kaynaklar: RapidVid ile aynı çıkarma + UA yeniden denemesi
    private suspend fun resolveGeneric(embedUrl: String, pageUrl: String): Found? =
        resolveRapid(embedUrl, pageUrl)

    private suspend fun resolveSource(url: String, pageUrl: String): Found? {
        if (Regex("""rapidvid|rapid""", RegexOption.IGNORE_CASE).containsMatchIn(originOf(url))) {
            return resolveRapid(url, pageUrl)
        }
        if (Regex("""ok\.ru""").containsMatchIn(url)) return resolveOk(url)
        if (Regex("""vidmoly""").containsMatchIn(url)) return resolveVidmoly(url)
        return resolveGeneric(url, pageUrl)
    }

    // ============================================================
    //  Film sayfasından kaynak listesi
    // ============================================================

    private fun extractScxSources(html: String): List<Src> {
        val list = ArrayList<Src>()
        val m = Regex("""var\s+scx\s*=\s*(\{[\s\S]*?\})\s*;""").find(html) ?: return list
        val scx = try { JSONObject(m.groupValues[1]) } catch (e: Exception) { return list }
        val keys = ArrayList<String>()
        val iter = scx.keys()
        while (iter.hasNext()) keys.add(iter.next())
        // JS nesne sırası: tam sayı anahtarlar artan sırada önce, sonra diğerleri ekleme sırasıyla
        val intKeys = keys.filter { it.matches(Regex("""\d+""")) }.sortedBy { it.toLongOrNull() ?: 0L }
        val ordered = intKeys + keys.filter { it !in intKeys }
        for (key in ordered) {
            val item = scx.optJSONObject(key) ?: continue
            var nm = key
            val tt = item.optString("tt", "")
            if (tt.isNotEmpty()) {
                try {
                    val s = bytesToUtf8(b64ToBytes(tt))
                    if (s.isNotBlank()) nm = s
                } catch (e: Exception) {
                }
            }
            val sx = item.optJSONObject("sx") ?: continue
            for (g in listOf("t", "p")) {
                val arr = sx.optJSONArray(g) ?: continue
                for (i in 0 until arr.length()) {
                    val u = decodeToken(arr.optString(i, ""))
                    if (u.isNotEmpty()) list.add(Src(u, nm, g))
                }
            }
        }
        return list
    }

    private fun extractLegacySources(html: String): List<Src> {
        val list = ArrayList<Src>()
        val re = Regex("""loadSource\('([^']+)'\s*,\s*this\)[^>]*>([\s\S]*?)</button>""")
        for (m in re.findAll(html)) {
            val url = decodeHtml(m.groupValues[1])
            val label = decodeHtml(m.groupValues[2].replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()
            if (Regex("""youtube\.com|youtu\.be""").containsMatchIn(url) ||
                Regex("""fragman""", RegexOption.IGNORE_CASE).containsMatchIn(label)
            ) continue
            list.add(Src(url, label, "t"))
        }
        return list
    }

    private fun labelFor(s: Src): String =
        if (Regex("""rapid""", RegexOption.IGNORE_CASE).containsMatchIn(s.url)) {
            "RapidVid | ${s.label.lowercase()}"
        } else s.label

    private fun qualityOf(q: String): Int {
        if (q.equals("Auto", true)) return Qualities.Unknown.value
        return Regex("""\d+""").find(q)?.value?.toIntOrNull() ?: Qualities.Unknown.value
    }

    private suspend fun makeLink(label: String, f: Found): ExtractorLink {
        val hdr = f.headers ?: mapOf("User-Agent" to androidUa)
        val ref = hdr["Referer"] ?: "$mainUrl/"
        val q = qualityOf(f.quality)
        return newExtractorLink(
            name,
            label,
            f.url,
            if (f.type == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = ref
            this.quality = q
            this.headers = hdr
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = getText(data)
        if (html.isEmpty()) {
            d("sayfa alınamadı: $data")
            return false
        }
        var found = extractScxSources(html)
        if (found.isEmpty()) found = extractLegacySources(html)
        val sources = found
        d("kaynak sayısı=${sources.size} $data")
        if (sources.isEmpty()) return false

        val n = sources.size
        val results = arrayOfNulls<Found>(n)
        val done = BooleanArray(n)
        val lock = Any()
        var next = 0
        val seen = HashSet<String>()
        val labelCount = HashMap<String, Int>()
        var emitted = 0

        // Hepsi aynı anda çözülür; sonuçlar sayfadaki sırayla, hazır oldukça verilir
        sources.withIndex().toList().amap { (i, s) ->
            val r = try {
                resolveSource(s.url, data)
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                d("çözüm istisna ${s.label}: ${e.javaClass.simpleName}")
                null
            }
            d("${s.group} ${s.label} ${s.url.take(70)} -> ${r?.url?.take(80)}")
            val ready = ArrayList<Pair<String, Found>>()
            synchronized(lock) {
                results[i] = r
                done[i] = true
                while (next < n && done[next]) {
                    val fr = results[next]
                    if (fr != null && seen.add(fr.url)) {
                        val base = labelFor(sources[next])
                        val c = (labelCount[base] ?: 0) + 1
                        labelCount[base] = c
                        ready.add((if (c == 1) base else "$base #$c") to fr)
                    }
                    next++
                }
            }
            for ((lab, fr) in ready) {
                callback(makeLink(lab, fr))
                synchronized(lock) { emitted++ }
            }
        }
        return emitted > 0
    }

    // ============================================================
    //  Ana sayfa, arama, film sayfası
    // ============================================================

    private fun cardPoster(el: Element): String? {
        val img = if (el.tagName() == "img") el else el.selectFirst("img")
        val ds = img?.attr("data-src")?.trim()
        if (!ds.isNullOrBlank()) return fixUrl(ds)
        val s = img?.attr("src")?.trim()
        if (!s.isNullOrBlank() && s.startsWith("http")) return s
        val ss = el.selectFirst("source")?.attr("srcset")?.trim()?.substringBefore(" ")
        if (!ss.isNullOrBlank()) return fixUrl(ss)
        return null
    }

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a.tt") ?: el.selectFirst("a[href*=/film/]") ?: return null
        val href = a.attr("href").trim()
        if (!href.contains("/film/")) return null
        val url = fixUrl(href)
        val title = el.selectFirst(".film-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: a.text().replace(Regex("""(?i)\s+izle\s*$"""), "").trim()
        if (title.isBlank()) return null
        val poster = cardPoster(el)
        val year = el.selectFirst(".film-yil")?.text()?.trim()?.toIntOrNull()
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
            if (year != null) this.year = year
        }
    }

    private fun cards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (el in doc.select("li.film")) {
            val c = parseCard(el) ?: continue
            if (!out.containsKey(c.url)) out[c.url] = c
        }
        return out.values.toList()
    }

    // Sayfalama çubuğundaki "2" bağlantısından sayfa adresi kalıbı öğrenilir
    private val pageTpl = ConcurrentHashMap<String, String>()

    private fun learnTemplate(base: String, doc: Document) {
        val a2 = doc.select(".sayfalama a[href]").firstOrNull { it.text().trim() == "2" } ?: return
        val href = fixUrl(a2.attr("href"))
        if (Regex("""2/?$""").containsMatchIn(href)) {
            pageTpl[base] = href.replace(Regex("""2(?=/?$)"""), "{n}")
        }
    }

    private fun pageUrl(base: String, page: Int): String =
        pageTpl[base]?.replace("{n}", page.toString()) ?: "${base.trimEnd('/')}/$page"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = when {
            page <= 1 -> if (base.endsWith("/yeni-filmler")) "$mainUrl/" else base
            else -> pageUrl(base, page)
        }
        val html = getText(url)
        val doc = Jsoup.parse(html, url)
        if (page <= 1) learnTemplate(base, doc)
        val list = cards(doc)
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = list.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.length < 2) return newSearchResponseList(emptyList(), false)
        val base = "$mainUrl/arama/${URLEncoder.encode(q, "UTF-8")}"
        val url = if (page <= 1) base else pageUrl(base, page)
        val html = getText(url)
        val doc = Jsoup.parse(html, url)
        if (page <= 1) learnTemplate(base, doc)
        val list = cards(doc)
        return newSearchResponseList(list, list.isNotEmpty() && pageTpl.containsKey(base))
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = getText(url)
        if (html.isEmpty()) return null
        val doc = Jsoup.parse(html, url)

        val ldName = Regex("""\x22@type\x22\s*:\s*\x22Movie\x22[\s\S]*?\x22name\x22\s*:\s*\x22([^\x22]+)""")
            .find(html)?.groupValues?.get(1)
        val title = listOf(
            doc.selectFirst("h1")?.text()?.replace(Regex("""(?i)\s+izle\s*$"""), "")?.trim(),
            ldName?.let { decodeHtml(it).trim() },
            doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace(Regex("""(?i)\s+izle.*$"""), "")?.trim()
        ).firstOrNull { !it.isNullOrBlank() } ?: url.trimEnd('/').substringAfterLast('/')

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: Regex("""\x22image\x22\s*:\s*\x22(https?:[^\x22]+)""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: doc.selectFirst("img.afis, img.mafis, .poster img")?.let { cardPoster(it) }

        val plot = doc.selectFirst(".ozet-ic")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("[itemprop=description]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() && !it.contains("FullHDFilmizlesene", true) }

        val year = Regex("""<title>[^<]*\((\d{4})\)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""/yil/(\d{4})-""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        val actors = doc.select("a[href*=/oyuncu/]").map { it.text().trim() }
            .filter { it.length in 2..40 }.distinct().take(20).map { ActorData(actor = Actor(it)) }

        val yt = Regex("""youtube\.com/embed/([\w-]{11})""").find(html)?.groupValues?.get(1)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            if (year != null) this.year = year
            if (actors.isNotEmpty()) this.actors = actors
            if (!yt.isNullOrBlank()) addTrailer("https://www.youtube.com/watch?v=$yt")
        }
    }
}
