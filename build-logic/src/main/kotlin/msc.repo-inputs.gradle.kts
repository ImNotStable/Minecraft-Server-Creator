// Tests that read files outside their module's sources (docs, other modules, shared fixtures) get them only from the
// named sets declared with `repoInputs.set(...)`: the same sets are the test task's inputs, so what the tests read
// and what Gradle tracks cannot drift apart (#129, #355). The tests read them through the generated class
// io.github.imnotstable.msc.buildlogic.RepoFiles, which refuses a path no set declares.
import io.github.imnotstable.msc.buildlogic.RepoInputs
import io.github.imnotstable.msc.buildlogic.repoFilesManifest

plugins {
    java
}

val repoInputs = RepoInputs(objects, tasks.named<Test>("test"), rootDir)
extensions.add(RepoInputs::class.java, "repoInputs", repoInputs)

// The helper's source ships in build-logic, so every module compiles the same RepoFiles.
val helperSource = RepoInputs::class.java.getResource("RepoFiles.java")!!.readText()
val repoFilesSource = tasks.register("repoFilesSource") {
    description = "Writes the RepoFiles test helper that reads the repoInputs manifest."
    val out = layout.buildDirectory.dir("generated/sources/repoFiles")
    val text = helperSource
    inputs.property("source", text)
    outputs.dir(out)
    doLast {
        val file = out.get().file("io/github/imnotstable/msc/buildlogic/RepoFiles.java").asFile
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}
sourceSets.test { java.srcDir(repoFilesSource) }

tasks.test {
    val manifest = temporaryDir.resolve("repo-files.txt")
    val sets = repoInputs.sets
    val root = repoInputs.root
    doFirst {
        manifest.writeText(repoFilesManifest(sets, root))
    }
    // The manifest's path through an argument provider, so the checkout's location stays out of the cache key.
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dmsc.repoFiles=" + manifest.absolutePath) })
}
