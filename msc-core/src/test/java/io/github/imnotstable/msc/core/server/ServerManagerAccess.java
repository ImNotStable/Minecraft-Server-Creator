package io.github.imnotstable.msc.core.server;

/** For tests outside this package: a core in this JVM that stands for another one, so two cores are two sessions. */
public final class ServerManagerAccess {
    private ServerManagerAccess() {
    }

    /** {@code server} belongs to the core {@code core} stands for: it owns what it starts while {@code core} runs. */
    public static void self(ServerManager server, ProcessHandle core) {
        server.self(PidFile.Identity.of(core));
    }
}
