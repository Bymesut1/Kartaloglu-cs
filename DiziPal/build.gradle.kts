// use an integer for version numbers
version = 6

cloudstream {
    description = "DiziPal - Dizi ve film izle (Türkçe dublaj / altyazılı)"
    authors = listOf("bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=dizipal1592.com&sz=%size%"
}

android {
    namespace = "com.bymesut1.DiziPal"
}

dependencies {
    // withTimeoutOrNull için gerekli (DiziPal.kt: import kotlinx.coroutines.withTimeoutOrNull)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
