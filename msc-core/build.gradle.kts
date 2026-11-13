import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("msc.application-conventions")
    id("msc.previous-protocol")
    id("msc.repo-inputs")
}

description = "The MCP server the AI talks to."

dependencies {
    implementation(project(":msc-protocol"))
    implementation(project(":msc-templates"))
    implementation(project(":msc-textures"))
    implementation(project(":msc-renderer"))
    implementation(project(":msc-bot"))
    implementation(project(":msc-process"))
    implementation(libs.mcp.sdk)
    // Lifts the SDK's transitive Jackson 3 past known advisories; it parses every MCP message.
    implementation(platform(libs.jackson.bom))
}

application {
    mainClass = "io.github.imnotstable.msc.core.Main"
}

// The agent jars ship inside the distribution (agents/), so server_setup finds them with no setup.
val agents = configurations.dependencyScope("agents")
val agentJars = configurations.resolvable("agentJars") { extendsFrom(agents.get()) }
dependencies {
    agents(project(path = ":msc-agent-paper", configuration = "shadowRuntimeElements")) { isTransitive = false }
    agents(project(path = ":msc-agent-fabric", configuration = "runtimeElements")) { isTransitive = false }
    agents(project(path = ":msc-agent-neoforge", configuration = "shadowRuntimeElements")) { isTransitive = false }
    agents(project(path = ":msc-agent-forge", configuration = "shadowRuntimeElements")) { isTransitive = false }
}

// The test player's client for the previous Minecraft version (Minestom lags Paper by one), in bot/<version>/.
// Not on the class path: msc-bot's Bots loads it in its own class loader when a server answers with that protocol.
// The configuration and the bot/<version> layout come from msc.previous-protocol, shared with msc-bot and msc-bench.

distributions {
    main {
        contents {
            into("agents") {
                from(agentJars)
                // Loom's runtimeElements may also carry the dev (named) jar; only the remapped one loads.
                exclude("*-dev.jar", "*-sources.jar")
            }
            into(previousProtocolClient.distributionDir) {
                from(previousProtocolClient.jars)
            }
        }
    }
}

// installMsc: copy the distribution to a new folder under ~/.msc/core (outside build/, so `clean` and rebuilds don't
// break a running Claude Code). Each run makes its own folder, msc-core-<version>-<time>, and the msc plugin's
// launcher (msc-plugin/, ADR 0021) starts the one installed last, so a new install needs no `claude mcp add` and never
// overwrites jars a running core holds open (Windows locks them). Older installs are pruned when nothing holds them.
val installRoot = providers.gradleProperty("msc.installDir")
    .orElse(providers.systemProperty("user.home").map { "$it/.msc/core" })
val toolchainJava = javaToolchains.launcherFor(java.toolchain).map { it.executablePath.asFile.absolutePath }
val installDist = tasks.named<Sync>("installDist")

