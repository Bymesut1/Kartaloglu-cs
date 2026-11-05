// Her güncellemede bu sayıyı 1 artır
version = 2

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "FilmizleHell - Türkçe film izle"
    authors = listOf("Bymesut1")

    // 0: Kapalı, 1: Çalışıyor, 2: Yavaş, 3: Sadece beta
    status = 1
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=filmizlehell.net&sz=%size%"
}

dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")
}
