version = 7

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "SeyirTURK - Dizi, Film ve IPTV (TMDB bilgileriyle)"
    authors = listOf("bymesut1")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Live")
    requiresResources = false
    iconUrl = "https://www.google.com/s2/favicons?domain=seyirturk.net&sz=%size%"
}
