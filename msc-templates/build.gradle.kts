import java.util.zip.ZipFile

plugins {
    id("msc.java-conventions")
}

description = "Starter server projects per platform, packaged as resources for msc-core."

// Template sources live in src/main/templates/<id>/. The packTemplates task copies them into
// the jar under msc/templates/, adds the Gradle wrapper and the paper-server files, and writes
// the index files the runtime reads (index.txt, <id>/files.txt, versions.properties).

val catalog = versionCatalogs.named("libs")
fun catalogVersion(name: String): String =
    catalog.findVersion(name).map { it.requiredVersion }
        .orElseThrow { GradleException("Version '$name' is missing from gradle/libs.versions.toml") }

val paperApi = catalogVersion("paper")
val minestom = catalogVersion("minestom")

// Versions written into scaffolded projects. Java 25 is what Minecraft 26.x requires, so it is
// fixed here and not taken from msc.javaVersion (which only changes how this repo compiles).
val templateVersions = mapOf(
    "javaVersion" to "25",
    "paperVersion" to paperApi,
    "mcVersion" to paperApi.substringBefore(".build"),
    "junitVersion" to catalogVersion("junit"),
    "assertjVersion" to catalogVersion("assertj"),
    "runPaperVersion" to catalogVersion("run-paper"),
    "paperweightVersion" to catalogVersion("paperweight"),
    "resourceFactoryVersion" to catalogVersion("resource-factory"),
    "mockbukkitVersion" to catalogVersion("mockbukkit"),
    "foojayVersion" to catalogVersion("foojay"),
    "fabricLoaderVersion" to catalogVersion("fabric-loader"),
    "fabricApiVersion" to catalogVersion("fabric-api"),
    "fabricLoomVersion" to catalogVersion("fabric-loom"),
    "quiltLoaderVersion" to catalogVersion("quilt-loader"),
    "quiltInstallerVersion" to catalogVersion("quilt-installer"),
    "neoforgeVersion" to catalogVersion("neoforge"),
    "moddevVersion" to catalogVersion("moddev"),
    "forgeVersion" to catalogVersion("forge"),
    "forgeGradleVersion" to catalogVersion("forgegradle"),
    "forgeEventbusVersion" to catalogVersion("forge-eventbus"),
    "velocityVersion" to catalogVersion("velocity"),
    "minestomVersion" to minestom,
    "minestomMcVersion" to minestom.substringAfterLast("-"),
    "slf4jVersion" to catalogVersion("slf4j"),
    "shadowVersion" to catalogVersion("shadow"),
    "mscStdlibVersion" to catalogVersion("msc-stdlib"),
    "sqliteJdbcVersion" to catalogVersion("sqlite-jdbc"),
    "hikaricpVersion" to catalogVersion("hikaricp"),
)

// The Minestom agent jar (msc-protocol bundled), shipped in minestom-server and minestom-game as libs/msc-agent-minestom.jar.
val minestomAgent = configurations.dependencyScope("minestomAgent")
val minestomAgentJar = configurations.resolvable("minestomAgentJar") { extendsFrom(minestomAgent.get()) }
dependencies {
    minestomAgent(project(path = ":msc-agent-minestom", configuration = "shadowRuntimeElements")) { isTransitive = false }
    // For TemplateNameCasesTest: Gson reads fabric.mod.json; Paper's API reads plugin.yml as Paper does (its
    // SnakeYAML and PluginDescriptionFile checks).
    testImplementation(libs.gson)
    testImplementation(libs.paper.api)
}