val installMsc = tasks.register("installMsc") {
    group = "distribution"
    description = "Installs msc-core into a new folder under ~/.msc/core (or -Pmsc.installDir) for the msc plugin."
    dependsOn(installDist)
    val from = installDist.map { it.destinationDir }
    val root = installRoot
    val javaPath = toolchainJava
    val source = rootDir.absolutePath
    val version = project.version.toString()
    val acceptEula = providers.gradleProperty("msc.acceptEula").map { it.toBoolean() }.orElse(false)
    val userHome = providers.systemProperty("user.home")
    // Never up to date: every run is a new install.
    outputs.upToDateWhen { false }
    doLast {
        val now = System.currentTimeMillis()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now))
        val rootDir = File(root.get()).absoluteFile
        val dir = rootDir.resolve("msc-core-$version-$stamp")
        if (dir.exists()) {
            throw GradleException("$dir already exists; run installMsc again in a second")
        }
        from.get().copyRecursively(dir)
        // copyRecursively drops file modes: without this the Linux and macOS launcher is not executable.
        dir.resolve("bin").listFiles().orEmpty().filter { !it.name.endsWith(".bat") }.forEach { it.setExecutable(true) }
        // Read by the launcher (installed, java) and by the core's stale-install check (source).
        val props = Properties()
        props["source"] = source
        props["installed"] = now.toString()
        props["java"] = javaPath.get()
        dir.resolve("install.properties").writer().use { props.store(it, "written by installMsc") }
        // Keep this install and the one before it (a running session may still use it); older installMsc
        // folders go. A folder whose jars are open cannot be renamed on Windows, so it is skipped, not half deleted.
        val earlier = rootDir.listFiles().orEmpty()
            .filter { it != dir && it.isDirectory && it.name.startsWith("msc-core-") }
            .filter { it.resolve("install.properties").isFile && it.resolve("lib").isDirectory }
            .sortedByDescending { d ->
                val p = Properties()
                d.resolve("install.properties").reader().use { p.load(it) }
                p.getProperty("installed", "0").toLongOrNull() ?: 0L
            }
        for (old in earlier.drop(1)) {
            val doomed = rootDir.resolve(".delete-" + old.name)
            if (old.renameTo(doomed)) {
                doomed.deleteRecursively()
            }
        }
        // An install from before the plugin (one flat folder, registered with a fixed `claude mcp add` line) would
        // keep running its old jars: refresh it from this one. Overwrites and renames only, never deletes.
        val refresh = ProcessBuilder(javaPath.get(), "-cp", dir.resolve("lib").absolutePath + File.separator + "*",
            "io.github.imnotstable.msc.core.Main", "refresh-legacy-install", rootDir.absolutePath, dir.absolutePath)
            .redirectErrorStream(true).start()
        val said = refresh.inputStream.bufferedReader().readText()
        if (refresh.waitFor() != 0) {
            println("Could not refresh the older install in $rootDir (is a session using it? Close it and run installMsc again).")
            println(said.lines().take(3).joinToString("\n"))
        } else {
            print(said)
        }
        // The owner's one-time EULA acceptance for this machine (ADR 0020). Printed, never run: only the owner can
        // answer it, in an interactive terminal. It lives in the install root, so its path stays the same; each
        // install rewrites it to point at the newest install's jars.
        val classPath = dir.resolve("lib").absolutePath + File.separator + "*"
        // -Pmsc.acceptEula=true: the owner's explicit agreement given on their own install command (ADR 0020).
        // Never the default: installing MSC alone does not accept Mojang's EULA.
        if (acceptEula.get()) {
            val file = File(userHome.get()).resolve(".msc/eula.json")
            if (!file.isFile) {
                val user = System.getProperty("user.name").replace("\\", "\\\\").replace("\"", "\\\"")
                val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("this machine")
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                file.parentFile.mkdirs()
                file.writeText("{\n  \"eula\": \"https://aka.ms/MinecraftEULA\",\n  \"accepted\": true,\n" +
                    "  \"acceptedBy\": \"$user\",\n  \"host\": \"$host\",\n  \"acceptedAt\": \"${Instant.ofEpochMilli(now)}\",\n" +
                    "  \"how\": \"ran installMsc with -Pmsc.acceptEula=true\"\n}\n")
            }
            println("Minecraft EULA (https://aka.ms/MinecraftEULA) accepted for this machine, saved in $file.")
            println("By -Pmsc.acceptEula=true you confirm you read and accept Mojang's EULA; MSC now writes eula=true for")
            println("every Paper, Purpur, Fabric, Quilt, NeoForge and Forge dev server here without asking. Terms: TERMS.md")
            println("in this repo. Undo: the line in")
            println("eula-accept.txt with revoke instead of accept.")
        }
        val eula = "\"" + javaPath.get() + "\" -cp \"" + classPath + "\" io.github.imnotstable.msc.core.Main eula accept"
        rootDir.resolve("eula-accept.txt").writeText(eula + "\n")
        println("msc-core $version installed in $dir")
        println("With the msc plugin, Claude Code uses it at its next start, or now with /mcp > msc > Reconnect.")
        println("Plugin not added yet? In Claude Code, once: /plugin marketplace add $source")
        println("                                    then: /plugin install msc@msc")
        if (!acceptEula.get()) {
            println("Optional, once per machine: if you agree to the Minecraft EULA (https://aka.ms/MinecraftEULA), add")
            println("-Pmsc.acceptEula=true to this command (see TERMS.md), or run this yourself in a terminal")
            println("(saved in ${rootDir.resolve("eula-accept.txt")}):")
            println(eula)
        }
    }
}

