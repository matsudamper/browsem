plugins {
    id("browser-android-library-convention")
}

android {
    namespace = "net.matsudamper.browser.feature.forminputautofill"

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
    implementation(project(":feature-address-autofill"))

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
