// Sürüm numarasını her güncellemede 1 artır
version = 2

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "Zdizi: yabancı dizi ve film izle (Türkçe altyazı / dublaj)"
    authors = listOf("Bymesut1")

    /**
     * Status int:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta only
     */
    status = 1

    tvTypes = listOf("TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=zdizi.com&sz=%size%"
}
