pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

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
include(":feature-address-autofill")
include(":feature-form-input-autofill")
include(":feature-browser")
include(":feature-dev-tools")
include(":feature-find-in-page")
include(":feature-media")
include(":feature-mock-location")
include(":feature-network-log")
include(":feature-readability")
include(":feature-tabs")
include(":feature-theme-color")
include(":feature-twitter-share")
include(":feature-viewport-scale")
include(":proto")
include(":resources")
include(":ui:common")
include(":ui:browser")
include(":ui:tabs")
include(":ui:settings")
include(":ui:history")
include(":ui:extensions")
include(":ui:downloads")
