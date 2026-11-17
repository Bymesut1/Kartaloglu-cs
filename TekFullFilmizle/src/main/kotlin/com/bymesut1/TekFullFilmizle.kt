package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
    override val supportedTypes = setOf(TvType.Movie)

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

    // ---------------------------------------------------------------- Ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val document = app.get(url).document
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
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(cleanTitle(rawTitle), href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------- Arama

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val document = app.get("$mainUrl/search/$q").document
        return document.select("div.moviefilm").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------- İçerik sayfası

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

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

        val tags = document.select("div.filmaltiaciklama a[rel=category tag]").map { it.text().trim() }
        val actors = document.select("div.filmaltiaciklama a[href*=/search/]").map { it.text().trim() }

        val recommendations = document.select("div.moviefilm").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    // ---------------------------------------------------------------- Linkler (Platin / Gold / Silver)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val base = data.trimEnd('/')
        val pages = listOf(
            "Platin" to base,
            "Gold" to "$base/2",
            "Silver" to "$base/3"
        )

        var found = false
        for ((label, pageUrl) in pages) {
            try {
                val inner = getDecryptedHtml(pageUrl) ?: continue
                val srcList = extractSources(inner)
                for (src in srcList) {
                    if (handleSource(label, src, pageUrl, subtitleCallback, callback)) found = true
                }
            } catch (_: Exception) {
            }
        }
        return found
    }

    /** Sayfadaki AES-CBC şifreli #encrypted-content alanını çözer. */
    private suspend fun getDecryptedHtml(pageUrl: String): String? {
        val response = app.get(pageUrl, referer = "$mainUrl/")
        val html = response.text
        val el = response.document.selectFirst("#encrypted-content") ?: return null
        val enc = el.attr("data-enc")
        val iv = el.attr("data-iv")
        if (enc.isBlank() || iv.isBlank()) return null

        val pass = Regex("""pass\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: fallbackPass
        return decrypt(enc, iv, pass) ?: if (pass != fallbackPass) decrypt(enc, iv, fallbackPass) else null
    }

    private fun decrypt(enc: String, iv: String, pass: String): String? {
        return try {
            val key = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(Base64.decode(iv, Base64.DEFAULT))
            )
            String(cipher.doFinal(Base64.decode(enc, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Çözülen HTML'den iframe/video/kaynak linklerini toplar. */
    private fun extractSources(html: String): List<String> {
        val out = linkedSetOf<String>()
        val doc = Jsoup.parse(html)
        doc.select("iframe[src], iframe[data-src], video[src], source[src]").forEach {
            val s = it.attr("src").ifBlank { it.attr("data-src") }
            if (s.isNotBlank()) out.add(normalizeUrl(s))
        }
        if (out.isEmpty()) {
            Regex("""https?:(?:\\?/){2}[^\s"'<>\\]+(?:\\/[^\s"'<>\\]*)*""")
                .findAll(html.replace("\\/", "/"))
                .forEach { out.add(it.value) }
        }
        return out.toList()
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
            url.contains("loadvid.com") -> loadvid(label, url, callback)
            url.contains("myvideoplayer") -> myVideoPlayer(label, url, subtitleCallback, callback)
            else -> generic(label, url, pageUrl, subtitleCallback, callback)
        }
    }

    // ---- Platin: cdn.loadvid.com
    private suspend fun loadvid(
        label: String,
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = "https://cdn.loadvid.com/"
        val html = app.get(url, referer = "$mainUrl/").text.replace("\\/", "/")

        // 1) Sayfada doğrudan m3u8 / mp4 varsa
        findMedia(html)?.let { (link, type) ->
            addLink(label, link, ref, type, callback)
            return true
        }

        // 2) Thumbnail klasöründen tahmin
        val thumb = Regex("""thumbnailUrl:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
        val dir = thumb?.substringBeforeLast("/")
        if (dir != null) {
            for (n in listOf("master.m3u8", "index.m3u8", "playlist.m3u8", "video.m3u8", "hls.m3u8")) {
                val cand = "$dir/$n"
                val ok = try {
                    app.get(cand, referer = ref).text.trimStart().startsWith("#EXTM3U")
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    addLink(label, cand, ref, ExtractorLinkType.M3U8, callback)
                    return true
                }
            }
        }
        return false
    }

    // ---- Silver: myvideoplayer.top -> myvideoplayer.link -> (ok.ru vb.)
    private suspend fun myVideoPlayer(
        label: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(url, referer = "$mainUrl/").text

        var playerUrl: String? = null
        val b64 = Regex("""atob\("([A-Za-z0-9+/=]+)"\)""").find(html)?.groupValues?.get(1)
        if (b64 != null) {
            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).replace("\\/", "/")
            playerUrl = Regex("""src=\\?"(https?://[^"\\]+)""").find(decoded)?.groupValues?.get(1)
        }
        if (playerUrl == null) {
            playerUrl = Jsoup.parse(html).selectFirst("iframe[src]")?.attr("src")?.let { normalizeUrl(it) }
        }
        if (playerUrl == null) return generic(label, url, mainUrl, subtitleCallback, callback)

        val playerHtml = try {
            app.get(playerUrl, referer = url).text.replace("\\/", "/")
        } catch (_: Exception) {
            ""
        }

        findMedia(playerHtml)?.let { (link, type) ->
            addLink(label, link, playerUrl, type, callback)
            return true
        }

        Regex("""https?://(?:www\.)?ok\.ru/videoembed/\d+""").find(playerHtml)?.value?.let {
            if (loadExtractor(it, playerUrl, subtitleCallback, callback)) return true
        }

        return loadExtractor(playerUrl, url, subtitleCallback, callback)
    }

    // ---- Gold ve diğerleri: workers.dev m3u8 proxy vb.
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

        val text = try {
            app.get(url, referer = ref).text
        } catch (_: Exception) {
            ""
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
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = "$name $label",
                name = "$name $label",
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to userAgent)
            }
        )
    }
}
