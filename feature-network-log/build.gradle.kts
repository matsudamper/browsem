plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.matsudamper.browser.feature.networklog"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

dependencies {
    implementation(libs.mozilla.geckoview)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}
