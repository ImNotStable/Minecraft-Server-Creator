plugins {
    id("msc.java-conventions")
    alias(libs.plugins.shadow)
}

description = "Paper plugin that carries out msc-core's operations inside a running server."

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":msc-protocol")) {
        // Paper provides Gson at runtime.
        exclude(group = "com.google.code.gson")
    }

    testImplementation(libs.paper.api)
    testImplementation(libs.mockbukkit)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// The plugin jar bundles msc-protocol (Gson comes from Paper).
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
