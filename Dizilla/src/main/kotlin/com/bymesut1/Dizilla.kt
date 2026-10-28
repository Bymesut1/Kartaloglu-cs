package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    private val cf = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/turkce-dublaj-dizi-izle" to "Türkçe Dublaj Diziler",
        "$mainUrl/yerli-dizi-izle" to "Yerli Diziler",
        "$mainUrl/anime-izle" to "Anime",
        "$mainUrl/kdrama-izle" to "Kdrama",
        "$mainUrl/asya-dizileri-izle" to "Asya Dizileri",
        "$mainUrl/imdb-top-100" to "IMDb Top 100"
    )

    private fun d(m: String) {
        Log.d("DizillaDbg", m)
    }

    // ---------------- Yardımcılar ----------------

    private suspend fun fetch(url: String, referer: String? = null): String {
        val h = mapOf("User-Agent" to ua, "Accept-Language" to "tr-TR,tr;q=0.9")
        return try {
            val t = app.get(url, headers = h, referer = referer).text
            if (t.contains("Just a moment", true) && t.length < 20_000) {
                app.get(url, headers = h, referer = referer, interceptor = cf).text
            } else t
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            d("fetch hata ${e.javaClass.simpleName} $url")
            try {
                app.get(url, headers = h, referer = referer, interceptor = cf).text
            } catch (e2: Exception) {
                if (e2 is java.util.concurrent.CancellationException) throw e2
                ""
            }
        }
    }

    private fun abs(u: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> "$mainUrl/$u"
    }

    private fun cleanImg(u: String?): String? =
        u?.substringBefore("#")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"").replace("&amp;", "&")

    // ---------------- Kartlar (ana sayfa / kategori / arama) ----------------

    private fun cardsOf(doc: Document): List<SearchResponse> {
        val best = LinkedHashMap<String, Triple<String, String?, Int>>()
        for (a in doc.select("a[href]")) {
            val raw = a.attr("href")
            val path = raw.substringBefore("?").substringBefore("#").removePrefix(mainUrl)
            if (!path.startsWith("/dizi/")) continue
            val slug = path.removePrefix("/dizi/").trim('/')
            if (slug.isBlank()) continue
            val title = a.attr("aria-label").substringBefore(",")
                .replace(Regex("""^\d+\.\s+"""), "").replace(Regex("""\.\s*Sayfasını aç$"""), "").trim()
                .ifBlank { a.text().trim() }
            if (title.isBlank()) continue
            val src = cleanImg(a.selectFirst("img")?.attr("src"))
            val score = if (src?.contains("384/576") == true) 2 else if (src != null) 1 else 0
            val old = best[slug]
            if (old == null || score > old.third) best[slug] = Triple(title, src, score)
        }
        return best.map { (slug, v) ->
            newTvSeriesSearchResponse(v.first, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                this.posterUrl = v.second
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Sayfalama adresi doğrulanana kadar yalnızca ilk sayfa alınır
        if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val doc = org.jsoup.Jsoup.parse(fetch(request.data), mainUrl)
        val list = cardsOf(doc)
        d("anasayfa ${request.name} kart=${list.size}")
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = list, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = org.jsoup.Jsoup.parse(fetch("$mainUrl/ara?q=$q"), mainUrl)
        val list = cardsOf(doc)
        d("arama '$query' sonuç=${list.size}")
        return list
    }

    // ---------------- Dizi sayfası ----------------

    private val epRe = Regex("""/([a-z0-9][a-z0-9-]*)-(\d+)-sezon-(\d+)-bolum(-\d+)?(?![a-z0-9-])""")

    private data class Ep(val base: String, val s: Int, val e: Int, val suffix: String)

    private fun episodesIn(html: String): List<Ep> =
        epRe.findAll(html).map {
            Ep(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4])
        }.toList()

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = abs(url).substringBefore("?")
        val raw = fetch(pageUrl)
        if (raw.isBlank()) return null
        val html = unescape(raw)
        val doc = org.jsoup.Jsoup.parse(raw, mainUrl)

        // Bölüm adresleri dizi adresinden farklı bir kök kullanabilir (örn. /dizi/dreamland-2026-43 -> /dreamland-1-sezon-1-bolum-2)
        var eps = episodesIn(html)
        val base = eps.groupingBy { it.base }.eachCount().maxByOrNull { it.value }?.key
            ?: Regex("""/([a-z0-9][a-z0-9-]*)-\d+-sezon(?![a-z0-9-])""").find(html)?.groupValues?.get(1)
        d("kök=$base ilk sayfada bölüm=${eps.size}")

        if (base != null) {
            val seasons = (
                Regex("""/${Regex.escape(base)}-(\d+)-sezon(?![a-z0-9-])""").findAll(html)
                    .map { it.groupValues[1].toInt() }.toList() + eps.filter { it.base == base }.map { it.s } + 1
                ).distinct().sorted().take(40)
            val all = ArrayList(eps.filter { it.base == base })
            for (sn in seasons) {
                val t = fetch("$mainUrl/$base-$sn-sezon")
                if (t.isNotBlank()) all += episodesIn(unescape(t)).filter { it.base == base }
            }
            eps = all
        }

        val seen = HashSet<Pair<Int, Int>>()
        val episodes = eps.filter { base == null || it.base == base }
            .sortedWith(compareBy({ it.s }, { it.e }, { it.suffix.length }))
            .filter { seen.add(it.s to it.e) }
            .map {
                newEpisode("$mainUrl/${it.base}-${it.s}-sezon-${it.e}-bolum${it.suffix}") {
                    this.season = it.s
                    this.episode = it.e
                    this.name = "${it.e}. Bölüm"
                }
            }
        d("toplam bölüm=${episodes.size}")

        fun meta(p: String) = doc.selectFirst("meta[property=$p]")?.attr("content")?.trim()
        val title = (meta("og:title") ?: doc.selectFirst("title")?.text() ?: "Dizi")
            .replace(Regex("""(?i)\s*[-|–]\s*Dizilla.*$"""), "")
            .replace(Regex("""(?i)\s+(izle|türkçe dublaj izle)$"""), "").trim()
        val poster = cleanImg(meta("og:image"))
        val plot = meta("og:description") ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val isAnime = Regex("""(?i)"origin"\s*:\s*"anime"""").containsMatchIn(html)

        return newTvSeriesLoadResponse(title, pageUrl, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------- Oynatma ----------------

    private val iframeRe = Regex("""https?://[A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9_%.=-]+""")
    private val trailerRe =
        Regex("""(https?://[A-Za-z0-9.-]+/iframe\.php\?v=[A-Za-z0-9_%.=-]+)"[^}]{0,300}?"trailer"\s*:\s*(true|false)""")
    private val m3u8Re = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")
    private val subRe = Regex("""https?://[^"'\s\\<>]+\.(?:vtt|srt)[^"'\s\\<>]*""")

    private fun originOf(u: String) = Regex("""^https?://[^/]+""").find(u)?.value ?: mainUrl

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = unescape(fetch(data, "$mainUrl/"))
        val trailers = trailerRe.findAll(html).filter { it.groupValues[2] == "true" }.map { it.groupValues[1] }.toSet()
        val frames = iframeRe.findAll(html).map { it.value }.distinct().filter { it !in trailers }.toList()
        d("iframe sayısı=${frames.size}")

        var ok = false
        for ((i, f) in frames.withIndex()) {
            val origin = originOf(f)
            val label = "Kaynak ${i + 1} (${origin.removePrefix("https://").removePrefix("http://")})"

            // 1) iframe sayfasında açık m3u8 varsa doğrudan al
            val ifr = unescape(fetch(f, "$mainUrl/"))
            for (m in subRe.findAll(ifr).map { it.value }.distinct()) {
                subtitleCallback(SubtitleFile("Altyazı", m))
            }
            var m3u8 = m3u8Re.find(ifr)?.value

            // 2) yoksa iframe'i WebView'da açıp ağdan master.m3u8 yakala
            if (m3u8 == null) {
                m3u8 = try {
                    val r = app.get(
                        f,
                        referer = "$mainUrl/",
                        interceptor = WebViewResolver(
                            Regex("""(?i)\.m3u8"""),
                            userAgent = null,
                            useOkhttp = false,
                            timeout = 25_000L
                        ),
                        timeout = 35L
                    )
                    r.url.takeIf { it != f && it.contains(".m3u8", true) }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    d("webview hata ${e.javaClass.simpleName}")
                    null
                }
            }
            d("$label m3u8=${m3u8?.take(80)}")
            if (m3u8 == null) continue

            callback(
                newExtractorLink(name, label, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin)
                }
            )
            ok = true
        }
        return ok
    }
}
