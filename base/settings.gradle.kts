pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "Myspoti"
include(":app")
include(":api")
include(":catalog")
include(":automix")
include(":extensions")
include(":extensions-cli")
include(":api-spotify")
include(":host-cdp")
include(":host-android")
include(":api-web")
include(":api-ytmusic")
include(":engine")
include(":engine-android")
include(":wear")
include(":pass")
