package net.matsudamper.browser.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * ktlint Gradle プラグインの適用と ktlint 本体のバージョン指定を行う convention plugin。
 */
class KtlintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")

            val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
            extensions.configure<KtlintExtension> {
                version.set(libs.findVersion("ktlint").get().requiredVersion)
                filter {
                    verbose.set(true)
                    exclude { it.file.path.contains("generated") }
                }
            }
        }
    }
}
