version = 4

android {
    namespace = "com.bymesut1"
}

cloudstream {
    authors     = listOf("Bymesut1")
    language    = "tr"
    description = "SetFilmIzle ile en yeni film ve dizileri Türkçe dublaj ve altyazılı, Full HD kalitede izleyin."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.setfilmizle.ltd&sz=%size%"
}
