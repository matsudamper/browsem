import com.github.burrunan.s3cache.AwsS3BuildCache

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.github.burrunan.s3-build-cache") version "1.9.11"
}

val remoteBuildCacheAccessKeyId: String? = System.getenv("S3_BUILD_CACHE_ACCESS_KEY_ID")
buildCache {
    remote<AwsS3BuildCache> {
        isEnabled = !remoteBuildCacheAccessKeyId.isNullOrBlank()
        isPush = System.getenv("CI") == "true"
        endpoint = System.getenv("S3_BUILD_CACHE_ENDPOINT").orEmpty().ifBlank { "s3.matsudamper.net" }
        forcePathStyle = true
        region = "us-east-1"
        bucket = "gradle-remote-cache"
        prefix = "matsudamper/browsem/"
        // 自前の S3 互換サーバーなので AWS 固有のストレージクラスを指定しない
        isReducedRedundancy = false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            url = uri("https://maven.mozilla.org/maven2")
        }
        mavenCentral()
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
include(":feature-webauthn-compat")
include(":feature-web-share-files")
include(":feature-translator-api")
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
