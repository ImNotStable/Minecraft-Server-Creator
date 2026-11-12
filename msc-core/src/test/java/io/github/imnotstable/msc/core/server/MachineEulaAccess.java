package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** Lets tests in other packages record an acceptance, which main code outside {@link EulaCommand} cannot. */
public final class MachineEulaAccess {
    private MachineEulaAccess() {
    }

    /** Writes an acceptance by "owner" on "pc" to {@code file} and returns it. */
    public static Path accepted(Path file) throws IOException {
        MachineEula.write(file, new MachineEula.Acceptance("owner", "pc", Instant.parse("2026-11-12T08:00:00Z")));
        return file;
    }
}
