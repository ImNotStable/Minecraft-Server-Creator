pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") {
            name = "fabric"
        }
        maven("https://maven.neoforged.net/releases/") {
            name = "neoforged"
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
    // NeoForge's ModDevGradle (msc-agent-neoforge, msc-agent-forge) declares its repositories here for the same
    // reason. Keep the version equal to moddev in gradle/libs.versions.toml.
    id("net.neoforged.moddev.repositories") version "2.0.148"
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
        // Forge's API jars, compiled against by msc-agent-forge (ForgeGradle needs project repositories).
        maven("https://maven.minecraftforge.net/") {
            name = "forge"
            content { includeGroupByRegex("net\\.minecraftforge(\\..*)?") }
        }
        // MCProtocolLib and its own libraries, for msc-bot's test player.
        maven("https://repo.opencollab.dev/main/") {
            name = "opencollab"
            content {
                includeGroupByRegex("org\\.geysermc(\\..*)?")
                includeGroupByRegex("org\\.cloudburstmc(\\..*)?")
                includeGroupByRegex("com\\.nukkitx(\\..*)?")
                includeGroupByRegex("net\\.raphimc(\\..*)?")
                includeGroupByRegex("net\\.lenni0451(\\..*)?")
            }
        }
    }
}

rootProject.name = "minecraft-server-creator"

// MVP modules
include("msc-protocol")
include("msc-core")
include("msc-agent-common")
include("msc-agent-paper")
include("msc-templates")
include("msc-bench")
include("msc-process")

// Post-MVP modules
include("msc-agent-fabric")
include("msc-agent-minestom")
include("msc-agent-neoforge")
include("msc-agent-forge")
include("msc-renderer")
include("msc-textures")
include("msc-bot")
include("msc-plugin")
include("msc-stdlib")
include("msc-stdlib-paper")
include("msc-stdlib-minestom")
include("msc-stdlib-fabric")
