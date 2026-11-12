plugins {
    id("msc.java-conventions")
}

description = "The Claude Code plugin: MCP config plus a launcher that runs the newest installed msc-core."

// The launcher is one source file the plugin runs with `java MscLauncher.java`. It lives outside src/ so the
// plugin folder stays small, and it compiles against Java 17 because the java on PATH may be older than 25.
sourceSets {
    main {
        java.setSrcDirs(listOf("launcher"))
    }
}

tasks.compileJava {
    options.release = 17
}

dependencies {
    testImplementation(libs.gson)
}

// The end-to-end test starts the real core through the launcher, from msc-core's installed distribution.
val coreInstall = rootProject.layout.projectDirectory.dir("msc-core/build/install/msc-core")
tasks.test {
    dependsOn(":msc-core:installDist")
    inputs.dir(coreInstall).withPropertyName("coreInstall").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("msc.coreInstall", coreInstall.asFile.absolutePath)
    systemProperty("msc.pluginDir", layout.projectDirectory.asFile.absolutePath)
}
