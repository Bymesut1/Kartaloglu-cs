// Her yayında bu sayıyı 1 artır (eklenti güncellemesi böyle algılanır)
version = 1

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"

    description = "DiziCehennemi: dizi, film ve anime izle (Türkçe dublaj / altyazı)"
    authors = listOf("Bymesut1")

    /**
     * Durum:
     * 0: Çalışmıyor
     * 1: Çalışıyor
     * 2: Yavaş
     * 3: Sadece beta
     **/
    status = 1

    tvTypes = listOf("TvSeries", "Movie", "Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=dizicehennemi.click&sz=%size%"
}
