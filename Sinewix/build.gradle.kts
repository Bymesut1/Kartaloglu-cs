import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Her güncellemede 1 artır
version = 18

android {
    namespace = "com.bymesut1"

    // CloudStream kütüphanesi JVM 11 ile derli (parseJson için gerekli)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // @JsonProperty için (sadece bu modülde)
    add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}

cloudstream {
    description = "Sinewix - dizi ve film"
    authors = listOf("Bymesut1")

    // 0: kapalı, 1: çalışıyor, 2: yavaş, 3: beta
    status = 1

    tvTypes = listOf("TvSeries", "Movie", "Anime", "AsianDrama", "Cartoon")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=sinewix.com&sz=%size%"
}
