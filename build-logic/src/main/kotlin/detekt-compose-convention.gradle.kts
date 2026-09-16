import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask

plugins {
    id("dev.detekt")
}

detekt {
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt-compose.yml"))
    buildUponDefaultConfig = false
}

// バリアントごとに別ファイルを持たず、モジュール 1 つに 1 つの baseline を共有する
val detektBaselineFile = file("detekt-baseline.xml")

tasks.withType<Detekt>().configureEach {
    baseline.set(detektBaselineFile)
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    baseline.set(detektBaselineFile)
}

dependencies {
    "detektPlugins"(versionCatalogs.named("libs").findLibrary("detekt-compose-rules").get())
}
