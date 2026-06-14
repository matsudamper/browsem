plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.matsudamper.browser.feature.tabs"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(project(":browser-core"))
    implementation(project(":browser-engine-gecko"))
    implementation(project(":data"))
    implementation(project(":ui:tabs"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
