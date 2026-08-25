plugins {
    id("browser-android-compose-library-convention")
}

android {
    namespace = "net.matsudamper.browser.ui.downloads"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
