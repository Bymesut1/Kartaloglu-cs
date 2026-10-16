// use an integer for version numbers
version = 2

android {
    namespace = "com.bymesut1.sezonlukdizi"
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}

cloudstream {
    language    = "tr"
    description = "SezonlukDizi - Türkçe dizi izle"
    authors     = listOf("bymesut1")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
    **/
    status  = 1
    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=sezonlukdizi.cc&sz=%size%"
}
