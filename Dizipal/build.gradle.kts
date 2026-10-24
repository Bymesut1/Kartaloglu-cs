// Sürüm numarasını her değişiklikte 1 artır (CloudStream güncellemeyi bu sayıdan anlar)
version = 8

cloudstream {
    language = "tr"
    description = "Dizipal - yabancı dizi ve film izle (dizipal2142.com)"
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=dizipal2142.com&sz=%size%"
}

android {
    namespace = "com.bymesut1"
}
