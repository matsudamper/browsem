plugins {
    id("browser-android-library-convention")
}

android {
    namespace = "net.matsudamper.browser.feature.browser"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(project(":browser-engine-gecko"))
    implementation(project(":data"))
    implementation(project(":ui:browser"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mozilla.geckoview)
}
