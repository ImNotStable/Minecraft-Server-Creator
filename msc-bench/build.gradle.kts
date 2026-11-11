plugins {
    id("msc.application-conventions")
}

description = "Benchmark tasks and runner comparing AI work with and without the toolset."

dependencies {
    // Reads task files, the bench config, Claude Code's JSON output and msc-core's tool log.
    implementation(libs.gson)
}

application {
    mainClass = "io.github.imnotstable.msc.bench.Main"
}

// Run from the module folder so the default paths (tasks/, bench.json, runs/) resolve.
tasks.named<JavaExec>("run") {
    workingDir = projectDir
}
