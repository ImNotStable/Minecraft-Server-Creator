plugins {
    id("msc.java-conventions")
    // ModDevGradle in vanilla mode: plain Minecraft on the compile class path, no NeoForge. ForgeGradle 7 needs
    // project repositories, which this build forbids, and Minecraft 26.x is unobfuscated, so compiling against
    // vanilla Minecraft plus Forge's API jars gives the same bytecode. See ADR 0027.
    id("net.neoforged.moddev")
    alias(libs.plugins.shadow)
}

description = "Minecraft Forge server mod that carries out msc-core's operations inside a running server."

// The Minecraft side is shared with the Fabric agent, which also tests it (ADR 0027).
sourceSets {
    main { java.srcDir(rootProject.file("msc-agent-vanilla/src/main/java")) }
}

neoForge {
    neoFormVersion = libs.versions.neoform.get()
}

val bundled = configurations.dependencyScope("bundled")
val bundledClasspath = configurations.resolvable("bundledClasspath") { extendsFrom(bundled.get()) }
configurations.implementation { extendsFrom(bundled.get()) }

dependencies {
    // Forge's API, provided by the server at runtime.
    compileOnly(variantOf(libs.forge.universal) { classifier("universal") }) { isTransitive = false }
    compileOnly(libs.forge.fmlcore) { isTransitive = false }
    compileOnly(libs.forge.javafmllanguage) { isTransitive = false }
    compileOnly(libs.forge.eventbus) { isTransitive = false }
    bundled(project(":msc-agent-common")) {
        exclude(group = "com.google.code.gson")
    }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "forgeMajor" to libs.versions.forge.asProvider().get().substringAfter('-').substringBefore('.'),
        "minecraftVersion" to libs.versions.minecraft.get(),
    )
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") { expand(props) }
}

// One jar: the mod plus msc-agent-common and msc-protocol, as in msc-agent-neoforge.
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
