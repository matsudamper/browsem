plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("detekt-compose-convention")
}

android {
    namespace = "net.matsudamper.browser.ui.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(24)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(project(":data"))

    testImplementation(libs.junit4)
}
