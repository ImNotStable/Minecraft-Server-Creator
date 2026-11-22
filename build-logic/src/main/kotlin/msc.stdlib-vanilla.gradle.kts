// The Minecraft-only part of the mod-loader adapters (ADR 0066): one source folder, msc-stdlib-vanilla, compiled into
// msc-stdlib-fabric, -neoforge and -forge with its package renamed to the loader's own (stdlib.vanilla becomes
// stdlib.fabric), next to that loader's Stdlib, scheduler and event hooks. Each mod then has its own package, so two
// mods' copies never meet, and a project imports one package. Tests of the shared code run in msc-stdlib-fabric.
plugins {
    id("msc.stdlib-conventions")
}

val loader = project.name.removePrefix("msc-stdlib-")
val vanillaSources = tasks.register<Sync>("vanillaSources") {
    val target = "io.github.imnotstable.msc.stdlib.$loader"
    from(rootProject.file("msc-stdlib-vanilla/src/main/java/io/github/imnotstable/msc/stdlib/vanilla")) {
        into("io/github/imnotstable/msc/stdlib/$loader")
    }
    into(layout.buildDirectory.dir("generated/vanilla"))
    filter { line: String -> line.replace("io.github.imnotstable.msc.stdlib.vanilla", target) }
    inputs.property("target", target)
}

project.extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main").java.srcDir(vanillaSources)
