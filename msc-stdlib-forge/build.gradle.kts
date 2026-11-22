plugins {
    id("msc.stdlib-vanilla")
    // ModDevGradle in vanilla mode with Forge's API jars, as in msc-agent-forge (ADR 0027).
    id("net.neoforged.moddev")
}

description = "msc-stdlib for Minecraft Forge: menus, items, particles, feedback, boss bars, holograms, player data, " +
        "custom items, regions, prompts. The Minecraft side is shared with msc-stdlib-fabric (msc-stdlib-vanilla)."

neoForge {
    neoFormVersion = libs.versions.neoform.get()
}

dependencies {
    api(project(":msc-stdlib"))
    compileOnly(variantOf(libs.forge.universal) { classifier("universal") }) { isTransitive = false }
    compileOnly(libs.forge.fmlcore) { isTransitive = false }
    compileOnly(libs.forge.eventbus) { isTransitive = false }
}
