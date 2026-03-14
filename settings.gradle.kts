pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
// }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.mozilla.org/maven2")
        }
    }
}

rootProject.name = "browser"
include(":app")
include(":browser-core")
include(":browser-engine-gecko")
include(":data")
include(":feature-browser")
include(":feature-tabs")
include(":proto")
