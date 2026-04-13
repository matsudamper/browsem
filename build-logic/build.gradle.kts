plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugins.detekt.get().let { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
}

gradlePlugin {
    plugins {
        register("detektCompose") {
            id = "browsem.detekt-compose"
            implementationClass = "DetektComposeConventionPlugin"
        }
    }
}
