package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** Lets tests in other packages record an acceptance, which main code outside {@link EulaCommand} cannot. */
public final class MachineEulaAccess {
    private MachineEulaAccess() {
    }

    /** Writes an acceptance by "owner" on "pc" for the owner whose home is {@code home} and returns its file. */
    public static Path accepted(Path home) throws IOException {
        MachineEula.forHome(home).recordAcceptance(new MachineEula.Acceptance("owner", "pc", Instant.parse("2026-11-12T08:00:00Z")),
                MachineEula.VIA_TERMINAL);
        return home.resolve(".msc/eula.json");
    }
}
