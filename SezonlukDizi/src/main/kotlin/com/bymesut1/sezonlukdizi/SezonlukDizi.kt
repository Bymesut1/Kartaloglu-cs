package com.bymesut1.sezonlukdizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
    private val baseHeaders = mapOf("User-Agent" to ua)
    private val ic = RegexOption.IGNORE_CASE
    private val slugRe = Regex("""/diziler/([a-z0-9\-]+)\.html""")

    // true: link çıkmazsa kaynak listesinde "DEBUG ..." satırları gösterir
    private val debugMode = true
    private val dbg = ArrayList<String>()

    private fun note(s: String) {
        synchronized(dbg) {
            if (dbg.size < 30) dbg.add(s.replace(Regex("\\s+"), " ").take(110))
        }
    }

    private val turler = listOf(
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
                "$mainUrl/diziler.asp?siralama_tipi=id&s=" to "Son Eklenenler",
                "$mainUrl/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
                "$mainUrl/diziler.asp?siralama_tipi=id&kat=2&s=" to "Yerli Diziler",
                "$mainUrl/diziler.asp?siralama_tipi=id&kat=1&s=" to "Yabancı Diziler",
                "$mainUrl/diziler.asp?siralama_tipi=id&kat=3&s=" to "Asya Dizileri",
                "$mainUrl/diziler.asp?siralama_tipi=id&kat=4&s=" to "Animasyonlar",
                "$mainUrl/diziler.asp?siralama_tipi=id&kat=5&s=" to "Animeler",
                "$mainUrl/diziler.asp?siralama_tipi=id&kat=6&s=" to "Belgeseller"
            ) + turler.map { "$mainUrl/diziler.asp?siralama_tipi=id&tur=${it.first}&s=" to it.second }
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

    private fun hostOf(u: String) =
        Regex("""^https?://([^/?#]+)""", ic).find(u)?.groupValues?.get(1)?.lowercase() ?: ""

    private fun qualityOf(u: String): Int =
        Regex("(2160|1440|1080|720|480|360)").find(u)?.value?.toIntOrNull() ?: Qualities.Unknown.value

    private fun cleanTitle(t: String) =
        t.substringBefore(" - ").substringBefore(" | ")
            .replace(Regex("""\s+(dizi\s+)?izle\s*$""", ic), "")
            .replace(Regex("""\s+dizisi\s*$""", ic), "")
            .trim()

    private fun titleFromSlug(slug: String) =
        slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun findM3u8(text: String): String? {
        val t = text.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        return Regex("""["'](https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*)["']""", ic).find(t)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*)""", ic).find(t)?.groupValues?.get(1)
    }

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
        "Referer" to referer
    )

    // ---------------- Ana sayfa / arama ----------------

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.description")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun cards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (a in doc.select("div.afis a")) {
            val r = a.toSearchResult() ?: continue
            if (seen.add(r.url)) out.add(r)
        }
        if (out.isNotEmpty()) return out

        // yedek: /diziler/xxx.html linklerini tara
        for (a in doc.select("a[href]")) {
            val href: String = a.attr("href")
            val m = slugRe.find(href) ?: continue
            val url = fixUrl(href.substringBefore("#").substringBefore("?"))
            if (!seen.add(url)) continue
            val altText: String = a.selectFirst("img")?.attr("alt") ?: ""
            val innerText: String = a.text()
            val t = if (altText.isNotBlank()) altText else innerText
            val title = cleanTitle(t).ifBlank { titleFromSlug(m.groupValues[1]) }
            val poster = fixUrlNull(a.selectFirst("img")?.attr("data-src"))
            out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page", headers = baseHeaders, referer = "$mainUrl/").document
        val home = cards(document)
        return newHomePageResponse(request.name, home, home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/diziler.asp?adi=$q", headers = baseHeaders, referer = "$mainUrl/").document
        return cards(document)
    }

    // ---------------- Dizi sayfası ----------------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders, referer = "$mainUrl/").document
        val slug = slugRe.find(url)?.groupValues?.get(1) ?: ""

        val headerText: String = document.selectFirst("div.header")?.text()?.trim() ?: ""
        val ogTitle: String = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val title = when {
            headerText.isNotBlank() -> headerText
            ogTitle.isNotBlank() -> cleanTitle(ogTitle)
            slug.isNotBlank() -> titleFromSlug(slug)
            else -> return null
        }

        val poster = fixUrlNull(document.selectFirst("div.image img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = ArrayList<String>()
        for (a in document.select("div.labels a[href*='tur']")) {
            val t: String = a.text().trim()
            if (t.isNotBlank()) tags.add(t)
        }

        val endpoint = url.substringBefore("?").trimEnd('/').substringAfterLast("/")

        val actors = ArrayList<Actor>()
        runCatching {
            val actorsReq = app.get("$mainUrl/oyuncular/$endpoint", headers = baseHeaders, referer = url).document
            for (el in actorsReq.select("div.doubling div.ui")) {
                val n: String = el.selectFirst("div.header")?.text()?.trim() ?: continue
                actors.add(Actor(n, fixUrlNull(el.selectFirst("img")?.attr("src"))))
            }
        }

        // Bölümler: /bolumler/<dizi> sayfasındaki tablo
        val episodes = ArrayList<Episode>()
        val episodesResp = runCatching {
            app.get("$mainUrl/bolumler/$endpoint", headers = baseHeaders, referer = url)
        }.getOrNull()
        if (episodesResp != null) {
            for (sezon in episodesResp.document.select("table.unstackable")) {
                for (bolum in sezon.select("tbody tr")) {
                    val a = bolum.selectFirst("td:nth-of-type(4) a") ?: continue
                    val epName: String = a.text().trim()
                    val epHref = fixUrlNull(a.attr("href")) ?: continue
                    val epEpisode = bolum.selectFirst("td:nth-of-type(3)")?.text()
                        ?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                    val epSeason = bolum.selectFirst("td:nth-of-type(2)")?.text()
                        ?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                    episodes.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epEpisode
                    })
                }
            }
        }

        // Yedek: sayfalardaki "N-sezon-M-bolum.html" linklerini tara
        if (episodes.isEmpty()) {
            val blob = ((episodesResp?.text ?: "") + document.html()).replace("\\/", "/")
            val seen = HashSet<String>()
            val found = ArrayList<Triple<String, Int, Int>>()
            for (m in Regex("""["']([^"']*?(\d+)-sezon-(\d+)-bolum\.html)["']""", ic).findAll(blob)) {
                val full = fixUrl(m.groupValues[1])
                if (slug.isNotBlank() && !full.contains("/$slug/")) continue
                val s = m.groupValues[2].toInt()
                val e = m.groupValues[3].toInt()
                if (seen.add("$s-$e")) found.add(Triple(full, s, e))
            }
            for (f in found.sortedWith(compareBy({ it.second }, { it.third }))) {
                episodes.add(newEpisode(f.first) {
                    this.season = f.second
                    this.episode = f.third
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    // ---------------- Bölüm linkleri ----------------

    // sitenin /js/site.min.js dosyasından dataAlternatif<N>.asp ve dataEmbed<N>.asp numaralarını al
    private suspend fun getAspNumbers(): Pair<String?, String?> {
        val js = runCatching {
            app.get("$mainUrl/js/site.min.js", headers = baseHeaders, referer = "$mainUrl/").text
        }.getOrDefault("")
        val a = Regex("""dataAlternatif([^"'\s+()]*?)\.asp""").find(js)?.groupValues?.get(1)
        val e = Regex("""dataEmbed([^"'\s+()]*?)\.asp""").find(js)?.groupValues?.get(1)
        note("asp alt=$a emb=$e jsLen=${js.length}")
        return a to e
    }

    private fun numCandidates(first: String?): List<String> =
        (listOfNotNull(first) + listOf("3", "4", "5", "2", "6", "7", "8", "1", "9", "")).distinct()

    private fun parseAlts(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in Regex("""\{[^{}]*\}""").findAll(text)) {
            val o = m.value
            val id = Regex("""["']?id["']?\s*:\s*["']?(\d+)""").find(o)?.groupValues?.get(1)
            val ad = Regex("""["']?(?:baslik|name|isim)["']?\s*:\s*["']([^"']*)["']""").find(o)
                ?.groupValues?.get(1) ?: ""
            if (id != null) out.add(id to ad)
        }
        return out.distinctBy { it.first }
    }

    private suspend fun fetchAlts(
        bid: String,
        dil: String,
        nums: List<String>,
        page: String
    ): Pair<String, List<Pair<String, String>>>? {
        for (n in nums) {
            val r = runCatching {
                app.post(
                    "$mainUrl/ajax/dataAlternatif$n.asp",
                    headers = ajaxHeaders(page),
                    referer = page,
                    data = mapOf("bid" to bid, "dil" to dil)
                )
            }.getOrNull() ?: continue
            if (r.code == 404) continue
            val t = r.text
            val alts = parseAlts(t)
            if (alts.isNotEmpty() || Regex("""["']status["']""").containsMatchIn(t)) {
                note("alt dil=$dil no=$n kayit=${alts.size}")
                return n to alts
            }
        }
        note("alt dil=$dil: adres bulunamadi")
        return null
    }

    private suspend fun fetchIframe(id: String, nums: List<String>, page: String): Pair<String, String>? {
        for (n in nums) {
            val r = runCatching {
                app.post(
                    "$mainUrl/ajax/dataEmbed$n.asp",
                    headers = ajaxHeaders(page),
                    referer = page,
                    data = mapOf("id" to id)
                )
            }.getOrNull() ?: continue
            if (r.code == 404) continue
            val t = r.text.replace("\\/", "/").replace("\\\"", "\"")
            val src = Regex("""<iframe[^>]+?(?:data-src|src)=["']([^"']+)["']""", ic)
                .find(t)?.groupValues?.get(1) ?: continue
            return n to absUrl(src)
        }
        return null
    }

    private suspend fun mk(url: String, label: String, type: ExtractorLinkType, referer: String): ExtractorLink =
        newExtractorLink(label, label, url, type) {
            this.referer = referer
            this.quality = qualityOf(url)
            this.headers = mapOf("User-Agent" to ua)
        }

    // CloudStream'in hazır çıkarıcısı (VidMoly, Pixeldrain, Filemoon...) çözemezse buraya düşer
    private suspend fun fallbackLinks(u: String, page: String, label: String): List<ExtractorLink> {
        if (Regex("""\.m3u8(\?|$)""", ic).containsMatchIn(u)) return listOf(mk(u, label, ExtractorLinkType.M3U8, page))
        if (Regex("""\.(mp4|mkv|webm)(\?|$)""", ic).containsMatchIn(u)) return listOf(mk(u, label, ExtractorLinkType.VIDEO, page))

        Regex("""pixeldrain\.[a-z]+/(?:u|api/file)/([A-Za-z0-9]+)""", ic).find(u)?.let {
            return listOf(
                mk("https://pixeldrain.com/api/file/${it.groupValues[1]}", label, ExtractorLinkType.VIDEO, "https://pixeldrain.com/")
            )
        }

        val text = runCatching {
            app.get(u, headers = baseHeaders, referer = "$mainUrl/").text
        }.getOrNull() ?: return emptyList()

        for (h in listOf(text, runCatching { getAndUnpack(text) }.getOrDefault(""))) {
            val m3 = findM3u8(h)
            if (m3 != null) return listOf(mk(m3, label, ExtractorLinkType.M3U8, "https://${hostOf(u)}/"))
            val t = h.replace("\\/", "/").replace("&amp;", "&")
            val mp = Regex("""["'](https?:[^"'\s<>\\]+\.(?:mp4|mkv|webm)[^"'\s<>\\]*)["']""", ic)
                .find(t)?.groupValues?.get(1)
            if (mp != null) return listOf(mk(mp, label, ExtractorLinkType.VIDEO, "https://${hostOf(u)}/"))
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        synchronized(dbg) { dbg.clear() }

        val document = app.get(data, headers = baseHeaders, referer = "$mainUrl/").document
        val bid: String = document.selectFirst("div#dilsec")?.attr("data-id") ?: ""
        note("bid=$bid")
        if (bid.isBlank()) {
            if (debugMode) {
                callback(newExtractorLink(name, "DEBUG: bid yok", "https://debug.invalid/", ExtractorLinkType.VIDEO))
                return true
            }
            return false
        }

        val asp = getAspNumbers()
        var altNums = numCandidates(asp.first)
        var embNums = numCandidates(asp.second)

        var ok = false
        for ((dil, label) in listOf("1" to "AltYazı", "0" to "Dublaj")) {
            val res = fetchAlts(bid, dil, altNums, data) ?: continue
            altNums = listOf(res.first)

            for (veri in res.second) {
                val tag = if (veri.second.isBlank()) label else "$label - ${veri.second}"

                val fr = fetchIframe(veri.first, embNums, data)
                if (fr == null) {
                    note("iframe yok id=${veri.first}")
                    continue
                }
                embNums = listOf(fr.first)
                val iframe = fr.second
                note("iframe ${hostOf(iframe)}")

                val got = mutableListOf<ExtractorLink>()
                runCatching { loadExtractor(iframe, "$mainUrl/", subtitleCallback) { got.add(it) } }

                if (got.isNotEmpty()) {
                    for (l in got) {
                        callback(
                            newExtractorLink(tag, tag, l.url, l.type) {
                                this.referer = l.referer
                                this.quality = l.quality
                                this.headers = l.headers
                            }
                        )
                        ok = true
                    }
                } else {
                    val fb = fallbackLinks(iframe, data, tag)
                    note("fallback ${hostOf(iframe)} -> ${fb.size}")
                    for (l in fb) {
                        callback(l)
                        ok = true
                    }
                }
            }
        }

        if (!ok && debugMode) {
            val items = synchronized(dbg) { dbg.take(18).toList() }
            items.forEachIndexed { i, s ->
                callback(newExtractorLink(name, "DEBUG ${i + 1}: $s", "https://debug.invalid/", ExtractorLinkType.VIDEO))
            }
            return items.isNotEmpty()
        }
        return ok
    }
}
