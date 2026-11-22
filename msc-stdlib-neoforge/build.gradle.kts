plugins {
    id("msc.stdlib-vanilla")
    // Version comes from settings.gradle.kts, where ModDevGradle's repository plugin is applied.
    id("net.neoforged.moddev")
}

description = "msc-stdlib for NeoForge: menus, items, particles, feedback, boss bars, holograms, player data, " +
        "custom items, regions, prompts. The Minecraft side is shared with msc-stdlib-fabric (msc-stdlib-vanilla)."

neoForge {
    version = libs.versions.neoforge.get()
}

dependencies {
    api(project(":msc-stdlib"))
}
