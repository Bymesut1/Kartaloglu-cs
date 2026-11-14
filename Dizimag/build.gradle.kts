// use an integer for version numbers
version = 1

android {
    namespace = "com.bymesut1"
}

cloudstream {
    description = "Dizimag - Yabancı dizi, film ve anime izle"
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries", "Movie", "Anime")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=ddizimag.icu&sz=%size%"
}
