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
tasks.register<Sync>("stdlibRepo") {
    val repoDir = "${project.group.toString().replace('.', '/')}/${project.name}/${project.version}"
    val pom = "${project.name}-${project.version}.pom"
    from(tasks.named("jar"))
    from(tasks.named("sourcesJar"))
    from(tasks.named("generatePomFileForMavenPublication")) { rename { pom } }
    into(layout.buildDirectory.dir("repo/$repoDir"))
}

// sqlite-jdbc loads its native library; say so instead of printing a warning on every run.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
