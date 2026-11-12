plugins {
    id("msc.java-conventions")
    // Version comes from settings.gradle.kts, where ModDevGradle's repository plugin is applied.
    id("net.neoforged.moddev")
    alias(libs.plugins.shadow)
}

description = "NeoForge server mod that carries out msc-core's operations inside a running server."

// The Minecraft side is shared with the Fabric agent, which also tests it (ADR 0027).
sourceSets {
    main { java.srcDir(rootProject.file("msc-agent-vanilla/src/main/java")) }
}

neoForge {
    version = libs.versions.neoforge.get()
}

// Bundled into the mod jar by shadowJar below; NeoForge and Minecraft provide Gson and slf4j.
val bundled = configurations.dependencyScope("bundled")
val bundledClasspath = configurations.resolvable("bundledClasspath") { extendsFrom(bundled.get()) }
configurations.implementation { extendsFrom(bundled.get()) }

dependencies {
    bundled(project(":msc-agent-common")) {
        exclude(group = "com.google.code.gson")
    }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "neoforgeVersion" to libs.versions.neoforge.get(),
        "minecraftVersion" to libs.versions.minecraft.get(),
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

// One jar: the mod plus msc-agent-common and msc-protocol. Their packages are unique to this mod, so they can live in
// its module without jar-in-jar.
tasks.shadowJar {
    archiveClassifier = ""
    configurations = listOf(bundledClasspath.get())
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
