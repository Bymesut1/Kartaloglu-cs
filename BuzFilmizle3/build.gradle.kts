// BuzFilmizle3 sürümünü her güncellemede artır
version = 6

android {
    namespace = "com.bymesut1"
}

cloudstream {
    description = "buzfilmizle3.com - Full HD film izle (Vidmoly, Bronz, FASTM)"
    authors = listOf("Bymesut1")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Movie")

    requiresResources = false
    language = "tr"

    iconUrl = "https://www.buzfilmizle3.com/wp-content/uploads/cropped-unnamed-192x192.png"
}
