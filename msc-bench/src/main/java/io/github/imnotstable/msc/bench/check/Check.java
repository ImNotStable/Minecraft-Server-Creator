package io.github.imnotstable.msc.bench.check;

import java.nio.file.Path;

/** One pass/fail check run against a finished workspace (and the server, if the check needs it). */
public interface Check {

    /** Short label used in results and reports. */
    String name();

    /** True when the check talks to the running server over RCON. */
    default boolean needsServer() {
        return false;
    }

    /**
     * True when the check starts a server of its own on the bench's game port, as the {@code player} check starts the
     * jar the workspace built: whatever holds the bench's ports must be stopped before it runs (#577).
     */
    default boolean startsServer() {
        return false;
    }

    /**
     * The task folder this check copies into the workspace while it runs, such as tests the agent never saw (a
     * {@code command} check's {@code addFiles}), or null.
     */
    default Path addFiles() {
        return null;
    }

    /** Runs the check. Implementations report problems as a failed result instead of throwing. */
    CheckResult run(CheckContext context);
}
