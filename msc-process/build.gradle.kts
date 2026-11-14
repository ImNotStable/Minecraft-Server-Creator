plugins {
    id("msc.java-conventions")
    // FakeRconServer, the vanilla-like RCON server the RCON tests of msc-process, msc-core and msc-bench share.
    `java-test-fixtures`
}

description = "Server process control shared by msc-core and msc-bench: the process tree kill, the RCON client, " +
    "the server.properties reader and writer, and the atomic file writer."
