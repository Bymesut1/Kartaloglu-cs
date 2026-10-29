// Sürüm numarası: her güncellemede 1 artır
version = 1

cloudstream {
    description = "Selcukflix - yabancı dizi, film, anime ve kdrama (Türkçe altyazılı / dublajlı)"
    authors = listOf("Bymesut1")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta only
    status = 1
    tvTypes = listOf("TvSeries", "Movie", "Anime")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=selcukflix.app&sz=%size%"
}

android {
    namespace = "com.bymesut1.selcukflix"
}
