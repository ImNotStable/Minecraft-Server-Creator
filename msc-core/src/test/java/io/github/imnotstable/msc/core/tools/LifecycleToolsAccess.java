package io.github.imnotstable.msc.core.tools;

import java.nio.file.Path;
import java.util.function.BiPredicate;

/** Exposes package-private helpers of {@link LifecycleTools} to tests in other packages. */
public final class LifecycleToolsAccess {
    private LifecycleToolsAccess() {
    }

    public static String deployName(Path jar, Path project) {
        return DevCycle.deployName(jar, project);
    }

    /** How dev_cycle on this thread tells two names of one file apart; null: the file system's answer. */
    public static void sameFile(BiPredicate<Path, Path> sameFile) {
        DevCycle.sameFileForTests(sameFile);
    }
}
