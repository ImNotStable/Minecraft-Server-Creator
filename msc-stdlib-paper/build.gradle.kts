plugins {
    id("msc.stdlib-conventions")
}

description = "msc-stdlib for Paper: GUIs, items, particles, text, sidebars, player data and a scheduler."

dependencies {
    api(project(":msc-stdlib"))
    compileOnly(libs.paper.api)

    testImplementation(libs.paper.api.mockbukkit)
    testImplementation(libs.mockbukkit)
    testRuntimeOnly(libs.sqlite.jdbc)
}

// Paper's JOML (Hologram scaling) is compiled for an old class file version and has type annotations javac warns about.
tasks.compileJava {
    options.compilerArgs.add("-Xlint:-classfile")
}
