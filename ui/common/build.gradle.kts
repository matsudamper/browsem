plugins {
    id("browser-android-compose-library-convention")
}

android {
    namespace = "net.matsudamper.browser.ui.common"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(project(":data"))

    testImplementation(libs.junit4)
}