// The version msc_status and the MCP serverInfo report: project.version (a release passes -Pversion=<tag>).
val versionResource = tasks.register<WriteProperties>("versionResource") {
    destinationFile = layout.buildDirectory.file("generated/version/io/github/imnotstable/msc/core/version.properties")
    property("version", project.version.toString())
}
sourceSets.main { resources.srcDir(versionResource.map { layout.buildDirectory.dir("generated/version").get() }) }

// e2eTest: a real Minestom server driven through the installed core over MCP stdio (MinestomEndToEndTest, tag e2e).
// Not part of `build`: it scaffolds a project and runs its own Gradle build. CI runs it on Linux and Windows.
tasks.test {
    useJUnitPlatform { excludeTags("e2e") }
    systemProperty("msc.expectedVersion", project.version.toString())
}

// Files outside msc-core the tests read (msc.repo-inputs): each set is both what the test reads and a test input.
// DocumentedCommandsTest: every text file of the repository outside build output, server and IDE run folders (at
// the root or a module's root, so source packages named run stay in), test sources and dot folders. A `**/.*/**`
// pattern would also drop dot files such as msc-plugin/.mcp.json, so dot folders are matched by their contents.
repoInputs.set("documentedCommands", rootProject.fileTree(rootDir) {
    include("**/*.md", "**/*.java", "**/*.kts", "**/*.txt", "**/*.json", "**/*.yml", "**/*.properties")
    exclude("**/build/**", "run/**", "*/run/**", "out/**", "*/out/**", "**/node_modules/**", "**/test/**",
        "**/testFixtures/**", "graphify-out/**", "**/.*/*/**")
})
// RepoInputsTest: every module's test sources, checked for reads that bypass these sets.
repoInputs.set("testSources", rootProject.fileTree(rootDir) {
    include("msc-*/src/test/**/*.java", "msc-*/src/test/**/*.kt")
})

tasks.register<Test>("e2eTest") {
    description = "Scaffolds minestom-game and drives a real Minestom server end to end through the installed core."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    dependsOn(installDist)
    val install = installDist.map { it.destinationDir.absolutePath }
    val report = layout.buildDirectory.dir("e2e").map { it.asFile.absolutePath }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmsc.e2e.install=${install.get()}", "-Dmsc.e2e.report=${report.get()}")
    })
    outputs.upToDateWhen { false }
}

// tools.md is generated from the tool definitions (ADR 0026): toolDocs rewrites its marked tables, checkToolDocs
// (part of `check`) fails when they or the wiki's tool page are out of date.
val toolDoc = rootProject.layout.projectDirectory.file("msc-docs/docs/tools.md")
val wikiToolPage = rootProject.layout.projectDirectory.file("docs/wiki/Tools-reference.md")

tasks.register<JavaExec>("toolDocs") {
    description = "Regenerates the tool tables in msc-docs/docs/tools.md from the tool definitions."
    group = "documentation"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.imnotstable.msc.core.ToolDocs"
    args("write", toolDoc.asFile.absolutePath, wikiToolPage.asFile.absolutePath)
}

val checkToolDocs = tasks.register<JavaExec>("checkToolDocs") {
    description = "Fails when msc-docs/docs/tools.md or the wiki tool page is out of date with the tool definitions."
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.imnotstable.msc.core.ToolDocs"
    args("check", toolDoc.asFile.absolutePath, wikiToolPage.asFile.absolutePath)
    inputs.files(toolDoc, wikiToolPage)
    inputs.files(classpath)
    val marker = layout.buildDirectory.file("tool-docs/checked")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok\n") }
}

tasks.named("check") { dependsOn(checkToolDocs) }
