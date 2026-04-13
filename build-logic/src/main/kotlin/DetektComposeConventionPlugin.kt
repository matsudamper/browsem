import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

class DetektComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")

            extensions.configure<DetektExtension> {
                // デフォルトのdetektルールを全て無効化し、compose rulesのみ使用する
                buildUponDefaultConfig = false
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            }

            dependencies {
                val composeRulesDetekt = libs.findLibrary("compose-rules-detekt").orElseThrow {
                    NoSuchElementException("version catalogに 'compose-rules-detekt' ライブラリが見つかりません")
                }
                "detektPlugins"(composeRulesDetekt)
            }

            tasks.withType<Detekt>().configureEach {
                reports {
                    html.required.set(true)
                    xml.required.set(false)
                    txt.required.set(false)
                    sarif.required.set(false)
                    md.required.set(false)
                }
            }
        }
    }
}

// version catalog にアクセスするためのヘルパー
private val Project.libs
    get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
        .named("libs")
