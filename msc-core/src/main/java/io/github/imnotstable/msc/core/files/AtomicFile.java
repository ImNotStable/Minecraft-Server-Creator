package io.github.imnotstable.msc.core.files;

import java.nio.file.Path;

/**
 * Forwards to {@link io.github.imnotstable.msc.process.AtomicFile}, which moved to msc-process (ADR 0034). Kept only
 * for {@code DevCycle}'s import, which another change is editing; delete it once that import names msc-process.
 */
public final class AtomicFile {
    private AtomicFile() {
    }

    /** See {@link io.github.imnotstable.msc.process.AtomicFile#sameFile}. */
    public static boolean sameFile(Path a, Path b) {
        return io.github.imnotstable.msc.process.AtomicFile.sameFile(a, b);
    }
}
