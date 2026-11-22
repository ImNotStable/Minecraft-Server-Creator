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

    /** How young a backup makes an edit skip its own; returns the old age. */
    public static Duration autoEvery(Duration every) {
        Duration old = WorldBackups.autoEvery;
        WorldBackups.autoEvery = every;
        return old;
    }
}
