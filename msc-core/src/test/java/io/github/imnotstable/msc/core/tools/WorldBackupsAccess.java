package io.github.imnotstable.msc.core.tools;

import java.time.Duration;

/** Exposes package-private settings of {@link WorldBackups} to tests in other packages. */
public final class WorldBackupsAccess {
    private WorldBackupsAccess() {
    }

    /** How long a backup waits for a server without an agent to confirm save-all flush; returns the old wait. */
    public static Duration saveWait(Duration wait) {
        Duration old = WorldBackups.saveWait;
        WorldBackups.saveWait = wait;
        return old;
    }
}
