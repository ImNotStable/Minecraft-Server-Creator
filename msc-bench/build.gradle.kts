plugins {
    id("msc.application-conventions")
    id("msc.previous-protocol")
}

description = "Benchmark tasks and runner comparing AI work with and without the toolset."

dependencies {
    // Reads task files, the bench config, Claude Code's JSON output and msc-core's tool log.
    implementation(libs.gson)
    // Minestom tasks start from a scaffolded template, and their player checks join with the test player.
    implementation(project(":msc-templates"))
    implementation(project(":msc-bot"))
}

application {
    mainClass = "io.github.imnotstable.msc.bench.Main"
}

// Run from the module folder so the default paths (tasks/, bench.json) resolve; runs go outside the repository. The test player speaks the
// previous Minecraft version (Minestom lags Paper) from a jar in its own class loader, staged by msc.previous-protocol.
tasks.named<JavaExec>("run") {
    workingDir = projectDir
    previousProtocolClient.passTo(this)
}

tasks.test {
    previousProtocolClient.passTo(this)
    // eula.json cases shared with msc-core's MachineEulaTest, so the bench copy of the rule cannot drift (ADR 0004).
    val eulaFixtures = rootProject.layout.projectDirectory.dir("msc-core/src/test/resources/eula-fixtures")
    inputs.dir(eulaFixtures).withPropertyName("eulaFixtures")
    systemProperty("msc.eulaFixtures", eulaFixtures.asFile.absolutePath)
}
