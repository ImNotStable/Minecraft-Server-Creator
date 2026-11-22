plugins {
    id("msc.stdlib-conventions")
}

description = "msc-stdlib for Paper: GUIs, items, particles, text, sidebars, player data and a scheduler."

dependencies {
    api(project(":msc-stdlib"))
    compileOnly(libs.paper.api)

    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
    testRuntimeOnly(libs.sqlite.jdbc)
}
