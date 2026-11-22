plugins {
    id("msc.stdlib-conventions")
}

description = "msc-stdlib for Minestom: menus, items, particles, player data and a scheduler."

dependencies {
    api(project(":msc-stdlib"))
    compileOnly(libs.minestom)
    // Minestom ships Adventure but not MiniMessage, which msc-stdlib's Text needs.
    api(libs.adventure.minimessage)

    testImplementation(libs.minestom)
    testImplementation(libs.minestom.testing)
    testRuntimeOnly(libs.sqlite.jdbc)
}

tasks.test {
    // Minestom's test environment: lets test players connect from a platform thread.
    systemProperty("minestom.inside-test", "true")
}
