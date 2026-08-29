@file:Suppress("UnstableApiUsage")

// Modified from Storyboard upstream to include the talk-specific :deck module.

pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven(url = uri("https://repo.eclipse.org/content/repositories/tm4e-snapshots/")) {
            mavenContent {
                includeGroupAndSubgroups("org.eclipse")
            }
        }
    }
}

rootProject.name = "storyboard-root"

include(":storyboard")
include(":storyboard-easel")
include(":storyboard-layout")
include(":storyboard-text")
include(":storyboard-warp")
include(":deck")

include(":examples:basic")
include(":examples:diagram")
include(":examples:interactive")
include(":examples:shared")
include(":examples:teleprompter")
