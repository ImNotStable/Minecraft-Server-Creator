plugins {
    id("msc.stdlib-conventions")
    // Version comes from settings.gradle.kts, where Loom's repository plugin is applied.
    id("net.fabricmc.fabric-loom")
}

description = "msc-stdlib for Fabric: menus, items, particles, player data and a scheduler."

loom {
    serverOnlyMinecraftJar()
}

// Fabric API modules the adapter calls; the mod using it has Fabric API (the templates depend on all of it).
val fabricApiModules = listOf("fabric-api-base", "fabric-lifecycle-events-v1", "fabric-networking-api-v1",
    "fabric-events-interaction-v0", "fabric-message-api-v1")

dependencies {
    minecraft(libs.minecraft)
    compileOnly(libs.fabric.loader)
    for (module in fabricApiModules) {
        compileOnly(fabricApi.module(module, libs.versions.fabric.api.get()))
        testImplementation(fabricApi.module(module, libs.versions.fabric.api.get()))
    }
    api(project(":msc-stdlib"))

    testImplementation(libs.fabric.loader.junit)
    testRuntimeOnly(libs.sqlite.jdbc)
}

tasks.test {
    // fabric-loader-junit starts Fabric Loader in the test JVM; this library only has the server jar.
    systemProperty("fabric.side", "server")
}
