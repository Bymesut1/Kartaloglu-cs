// use an integer for version numbers
version = 1

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "HDFilmCehennemi (hdfilmcehennemi.land) - film ve dizi izle"
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    requiresResources = false

    iconUrl = "https://www.google.com/s2/favicons?domain=hdfilmcehennemi.land&sz=%size%"
}
