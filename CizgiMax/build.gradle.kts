// use an integer for version numbers
version = 1

android {
    namespace = "com.Bymesut1"
}

cloudstream {
    language = "tr"
    description = "ÇizgiMax - Türkçe dublajlı çizgi film ve anime. TMDB Türkçe bilgiler, bölüm açıklamaları ve görselleri."
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries", "Cartoon", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=cizgimax.online&sz=%size%"
}
