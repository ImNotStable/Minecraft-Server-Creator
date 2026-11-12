package io.github.imnotstable.msc.core.tools;

import java.nio.file.Path;

/** Exposes package-private helpers of {@link LifecycleTools} to tests in other packages. */
public final class LifecycleToolsAccess {
    private LifecycleToolsAccess() {
    }

    public static String deployName(Path jar, Path project) {
        return DevCycle.deployName(jar, project);
    }
}
