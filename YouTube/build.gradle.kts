// Sürüm numarasını her güncellemede 1 artır
version = 46

android {
    namespace = "com.bymesut1"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

cloudstream {
    language = "tr"
    description = "YouTube, izlediğiniz videolarla önerilerini iyileştirir. İlginizi çekebilecek içeriklerle dolu bir akış oluşturmak için video izlemeye başlayın."
    authors = listOf("Bymesut1")

    /**
     * Status int:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta only
     */
    status = 1

    tvTypes = listOf("Others")

    iconUrl = "https://www.google.com/s2/favicons?domain=youtube.com&sz=%size%"
}
