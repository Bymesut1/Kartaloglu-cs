// Sürüm numarası: her güncellemede 1 artır
version = 1

cloudstream {
    description = "RecTV - film, dizi ve canlı TV"
    authors = listOf("Bymesut1")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta only
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Live")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=prectv71.lol&sz=%size%"
}

android {
    namespace = "com.bymesut1.rectv"
}
