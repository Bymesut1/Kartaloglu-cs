// Sürüm numarası: her güncellemede 1 artır
version = 1

cloudstream {
    description = "DiziLife - film ve dizi (Türkçe dublaj / altyazı)"
    authors = listOf("Bymesut1")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta only
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=dizi82.life&sz=%size%"
}

android {
    namespace = "com.bymesut1.dizilife"
}

dependencies {
    // withTimeoutOrNull için gerekli (DiziLife.kt: import kotlinx.coroutines.withTimeoutOrNull)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
