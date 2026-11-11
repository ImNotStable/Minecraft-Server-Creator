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

// Versions written into scaffolded projects. Java 25 is what Minecraft 26.x requires, so it is
// fixed here and not taken from msc.javaVersion (which only changes how this repo compiles).
val templateVersions = mapOf(
    "javaVersion" to "25",
    "paperVersion" to paperApi,
    "mcVersion" to paperApi.substringBefore(".build"),
    "junitVersion" to catalogVersion("junit"),
    "assertjVersion" to catalogVersion("assertj"),
    "runPaperVersion" to catalogVersion("run-paper"),
    "mockbukkitVersion" to catalogVersion("mockbukkit"),
    "foojayVersion" to catalogVersion("foojay"),
    "fabricLoaderVersion" to catalogVersion("fabric-loader"),
    "fabricApiVersion" to catalogVersion("fabric-api"),
    "fabricLoomVersion" to catalogVersion("fabric-loom"),
)

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

    @get:Input
    abstract val versions: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    companion object {
        val PROJECT_TEMPLATES = setOf("paper-plugin", "fabric-mod")
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
    wrapperRoot = rootProject.layout.projectDirectory
    versions = templateVersions
    outputDir = layout.buildDirectory.dir("generated/templates")
}

sourceSets.main {
    resources.srcDir(packTemplates)
}

// Scaffolds every project template into build/ and runs its own Gradle build (compile + tests).
// Needs Java 25, repo.papermc.io and maven.fabricmc.net, so it is not part of `check`: run it on a dev machine.
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
