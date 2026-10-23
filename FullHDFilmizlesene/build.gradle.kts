// use an integer for version numbers
version = 2

cloudstream {
    description = "fullhdfilmizlesene.now - Full HD film izle (Türkçe dublaj / altyazılı)"
    authors = listOf("bymesut1")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=fullhdfilmizlesene.now&sz=%size%"
}

android {
    namespace = "com.bymesut1.fullhdfilmizlesene"
}
