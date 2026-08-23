plugins {
    id("com.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "net.matsudamper.browser.data"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Room がエクスポートしたスキーマ JSON を Robolectric 単体テストのアセットに含め、
    // MigrationTestHelper から読めるようにする (Room 公式のマイグレーションテスト手順)
    sourceSets {
        named("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            // Robolectric から merge 済みアセット (スキーマ JSON) を参照できるようにする
            isIncludeAndroidResources = true
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
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
}
