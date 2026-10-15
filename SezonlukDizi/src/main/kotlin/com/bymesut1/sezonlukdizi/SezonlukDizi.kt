package com.bymesut1.sezonlukdizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

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

    // true: link çıkmazsa kaynak listesinde "DEBUG ..." satırları gösterir
    private val debugMode = true
    private val dbg = ArrayList<String>()

    private fun note(s: String) {
        synchronized(dbg) {
            if (dbg.size < 60) dbg.add(s.replace(Regex("\\s+"), " ").take(110))
        }
    }

    private fun snip(s: String, n: Int = 50) = s.replace(Regex("\\s+"), " ").take(n)

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

        // Dizi id'sini (did) bul: önce dizi sayfasında, yoksa bir bölüm sayfasında
        var did: String? = findDid(res.text)
        if (did == null && slug.isNotBlank()) {
            val first = epMap.values.firstOrNull()?.url ?: "$mainUrl/$slug/1-sezon-1-bolum.html"
            val t = runCatching {
                app.get(first, headers = mapOf("User-Agent" to ua), referer = url).text
            }.getOrNull()
            if (t != null) did = findDid(t)
        }

        // Tüm sezonların bölümlerini sitenin kendi ajax adresinden çek
        if (did != null) {
            for (sno in 1..40) {
                val before = epMap.size
                val t = runCatching {
                    app.post(
                        "$mainUrl/ajax/dataIzleBolumler.asp",
                        headers = ajaxHeaders(url),
                        referer = url,
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

    // ---------------- Bölüm linkleri ----------------

    private data class AltRes(val path: String?, val alts: List<Pair<String, String>>)

    private fun ajaxPathsIn(text: String): List<String> =
        Regex("""(/?ajax/[A-Za-z0-9_\-]+(?:\.[A-Za-z]{2,5})?)""", ic)
            .findAll(text).map {
                var p = it.groupValues[1]
                if (!p.startsWith("/")) p = "/$p"
                p
            }.distinct().toList()

    // Sayfadaki ve sitenin .js dosyalarındaki ajax adreslerini bul
    private suspend fun discoverAjax(html: String): List<String> {
        var found = ajaxPathsIn(html)
        val myHost = hostOf(mainUrl)
        val srcs = Regex("""<script[^>]+src=["']([^"']+)["']""", ic).findAll(html)
            .map { absUrl(it.groupValues[1]) }
            .filter { hostOf(it) == myHost && Regex("""\.js(\?|$)""", ic).containsMatchIn(it) }
            .distinct().take(6).toList()
        note("js dosyasi ${srcs.size}")
        for (s in srcs) {
            val t = runCatching {
                app.get(s, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/").text
            }.getOrNull()
            if (t == null) {
                note("js alinamadi ${snip(s.substringAfterLast('/'), 30)}")
                continue
            }
            val p = ajaxPathsIn(t)
            note("js ${snip(s.substringAfterLast('/'), 22)} len=${t.length} ajax=${p.size}")
            found = found + p
        }
        return found.distinct()
    }

    private fun withExt(p: String): List<String> =
        if (Regex("""\.[A-Za-z]{2,5}$""").containsMatchIn(p)) listOf(p) else listOf(p, "$p.asp")

    private fun isEmbedPath(p: String) = p.contains("embed", true)
    private fun isOtherPath(p: String) = p.contains("bolumler", true) || p.contains("izlendi", true)

    private fun altCandidates(found: List<String>): List<String> {
        val named = found.filter { it.contains("alternatif", true) }.flatMap { withExt(it) }
        val others = found.filter { !isEmbedPath(it) && !isOtherPath(it) && !it.contains("alternatif", true) }
            .flatMap { withExt(it) }
        val guess = listOf("3", "4", "5", "2", "6", "7", "8", "1", "9", "").map { "/ajax/dataAlternatif$it.asp" }
        return (named + guess + others).distinct()
    }

    private fun embedCandidates(altPath: String?, found: List<String>): List<String> {
        val named = found.filter { isEmbedPath(it) }.flatMap { withExt(it) }
        val derived = if (altPath != null) listOf(altPath.replace(Regex("alternatif", ic), "Embed")) else emptyList()
        val others = found.filter { !isEmbedPath(it) && !isOtherPath(it) && it != altPath && !it.contains("alternatif", true) }
            .flatMap { withExt(it) }
        return (named + derived + listOf("/ajax/dataEmbed.asp") + others).distinct()
    }

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl
    ) + mapOf("Referer" to referer)

    private fun parseAlts(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        Regex("""\{[^{}]*\}""").findAll(text).forEach { m ->
            val o = m.value
            val id = Regex("""["']?id["']?\s*:\s*["']?(\d+)""").find(o)?.groupValues?.get(1)
            val ad = Regex("""["']?(?:baslik|name|isim)["']?\s*:\s*["']([^"']*)["']""").find(o)
                ?.groupValues?.get(1) ?: ""
            if (id != null) out.add(id to ad)
        }
        return out.distinctBy { it.first }
    }

    private suspend fun probeAlt(bid: String, dil: Int, page: String, cands: List<String>): AltRes {
        var okPath: String? = null
        var n404 = 0
        for (path in cands) {
            val r = runCatching {
                app.post(
                    mainUrl + path,
                    headers = ajaxHeaders(page),
                    referer = page,
                    data = mapOf("bid" to bid, "dil" to dil.toString())
                )
            }.getOrNull()
            if (r == null) {
                note("alt$dil ${path.removePrefix("/ajax/")} istek hatasi")
                continue
            }
            if (r.code == 404) {
                n404++
                continue
            }
            if (okPath == null) okPath = path
            val alts = parseAlts(r.text)
            note("alt$dil ${path.removePrefix("/ajax/")} ${r.code} n=${alts.size} ${snip(r.text, 40)}")
            if (alts.isNotEmpty()) return AltRes(path, alts)
        }
        if (n404 > 0) note("alt$dil 404 x$n404")
        return AltRes(okPath, emptyList())
    }

    private fun iframesOf(text: String): List<String> {
        val t = text.replace("\\/", "/").replace("\\\"", "\"")
        val out = Regex("""<iframe[^>]+?(?:data-src|src)=["']([^"']+)["']""", ic)
            .findAll(t).map { absUrl(it.groupValues[1]) }.toMutableList()
        if (out.isEmpty()) {
            Regex("""https?://[^"'\s<>]+""").find(t)?.let { out.add(absUrl(it.value)) }
        }
        return out
    }

    private fun isCaptcha(u: String) = Regex("recaptcha|captcha", ic).containsMatchIn(u)

    private suspend fun embedFetch(id: String, altPath: String?, page: String, found: List<String>): String? {
        var cap: String? = null
        for (path in embedCandidates(altPath, found)) {
            val r = runCatching {
                app.post(
                    mainUrl + path,
                    headers = ajaxHeaders(page),
                    referer = page,
                    data = mapOf("id" to id)
                )
            }.getOrNull() ?: continue
            if (r.code == 404) continue
            val frames = iframesOf(r.text)
            note("emb ${path.removePrefix("/ajax/")} ${r.code} fr=${frames.size} ${snip(frames.firstOrNull() ?: r.text, 45)}")
            frames.firstOrNull { !isCaptcha(it) }?.let { return it }
            if (cap == null) cap = frames.firstOrNull { isCaptcha(it) }
        }
        return cap
    }

    private suspend fun mk(url: String, label: String, type: ExtractorLinkType, referer: String): ExtractorLink =
        newExtractorLink(name, "$name • $label", url, type) {
            this.referer = referer
            this.quality = qualityOf(url)
            this.headers = mapOf("User-Agent" to ua)
        }

    private suspend fun relabel(l: ExtractorLink, label: String): ExtractorLink =
        newExtractorLink(l.source, "${l.name} • $label", l.url, l.type) {
            this.referer = l.referer
            this.quality = l.quality
            this.headers = l.headers
        }

    private suspend fun resolveEmbed(
        embed: String,
        page: String,
        label: String,
        subs: (SubtitleFile) -> Unit,
        depth: Int = 0
    ): List<ExtractorLink> {
        val u = absUrl(embed)
        note("res ${hostOf(u)} d$depth")

        if (Regex("""\.m3u8(\?|$)""", ic).containsMatchIn(u)) return listOf(mk(u, label, ExtractorLinkType.M3U8, page))
        if (Regex("""\.(mp4|mkv|webm)(\?|$)""", ic).containsMatchIn(u)) return listOf(mk(u, label, ExtractorLinkType.VIDEO, page))

        Regex("""pixeldrain\.[a-z]+/(?:u|api/file)/([A-Za-z0-9]+)""", ic).find(u)?.let {
            return listOf(
                mk("https://pixeldrain.com/api/file/${it.groupValues[1]}", label, ExtractorLinkType.VIDEO, "https://pixeldrain.com/")
            )
        }

        // İnsan doğrulama sayfası: içinden bilinen barındırıcı linki ara
        if (isCaptcha(u)) {
            val txt = runCatching {
                app.get(u, headers = mapOf("User-Agent" to ua), referer = page).text
            }.getOrNull() ?: return emptyList()
            val cand = Regex("""https?://[^"'\s<>\\)]+""").findAll(txt.replace("\\/", "/"))
                .map { it.value }
                .firstOrNull { Regex("pixeldrain|vidmoly|filemoon|bysejikuar|bysezoxexe|bysekoze", ic).containsMatchIn(hostOf(it)) }
            if (cand == null) note("captcha: hosterlink yok")
            return if (cand != null && depth < 3) resolveEmbed(cand, page, label, subs, depth + 1) else emptyList()
        }

        // Vidmoly: kendi çözücümüz (sayfadan m3u8'i çıkarır)
        if (Regex("vidmoly", ic).containsMatchIn(hostOf(u))) {
            val txt = runCatching {
                app.get(u, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/").text
            }.getOrNull()
            if (txt != null) {
                val m3 = findM3u8(txt) ?: runCatching { findM3u8(getAndUnpack(txt)) }.getOrNull()
                if (m3 != null) return listOf(mk(m3, label, ExtractorLinkType.M3U8, "https://${hostOf(u)}/"))
                note("vidmoly: m3u8 yok ${snip(txt, 40)}")
            } else {
                note("vidmoly: sayfa alinamadi")
            }
        }

        // CloudStream'in hazır çıkarıcıları (VidMoly, Filemoon vb.)
        val got = mutableListOf<ExtractorLink>()
        runCatching { loadExtractor(u, page, subs) { got.add(it) } }
        note("extractor ${hostOf(u)} -> ${got.size}")
        if (got.isNotEmpty()) return got.map { relabel(it, label) }

        // Genel çözüm: sayfada m3u8 / mp4 ara
        val text = runCatching {
            app.get(u, headers = mapOf("User-Agent" to ua), referer = page).text
        }.getOrNull() ?: return emptyList()
        val hay = listOf(text, runCatching { getAndUnpack(text) }.getOrDefault(""))
        for (h in hay) {
            val m3 = findM3u8(h)
            if (m3 != null) return listOf(mk(m3, label, ExtractorLinkType.M3U8, "https://${hostOf(u)}/"))
            val t = h.replace("\\/", "/").replace("&amp;", "&")
            val mp = Regex("""["'](https?:[^"'\s<>\\]+\.(?:mp4|mkv|webm)[^"'\s<>\\]*)["']""", ic).find(t)?.groupValues?.get(1)
            if (mp != null) return listOf(mk(mp, label, ExtractorLinkType.VIDEO, "https://${hostOf(u)}/"))
        }

        // İç içe iframe
        if (depth < 3) {
            val nxt = Regex("""<iframe[^>]+src=["']([^"']+)["']""", ic).find(text)?.groupValues?.get(1)
            if (nxt != null) {
                val n = absUrl(nxt)
                if (n.startsWith("http") && n != u) return resolveEmbed(n, page, label, subs, depth + 1)
            }
        }
        note("desteklenmeyen ${hostOf(u)}")
        return emptyList()
    }

    private suspend fun resolveAlt(
        alt: Pair<String, String>,
        altPath: String?,
        page: String,
        found: List<String>,
        label: String,
        subs: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val iu = embedFetch(alt.first, altPath, page, found)
        if (iu == null) {
            note("embed yok id=${alt.first}")
            return emptyList()
        }
        val tag = if (alt.second.isBlank()) label else "$label • ${alt.second}"
        return resolveEmbed(iu, page, tag, subs)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        synchronized(dbg) { dbg.clear() }

        val res = app.get(data, headers = mapOf("User-Agent" to ua), referer = "$mainUrl/")
        val bidRaw: String = res.document.selectFirst("#dilsec")?.attr("data-id") ?: ""
        note("sayfa ${res.code} len=${res.text.length} bid=$bidRaw")
        if (bidRaw.isBlank()) {
            if (debugMode) {
                callback(newExtractorLink(name, "DEBUG: bid yok (sayfa ${res.code})", "https://debug.invalid/", ExtractorLinkType.VIDEO))
                return true
            }
            return false
        }
        val bid = bidRaw

        val found = discoverAjax(res.text)
        note("ajax yollari: ${found.size}")
        found.take(10).forEach { note("ajax $it") }
        val cands = altCandidates(found)

        var ok = false
        var knownPath: String? = null
        for ((dil, label) in listOf(0 to "Türkçe Dublaj", 1 to "Altyazı")) {
            val pr = probeAlt(bid, dil, data, knownPath?.let { listOf(it) } ?: cands)
            if (pr.path != null) knownPath = pr.path

            val links = pr.alts.take(6).amap { alt ->
                runCatching { resolveAlt(alt, pr.path, data, found, label, subtitleCallback) }
                    .getOrDefault(emptyList())
            }.flatten()

            links.distinctBy { it.url }.forEach {
                callback(it)
                ok = true
            }
        }

        if (!ok && debugMode) {
            val items = synchronized(dbg) { dbg.take(22).toList() }
            items.forEachIndexed { i, s ->
                callback(newExtractorLink(name, "DEBUG ${i + 1}: $s", "https://debug.invalid/", ExtractorLinkType.VIDEO))
            }
            return items.isNotEmpty()
        }
        return ok
    }
}
