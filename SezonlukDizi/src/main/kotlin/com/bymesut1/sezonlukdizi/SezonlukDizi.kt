package com.bymesut1.sezonlukdizi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SezonlukDizi : MainAPI() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val ic = RegexOption.IGNORE_CASE
    private val slugRe = Regex("""/diziler/([a-z0-9\-]+)\.html""")

    private val turler = listOf(
        "mini" to "Mini Dizi",
        "animasyon" to "Animasyon",
        "aile" to "Aile",
        "aksiyon" to "Aksiyon",
        "belgesel" to "Belgesel",
        "bilimkurgu" to "Bilim Kurgu",
        "biyografik" to "Biyografi",
        "dram" to "Dram",
        "fantastik" to "Fantastik",
        "gerilim" to "Gerilim",
        "gizem" to "Gizem",
        "korku" to "Korku",
        "komedi" to "Komedi",
        "macera" to "Macera",
        "muzikal" to "Müzikal",
        "suc" to "Suç",
        "romantik" to "Romantik",
        "savas" to "Savaş",
        "tarihi" to "Tarihi",
        "western" to "Western"
    )

    override val mainPage = mainPageOf(
        *(
            listOf(
                "$mainUrl/" to "Ana Sayfa",
                "$mainUrl/diziler.asp" to "Tüm Diziler",
                "$mainUrl/trend" to "Trend",
                "$mainUrl/diziler.asp?kat=1" to "Yabancı Diziler",
                "$mainUrl/diziler.asp?kat=2" to "Yerli Diziler",
                "$mainUrl/diziler.asp?kat=3" to "Asya Dizileri",
                "$mainUrl/diziler.asp?kat=4" to "Animasyonlar",
                "$mainUrl/diziler.asp?kat=5" to "Animeler",
                "$mainUrl/diziler.asp?kat=6" to "Belgeseller"
            ) + turler.map { "$mainUrl/diziler.asp?tur=${it.first}" to it.second }
            ).toTypedArray()
    )

    // ---------------- Yardımcılar ----------------

    private fun absUrl(u: String): String {
        val x = u.trim().replace("&amp;", "&")
        return when {
            x.startsWith("//") -> "https:$x"
            x.startsWith("/") -> mainUrl + x
            else -> x
        }
    }

    private fun cleanTitle(t: String) =
        t.substringBefore(" - ").substringBefore(" | ")
            .replace(Regex("""\s+(dizi\s+)?izle\s*$""", ic), "")
            .replace(Regex("""\s+dizisi\s*$""", ic), "")
            .trim()

    private fun titleFromSlug(slug: String) =
        slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun Document.toCards(): List<SearchResponse> {
        val map = LinkedHashMap<String, Pair<String, String?>>()
        for (a in select("a[href]")) {
            val href: String = a.attr("href")
            if (!slugRe.containsMatchIn(href)) continue
            val url = fixUrl(href.substringBefore("#").substringBefore("?"))

            val altText: String = a.selectFirst("img")?.attr("alt") ?: ""
            val titleAttr: String = a.attr("title")
            val innerText: String = a.text()
            var t = ""
            for (c in listOf(altText, titleAttr, innerText)) {
                if (c.isNotBlank()) {
                    t = c.trim()
                    break
                }
            }

            var p: String? = null
            for (k in listOf("data-src", "data-original", "src")) {
                val v: String = a.selectFirst("img")?.attr(k) ?: ""
                if (v.isNotBlank() && !v.startsWith("data:")) {
                    p = v
                    break
                }
            }

            val old = map[url]
            val bestTitle = if (old != null && old.first.isNotBlank()) old.first else t
            val bestPoster = old?.second ?: p
            map[url] = Pair(bestTitle, bestPoster)
        }
        return map.map { (url, v) ->
            val slug = slugRe.find(url)?.groupValues?.get(1) ?: ""
            val title = cleanTitle(v.first).ifBlank { titleFromSlug(slug) }
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(v.second)
            }
        }
    }

    // ---------------- Ana sayfa / arama ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val doc = app.get(request.data, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/").document
        return newHomePageResponse(request.name, doc.toCards(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get(
            "$mainUrl/diziler.asp?adi=$q",
            headers = mapOf("User-Agent" to ua), referer = "$mainUrl/"
        ).document
        return doc.toCards()
    }

    // ---------------- Dizi sayfası ----------------

    private data class Ep(val url: String, val s: Int, val e: Int, val label: String?)

    private fun epName(raw: String): String? {
        var t = raw.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("""\d+\.\s*Sezon\s*\d+\.\s*Bölüm""", ic), " ")
        t = t.replace(Regex("""^\s*\d+\.?\s*(\d+[.,]\d+)?\s*"""), "")
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.isNotBlank() && t.length < 90) t else null
    }

    private fun epUrl(href: String, slug: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> mainUrl + href
        else -> "$mainUrl/$slug/$href"
    }

    private fun collectEps(raw: String, slug: String, map: MutableMap<String, Ep>) {
        val t = raw.replace("\\/", "/").replace("&amp;", "&")

        val reA = Regex(
            """<a[^>]+href=["']([^"']*?(\d+)-sezon-(\d+)-bolum\.html)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in reA.findAll(t)) {
            val url = epUrl(m.groupValues[1], slug)
            if (slug.isNotBlank() && !url.contains("/$slug/")) continue
            val s = m.groupValues[2].toInt()
            val e = m.groupValues[3].toInt()
            val key = "$s-$e"
            if (!map.containsKey(key)) map[key] = Ep(url, s, e, epName(m.groupValues[4]))
        }

        val reB = Regex("""["']([^"']*?(\d+)-sezon-(\d+)-bolum\.html)["']""", ic)
        for (m in reB.findAll(t)) {
            val url = epUrl(m.groupValues[1], slug)
            if (slug.isNotBlank() && !url.contains("/$slug/")) continue
            val s = m.groupValues[2].toInt()
            val e = m.groupValues[3].toInt()
            val key = "$s-$e"
            if (!map.containsKey(key)) map[key] = Ep(url, s, e, null)
        }
    }

    private fun findDid(html: String): String? =
        Regex("""\bdid\s*[=:]\s*["']?(\d+)""").find(html)?.groupValues?.get(1)

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/")
        val doc = res.document
        val slug = slugRe.find(url)?.groupValues?.get(1) ?: ""
        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
                ?: titleFromSlug(slug)
        ).ifBlank { titleFromSlug(slug) }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val epMap = LinkedHashMap<String, Ep>()
        collectEps(res.text, slug, epMap)

        var did: String? = findDid(res.text)
        if (did == null && slug.isNotBlank()) {
            val first = epMap.values.firstOrNull()?.url ?: "$mainUrl/$slug/1-sezon-1-bolum.html"
            val t = runCatching {
                app.get(first, headers = mapOf("User-Agent" to ua), referer = url).text
            }.getOrNull()
            if (t != null) did = findDid(t)
        }

        if (did != null) {
            for (sno in 1..40) {
                val before = epMap.size
                val t = runCatching {
                    app.post(
                        "$mainUrl/ajax/dataIzleBolumler.asp",
                        headers = mapOf(
                            "User-Agent" to ua,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to url
                        ),
                        data = mapOf("did" to did, "sno" to sno.toString())
                    ).text
                }.getOrNull() ?: ""
                collectEps(t, slug, epMap)
                if (sno > 1 && (t.isBlank() || epMap.size == before)) break
            }
        }

        val episodes = epMap.values
            .sortedWith(compareBy({ it.s }, { it.e }))
            .map { ep ->
                newEpisode(ep.url) {
                    this.season = ep.s
                    this.episode = ep.e
                    this.name = ep.label
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------- Bölüm Linkleri (Çalışan Orijinal Mantık) ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SZD", "data » $data")
        val document = app.get(data, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/").document
        val aspData = getAspData()
        val bid = document.selectFirst("div#dilsec")?.attr("data-id") ?: return false
        Log.d("SZD", "bid » $bid")

        val ajaxHeaders = mapOf(
            "User-Agent" to ua,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )

        // --- ALTYAZI KISMI (dil = 1) ---
        val altyaziResponse = app.post(
            "$mainUrl/ajax/dataAlternatif${aspData.alternatif}.asp",
            headers = ajaxHeaders,
            data = mapOf("bid" to bid, "dil" to "1")
        ).parsedSafe<Kaynak>()

        if (altyaziResponse?.status == "success" && altyaziResponse.data != null) {
            for (veri in altyaziResponse.data) {
                val veriId = veri.id ?: continue
                val veriBaslik = veri.baslik ?: "Altyazı"
                Log.d("SZD", "dil»1 | veri.baslik » $veriBaslik")

                val veriResponse = app.post(
                    "$mainUrl/ajax/dataEmbed${aspData.embed}.asp",
                    headers = ajaxHeaders,
                    data = mapOf("id" to "$veriId")
                ).document

                val iframeSrc = veriResponse.selectFirst("iframe")?.attr("src") ?: veriResponse.selectFirst("iframe")?.attr("data-src")
                val iframe = fixUrlNull(iframeSrc) ?: continue
                Log.d("SZD", "dil»1 | iframe » $iframe")

                loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
                    callback.invoke(
                        newExtractorLink(
                            source = "AltYazı - $veriBaslik",
                            name = "AltYazı - $veriBaslik",
                            url = link.url,
                            type = link.type
                        ) {
                            this.quality = link.quality
                            this.headers = link.headers
                            this.referer = link.referer
                        }
                    )
                }
            }
        }

        // --- DUBLAJ KISMI (dil = 0) ---
        val dublajResponse = app.post(
            "$mainUrl/ajax/dataAlternatif${aspData.alternatif}.asp",
            headers = ajaxHeaders,
            data = mapOf("bid" to bid
