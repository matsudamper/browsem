plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.matsudamper.browser.resources"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }
}
