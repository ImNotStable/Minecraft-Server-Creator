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
    // PluginTest reads these plugin files, so a change to one must rerun it.
    inputs.files(".mcp.json", ".claude-plugin/plugin.json", rootProject.file(".claude-plugin/marketplace.json"))
        .withPropertyName("pluginFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // Absolute paths through an argument provider, so the checkout's location stays out of the cache key.
    val coreDir = coreInstall.asFile.absolutePath
    val pluginDir = layout.projectDirectory.asFile.absolutePath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmsc.coreInstall=$coreDir", "-Dmsc.pluginDir=$pluginDir")
    })
}
