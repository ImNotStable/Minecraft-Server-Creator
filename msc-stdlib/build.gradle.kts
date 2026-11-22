plugins {
    id("msc.stdlib-conventions")
}

description = "Standard library for plugins and mods made with MSC: databases, cooldowns, durations, shapes, scheduling, text."

dependencies {
    api(libs.hikaricp)
    // Every platform MSC builds for ships Gson, so it is not bundled.
    compileOnly(libs.gson)
    // Text: the platform (Paper, Velocity) or its adapter (Minestom) brings Adventure.
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.serializer.plain)
    compileOnly(libs.adventure.serializer.legacy)

    testImplementation(libs.gson)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.serializer.plain)
    testImplementation(libs.adventure.serializer.legacy)
    testRuntimeOnly(libs.sqlite.jdbc)
}
