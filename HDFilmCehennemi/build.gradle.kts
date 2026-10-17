// use an integer for version numbers
version = 1

android {
    namespace = "com.HDFilmCehennemi"
}

cloudstream {
    language = "tr"
    description = "HDFilmCehennemi - TMDB tabanlı film ve dizi eklentisi"
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=hdfilmcehennemi.nl&sz=%size%"
}