abstract class PackTemplates : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templatesDir: DirectoryProperty

    @get:Internal
    abstract val wrapperRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val wrapperInputs: Provider<List<File>>
        get() = wrapperRoot.map { root -> WRAPPER_FILES.map(root.asFile::resolve) }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val minestomAgent: ConfigurableFileCollection

    /** msc-stdlib as a Maven repository (ADR 0066), shipped in the STDLIB_TEMPLATES as libs/repo/. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stdlibRepo: ConfigurableFileCollection

    /** The committed graphify graphs of msc-stdlib per platform (scripts/stdlib-graph.sh), shipped as libs/stdlib/graphify-out/. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stdlibGraphs: DirectoryProperty

    @get:Input
    abstract val versions: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    companion object {
        val PROJECT_TEMPLATES = setOf("paper-plugin", "paper-game", "fabric-mod", "fabric-game", "neoforge-mod", "forge-mod")
        val MINESTOM_TEMPLATES = setOf("minestom-server", "minestom-game")
        /** A proxy plugin: its own wrapper, no Minecraft server files in run/ (the proxy writes its own config). */
        val PROXY_TEMPLATES = setOf("velocity-plugin")
        /** Templates that ship msc-stdlib, and the platform adapter each gets beside the core. */
        val STDLIB_TEMPLATES = mapOf("paper-plugin" to "paper", "paper-game" to "paper",
            "minestom-server" to "minestom", "minestom-game" to "minestom",
            "fabric-mod" to "fabric", "fabric-game" to "fabric")
        val WRAPPER_FILES = listOf(
            "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
    }

    @TaskAction
    fun pack() {
        val out = outputDir.get().asFile.resolve("msc/templates")
        out.deleteRecursively()
        out.mkdirs()
        val root = templatesDir.get().asFile
        val wrapper = wrapperRoot.get().asFile
        val ids = root.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()
        for (id in ids) {
            val files = sortedMapOf<String, File>()
            fun addTree(dir: File, prefix: String) = dir.walkTopDown().filter { it.isFile }
                .filter { prefix.isEmpty() || it.name != "template.properties" }.forEach {
                files[prefix + it.relativeTo(dir).invariantSeparatorsPath] = it
            }
            addTree(root.resolve(id), "")
            if (id in PROJECT_TEMPLATES) {
                // A plugin or mod project carries its own dev server in run/ and its own wrapper.
                // paper-server's files are plain vanilla server config, so Fabric uses them too.
                addTree(root.resolve("paper-server"), "run/")
                WRAPPER_FILES.forEach { files[it] = wrapper.resolve(it) }
            }
            if (id in MINESTOM_TEMPLATES) {
                // A Minestom server is its own project: own wrapper, agent jar on its classpath.
                WRAPPER_FILES.forEach { files[it] = wrapper.resolve(it) }
                files["libs/msc-agent-minestom.jar"] = minestomAgent.singleFile
            }
            STDLIB_TEMPLATES[id]?.let { platform ->
                // Each entry is <module>/build/repo; it holds the module's jar, sources jar, pom and API index.
                val modules = listOf("msc-stdlib", "msc-stdlib-$platform")
                val repos = modules.map { m -> stdlibRepo.files.single { it.parentFile.parentFile.name == m } }
                repos.forEach { repo ->
                    repo.walkTopDown().filter { it.isFile && !it.name.endsWith("-API.md") }.forEach {
                        files["libs/repo/" + it.relativeTo(repo).invariantSeparatorsPath] = it
                    }
                }
                // For agents (ADR 0066): one API index, the sources to read by line, and the graph to query.
                val stage = temporaryDir.resolve(platform).apply { deleteRecursively(); mkdirs() }
                val api = stage.resolve("API.md")
                api.writeText(repos.joinToString("\n") { repo ->
                    repo.walkTopDown().single { it.name.endsWith("-API.md") }.readText()
                })
                files["libs/stdlib/API.md"] = api
                modules.zip(repos).forEach { (module, repo) ->
                    val sourcesJar = repo.walkTopDown().single { it.name.endsWith("-sources.jar") }
                    ZipFile(sourcesJar).use { zip ->
                        zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".java") }.forEach { e ->
                            val f = stage.resolve("src/$module/${e.name}").apply { parentFile.mkdirs() }
                            zip.getInputStream(e).use { input -> f.outputStream().use { input.copyTo(it) } }
                            files["libs/stdlib/src/$module/${e.name}"] = f
                        }
                    }
                }
                stdlibGraphs.get().asFile.resolve(platform).listFiles()!!.forEach {
                    files["libs/stdlib/graphify-out/${it.name}"] = it
                }
            }
            if (id in PROXY_TEMPLATES) {
                WRAPPER_FILES.forEach { files[it] = wrapper.resolve(it) }
            }
            val description = files.remove("template.properties")
            for ((path, file) in files) {
                file.copyTo(out.resolve("$id/files/$path"), overwrite = true)
            }
            out.resolve("$id/files.txt").writeText(files.keys.joinToString("\n", postfix = "\n"))
            description?.copyTo(out.resolve("$id/template.properties"), overwrite = true)
        }
        out.resolve("index.txt").writeText(ids.joinToString("\n", postfix = "\n"))
        out.resolve("versions.properties").writeText(
            versions.get().toSortedMap().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}

val packTemplates = tasks.register<PackTemplates>("packTemplates") {
    templatesDir = layout.projectDirectory.dir("src/main/templates")
    stdlibGraphs = layout.projectDirectory.dir("src/main/stdlib-graphs")
    wrapperRoot = rootProject.layout.projectDirectory
    versions = templateVersions
    minestomAgent.from(minestomAgentJar)
    // Each module's build/repo holds io/github/imnotstable/msc/<module>/<version>/.
    for (module in listOf(":msc-stdlib", ":msc-stdlib-paper", ":msc-stdlib-minestom", ":msc-stdlib-fabric")) {
        stdlibRepo.from(files(project(module).layout.buildDirectory.dir("repo")).builtBy("$module:stdlibRepo"))
    }
    outputDir = layout.buildDirectory.dir("generated/templates")
}

sourceSets.main {
    resources.srcDir(packTemplates)
}

// Scaffolds every project template into build/ and runs its own Gradle build (compile + tests).
// Needs Java 25, repo.papermc.io, maven.fabricmc.net, maven.neoforged.net, maven.minecraftforge.net and Maven Central, so it is not part of `check`: run it on a dev machine.
tasks.register<Test>("verifyTemplates") {
    description = "Scaffolds each template and builds it with its own Gradle wrapper."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("template-build") }
    systemProperty("msc.templates.verifyDir", layout.buildDirectory.dir("verify").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.test {
    useJUnitPlatform { excludeTags("template-build") }
}
