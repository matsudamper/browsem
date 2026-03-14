plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.matsudamper.browser.feature.tabs"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(project(":browser-core"))
    implementation(project(":browser-engine-gecko"))
    implementation(project(":data"))

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
