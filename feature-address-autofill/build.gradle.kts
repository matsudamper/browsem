plugins {
    id("browser-android-library-convention")
}

android {
    namespace = "net.matsudamper.browser.feature.addressautofill"

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.mozilla.geckoview)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":data"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
