pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
include(":feature-browser")
include(":feature-tabs")
include(":proto")
