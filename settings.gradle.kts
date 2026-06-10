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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Shizuku artifacts (dev.rikka.*) are published here.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("dev\\.rikka.*") }
        }
    }
}

rootProject.name = "portage"

include(":app-send")
include(":app-recv")
include(":core-model")
include(":core-transport")
include(":providers")
include(":privileged")
include(":settings-catalog")
