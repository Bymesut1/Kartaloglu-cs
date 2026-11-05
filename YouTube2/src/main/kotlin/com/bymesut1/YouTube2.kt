package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class YouTube2 : MainAPI() {
    override var mainUrl = "https://m.youtube.com"
    override var name = "YouTube 2"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; 21081111RG) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cookie" to "SOCS=CAI; CONSENT=YES+1"
    )

    // Ana sayfa satırları: soldaki arama sorgusu, sağdaki başlık. İstediğini değiştir.
    override val mainPage = mainPageOf(
        "gündem" to "Gündem",
        "türkçe film" to "Türkçe Film",
        "yerli dizi" to "Yerli Dizi",
        "belgesel" to "Belgesel",
        "müzik" to "Müzik",
        "komedi" to "Komedi"
    )

    // ---------------------------------------------------------------- yardımcılar

    private fun videoId(url: String): String? =
        Regex("""(?:[?&]v=|youtu\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})""")
            .find(url)?.groupValues?.get(1)

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun posterOf(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** JS string içindeki \xHH, \uHHHH ve \\ gibi kaçışları çöz. */
    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
                continue
            }
            val n = s[i + 1]
            when (n) {
                'x' -> {
                    val v = if (i + 3 < s.length) s.substring(i + 2, i + 4).toIntOrNull(16) else null
                    if (v != null) {
                        sb.append(v.toChar())
                        i += 4
                        continue
                    }
                    sb.append(n)
                }
                'u' -> {
                    val v = if (i + 5 < s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (v != null) {
                        sb.append(v.toChar())
                        i += 6
                        continue
                    }
                    sb.append(n)
                }
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    /** startIdx'teki '{' ile başlayan dengeli JSON nesnesini keser (string içindeki parantezleri sayma). */
    private fun balancedJson(text: String, startIdx: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        var i = startIdx
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(startIdx, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /** Sayfadaki ytInitialData'yı bulur: hem '\x7b...' (mobil) hem düz {...} biçimini dener. */
    private fun extractInitialData(html: String): JSONObject? {
        var from = 0
        while (true) {
            val key = html.indexOf("ytInitialData", from)
            if (key < 0) return null
            from = key + 13
            val eq = html.indexOf('=', from)
            if (eq < 0 || eq - from > 40) continue
            var p = eq + 1
            while (p < html.length && html[p].isWhitespace()) p++
            if (p >= html.length) return null

            if (html[p] == '\'') {
                // kaçışlı string
                var q = p + 1
                while (q < html.length) {
                    if (html[q] == '\\') {
                        q += 2
                        continue
                    }
                    if (html[q] == '\'') break
                    q++
                }
                if (q >= html.length) continue
                val raw = unescapeJs(html.substring(p + 1, q))
                runCatching { return JSONObject(raw) }
            } else if (html[p] == '{') {
                val json = balancedJson(html, p) ?: continue
                runCatching { return JSONObject(json) }
            }
        }
    }

    private fun textOf(node: Any?): String? {
        when (node) {
            is String -> return node.takeIf { it.isNotBlank() }
            is JSONObject -> {
                node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                val runs = node.optJSONArray("runs") ?: return null
                val sb = StringBuilder()
                for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
                return sb.toString().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun collectVideos(node: Any?, out: MutableList<SearchResponse>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val id = node.optString("videoId")
                if (id.length == 11 && !seen.contains(id)) {
                    val title = textOf(node.opt("title")) ?: textOf(node.opt("headline"))
                    if (title != null) {
                        seen.add(id)
                        out.add(
                            newMovieSearchResponse(title, watchUrl(id), TvType.Movie) {
                                this.posterUrl = posterOf(id)
                            }
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectVideos(node.opt(keys.next()), out, seen)
            }
            is JSONArray -> for (i in 0 until node.length()) collectVideos(node.opt(i), out, seen)
        }
    }

    private suspend fun searchVideos(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // sp=EgIQAQ%253D%253D -> sadece videolar
        val url = "$mainUrl/results?search_query=$q&sp=EgIQAQ%253D%253D"
        val html = runCatching { app.get(url, headers = pageHeaders, referer = "$mainUrl/").text }
            .getOrNull().orEmpty()
        val data = extractInitialData(html)
        if (data == null) {
            Log.d(name, "ytInitialData bulunamadı, html=${html.length}")
            return emptyList()
        }
        val out = ArrayList<SearchResponse>()
        collectVideos(data, out, HashSet())
        Log.d(name, "arama '$query' -> ${out.size} sonuç")
        return out
    }

    // ---------------------------------------------------------------- CloudStream API

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val items = searchVideos(request.data)
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank() || page > 1) return newSearchResponseList(emptyList(), false)
        return newSearchResponseList(searchVideos(query), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = videoId(url) ?: return null
        val watch = watchUrl(id)
        var title = id
        var plot: String? = null
        var poster = posterOf(id)

        runCatching {
            val doc = app.get("$mainUrl/watch?v=$id", headers = pageHeaders, referer = "$mainUrl/").document
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { plot = it }
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.startsWith("http") }?.let { poster = it }
        }

        return newMovieLoadResponse(title, watch, TvType.Movie, watch) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = videoId(data) ?: return false
        // Akış çözme (imza, n-parametresi vb.) CloudStream'in kendi YouTube çıkarıcısına bırakıldı.
        return loadExtractor(watchUrl(id), subtitleCallback, callback)
    }
}
