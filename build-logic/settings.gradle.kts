pluginManagement {
    // The kotlin-dsl plugin pulls the Kotlin Gradle plugin and stdlib; the Plugin Portal does not
    // always serve those, so look in Maven Central too (fresh CI machines have no cached copy).
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
