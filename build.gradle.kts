import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

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

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        the<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>().jvmToolchain(24)
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        the<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>().jvmToolchain(24)
    }

    tasks.withType<Test>().configureEach {
        val javaToolchainService = project.extensions.getByType<JavaToolchainService>()
        javaLauncher.set(javaToolchainService.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(24))
        })
        testLogging {
            events("failed", "skipped")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
