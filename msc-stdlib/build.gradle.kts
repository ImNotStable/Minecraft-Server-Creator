plugins {
    id("msc.stdlib-conventions")
}

description = "Standard library for plugins and mods made with MSC: databases, cooldowns, durations, shapes, scheduling."

dependencies {
    api(libs.hikaricp)
    // Every platform MSC builds for ships Gson, so it is not bundled.
    compileOnly(libs.gson)

    testImplementation(libs.gson)
    testRuntimeOnly(libs.sqlite.jdbc)
}
