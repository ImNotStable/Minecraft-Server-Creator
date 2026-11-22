// The standard library modules (ADR 0066): versioned on their own and laid out as a Maven repository that
// msc-templates copies into scaffolded projects as libs/repo/. Only the pom comes from maven-publish: its
// publish tasks never count as up to date, so the jars and poms are copied into the layout by a Sync instead.
plugins {
    id("msc.java-conventions")
    `maven-publish`
}

val libs = the<VersionCatalogsExtension>().named("libs")
version = libs.findVersion("msc-stdlib").get().requiredVersion

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// build/repo/<group path>/<artifact>/<version>/<artifact>-<version>[-sources].{jar,pom}
// API.md: one line per public member, for agents to grep (ADR 0066).
val apiIndex = tasks.register<io.github.imnotstable.msc.buildlogic.StdlibApiIndex>("stdlibApiIndex") {
    sources.from(project.extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main").java.sourceDirectories)
    title = "${project.name} ${project.version}: io.github.imnotstable.msc.stdlib"
    output = layout.buildDirectory.file("api/${project.name}-API.md")
}

tasks.register<Sync>("stdlibRepo") {
    val repoDir = "${project.group.toString().replace('.', '/')}/${project.name}/${project.version}"
    val pom = "${project.name}-${project.version}.pom"
    // Synced at the repo root, so an older version's folder is removed when the version changes.
    into(layout.buildDirectory.dir("repo"))
    from(tasks.named("jar")) { into(repoDir) }
    from(tasks.named("sourcesJar")) { into(repoDir) }
    from(apiIndex) { into(repoDir) }
    from(tasks.named("generatePomFileForMavenPublication")) {
        into(repoDir)
        rename { pom }
    }
}

// sqlite-jdbc loads its native library; say so instead of printing a warning on every run.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// NeoForge and Forge load nested jars as Java modules: each needs a stable module name, and the loader adapters
// (which use Minecraft classes) must load with the game (FMLModType GAMELIBRARY). Other platforms ignore both.
tasks.named<Jar>("jar") {
    val moduleName = "io.github.imnotstable.msc." + project.name.removePrefix("msc-").replace('-', '.')
    val gameLibrary = project.name == "msc-stdlib-neoforge" || project.name == "msc-stdlib-forge"
    manifest {
        attributes("Automatic-Module-Name" to moduleName)
        if (gameLibrary) {
            attributes("FMLModType" to "GAMELIBRARY")
        }
    }
}
