plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "net.matsudamper.browser.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Room がエクスポートしたスキーマ JSON を androidTest のアセットに含め、
    // MigrationTestHelper から読めるようにする (Room 公式のマイグレーションテスト手順)
    sourceSets {
        named("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                maybeCreate("pixel6Api34").apply {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                    require64Bit = true
                    testedAbi = "x86_64"
                }
            }
            groups {
                maybeCreate("gmd").apply {
                    targetDevices.add(localDevices["pixel6Api34"])
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

ksp {
    // マイグレーション検証用にスキーマ JSON をエクスポートする
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":proto"))
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
