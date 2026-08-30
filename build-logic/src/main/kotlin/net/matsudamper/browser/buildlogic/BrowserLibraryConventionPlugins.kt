package net.matsudamper.browser.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Android ライブラリモジュール共通のビルド設定を適用する convention plugin。
 */
class BrowserAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = COMPILE_SDK
                compileSdkMinor = COMPILE_SDK_MINOR
                defaultConfig {
                    minSdk = MIN_SDK
                }
                compileOptions {
                    sourceCompatibility = JAVA_VERSION
                    targetCompatibility = JAVA_VERSION
                }
            }

            configureKotlinJvmTarget()
        }
    }

    internal companion object {
        const val COMPILE_SDK = 37
        const val COMPILE_SDK_MINOR = 1
        const val MIN_SDK = 30
        val JAVA_VERSION = JavaVersion.VERSION_21
        val JVM_TARGET = JvmTarget.JVM_21
    }
}

/**
 * Compose を使う Android ライブラリモジュール共通のビルド設定を適用する convention plugin。
 */
class BrowserAndroidComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("browser-android-library-convention")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("detekt-compose-convention")

            extensions.configure<LibraryExtension> {
                buildFeatures {
                    compose = true
                }
            }

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies {
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            }
        }
    }
}

/**
 * JVM ライブラリモジュール共通のビルド設定を適用する convention plugin。
 */
class BrowserJvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = BrowserAndroidLibraryConventionPlugin.JAVA_VERSION
                targetCompatibility = BrowserAndroidLibraryConventionPlugin.JAVA_VERSION
            }

            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions {
                    jvmTarget.set(BrowserAndroidLibraryConventionPlugin.JVM_TARGET)
                }
            }
        }
    }
}

private fun Project.configureKotlinJvmTarget() {
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(BrowserAndroidLibraryConventionPlugin.JVM_TARGET)
            }
        }
    }

    // AGP 9 では kotlin.android が明示適用されない場合があるため、KotlinCompile タスクでも設定する
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(BrowserAndroidLibraryConventionPlugin.JVM_TARGET)
        }
    }
}
