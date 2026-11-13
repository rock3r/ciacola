@file:Suppress("UnstableApiUsage")

// Modified from Storyboard upstream to include the talk-specific :deck module.

pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven(url = uri("https://www.jetbrains.com/intellij-repository/releases"))
        maven(url = uri("https://repo.eclipse.org/content/repositories/tm4e-snapshots/")) {
            mavenContent {
                includeGroupAndSubgroups("org.eclipse")
            }
        }
    }
}

rootProject.name = "ciacola"

include(":storyboard")
include(":storyboard-easel")
include(":storyboard-layout")
include(":storyboard-text")
include(":storyboard-warp")
include(":deck-core")
include(":deck-effects")
include(":deck-presenter")
include(":deck-review")
include(":deck-timeline")
include(":deck-inspector")
include(":deck")

include(":examples:basic")
include(":examples:diagram")
include(":examples:interactive")
include(":examples:shared")
include(":examples:teleprompter")
