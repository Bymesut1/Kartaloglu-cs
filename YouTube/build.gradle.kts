version = 27

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "YouTube - video ara ve izle"
    authors = listOf("Bymesut1")

    /**
     * Status int:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta only
     */
    status = 1

    tvTypes = listOf("Others")

    iconUrl = "https://www.google.com/s2/favicons?domain=youtube.com&sz=%size%"
}
