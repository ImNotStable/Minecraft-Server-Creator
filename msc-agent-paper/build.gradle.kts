plugins {
    id("msc.java-conventions")
}

description = "Paper plugin that carries out msc-core's operations inside a running server."

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":msc-protocol")) {
        // Paper provides Gson at runtime.
        exclude(group = "com.google.code.gson")
    }
}
