plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.paparazzi)
}

val ciDebugKeystorePath = System.getenv("DEBUG_KEYSTORE_PATH")
val ciDebugKeystoreFile = ciDebugKeystorePath?.let { file(it) }
val useCiDebugKeystore = ciDebugKeystoreFile != null && ciDebugKeystoreFile.exists()

android {
    namespace = "net.matsudamper.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.matsudamper.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (useCiDebugKeystore) {
            create("debugCi") {
                storeFile = ciDebugKeystoreFile
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            if (useCiDebugKeystore) {
                signingConfig = signingConfigs.getByName("debugCi")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useCiDebugKeystore) {
                signingConfig = signingConfigs.getByName("debugCi")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

tasks.withType<Test>().configureEach {
    val hasPaparazziTask = gradle.startParameter.taskNames.any {
        it.lowercase().contains("paparazzi")
    }
    useJUnit {
        if (hasPaparazziTask) {
            includeCategories("net.matsudamper.browser.PaparazziTestCategory")
        } else {
            excludeCategories("net.matsudamper.browser.PaparazziTestCategory")
        }
    }
    systemProperty("paparazzi.filter", System.getProperty("paparazzi.filter", ""))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.mozilla.geckoview)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(project(":data"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.composable.preview.scanner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
