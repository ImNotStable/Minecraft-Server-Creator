plugins {
    id("msc.java-conventions")
    // FakeRconServer, the vanilla-like RCON server the RCON tests of msc-process, msc-core and msc-bench share.
    `java-test-fixtures`
}

description = "Server process control shared by msc-core, msc-bench and msc-bot: the process tree kill, the RCON " +
    "client, the server.properties reader and writer with the server address read from it (ServerAddress), the " +
    "HTTP stall rule (HttpStall), the UTF-8 console flags of a server's Java (ServerJava) and the atomic file writer."
