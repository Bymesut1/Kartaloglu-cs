version = 1

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "RecTV - film, dizi ve canlı TV"
    authors = listOf("Bymesut1")

    /**
     * Status int:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Live")
}
