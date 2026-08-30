plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.compose.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("browserAndroidLibrary") {
            id = "browser-android-library-convention"
            implementationClass = "net.matsudamper.browser.buildlogic.BrowserAndroidLibraryConventionPlugin"
        }
        register("browserAndroidComposeLibrary") {
            id = "browser-android-compose-library-convention"
            implementationClass = "net.matsudamper.browser.buildlogic.BrowserAndroidComposeLibraryConventionPlugin"
        }
        register("browserJvmLibrary") {
            id = "browser-jvm-library-convention"
            implementationClass = "net.matsudamper.browser.buildlogic.BrowserJvmLibraryConventionPlugin"
        }
        register("ktlintConvention") {
            id = "ktlint-convention"
            implementationClass = "net.matsudamper.browser.buildlogic.KtlintConventionPlugin"
        }
    }
}
