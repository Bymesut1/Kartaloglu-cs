version = 1

android {
    namespace = "com.hdfilmcehennemi"
}

cloudstream {
    language = "tr"
    description = "TMDB (Türkçe) verileriyle HDFilmCehennemi film ve dizi eklentisi"
    authors = listOf("Ben")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false

    iconUrl = "https://www.google.com/s2/favicons?domain=hdfilmcehennemi.nl&sz=%size%"
}
