// Sürüm numarasını her güncellemede 1 artır
version = 9

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "Dizilla - Yabancı dizi, yerli dizi, anime ve Asya dizileri izle"
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1

    tvTypes = listOf("TvSeries", "Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=dizilla.now&sz=%size%"
}
