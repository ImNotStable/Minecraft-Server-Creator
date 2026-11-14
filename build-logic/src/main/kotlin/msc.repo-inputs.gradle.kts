// Tests that read files outside their module's sources (docs, other modules, shared fixtures) get them only from the
// named sets declared with `repoInputs.set(...)` (or `repoInputs.on(task).set(...)` for another Test task): the same
// sets are that task's inputs, so what the tests read and what Gradle tracks cannot drift apart (#129, #355). The
// tests read them through the generated class io.github.imnotstable.msc.buildlogic.RepoFiles, which refuses a path
// no set of the running task declares. A set every file of the repository is in belongs on its own task (#370).
import io.github.imnotstable.msc.buildlogic.RepoInputs

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
