pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Google's LiteRT-LM artifacts are Java 21 bytecode, so the JVM side builds and runs on a Java 21 toolchain.
    // This fetches one where none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // LiteRT-LM's Android and JVM artifacts are on Google Maven, not Maven Central.
        google()
        mavenCentral()
    }
}

rootProject.name = "litert-lm-kmp"

include(":litertlm-kmp")
include(":litertlm-genai")
include(":litertlm-genai-nano")
include(":sample-shared")
include(":sample-android")
