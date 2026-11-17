// use an integer for version numbers
version = 1

cloudstream {
    language = "tr"
    description = "TekFullFilmizle - Türkçe dublaj film izle (Platin, Gold, Silver kaynakları)"
    authors = listOf("bymesut1")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1
    tvTypes = listOf("Movie")
    iconUrl = "https://www.tekfullfilmizle6.com/wp-content/themes/keremiya/images/icon/favicon-32x32.png"
}

android {
    namespace = "com.bymesut1"
}
