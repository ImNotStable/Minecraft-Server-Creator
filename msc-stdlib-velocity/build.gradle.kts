plugins {
    id("msc.stdlib-conventions")
}

description = "msc-stdlib for Velocity: scheduler, player data, languages and server switching."

dependencies {
    api(project(":msc-stdlib"))
    compileOnly(libs.velocity.api)

    testImplementation(libs.velocity.api)
    testRuntimeOnly(libs.sqlite.jdbc)
}
