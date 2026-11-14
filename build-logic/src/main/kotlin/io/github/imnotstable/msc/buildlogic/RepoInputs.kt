package io.github.imnotstable.msc.buildlogic

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * The files outside its own sources a module's tests read (msc.repo-inputs, #129, #355). Each named set is declared
 * as an input of one Test task and is the only list that task's tests get, through the generated `RepoFiles` helper:
 * what the tests read and what Gradle tracks come from this one definition, so a change to any of those files reruns
 * them. `repoInputs.set(...)` feeds the module's `test` task; `repoInputs.on(task).set(...)` feeds another Test task,
 * so a check that reads a repository-wide set reruns alone instead of taking the whole `test` suite with it (#370).
 */
open class RepoInputs(
    private val objects: ObjectFactory,
    private val test: TaskProvider<Test>,
    /** The repository root the manifest's paths are relative to. */
    val root: File,
) {
    internal val sets = linkedMapOf<String, ConfigurableFileCollection>()
    private val others = linkedMapOf<String, RepoInputs>()

    init {
        wire(test, sets, root)
    }

    /** Adds [paths] (files, file trees, anything `files()` takes) to the set this task's tests read as [name]. */
    fun set(name: String, vararg paths: Any) {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) { "set name: letters and digits, got '$name'" }
        val files = sets.getOrPut(name) {
            objects.fileCollection().also { created ->
                test.configure {
                    inputs.files(created).withPropertyName("repoFiles.$name")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
        files.from(*paths)
    }

    /** The sets of [task], another Test task of this module: only its tests see them, and only it reruns on them. */
    fun on(task: TaskProvider<Test>): RepoInputs {
        require(task.name != test.name) { "'${task.name}' is the task repoInputs.set(...) already feeds" }
        return others.getOrPut(task.name) { RepoInputs(objects, task, root) }
    }
}

/** Writes [task]'s manifest before it runs and hands its path to the tests as `msc.repoFiles`. */
private fun wire(task: TaskProvider<Test>, sets: Map<String, FileCollection>, root: File) {
    task.configure {
        val manifest = temporaryDir.resolve("repo-files.txt")
        val name = path
        doFirst {
            manifest.writeText(repoFilesManifest(name, sets, root))
        }
        // The manifest's path through an argument provider, so the checkout's location stays out of the cache key.
        jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dmsc.repoFiles=" + manifest.absolutePath) })
    }
}

/**
 * The manifest `RepoFiles` reads: `root<TAB>dir`, `task<TAB>path`, then one `set<TAB>relative/path` line per file.
 */
internal fun repoFilesManifest(task: String, sets: Map<String, FileCollection>, root: File): String {
    val lines = mutableListOf("root\t" + root.absolutePath, "task\t" + task)
    for ((name, files) in sets) {
        files.files.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .forEach { lines += "$name\t$it" }
    }
    return lines.joinToString("\n", postfix = "\n")
}
