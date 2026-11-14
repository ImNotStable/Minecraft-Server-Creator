plugins {
    id("msc.java-conventions")
}

description = "Headless software renderer: block grids to small PNG views for the AI."

dependencies {
    // BlockGrid and Region are part of this module's public API.
    api(project(":msc-protocol"))
}

tasks.test {
    // VanillaColorsTest checks the colour table against the block list of the catalog's Minecraft version.
    systemProperty("msc.minecraft", libs.versions.minecraft.get())
}
