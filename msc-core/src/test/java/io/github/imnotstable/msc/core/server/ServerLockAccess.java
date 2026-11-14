package io.github.imnotstable.msc.core.server;

import java.time.Duration;

/**
 * For tests where another core holds {@code .msc/server.lock} throughout the call: the refusal is certain however long
 * the call waits, so it waits {@link #HELD} instead of the production {@link ServerPidFile#WAIT} (#497).
 */
public final class ServerLockAccess {
    /** The wait for a lock that is held until the test ends: any wait refuses, so a short one. */
    public static final Duration HELD = Duration.ofMillis(100);

    private ServerLockAccess() {
    }

    /** Makes {@code server} refuse after {@link #HELD} when another core holds the lock. */
    public static void refuseQuickly(ServerManager server) {
        server.lockWait(HELD);
    }
}
