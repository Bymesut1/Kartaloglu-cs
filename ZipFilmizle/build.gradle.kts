version = 1

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "ZipFilmizle - Türkçe dublaj ve altyazılı film izle"
    authors = listOf("Bymesut1")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta-only
    status = 1

    tvTypes = listOf("Movie")

    requiresResources = false
    iconUrl = "https://www.google.com/s2/favicons?domain=zipfilmizle.org&sz=%size%"
}
