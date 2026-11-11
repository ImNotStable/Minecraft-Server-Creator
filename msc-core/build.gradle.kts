plugins {
    id("msc.application-conventions")
}

description = "The MCP server the AI talks to."

dependencies {
    implementation(project(":msc-protocol"))
    implementation(project(":msc-templates"))
    implementation(project(":msc-textures"))
    implementation(project(":msc-renderer"))
    implementation(libs.mcp.sdk)
}

application {
    mainClass = "io.github.imnotstable.msc.core.Main"
}
