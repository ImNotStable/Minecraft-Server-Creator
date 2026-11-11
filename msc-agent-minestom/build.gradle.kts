plugins {
    id("msc.java-conventions")
    alias(libs.plugins.shadow)
}

description = "Minestom library that carries out msc-core's operations inside a running server."

dependencies {
    compileOnly(libs.minestom)
    implementation(project(":msc-protocol")) {
        // Minestom provides Gson at runtime.
        exclude(group = "com.google.code.gson")
    }

    testImplementation(libs.minestom)
    testImplementation(libs.minestom.testing)
}

tasks.test {
    // Minestom's test environment: lets test players connect from a platform thread.
    systemProperty("minestom.inside-test", "true")
}

// The library jar bundles msc-protocol, so a server project needs only this one file
// (msc-templates ships it in the minestom-server template as libs/msc-agent-minestom.jar).
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
