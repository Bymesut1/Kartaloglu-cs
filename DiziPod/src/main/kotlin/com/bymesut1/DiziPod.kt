package com.bymesut1

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.Normalizer

private const val TAG = "DIZIPOD"

// =============================================================================
//  Ana sağlayıcı
// =============================================================================
class DiziPod : MainAPI() {
    override var mainUrl = "https://dizipod.com"
    override var name = "DiziPod"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/" to "Diziler",
        "$mainUrl/filmler/" to "Filmler",
        "$mainUrl/yapim/netflix/" to "Netflix",
        "$mainUrl/yapim/prime-video/" to "Prime Video",
        "$mainUrl/yapim/hbo/" to "HBO",
        "$mainUrl/yapim/national-geographic/" to "National Geographic",
        "$mainUrl/yapim/hulu/" to "Hulu"
    )

    // ------------------------------------------------------------- yardımcılar

    private fun headers(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to referer
    )

    /** JSON/HTML içinde kaçışlı gelen adresleri düzeltir. */
    private fun absolute(raw: String): String {
        val s = raw
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\", "")
            .trim()
            .trim('"', '\'')
            .replace("&amp;", "&")
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "$mainUrl$s"
            else -> "$mainUrl/$s"
        }
    }

    private fun typeOf(url: String) = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

    private fun pageUrl(base: String, page: Int): String = when {
        page <= 1 -> base
        base.contains("/yapim/") -> "$base?pg=$page"
        else -> "${base.trimEnd('/')}/page/$page/"
    }

    private fun cleanTitle(doc: Document): String =
        doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank {
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - Dizipod")?.trim().orEmpty()
        }

    private fun poster(doc: Document): String =
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty().ifBlank {
            doc.selectFirst(".poster img, .post-thumbnail img, img.wp-post-image")
                ?.attr("src")?.trim().orEmpty()
        }

    private fun plot(doc: Document): String =
        doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty().ifBlank {
            doc.selectFirst(".description,.content,.summary,.plot")?.text()?.trim().orEmpty()
        }

    private fun norm(s: String): String {
        val t = s.replace('ı', 'i').replace('İ', 'i').lowercase()
        val noMarks = Regex("\\p{M}+").replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        return Regex("[^a-z0-9]+").replace(noMarks, " ").trim()
    }

    // --------------------------------------------------------------- kartlar

    private class RawCard(val url: String, var title: String, var poster: String?, var year: Int?)

    private val badImgRx = Regex(
        "(?i)(^data:|placeholder|blank\\.|spacer|loading|lazy\\.|noimage|no-image|default\\.(?:jpg|jpeg|png|webp)|\\.svg(?:$|\\?)|1x1)"
    )

    private fun badImg(v: String): Boolean = v.isBlank() || badImgRx.containsMatchIn(v)

    /** Kart içinden gerçek afiş adresini bulur (lazy-load / srcset / noscript / background destekli). */
    private fun pickPoster(card: Element, a: Element): String? {
        val imgs = LinkedHashSet<Element>()
        imgs.addAll(a.select("img"))
        imgs.addAll(card.select("img"))

        val attrs = listOf(
            "data-original", "data-src", "data-lazy-src", "data-lazy", "data-image",
            "data-poster", "data-thumb", "src"
        )
        for (img in imgs) {
            for (k in attrs) {
                val v = img.attr(k).trim()
                if (!badImg(v)) return v
            }
            for (k in listOf("data-srcset", "data-lazy-srcset", "srcset")) {
                val first = img.attr(k).trim().split(",").firstOrNull()
                    ?.trim()?.substringBefore(" ")?.trim().orEmpty()
                if (!badImg(first)) return first
            }
        }

        // <noscript><img src="..."></noscript>
        val srcRx = Regex("src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        for (ns in card.select("noscript")) {
            val m = srcRx.find(ns.html() + " " + ns.data())
            val v = m?.groupValues?.get(1)?.trim().orEmpty()
            if (!badImg(v)) return v
        }

        // background-image
        val bgRx = Regex("url\\((['\"]?)([^)'\"]+)\\1\\)", RegexOption.IGNORE_CASE)
        for (el in card.select("[data-bg],[data-background],[style*=url(]")) {
            val raw = el.attr("data-bg").ifBlank { el.attr("data-background") }
                .ifBlank { bgRx.find(el.attr("style"))?.groupValues?.get(2).orEmpty() }
                .trim()
            if (!badImg(raw)) return raw
        }
        return null
    }

    private fun insideChrome(a: Element): Boolean =
        a.parents().any { it.tagName() in setOf("nav", "header", "footer", "aside") }

    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Siteden afiş çıkmayan kartlar için TMDB afişi (önbellekli). */
    private suspend fun tmdbPosterFor(title: String, isMovie: Boolean): String? {
        val key = (if (isMovie) "m:" else "t:") + norm(title)
        posterCache[key]?.let { return it.ifBlank { null } }
        val path: String? = try {
            val q = titleVariants(title).firstOrNull() ?: title
            val k = norm(q)
            if (isMovie) {
                val r = tmdbSearchMovie(q)
                (r.firstOrNull { norm(it.title.orEmpty()) == k || norm(it.original_title.orEmpty()) == k }
                    ?: r.firstOrNull())?.poster_path
            } else {
                val r = tmdbSearchTv(q)
                (r.firstOrNull { norm(it.name.orEmpty()) == k || norm(it.original_name.orEmpty()) == k }
                    ?: r.firstOrNull())?.poster_path
            }
        } catch (e: Exception) {
            null
        }
        val full = path?.takeIf { it.isNotBlank() }?.let { "$tmdbImg/w500$it" }
        posterCache[key] = full.orEmpty()
        return full
    }

    private fun collectCards(doc: Document, strict: Boolean): List<RawCard> {
        val map = LinkedHashMap<String, RawCard>()

        for (a in doc.select("a[href*=/diziler/], a[href*=/film/]")) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue
            // /diziler/ ya da /filmler/ liste sayfalarının kendisini atla
            if (href.trimEnd('/').endsWith("/diziler") || href.trimEnd('/').endsWith("/filmler")) continue
            // menü / üst bilgi / alt bilgi / yan panel bağlantıları kart değildir
            if (strict && insideChrome(a)) continue

            val url = absolute(href)

            val card = a.closest("article, .item, .post, .movie, .series, .video-card, .card") ?: a
            val img = a.selectFirst("img") ?: card.selectFirst("img")

            val title = a.attr("title").trim()
                .ifBlank { card.selectFirst("h2,h3,h4,.title,.name")?.text()?.trim().orEmpty() }
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
                .ifBlank { a.text().trim() }

            val posterRaw = pickPoster(card, a)
            val posterUrl: String? = posterRaw?.let { absolute(it) }
            val year = Regex("(?:19|20)\\d{2}").find(card.text())?.value?.toIntOrNull()

            val old = map[url]
            if (old == null) {
                if (title.isBlank() && posterUrl == null) continue
                map[url] = RawCard(url, title, posterUrl, year)
            } else {
                // Aynı adres birden fazla yerde geçiyorsa (metin bağlantısı + gerçek kart) afişli olanı tut
                if (old.poster == null && posterUrl != null) old.poster = posterUrl
                if (old.title.isBlank() && title.isNotBlank()) old.title = title
                if (old.year == null && year != null) old.year = year
            }
        }
        return map.values.filter { it.title.isNotBlank() }
    }

    private suspend fun parseCards(doc: Document): List<SearchResponse> {
        var cards = collectCards(doc, strict = true)
        if (cards.isEmpty()) cards = collectCards(doc, strict = false)

        // Afişli kartlar varsa afişsiz metin bağlantıları (popüler/öneri listeleri) gürültüdür
        val withPoster = cards.count { it.poster != null }
        val kept = if (withPoster > 0) cards.filter { it.poster != null } else cards

        // Hâlâ afişi olmayanlar için TMDB yedeği
        kept.filter { it.poster == null }.amap { c ->
            c.poster = tmdbPosterFor(c.title, typeOf(c.url) == TvType.Movie)
        }

        val posterHdr = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua)

        return kept.map { c ->
            if (typeOf(c.url) == TvType.Movie) {
                newMovieSearchResponse(c.title, c.url, TvType.Movie) {
                    this.posterUrl = c.poster
                    this.posterHeaders = posterHdr
                    this.year = c.year
                }
            } else {
                newTvSeriesSearchResponse(c.title, c.url, TvType.TvSeries) {
                    this.posterUrl = c.poster
                    this.posterHeaders = posterHdr
                    this.year = c.year
                }
            }
        }
    }

    // -------------------------------------------------------------- ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        Log.d(TAG, "MAIN $url page=$page")
        val doc = app.get(url, headers = headers()).document
        return newHomePageResponse(request.name, parseCards(doc), hasNext = true)
    }

    // ------------------------------------------------------------------ arama

    private fun matchesSearchQuery(query: String, title: String): Boolean {
        fun normalize(value: String): String {
            val lowered = value.lowercase(java.util.Locale.ROOT).replace('ı', 'i')
            val decomposed = java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        }
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return false
        val ignored = setOf("the", "a", "an", "of", "and", "or", "ve")
        val year = Regex("(?:18|19|20|21)\\d{2}")
        val words = q.split(' ').filterNot { it in ignored || year.matches(it) }
        if (words.isEmpty()) return q == t
        val titleWords = t.split(' ').toSet()
        val numberWords = mapOf("0" to "zero", "1" to "one", "2" to "two", "3" to "three",
            "4" to "four", "5" to "five", "6" to "six", "7" to "seven", "8" to "eight",
            "9" to "nine", "10" to "ten")
        return words.all { word ->
            val mapped = numberWords[word]
            titleWords.any { candidate ->
                candidate == word || (mapped != null && candidate == mapped) ||
                    (candidate.length >= 4 && word.length >= 4 &&
                        kotlin.math.abs(candidate.length - word.length) <= 2 &&
                        (candidate.startsWith(word) || word.startsWith(candidate)))
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rawQuery = query.trim()
        val q = norm(rawQuery)
        if (q.isBlank()) return emptyList()

        fun relevant(list: List<SearchResponse>): List<SearchResponse> =
            list.filter { matchesSearchQuery(rawQuery, it.name) }

        val enc = URLEncoder.encode(rawQuery, "UTF-8")
        val ajaxDocument = runCatching {
            app.post(
                "$mainUrl/wp/wp-admin/admin-ajax.php",
                headers = headers() + ("X-Requested-With" to "XMLHttpRequest"),
                data = mapOf(
                    "action" to "dp_live_search",
                    "query" to rawQuery,
                    "search" to rawQuery,
                    "term" to rawQuery
                )
            ).document
        }.onFailure {
            Log.w(TAG, "Live search isteği başarısız", it)
        }.getOrNull()

        if (ajaxDocument != null) {
            val parsed = parseCards(ajaxDocument).distinctBy { it.url }
            val matched = relevant(parsed)
            if (matched.isNotEmpty()) {
                Log.d(TAG, "SEARCH ajax q=$rawQuery items=${matched.size}")
                return matched
            }
        }

        val urls = listOf(
            "$mainUrl/?s=$enc",
            "$mainUrl/?search=$enc",
            "$mainUrl/?q=$enc",
            "$mainUrl/search/?q=$enc",
            "$mainUrl/arama/?q=$enc",
            "$mainUrl/arama/$enc"
        ).distinct()

        for (url in urls) {
            val doc = runCatching {
                app.get(url, headers = headers(), referer = "$mainUrl/", allowRedirects = true).document
            }.onFailure {
                Log.w(TAG, "SEARCH GET failed: $url", it)
            }.getOrNull() ?: continue
            val scope = doc.selectFirst("main, #main, .search-results, .content-area, #content")
                ?.outerHtml()?.let { Jsoup.parse(it, mainUrl) } ?: doc
            val parsed = parseCards(scope).distinctBy { it.url }
            val matched = relevant(parsed)
            Log.d(TAG, "SEARCH url=$url parsed=${parsed.size} matched=${matched.size}")
            if (matched.isNotEmpty()) return matched
        }
        return emptyList()
    }

    // ------------------------------------------------------------------- load

    private val epRx = Regex("-(\\d+)-sezon-(\\d+)-bolum", RegexOption.IGNORE_CASE)
    private val epSuffixRx = Regex("^/?(?:[?#].*)?$")
    private val rawEpRx = Regex(
        """(?:https?://[A-Za-z0-9.-]+)?/[^"'\s<>\\()]*?-\d+-sezon-\d+-bolum/?(?:\?[^"'\s<>\\()]*)?""",
        RegexOption.IGNORE_CASE
    )

    private class EpHit(
        val url: String,
        val prefix: String,
        val suffix: String,
        val season: Int,
        val ep: Int,
        val label: String
    )

    /** Bölüm adresi şablonu: prefix-{sezon}-sezon-{bölüm}-bolum+suffix */
    private class EpTemplate(val prefix: String, val suffix: String) {
        fun url(season: Int, ep: Int) = "$prefix-$season-sezon-$ep-bolum$suffix"
    }

    private fun hitOf(rawUrl: String, label: String): EpHit? {
        val url = absolute(rawUrl)
        val m = epRx.findAll(url).lastOrNull() ?: return null
        val suffix = url.substring(m.range.last + 1)
        if (!epSuffixRx.matches(suffix)) return null
        val s = m.groupValues[1].toIntOrNull() ?: return null
        val e = m.groupValues[2].toIntOrNull() ?: return null
        return EpHit(url, url.substring(0, m.range.first), suffix, s, e, label)
    }

    /** Sayfadaki bağlantılar + ham HTML/JSON içinde gömülü bölüm adresleri. */
    private fun collectHits(doc: Document, html: String): List<EpHit> {
        val hits = ArrayList<EpHit>()
        for (a in doc.select("a[href]")) {
            hitOf(a.attr("href"), a.text().trim())?.let { hits += it }
        }
        val raw = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        for (m in rawEpRx.findAll(raw)) {
            hitOf(m.value, "")?.let { hits += it }
        }
        return hits
    }

    private fun cleanEpName(label: String, title: String): String? {
        val l = label.trim()
        if (l.isBlank() || l.length > 45) return null
        if (l.contains("sezon", true) || l.contains("izle", true)) return null
        if (l.contains(title, true) || l[0].isDigit()) return null
        return l
    }

    private fun episodes(doc: Document, html: String, pageUrl: String, title: String): Pair<List<Episode>, EpTemplate?> {
        val hits = collectHits(doc, html)
        if (hits.isEmpty()) return emptyList<Episode>() to null

        // Yan panel / "son eklenen" gibi başka dizilerin bölümlerini ele: en kalabalık şablonu seç
        val slug = pageUrl.trimEnd('/').substringAfterLast('/')
        val best = hits.groupBy { it.prefix + "|" + it.suffix }.values.maxByOrNull { g ->
            g.map { it.season to it.ep }.distinct().size +
                (if (slug.isNotBlank() && g.first().prefix.contains(slug)) 1000 else 0)
        } ?: return emptyList<Episode>() to null

        val template = EpTemplate(best.first().prefix, best.first().suffix)
        val byKey = LinkedHashMap<Pair<Int, Int>, EpHit>()
        for (h in best) {
            val k = h.season to h.ep
            val old = byKey[k]
            if (old == null || (old.label.isBlank() && h.label.isNotBlank())) byKey[k] = h
        }

        val list = byKey.values.map { h ->
            newEpisode(h.url) {
                this.name = cleanEpName(h.label, title)
                this.season = h.season
                this.episode = h.ep
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return list to template
    }

    /** Sayfadaki "N. Sezon" butonlarından sezon numaralarını okur. */
    private fun seasonButtons(doc: Document): Set<Int> {
        val out = sortedSetOf<Int>()
        val rx = Regex("^\\s*(\\d{1,2})\\s*\\.?\\s*sezon\\s*$", RegexOption.IGNORE_CASE)
        for (el in doc.select("button, a, li, span, div, option, label")) {
            if (el.children().size > 2) continue
            val t = el.text().trim()
            if (t.length > 14) continue
            val n = rx.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n in 1..60) out += n
        }
        for (el in doc.select("[data-season],[data-sezon]")) {
            val n = el.attr("data-season").ifBlank { el.attr("data-sezon") }.trim().toIntOrNull() ?: continue
            if (n in 1..60) out += n
        }
        return out
    }

    private suspend fun urlExists(url: String): org.jsoup.nodes.Document? {
        val res = runCatching { app.get(url, headers = headers()) }.getOrNull() ?: return null
        if (res.code !in 200..299) return null
        if (!epRx.containsMatchIn(res.url)) return null
        return res.document
    }

    private suspend fun fetchSeason(sn: Int, tpl: EpTemplate, tmdbCount: Int, title: String): List<Episode> {
        val firstDoc = urlExists(tpl.url(sn, 1)) ?: return emptyList()

        // Bölüm sayfası aynı sezonun listesini veriyorsa onu da kullan
        val found = collectHits(firstDoc, firstDoc.outerHtml())
            .filter { it.prefix == tpl.prefix && it.season == sn }
        var n = maxOf(tmdbCount, found.maxOfOrNull { it.ep } ?: 0)

        if (n == 0) {
            // Ne TMDB ne sayfa sayı verdi: ardışık dene
            n = 1
            for (e in 2..40) {
                if (urlExists(tpl.url(sn, e)) == null) break
                n = e
            }
        }

        val labels = found.associate { it.ep to it.label }
        return (1..n).map { e ->
            newEpisode(tpl.url(sn, e)) {
                this.name = cleanEpName(labels[e].orEmpty(), title)
                this.season = sn
                this.episode = e
            }
        }
    }

    /** Sitede görünmeyen sezonları (buton + TMDB) şablon adresleriyle tamamlar. */
    private suspend fun expandSeasons(
        siteEps: List<Episode>,
        tpl: EpTemplate?,
        buttons: Set<Int>,
        tmdbCounts: Map<Int, Int>,
        title: String
    ): List<Episode> {
        if (tpl == null) return siteEps
        val have = siteEps.mapNotNull { it.season }.toSet()
        val wanted = (buttons + tmdbCounts.keys + have).filter { it > 0 }.toSortedSet()
        val missing = wanted.filter { it !in have }
        if (missing.isEmpty()) return siteEps

        val extra = missing.amap { sn ->
            try {
                fetchSeason(sn, tpl, tmdbCounts[sn] ?: 0, title)
            } catch (e: Exception) {
                Log.w(TAG, "SEASON $sn alınamadı: ${e.message}")
                emptyList()
            }
        }.flatten()
        Log.d(TAG, "SEASONS site=$have eksik=$missing eklenen=${extra.size}")
        return (siteEps + extra).sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers())
        val doc = res.document
        val html = res.text
        val title = cleanTitle(doc)
        val poster = poster(doc)
        val plot = plot(doc)
        val year = Regex("(?:19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        // TMDB yalnizca icerige tiklaninca devreye girer (ana sayfa ve arama etkilenmez)
        return if (url.contains("/film/")) {
            val info = try { tmdbFilm(title, year) } catch (e: Exception) { null }
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = info?.poster ?: poster
                this.backgroundPosterUrl = info?.backdrop
                this.plot = info?.overview ?: plot
                this.year = info?.year ?: year
                this.tags = info?.tags
            }
        } else {
            val (siteEps0, template) = episodes(doc, html, url, title)
            val buttons = seasonButtons(doc)
            val counts0 = siteEps0.groupBy { it.season ?: 0 }.mapValues { it.value.size }

            val tm = try { tmdbMatch(title, year, counts0) } catch (e: Exception) { null }
            val tmdbCounts: Map<Int, Int> = tm?.seasons.orEmpty()
                .filter { (it.season_number ?: 0) > 0 && (it.episode_count ?: 0) > 0 }
                .associate { it.season_number!! to it.episode_count!! }

            val siteEps = try {
                expandSeasons(siteEps0, template, buttons, tmdbCounts, title)
            } catch (e: Exception) {
                siteEps0
            }

            val info = try { tmdbDizi(tm, siteEps) } catch (e: Exception) { null }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, info?.episodes ?: siteEps) {
                this.posterUrl = info?.poster ?: poster
                this.backgroundPosterUrl = info?.backdrop
                this.plot = info?.overview ?: plot
                this.year = info?.year ?: year
                this.tags = info?.tags
            }
        }
    }

    // ---------------------------------------------------------------- TMDB
    // Yalnizca load() icinde kullanilir: Turkce ad/aciklama, poster, arka plan, bolum gorseli, sure, tarih

    private val tmdbKey = "000316508321ce461cf81e7c6815eec7"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val reGenericEp = Regex("""(?iu)^\s*(bölüm|episode|ep\.?)\s*\d+\s*$""")

    private fun titleVariants(title: String): List<String> {
        val t1 = title.trim()
        val t2 = t1.replace(Regex("""(?iu)\s*(izle|türkçe dublaj|altyazılı|full hd|hd)\b.*$"""), "").trim()
        val t3 = t2.replace(Regex("""\(\s*\d{4}\s*\)"""), "").trim()
        return listOf(t1, t2, t3).filter { it.isNotBlank() }.distinct()
    }

    private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    private fun dateMs(s: String?): Long? = try {
        if (s.isNullOrBlank()) null else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s)?.time
    } catch (e: Exception) {
        null
    }

    private suspend fun tmdbSearchTv(q: String, year: Int? = null): List<TmdbTvResult> = try {
        val y = if (year != null) "&first_air_date_year=$year" else ""
        app.get(
            "$tmdbApi/search/tv?api_key=$tmdbKey&language=tr-TR&include_adult=false$y&query=${URLEncoder.encode(q, "UTF-8")}",
            timeout = 15L
        ).parsedSafe<TmdbTvSearch>()?.results.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun tmdbSearchMovie(q: String): List<TmdbMovieResult> = try {
        app.get(
            "$tmdbApi/search/movie?api_key=$tmdbKey&language=tr-TR&include_adult=false&query=${URLEncoder.encode(q, "UTF-8")}",
            timeout = 15L
        ).parsedSafe<TmdbMovieSearch>()?.results.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun tmdbTvDetails(id: Int, lang: String): TmdbTvDetails? = try {
        app.get("$tmdbApi/tv/$id?api_key=$tmdbKey&language=$lang", timeout = 15L).parsedSafe<TmdbTvDetails>()
    } catch (e: Exception) {
        null
    }

    private suspend fun tmdbMovieDetails(id: Int, lang: String): TmdbMovieDetails? = try {
        app.get("$tmdbApi/movie/$id?api_key=$tmdbKey&language=$lang", timeout = 15L).parsedSafe<TmdbMovieDetails>()
    } catch (e: Exception) {
        null
    }

    private suspend fun tmdbSeason(id: Int, sn: Int, lang: String): Map<Int, TmdbEpisode> = try {
        app.get("$tmdbApi/tv/$id/season/$sn?api_key=$tmdbKey&language=$lang", timeout = 15L)
            .parsedSafe<TmdbSeasonDetails>()?.episodes.orEmpty()
            .mapNotNull { e -> e.episode_number?.let { it to e } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private suspend fun tmdbFilm(title: String, year: Int?): TmdbInfo? {
        for (q in titleVariants(title)) {
            val key = norm(q)
            val results = tmdbSearchMovie(q)
            if (results.isEmpty()) continue
            val exact = results.filter { norm(it.title.orEmpty()) == key || norm(it.original_title.orEmpty()) == key }
            val pool = if (exact.isNotEmpty()) exact else results.take(3)
            val pick = pool.firstOrNull { year != null && yearOf(it.release_date) == year }
                ?: pool.maxByOrNull { it.popularity ?: 0.0 } ?: continue
            val id = pick.id ?: continue
            val tr = tmdbMovieDetails(id, "tr-TR") ?: continue
            val en = if (tr.overview.isNullOrBlank()) tmdbMovieDetails(id, "en-US") else null
            return TmdbInfo(
                poster = tr.poster_path?.let { "$tmdbImg/w500$it" },
                backdrop = tr.backdrop_path?.let { "$tmdbImg/w1280$it" },
                overview = tr.overview?.takeIf { it.isNotBlank() } ?: en?.overview?.takeIf { it.isNotBlank() },
                year = yearOf(tr.release_date),
                tags = tr.genres?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() },
                episodes = null
            )
        }
        return null
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val ta = norm(a).split(' ').filter { it.length > 1 }.toSet()
        val tb = norm(b).split(' ').filter { it.length > 1 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / minOf(ta.size, tb.size)
    }

    private fun searchQueries(title: String): List<String> {
        val out = LinkedHashSet<String>()
        for (v in titleVariants(title)) {
            out += v
            out += v.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            out += v.substringBefore(",").trim()
            out += v.substringBefore(":").trim()
        }
        return out.filter { it.length >= 2 }
    }

    // Ayni adli diziler (Ben 10 gibi) icin sezon basina bolum sayisi ve yil en yakin olan secilir.
    // 1. tur: ad eslesmesi (tam / iceren / kelime ortusmesi). 2. tur (Kore/Asya dizileri gibi adi
    // TMDB'de farkli yazilanlar): ayni yila filtrelenmis aramanin ilk sonucu.
    private suspend fun tmdbMatch(title: String, year: Int?, siteCounts: Map<Int, Int>): TmdbTvDetails? {
        val queries = searchQueries(title)
        val years = listOf(year, null).distinct()
        val cache = HashMap<String, List<TmdbTvResult>>()

        for (pass in 0..1) {
            for (q in queries) {
                val key = norm(q)
                if (key.isEmpty()) continue
                for (yr in years) {
                    if (pass == 1 && yr == null) continue
                    val results = cache.getOrPut("$q|$yr") { tmdbSearchTv(q, yr) }
                    if (results.isEmpty()) continue
                    val exact = results.filter { norm(it.name.orEmpty()) == key || norm(it.original_name.orEmpty()) == key }
                    val cands: List<TmdbTvResult> = if (exact.isNotEmpty()) exact.take(3) else listOfNotNull(
                        results.firstOrNull {
                            val n = norm(it.name.orEmpty())
                            n.isNotEmpty() && (n.contains(key) || key.contains(n))
                        } ?: results.firstOrNull {
                            maxOf(tokenOverlap(q, it.name.orEmpty()), tokenOverlap(q, it.original_name.orEmpty())) >= 0.6
                        } ?: (if (pass == 1) results.first() else null)
                    )
                    if (cands.isEmpty()) continue
                    if (cands.size == 1) {
                        val d = cands[0].id?.let { tmdbTvDetails(it, "tr-TR") }
                        if (d != null) return d
                        continue
                    }
                    val details = cands.amap { c -> c.id?.let { tmdbTvDetails(it, "tr-TR") } }.filterNotNull()
                    if (details.isEmpty()) continue
                    return details.minByOrNull { d ->
                        val cnt = d.seasons.orEmpty().associate { (it.season_number ?: -1) to (it.episode_count ?: 0) }
                        var score = 0
                        for ((sn, c) in siteCounts) score += kotlin.math.abs(c - (cnt[sn] ?: 0))
                        if (year != null && yearOf(d.first_air_date) == year) score -= 5
                        score * 1000 - (d.popularity ?: 0.0).toInt().coerceAtMost(999)
                    }
                }
            }
        }
        return null
    }

    private suspend fun tmdbDizi(tm: TmdbTvDetails?, siteEps: List<Episode>): TmdbInfo? {
        if (tm == null) return null
        val counts = siteEps.groupBy { it.season ?: 0 }.mapValues { it.value.size }
        val tid = tm.id ?: return null
        val en = if (tm.overview.isNullOrBlank()) tmdbTvDetails(tid, "en-US") else null

        var newEps: List<Episode>? = null
        if (siteEps.isNotEmpty()) {
            val rows = counts.keys.filter { it > 0 }.amap { sn ->
                Triple(sn, tmdbSeason(tid, sn, "tr-TR"), tmdbSeason(tid, sn, "en-US"))
            }
            val trMap = rows.associate { it.first to it.second }
            val enMap = rows.associate { it.first to it.third }
            newEps = siteEps.map { old ->
                val sn = old.season ?: 0
                val no = old.episode ?: 0
                val tr = trMap[sn]?.get(no)
                val us = enMap[sn]?.get(no)
                if (tr == null && us == null) return@map old
                val epName = tr?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                    ?: us?.name?.takeIf { it.isNotBlank() && !reGenericEp.matches(it) }
                    ?: old.name
                val overview = tr?.overview?.takeIf { it.isNotBlank() } ?: us?.overview?.takeIf { it.isNotBlank() }
                val still = (tr?.still_path ?: us?.still_path)?.let { "$tmdbImg/w500$it" }
                val runtime = tr?.runtime ?: us?.runtime
                val airMs = dateMs(tr?.air_date ?: us?.air_date)
                newEpisode(old.data) {
                    this.season = old.season
                    this.episode = old.episode
                    this.name = epName
                    this.description = overview
                    this.posterUrl = still
                    if (runtime != null && runtime > 0) this.runTime = runtime
                    if (airMs != null) this.date = airMs
                }
            }
        }
        return TmdbInfo(
            poster = tm.poster_path?.let { "$tmdbImg/w500$it" },
            backdrop = tm.backdrop_path?.let { "$tmdbImg/w1280$it" },
            overview = tm.overview?.takeIf { it.isNotBlank() } ?: en?.overview?.takeIf { it.isNotBlank() },
            year = yearOf(tm.first_air_date),
            tags = tm.genres?.mapNotNull { it.name }?.takeIf { it.isNotEmpty() },
            episodes = newEps
        )
    }

    // -------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = 0
        val counting: (ExtractorLink) -> Unit = {
            found++
            callback(it)
        }

        val res = app.get(data, headers = headers())
        val html = res.text
        val doc = res.document

        // 1) sayfa kimliği (post id)
        val postId = doc.selectFirst("#episode-player-container[data-post-id], [data-post-id]")
            ?.attr("data-post-id")?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("postid-(\\d+)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("[\"']post_id[\"']\\s*[:=]\\s*[\"']?(\\d+)").find(html)?.groupValues?.get(1)

        // 2) oynatıcı embed adreslerini topla
        val embeds = LinkedHashSet<String>()

        if (postId != null) {
            val ajaxUrl = "$mainUrl/wp/wp-admin/admin-ajax.php?action=get_episode_player&post_id=$postId"
            val body = runCatching {
                app.get(ajaxUrl, headers = headers(data) + ("X-Requested-With" to "XMLHttpRequest")).text
            }.getOrNull().orEmpty()
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\u0026", "&")
                .replace("\\n", "\n")

            Jsoup.parse(body, mainUrl).select("iframe[src],iframe[data-src]").forEach { f ->
                val src = f.attr("data-src").ifBlank { f.attr("src") }
                if (src.isNotBlank()) {
                    val abs = absolute(src)
                    if (abs.startsWith("http")) embeds += abs
                }
            }
            Regex("https?://player\\.dizipod\\.com/embed/[^\"'\\\\\\s<]+", RegexOption.IGNORE_CASE)
                .findAll(body).forEach { embeds += it.value.replace("&amp;", "&") }
        }

        // sayfa HTML'inde doğrudan gömülü olabilir
        Regex("https?://player\\.dizipod\\.com/embed/[^\"'\\\\\\s<]+", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { embeds += it.value.replace("&amp;", "&") }

        Log.d(TAG, "EMBEDS post=$postId count=${embeds.size} list=$embeds")

        // 3) her embed'i extractor'a ver
        for (embed in embeds) {
            try {
                loadExtractor(embed, "$mainUrl/", subtitleCallback, counting)
            } catch (e: Exception) {
                Log.e(TAG, "EMBED_FAIL $embed: ${e.message}")
            }
        }
        return found > 0
    }
}

// =============================================================================
//  player.dizipod.com çıkarıcısı
// =============================================================================
class DiziPodPlayer : ExtractorApi() {
    override val name = "DiziPod Player"
    override val mainUrl = "https://player.dizipod.com"
    override val requiresReferer = true

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/153.0.0.0 Mobile Safari/537.36"

    private data class SourceItem(val url: String, val label: String)

    private fun clean(s: String) = s
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\x26", "&")
        .replace("&amp;", "&")

    private fun qualityFromLabel(label: String): Int {
        val n = Regex("(\\d{3,4})").find(label)?.value?.toIntOrNull()
        return when {
            n == null -> Qualities.Unknown.value
            n >= 2160 -> Qualities.P2160.value
            n >= 1440 -> Qualities.P1440.value
            n >= 1080 -> Qualities.P1080.value
            n >= 720 -> Qualities.P720.value
            n >= 480 -> Qualities.P480.value
            n >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // ---------------------------------------------------------- kaynak toplama

    private fun collectSources(text: String): LinkedHashSet<SourceItem> {
        val out = LinkedHashSet<SourceItem>()
        val body = clean(text)

        // sources:[{file:"...",label:"720p"}]
        val sourcesRx = Regex(
            "(?:sources\\s*:\\s*|[\"']sources[\"']\\s*:\\s*)\\[\\s*\\{([\\s\\S]*?)\\}\\s*\\]",
            RegexOption.IGNORE_CASE
        )
        val fileRx = Regex("(?:[\"']?file[\"']?\\s*:\\s*[\"'])([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val labelRx = Regex("(?:[\"']?label[\"']?\\s*:\\s*[\"'])([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        sourcesRx.findAll(body).forEach { block ->
            val inner = block.groupValues[1]
            val file = fileRx.find(inner)?.groupValues?.get(1)?.replace("\\", "")
            val label = labelRx.find(inner)?.groupValues?.get(1) ?: "720p"
            if (file != null && file.contains(".m3u8")) out += SourceItem(file, label)
        }

        // { ... file:"https://....m3u8" ... }
        Regex(
            "\\{[^{}]*?(?:[\"']?file[\"']?\\s*:\\s*[\"'])(https?://[^\"']+?\\.m3u8[^\"']*)[\"'][^{}]*?\\}",
            setOf(RegexOption.IGNORE_CASE)
        ).findAll(body).forEach { m ->
            val u = m.groupValues[1].replace("\\", "")
            val label = labelRx.find(m.value)?.groupValues?.get(1) ?: "HLS"
            out += SourceItem(u, label)
        }

        // tyuopix CDN
        Regex(
            "https?://[a-zA-Z0-9.-]+\\.tyuopix\\.com[^\\s\"'<>\\\\]+\\.m3u8(?:\\?[^\\s\"'<>\\\\]*)?",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { out += SourceItem(it.value, "HLS") }

        // genel .m3u8
        Regex("https?://[^\\s\"'<>\\\\]+\\.m3u8(?:\\?[^\\s\"'<>\\\\]*)?", RegexOption.IGNORE_CASE)
            .findAll(body).forEach { out += SourceItem(it.value, "HLS") }

        return out
    }

    // ------------------------------------------------------------ altyazılar

    private fun langLabel(label: String, fallback: String): String {
        val l = label.trim().lowercase().ifBlank { fallback.trim().lowercase() }
        return when {
            Regex("(^|[^a-z])(tr|tur|turkce|türkçe|turkish)([^a-z]|$)").containsMatchIn(l) -> "Türkçe"
            Regex("(^|[^a-z])(en|eng|english|ingilizce)([^a-z]|$)").containsMatchIn(l) -> "English"
            l.isBlank() || l == "default" -> "Altyazı"
            else -> label.trim().ifBlank { "Altyazı" }
        }
    }

    private fun absUrl(base: String, raw: String): String {
        val s = raw.trim().trim('"', '\'')
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> base.trimEnd('/') + s
            else -> "${base.trimEnd('/')}/$s"
        }
    }

    private fun collectSubtitles(text: String, base: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>() // url -> label
        val body = clean(text).replace("\\", "")

        // tracks:[{file:"..vtt", label:"Türkçe", kind:"captions"}]
        Regex("[\"']?tracks[\"']?\\s*:\\s*\\[([\\s\\S]*?)\\]", RegexOption.IGNORE_CASE).findAll(body).forEach { tr ->
            Regex("\\{([^{}]*)\\}").findAll(tr.groupValues[1]).forEach { obj ->
                val o = obj.groupValues[1]
                val kind = Regex("[\"']?kind[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(o)?.groupValues?.get(1).orEmpty()
                if (kind.equals("chapters", true) || kind.equals("thumbnails", true)) return@forEach
                val file = Regex("[\"']?(?:file|src)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(o)?.groupValues?.get(1) ?: return@forEach
                if (file.contains(".jpg", true)) return@forEach
                val label = Regex("[\"']?label[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(o)?.groupValues?.get(1).orEmpty()
                out.putIfAbsent(absUrl(base, file), langLabel(label, kind))
            }
        }

        // <track src=... kind=... label=...>
        Jsoup.parse(body, base).select("track[src], track[data-src]").forEach { t ->
            val kind = t.attr("kind")
            if (kind.equals("chapters", true) || kind.equals("thumbnails", true)) return@forEach
            val src = t.attr("src").ifBlank { t.attr("data-src") }
            out.putIfAbsent(absUrl(base, src), langLabel(t.attr("label"), t.attr("srclang")))
        }

        // subtitle: "https://..."
        Regex(
            "[\"']?(?:subtitles?|sub|captions?|altyazi)[\"']?\\s*[:=]\\s*[\"'](https?://[^\"']+|/[^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { out.putIfAbsent(absUrl(base, it.groupValues[1]), "Altyazı") }

        // çıplak .vtt/.srt/.ass adresleri
        Regex("https?://[^\\s\"'<>]+?\\.(?:vtt|srt|ass)(?:\\?[^\\s\"'<>]*)?", RegexOption.IGNORE_CASE)
            .findAll(body).forEach { out.putIfAbsent(it.value, "Altyazı") }

        return out
    }

    // ------------------------------------------------- Dean Edwards p,a,c,k,e,d

    private fun baseN(num: Int, radix: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(digits[n % radix])
            n /= radix
        }
        return sb.reverse().toString()
    }

    private fun unpackDeanEdwards(text: String): String? {
        val rx = Regex(
            "eval\\s*\\(\\s*function\\s*\\(\\s*p\\s*,\\s*a\\s*,\\s*c\\s*,\\s*k\\s*,\\s*e\\s*,\\s*[rd]\\s*\\)" +
                "\\s*\\{[\\s\\S]+?\\}\\s*\\(\\s*(['\"])([\\s\\S]*?)\\1\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*," +
                "\\s*(['\"])([\\s\\S]*?)\\5\\.split\\s*\\(\\s*['\"]\\|['\"]\\s*\\)",
            RegexOption.IGNORE_CASE
        )
        val m = rx.find(text) ?: return null

        fun unesc(s: String) = s
            .replace("\\x27", "'").replace("\\x22", "\"")
            .replace("\\\"", "\"").replace("\\'", "'")
            .replace("\\\\", "\\")

        var payload = unesc(m.groupValues[2])
        val radix = m.groupValues[3].toIntOrNull() ?: return null
        val count = m.groupValues[4].toIntOrNull() ?: return null
        val dict = unesc(m.groupValues[6]).split("|")

        for (i in count - 1 downTo 0) {
            val word = dict.getOrNull(i) ?: continue
            if (word.isBlank()) continue
            payload = Regex("\\b" + Regex.escape(baseN(i, radix)) + "\\b").replace(payload, word)
        }
        return payload
    }

    // ------------------------------------------------------------------ getUrl

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer?.takeIf { it.isNotBlank() } ?: "https://dizipod.com/"
        val pageHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to ref,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        )
        Log.d(TAG, "PLAYER GET $url")

        val res = app.get(url, headers = pageHeaders)
        val html = res.text
        Log.d(TAG, "PLAYER HTTP url=$url bytes=${html.length}")

        val sources = LinkedHashSet<SourceItem>()
        val subs = LinkedHashMap<String, String>()

        sources += collectSources(html)
        collectSubtitles(html, url).forEach { (k, v) -> subs.putIfAbsent(k, v) }

        // packed JS
        if (sources.isEmpty()) {
            val unpacked = unpackDeanEdwards(html)
            if (unpacked != null) {
                Log.d(TAG, "PACKER UNPACK OK bytes=${unpacked.length}")
                sources += collectSources(unpacked)
                collectSubtitles(unpacked, url).forEach { (k, v) -> subs.putIfAbsent(k, v) }
            } else {
                Log.d(TAG, "PACKER NOT FOUND")
            }
        }

        // harici script dosyaları
        if (sources.isEmpty()) {
            val doc = Jsoup.parse(html, url)
            for (s in doc.select("script[src]")) {
                val raw = s.attr("src")
                val abs = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    else -> mainUrl.trimEnd('/') + "/" + raw.trimStart('/')
                }
                val js = runCatching {
                    app.get(abs, headers = mapOf("User-Agent" to ua, "Referer" to url, "Accept" to "*/*")).text
                }.getOrNull() ?: continue
                sources += collectSources(js)
                collectSubtitles(js, url).forEach { (k, v) -> subs.putIfAbsent(k, v) }
                if (sources.isNotEmpty()) break
            }
        }

        Log.d(TAG, "PLAYER candidates=${sources.size} subtitles=${subs.size}")

        // altyazılar
        val usedLabels = HashMap<String, Int>()
        subs.forEach { (subUrl, label) ->
            val n = (usedLabels[label] ?: 0) + 1
            usedLabels[label] = n
            subtitleCallback(newSubtitleFile(if (n > 1) "$label $n" else label, subUrl))
        }

        if (sources.isEmpty()) {
            Log.d(TAG, "PLAYER NO_HLS head=${html.take(200).replace("\n", " ")}")
            return
        }

        val streamHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to url,
            "Origin" to mainUrl
        )

        for (src in sources) {
            val link = src.url.replace("\\", "")
            if (!link.startsWith("http") || !link.contains(".m3u8")) continue
            Log.d(TAG, "HLS CANDIDATE label=${src.label} url=$link")

            // gerçekten m3u8 mi?
            val ok = runCatching {
                val probe = app.get(link, headers = streamHeaders)
                val ct = probe.headers["content-type"].orEmpty()
                probe.text.trimStart().startsWith("#EXTM3U") ||
                    ct.contains("mpegurl", true) || ct.contains("m3u", true)
            }.getOrElse {
                Log.e(TAG, "HLS PROBE ERROR ${it.message}")
                false
            }
            if (!ok) {
                Log.d(TAG, "HLS REJECT url=$link")
                continue
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name ${src.label}".trim(),
                    url = link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = qualityFromLabel(src.label)
                    this.headers = streamHeaders
                }
            )
        }
    }
}

// ---------------- TMDB yardimci siniflar ----------------
class TmdbInfo(
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val year: Int?,
    val tags: List<String>?,
    val episodes: List<Episode>?
)

data class TmdbGenre(val name: String? = null)

data class TmdbTvResult(
    val id: Int? = null,
    val name: String? = null,
    val original_name: String? = null,
    val poster_path: String? = null,
    val popularity: Double? = null
)

data class TmdbTvSearch(val results: List<TmdbTvResult>? = null)

data class TmdbMovieResult(
    val id: Int? = null,
    val title: String? = null,
    val original_title: String? = null,
    val release_date: String? = null,
    val poster_path: String? = null,
    val popularity: Double? = null
)

data class TmdbMovieSearch(val results: List<TmdbMovieResult>? = null)

data class TmdbSeasonInfo(
    val season_number: Int? = null,
    val episode_count: Int? = null
)

data class TmdbTvDetails(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val popularity: Double? = null,
    val genres: List<TmdbGenre>? = null,
    val seasons: List<TmdbSeasonInfo>? = null
)

data class TmdbMovieDetails(
    val id: Int? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val genres: List<TmdbGenre>? = null
)

data class TmdbEpisode(
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val runtime: Int? = null,
    val air_date: String? = null
)

data class TmdbSeasonDetails(val episodes: List<TmdbEpisode>? = null)
