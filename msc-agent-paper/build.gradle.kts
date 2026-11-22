plugins {
    id("msc.java-conventions")
    alias(libs.plugins.shadow)
}

description = "Paper plugin that carries out msc-core's operations inside a running server."

dependencies {
    compileOnly(libs.paper.api)
    // Shared agent code; brings msc-protocol. Paper provides Gson at runtime.
    implementation(project(":msc-agent-common")) {
        exclude(group = "com.google.code.gson")
    }

    testImplementation(libs.paper.api.mockbukkit)
    testImplementation(libs.mockbukkit)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// The plugin jar bundles msc-agent-common and msc-protocol (Gson comes from Paper).
tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
