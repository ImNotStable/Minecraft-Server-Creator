package io.github.imnotstable.msc.core.server;

import java.nio.file.Path;

/** For tests outside this package: a pid file naming a server and the core that started it, as another core wrote it. */
public final class PidFileAccess {
    private PidFileAccess() {
    }

    public static void write(Path file, ProcessHandle process, PidFile.Identity owner) {
        PidFile.write(file, process, owner);
    }
}
