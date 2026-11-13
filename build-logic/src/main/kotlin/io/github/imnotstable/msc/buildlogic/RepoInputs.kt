package io.github.imnotstable.msc.buildlogic

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * The files outside its own sources a module's tests read (msc.repo-inputs, #129, #355). Each named set is declared
 * as an input of the `test` task and is the only list the tests get, through the generated `RepoFiles` helper: what
 * the tests read and what Gradle tracks come from this one definition, so a change to any of those files reruns them.
 */
open class RepoInputs(
    private val objects: ObjectFactory,
    private val test: TaskProvider<Test>,
    /** The repository root the manifest's paths are relative to. */
    val root: File,
) {
    internal val sets = linkedMapOf<String, ConfigurableFileCollection>()

    /** Adds [paths] (files, file trees, anything `files()` takes) to the set the tests read as [name]. */
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
}

/** The manifest `RepoFiles` reads: `root<TAB>dir`, then one `set<TAB>relative/path` line per file. */
internal fun repoFilesManifest(sets: Map<String, FileCollection>, root: File): String {
    val lines = mutableListOf("root\t" + root.absolutePath)
    for ((name, files) in sets) {
        files.files.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .forEach { lines += "$name\t$it" }
    }
    return lines.joinToString("\n", postfix = "\n")
}
