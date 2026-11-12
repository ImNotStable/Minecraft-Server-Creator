plugins {
    id("msc.java-conventions")
}

description = "Platform-neutral agent code shared by the Paper, Fabric and Minestom agents: the operations, " +
    "sliced jobs, undo history and the connection to msc-core."

dependencies {
    // Agents exclude Gson from msc-protocol because every server ships it; they do the same here.
    api(project(":msc-protocol"))
}
