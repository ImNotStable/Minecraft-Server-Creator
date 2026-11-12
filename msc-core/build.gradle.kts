import java.util.Properties

plugins {
    id("msc.application-conventions")
}

description = "The MCP server the AI talks to."

dependencies {
    implementation(project(":msc-protocol"))
    implementation(project(":msc-templates"))
    implementation(project(":msc-textures"))
    implementation(project(":msc-renderer"))
    implementation(project(":msc-bot"))
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
}

// The test player's client for the previous Minecraft version (Minestom lags Paper by one), in bot/<version>/.
// Not on the class path: msc-bot's Bots loads it in its own class loader when a server answers with that protocol.
val previousProtocol = configurations.dependencyScope("previousProtocol")
val previousProtocolJars = configurations.resolvable("previousProtocolJars") { extendsFrom(previousProtocol.get()) }
dependencies {
    previousProtocol(libs.mcprotocollib.previous) { isTransitive = false }
}

distributions {
    main {
        contents {
            into("agents") {
                from(agentJars)
                // Loom's runtimeElements may also carry the dev (named) jar; only the remapped one loads.
                exclude("*-dev.jar", "*-sources.jar")
            }
            into("bot/${libs.versions.mcprotocollib.previous.get()}") {
                from(previousProtocolJars)
            }
        }
    }
}

// installMsc: copy the distribution to a stable folder (outside build/, so `clean` and rebuilds don't
// break a running Claude Code) and print the one command that registers it with Claude Code.
// The MCP command is java with an absolute path and a lib/* class path, so it needs no JAVA_HOME,
// no PATH entry and no .bat wrapper on Windows.
val installDir = providers.gradleProperty("msc.installDir")
    .orElse(providers.systemProperty("user.home").map { "$it/.msc/core" })
val toolchainJava = javaToolchains.launcherFor(java.toolchain).map { it.executablePath.asFile.absolutePath }

val installMsc = tasks.register<Sync>("installMsc") {
    group = "distribution"
    description = "Installs msc-core to ~/.msc/core (or -Pmsc.installDir) and prints the Claude Code command."
    with(distributions.main.get().contents)
    into(installDir)
    val target = installDir
    val java = toolchainJava
    val source = rootDir.absolutePath
    // Sync deletes whatever else is in the target, so refuse a folder that holds anything but an earlier install.
    doFirst {
        val dir = File(target.get()).absoluteFile
        val entries = dir.listFiles().orEmpty()
        val earlierInstall = dir.resolve("lib").listFiles().orEmpty().any { it.name.matches(Regex("msc-core-.*\\.jar")) }
        if (entries.isNotEmpty() && !earlierInstall) {
            throw GradleException("refusing to install into $dir: it is not empty and holds no earlier msc-core " +
                "install (installMsc replaces the folder's contents). Pick an empty or new folder with -Pmsc.installDir=...")
        }
    }
    doLast {
        val dir = File(target.get()).absoluteFile
        val classPath = dir.resolve("lib").absolutePath + File.separator + "*"
        val quote = { v: String -> "\"" + v + "\"" }
        val add = "claude mcp add msc --scope user -- " + quote(java.get()) + " -cp " + quote(classPath) +
            " io.github.imnotstable.msc.core.Main"
        dir.resolve("claude-mcp-add.txt").writeText(add + "\n")
        // Lets the core warn when the checkout it came from has changed since (a stale install).
        val props = Properties()
        props["source"] = source
        props["installed"] = System.currentTimeMillis().toString()
        dir.resolve("install.properties").writer().use { props.store(it, "written by installMsc") }
        println("msc-core installed in $dir")
        println("Register it with Claude Code once (any shell; also saved in claude-mcp-add.txt):")
        println(add)
    }
}
