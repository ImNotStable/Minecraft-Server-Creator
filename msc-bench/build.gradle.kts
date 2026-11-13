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
    // Docs that name the default run folder, checked against RunIsolation so they cannot drift.
    val root = rootProject.layout.projectDirectory
    inputs.files(file("README.md"), root.file("docs/wiki/Benchmark.md"), root.file("msc-docs/docs/adr/0004-bench.md"))
        .withPropertyName("runFolderDocs")
    systemProperty("msc.repoRoot", root.asFile.absolutePath)

    // The tests run on a stand-in machine, so no result depends on the real one (#335): user.home is a fresh fake
    // home that is a project in every way Claude Code knows (.claude/CLAUDE.md, CLAUDE.md, .git, as a Windows home
    // with Claude Code and dotfiles is), java.io.tmpdir and the working folder lie inside it (as
    // %USERPROFILE%\AppData\Local\Temp does), and the variables the fakes read are cleared. The whole fake home sits
    // in build/, inside this repository, so the repository's own CLAUDE.md and .git are above it as well.
    val machine = layout.buildDirectory.dir("test-machine").get().asFile
    val home = machine.resolve("home")
    val temp = home.resolve("AppData/Local/Temp")
    val work = home.resolve("work")
    systemProperty("user.home", home.absolutePath)
    systemProperty("java.io.tmpdir", temp.absolutePath)
    workingDir = work
    environment.keys.removeIf { it.startsWith("FAKE_") || it.startsWith("MSC_") }
    doFirst {
        machine.deleteRecursively()
        listOf(temp, work, home.resolve(".claude"), home.resolve(".git")).forEach { it.mkdirs() }
        home.resolve(".claude/CLAUDE.md").writeText("user memory\n")
        home.resolve("CLAUDE.md").writeText("home project\n")
        home.resolve(".git/HEAD").writeText("ref: refs/heads/main\n")
    }
}
