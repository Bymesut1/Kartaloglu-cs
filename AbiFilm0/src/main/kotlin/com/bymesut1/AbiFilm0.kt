package com.bymesut1

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AbiFilm0 : MainAPI() {
    override var mainUrl = "https://www.wfilmizle.cam"
    override var name = "AbiFilm0"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Son Eklenenler",
        "$mainUrl/yerli-filmler/" to "Yerli Filmler",
        "$mainUrl/turkce-dublaj-filmler/" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili-filmler/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/imdb-en-iyiler/" to "IMDb En İyiler",
        "$mainUrl/en-cok-izlenen-filmler/" to "En Çok İzlenen Filmler",
        "$mainUrl/en-cok-begenilen-filmler/" to "En Çok Beğenilen Filmler",
        "$mainUrl/seri-filmler/" to "Seri Filmler",
        "turler" to "Film Türleri"
    )

    private val genreNames = listOf(
        "Aile", "Aksiyon", "Animasyon", "Belgesel", "Bilim Kurgu", "Biyografi", "Dram",
        "Efsane", "Fantastik", "Gerilim", "Gizem", "Hint", "Komedi", "Korku", "Macera",
        "Müzik", "Müzikal", "Netflix", "Polisiye", "Politik", "Romantik", "Savaş", "Spor",
        "Suç", "Tarih", "TV film", "Vahşi Batı", "Vizyondaki"
    )

    private fun normName(s: String) =
        s.replace(Regex("""[\s(]*\d+[)\s]*$"""), "").trim()
            .lowercase(java.util.Locale("tr", "TR"))

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "turler") {
            if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

            val home = app.get(mainUrl, headers = mapOf("User-Agent" to ua)).document
