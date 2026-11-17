import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DhakaFlix Anime"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "14"

    source {
        lang = "all"
        baseUrl = "http://172.16.50.10"
    }
}
