plugins {
    id("dev.detekt")
}

detekt {
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt-compose.yml"))
    buildUponDefaultConfig = false
}

dependencies {
    "detektPlugins"(versionCatalogs.named("libs").findLibrary("detekt-compose-rules").get())
}
