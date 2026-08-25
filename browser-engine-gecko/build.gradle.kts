plugins {
    id("browser-android-compose-library-convention")
}

android {
    namespace = "net.matsudamper.browser.engine"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.mozilla.geckoview)
    api(project(":browser-core"))
    implementation(project(":data"))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
