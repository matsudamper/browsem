plugins {
    id("browser-android-library-convention")
}

android {
    namespace = "net.matsudamper.browser.feature.addressautofill"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.mozilla.geckoview)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":data"))

    testImplementation(libs.junit4)
}
