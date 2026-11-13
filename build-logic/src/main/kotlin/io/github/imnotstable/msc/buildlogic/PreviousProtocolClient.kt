package io.github.imnotstable.msc.buildlogic

import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.JavaForkOptions

/**
 * The previous-protocol test player client (msc.previous-protocol). [jars] are the resolved MCProtocolLib jars for
 * [version]; [stage] copies them into [root]/<version>/, the layout msc-bot's Bots reads from -Dmsc.bot.protocols.
 */
open class PreviousProtocolClient(
    val jars: FileCollection,
    val version: Provider<String>,
    val root: Provider<Directory>,
    val stage: TaskProvider<Sync>,
) {
    /** The folder the core distribution ships the jars in, relative to its root. */
    val distributionDir: Provider<String> get() = version.map { "bot/$it" }

    /** Stages the jars before [task] runs and passes their root to it as -Dmsc.bot.protocols. */
    fun <T> passTo(task: T) where T : Task, T : JavaForkOptions {
        task.dependsOn(stage)
        val dir = root
        task.jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dmsc.bot.protocols=" + dir.get().asFile.absolutePath)
        })
    }
}
