// Sürüm numarası: her güncellemede 1 artır
version = 2

android {
    namespace = "com.bymesut1"
}

cloudstream {
    description = "JetFilmizle - Türkçe dublaj ve altyazılı film izle"
    authors = listOf("Bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=jetfilmizle.now&sz=%size%"
}
