plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.matsudamper.browser.resources"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 30
    }
}
