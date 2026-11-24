plugins {
    id("msc.stdlib-conventions")
}

description = "msc-stdlib for BungeeCord and Waterfall: scheduler, commands, player data, languages, server switching."

dependencies {
    api(project(":msc-stdlib"))
    compileOnly(libs.waterfall.api)
    // BungeeCord has no Adventure: msc-stdlib's Text needs it, and BungeeText turns it into BungeeCord's chat.
    // Gson comes with BungeeCord.
    api(libs.adventure.minimessage)
    api(libs.adventure.serializer.plain)
    api(libs.adventure.serializer.legacy)
    api(libs.adventure.serializer.gson) {
        exclude(group = "com.google.code.gson")
    }

    testImplementation(libs.waterfall.api)
    testRuntimeOnly(libs.sqlite.jdbc)
}
