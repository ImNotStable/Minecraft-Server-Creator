plugins {
    id("msc.java-conventions")
}

description = "Messages, operations and socket transport shared by msc-core and every platform agent."

dependencies {
    // Gson ships with Paper, Fabric (Minecraft) and Minestom, so agents can use the server's copy.
    api(libs.gson)
}
