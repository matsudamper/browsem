plugins {
    id("browser-android-compose-library-convention")
}

android {
    namespace = "net.matsudamper.browser.ui.settings"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(project(":data"))
    implementation(project(":resources"))
}
