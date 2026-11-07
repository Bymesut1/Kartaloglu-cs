version = 4

cloudstream {
    description = "FilmEkseni - Türkçe film ve dizi izle"
    authors = listOf("bymesut1")

    /**
     * Status int:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=filmekseni.vip&sz=%size%"
}

android {
    namespace = "com.bymesut1"
}
