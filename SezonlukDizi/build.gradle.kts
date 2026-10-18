// Sürüm numarası: her güncellemede 1 artır
version = 15

android {
    namespace = "com.bymesut1.sezonlukdizi"
}

cloudstream {
    description = "SezonlukDizi - yabancı dizi izle (altyazılı / dublaj)"
    language = "tr"
    authors = listOf("Bymesut1")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=sezonlukdizi.cc&sz=%size%"
}
