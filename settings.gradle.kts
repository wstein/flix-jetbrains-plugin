import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
    plugins {
        // The rpc plugin's version is pinned to the Kotlin compiler build it was assembled
        // against -- ABI-compatible only within the same x.y.0 line. 2.4.0-RC-0.1 is the newest
        // build JetBrains has published; nothing exists yet for 2.4.10, so that stays out of
        // reach until they catch up.
        id("rpc") version "2.4.0-RC-0.1"
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
        id("org.jetbrains.qodana") version "2026.2.0"
        id("org.jetbrains.grammarkit") version "2023.3.0.3"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

rootProject.name = "flix.jetbrains.plugin"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include("shared")
include("language")
include("debugger")
include("frontend")
include("backend")
