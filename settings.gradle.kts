pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") {
            name = "fabric"
        }
    }
}

plugins {
    // Downloads the Java toolchain (Java 25) automatically when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Fabric Loom adds its own repositories (Mojang libraries, its local Minecraft caches). Applied
    // here it declares them on the settings, which RepositoriesMode.FAIL_ON_PROJECT_REPOS requires.
    // Keep the version equal to fabric-loom in gradle/libs.versions.toml. Needs Gradle on Java 25.
    id("net.fabricmc.fabric-loom-repositories") version "1.19.4"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://maven.fabricmc.net/") {
            name = "fabric"
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
