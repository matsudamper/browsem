plugins {
    id("browser-android-library-convention")
}

android {
    namespace = "net.matsudamper.browser.feature.tabs"

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
