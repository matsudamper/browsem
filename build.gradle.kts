import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.paparazzi) apply false
}

val robolectricPropertiesFile = layout.projectDirectory.file("robolectric.properties")
val robolectricResourcesDir = layout.buildDirectory.dir("robolectric-test-resources").get().asFile

tasks.register<Sync>("syncRobolectricProperties") {
    onlyIf { robolectricPropertiesFile.asFile.exists() }
    from(robolectricPropertiesFile)
    into(robolectricResourcesDir)
}

/**
 * リポジトリルートの [robolectric.properties] を各 Android モジュールの test リソースとして参照する。
 * Robolectric はモジュール内の src/test/resources しか読まないため、Gradle で参照を橋渡しする。
 */
fun Project.wireRobolectricPropertiesFromRoot() {
    if (!robolectricPropertiesFile.asFile.exists()) return

    extensions.configure<LibraryExtension>("android") {
        sourceSets.named("test") {
            resources.srcDir(robolectricResourcesDir)
        }
    }

    tasks.matching {
        it.name == "processDebugUnitTestJavaRes" || it.name == "processReleaseUnitTestJavaRes"
    }.configureEach {
        dependsOn(rootProject.tasks.named("syncRobolectricProperties"))
    }
    tasks.withType<Test>().configureEach {
        dependsOn(rootProject.tasks.named("syncRobolectricProperties"))
    }
}

fun Project.wireRobolectricPropertiesFromRootForApp() {
    if (!robolectricPropertiesFile.asFile.exists()) return

    extensions.configure<ApplicationExtension>("android") {
        sourceSets.named("test") {
            resources.srcDir(robolectricResourcesDir)
        }
    }

    tasks.matching {
        it.name == "processDebugUnitTestJavaRes" || it.name == "processReleaseUnitTestJavaRes"
    }.configureEach {
        dependsOn(rootProject.tasks.named("syncRobolectricProperties"))
    }
    tasks.withType<Test>().configureEach {
        dependsOn(rootProject.tasks.named("syncRobolectricProperties"))
    }
}

subprojects {
    pluginManager.withPlugin("com.android.library") {
        wireRobolectricPropertiesFromRoot()
    }
    pluginManager.withPlugin("com.android.application") {
        wireRobolectricPropertiesFromRootForApp()
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed", "skipped")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
