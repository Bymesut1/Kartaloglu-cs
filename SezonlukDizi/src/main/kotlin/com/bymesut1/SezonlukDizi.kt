package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentLinkedQueue

class SezonlukDizi : MainAPI() {
    override var mainUrl        = "https://sezonlukdizi.cc"
    override var name           = "SezonlukDizi"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // Her şey çalışınca false yap
    private val debugMode = true

    private val androidUA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=2&s="    to "Yerli Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=1&s="    to "Yabancı Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=3&s="    to "Asya Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=4&s="    to "Animasyonlar",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=5&s="    to "Animeler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=6&s="    to "Belgeseller",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.afis a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/diziler.asp?adi=${query}").document

        return document.select("div.afis a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.image img")?.attr("data-src")) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()

        val endpoint    = url.split("/").last()

        val actorsReq = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors    = actorsReq.select("div.doubling div.ui").mapNotNull {
            val actorName = it.selectFirst("div.header")?.text()?.trim() ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }

        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epName    = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epHref    = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("td:nth-of-type(3)")?.text()?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                val epSeason  = bolum.selectFirst("td:nth-of-type(2)")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            addActors(actors)
        }
    }

    // ---------------- Linkler ----------------
    //
    // Neden çalışıyor:
    //  1) Sitenin kendi AJAX uçları (dataAlternatif / dataEmbed) doğrudan iframe adresini veriyor.
    //  2) iframe, elle çözmek yerine CloudStream'in hazır extractor'larına (loadExtractor) veriliyor.
    //  3) Extractor'un döndürdüğü url / type / headers / referer AYNEN kullanılıyor.
    //  Olmazsa: WebView ile gerçek m3u8 isteği yakalanıyor ve #EXTM3U ile doğrulanıyor.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dbg = ArrayList<String>()
        var any = false
        Log.d("SZD", "data » $data")

        val document = app.get(data).document
        val aspData  = getAspData()
        val bid      = document.selectFirst("div#dilsec")?.attr("data-id")
        dbg.add("asp alt=${aspData.alternatif} emb=${aspData.embed} bid=$bid")

        if (bid != null) {
            for ((dil, etiket) in listOf("1" to "AltYazı", "0" to "Dublaj")) {
                val resp = try {
                    app.post(
                        "${mainUrl}/ajax/dataAlternatif${aspData.alternatif}.asp",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        data    = mapOf("bid" to bid, "dil" to dil)
                    ).parsedSafe<Kaynak>()
                } catch (e: Exception) {
                    dbg.add("ALT hata $etiket ${e.message?.take(50)}"); null
                }
                if (resp == null || resp.status != "success") {
                    dbg.add("$etiket yok (${resp?.status})"); continue
                }
                val list = resp.data ?: continue
                dbg.add("$etiket kaynak ${list.size}")

                for (veri in list) {
                    val label = "$etiket - ${veri.baslik}"

                    val embedDoc = try {
                        app.post(
                            "${mainUrl}/ajax/dataEmbed${aspData.embed}.asp",
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                            data    = mapOf("id" to "${veri.id}")
                        ).document
                    } catch (e: Exception) {
                        dbg.add("EMB hata $label ${e.message?.take(50)}"); continue
                    }
                    val iframe = fixUrlNull(embedDoc.selectFirst("iframe")?.attr("src"))
                    if (iframe == null) { dbg.add("$label iframe yok"); continue }

                    // 1) Hazır extractor
                    val got = ConcurrentLinkedQueue<ExtractorLink>()
                    try {
                        loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { got.add(it) }
                    } catch (e: Exception) {
                        dbg.add("LE hata $label ${e.message?.take(50)}")
                    }
                    dbg.add("LE $label ${got.size} ${iframe.take(60)}")

                    if (got.isNotEmpty()) {
                        for (link in got) {
                            callback(
                                newExtractorLink(
                                    source = label,
                                    name   = label,
                                    url    = link.url,
                                    type   = link.type
                                ) {
                                    this.referer = link.referer
                                    this.quality = link.quality
                                    this.headers = link.headers
                                }
                            )
                            any = true
                        }
                    } else {
                        // 2) WebView yedeği
                        val wv = try { webViewLink(iframe, data, label, dbg) } catch (e: Exception) {
                            dbg.add("WV hata $label ${e.message?.take(50)}"); null
                        }
                        if (wv != null) { callback(wv); any = true }
                    }
                }
            }
        }

        if (debugMode) {
            dbg.take(40).forEach {
                callback(
                    newExtractorLink(name, "DEBUG $it", "https://debug.invalid/", ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }
        return any
    }

    // ---------------- WebView yedeği + doğrulama ----------------

    private val badExt =
        Regex("""\.(vtt|srt|jpg|jpeg|png|webp|gif|css|js|ico|svg)(\?|$)""", RegexOption.IGNORE_CASE)

    private fun originOf(u: String) = Regex("^https?://[^/]+").find(u)?.value ?: ""

    private suspend fun webViewLink(
        iframe: String, pageUrl: String, label: String, dbg: MutableList<String>
    ): ExtractorLink? {
        val resp = app.get(
            iframe,
            referer = pageUrl,
            headers = mapOf("User-Agent" to androidUA),
            interceptor = WebViewResolver(
                Regex("""\.m3u8|\.mp4(\?|$)|master\.txt|/hls/""", RegexOption.IGNORE_CASE)
            )
        )
        val u = resp.url
        dbg.add("WV $label ${u.take(90)}")
        if (u == iframe || badExt.containsMatchIn(u)) return null

        val origin = originOf(iframe)
        val isMp4  = Regex("""\.mp4(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(u)
        val variants = listOf(
            mapOf("User-Agent" to androidUA, "Referer" to "$origin/", "Origin" to origin),
            mapOf("User-Agent" to androidUA, "Referer" to "$mainUrl/", "Origin" to mainUrl),
            mapOf("User-Agent" to androidUA)
        )
        var headers = variants[0]
        if (!isMp4) {
            for ((i, h) in variants.withIndex()) {
                val r = try { app.get(u, headers = h) } catch (e: Exception) { null } ?: continue
                dbg.add("V $label h$i ${r.code} ${r.text.take(20).replace(Regex("\\s+"), " ")}")
                if (r.code == 200 && r.text.trimStart().startsWith("#EXTM3U")) { headers = h; break }
            }
        }
        return newExtractorLink(
            label, label, u,
            if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
        ) {
            this.referer = "$origin/"
            this.quality = Qualities.Unknown.value
            this.headers = headers
        }
    }

    // dataAlternatif / dataEmbed sonundaki sürüm numarasını site.min.js'ten alır
    private suspend fun getAspData(): AspData {
        val js  = app.get("${this.mainUrl}/js/site.min.js").text
        val alt = Regex("""dataAlternatif(.*?).asp""").find(js)?.groupValues?.get(1) ?: ""
        val emb = Regex("""dataEmbed(.*?).asp""").find(js)?.groupValues?.get(1) ?: ""
        return AspData(alt, emb)
    }
}
