pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Downloads the Java toolchain (Java 25) automatically when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "minecraft-server-creator"

// MVP modules
include("msc-protocol")
include("msc-core")
include("msc-agent-paper")
include("msc-templates")
include("msc-bench")

// Post-MVP modules (empty for now, kept in the build so they stay wired in)
include("msc-agent-fabric")
include("msc-agent-minestom")
include("msc-renderer")
include("msc-textures")
