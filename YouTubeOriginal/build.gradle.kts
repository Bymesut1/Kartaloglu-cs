version = 1

android {
    namespace = "com.bymesut1"
}

cloudstream {
    language = "tr"
    description = "NewPipe altyapısıyla YouTube: ana sayfa (Canlı dahil), arama, video ve canlı yayın"
    authors = listOf("Bymesut1")
    status = 1
    tvTypes = listOf("Others", "Live")
    requiresResources = false
    iconUrl = "https://www.google.com/s2/favicons?domain=youtube.com&sz=128"
}

dependencies {
    // Çalışma anında CloudStream'in içindeki NewPipeExtractor kullanılır
    compileOnly("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
}
