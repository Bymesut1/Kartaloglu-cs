// use an integer for version numbers
version = 2

cloudstream {
    description = "DiziPod - dizi ve film izle (TMDB bilgili)"
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1 // will be 3 if unspecified

    tvTypes = listOf("TvSeries", "Movie")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=dizipod.com&sz=%size%"
}

android {
    namespace = "com.bymesut1"
}
