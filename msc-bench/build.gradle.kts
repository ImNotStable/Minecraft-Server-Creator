plugins {
    id("msc.application-conventions")
    id("msc.previous-protocol")
    id("msc.repo-inputs")
}

description = "Benchmark tasks and runner comparing AI work with and without the toolset."

dependencies {
    // Reads task files, the bench config, Claude Code's JSON output and msc-core's tool log.
    implementation(libs.gson)
    // Minestom tasks start from a scaffolded template, and their player checks join with the test player.
    implementation(project(":msc-templates"))
    implementation(project(":msc-bot"))
    // The tree kill and the RCON client shared with msc-core; their own module, since the bench must not depend on
    // core (ADR 0004, ADR 0031, ADR 0032). Its test fixtures hold the fake RCON server.
    implementation(project(":msc-process"))
    testImplementation(testFixtures(project(":msc-process")))
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

// Files outside msc-bench's sources the tests read (msc.repo-inputs): each set is both what the test reads and a test
// input, so a change to one reruns the tests.
val root = rootProject.layout.projectDirectory
// eula.json cases shared with msc-core's MachineEulaTest, so the bench copy of the rule cannot drift (ADR 0004).
repoInputs.set("eulaFixtures", root.dir("msc-core/src/test/resources/eula-fixtures").asFileTree)
// Docs that name the default run folder, checked against RunIsolation so they cannot drift.
repoInputs.set("runFolderDocs", file("README.md"), root.file("docs/wiki/Benchmark.md"),
    root.file("msc-docs/docs/adr/0004-bench.md"))
// The bundled tasks TaskLoaderTest loads.
repoInputs.set("bundledTasks", fileTree("tasks"))
// The README whose Tasks table TaskLoaderTest checks against the bundled tasks (#395).
repoInputs.set("benchReadme", file("README.md"))
// The shipped bench.json with the files it names, which must pass BenchConfig's load-time checks (#406).
repoInputs.set("shippedConfig", file("bench.json"), file("mcp-toolset.template.json"), root.file("gradlew"))
// The repository's CLAUDE.md, which a run folder inside the repository would sit under (RunIsolationTest).
repoInputs.set("repoClaudeMd", root.file("CLAUDE.md"))
// msc-bench's own production sources, which TaskNumbersTest scans for the number keys the task readers take (#416).
repoInputs.set("benchSources", fileTree("src/main/java"))
