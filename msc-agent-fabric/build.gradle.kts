plugins {
    id("msc.java-conventions")
    // Version comes from settings.gradle.kts, where Loom's repository plugin is applied.
    id("net.fabricmc.fabric-loom")
}

description = "Fabric server mod that carries out msc-core's operations inside a running server."

// The Minecraft side every mod-loader agent shares (ADR 0027): compiled here against Loom's Minecraft, and tested here,
// since fabric-loader-junit gives the tests a bootstrapped Minecraft.
sourceSets {
    main { java.srcDir(rootProject.file("msc-agent-vanilla/src/main/java")) }
    test { java.srcDir(rootProject.file("msc-agent-vanilla/src/test/java")) }
}

loom {
    // The agent is server-side only, so compile against the dedicated server jar alone.
    serverOnlyMinecraftJar()
}

// The two Fabric API modules the agent uses. They are nested in the mod jar (jar-in-jar), so the
// server needs only Fabric Loader and this one jar; Loader picks the newest copy if the server
// also has Fabric API installed.
val fabricApiModules = listOf("fabric-api-base", "fabric-lifecycle-events-v1", "fabric-networking-api-v1")

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    for (module in fabricApiModules) {
        val dep = fabricApi.module(module, libs.versions.fabric.api.get())
        implementation(dep)
        include(dep)
    }
    // Shared agent code; brings msc-protocol. Minecraft ships Gson.
    implementation(project(":msc-agent-common")) {
        exclude(group = "com.google.code.gson")
    }
    include(project(":msc-agent-common"))
    include(project(":msc-protocol"))

    testImplementation(libs.fabric.loader.junit)
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        // Quilt Loader provides fabricloader 0.19.x, and the agent uses nothing newer from the loader itself.
        "loaderFloor" to "0.17.0",
        "minecraftVersion" to libs.versions.minecraft.get(),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.test {
    // fabric-loader-junit starts Fabric Loader in the test JVM; this mod only has the server jar.
    systemProperty("fabric.side", "server")
}
