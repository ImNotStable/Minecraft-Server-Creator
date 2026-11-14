package io.github.imnotstable.msc.process;

import java.util.Optional;

/** Kills a process with its descendants, for msc-core and msc-bench alike (#339, #357, #358). */
public final class ProcessTree {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private ProcessTree() {
    }

    /**
     * Forcibly ends {@code root} and every process proven to descend from it, including a child the tree forks while
     * this runs. A process that only records the root's pid as its parent, such as a Windows orphan of an older
     * process that had the same pid, is never touched; see {@link TreeKill} for the rule and the order of kills.
     */
    public static void destroy(ProcessHandle root) {
        LiveProcessTable table = new LiveProcessTable();
        Optional<ProcessTable.Proc> proc = table.add(root);
        if (proc.isPresent()) {
            TreeKill.destroy(table, proc.get(), !WINDOWS);
        } else if (root.isAlive()) {
            // Its start instant is unknown, so nothing below it can be proven: the root alone.
            root.destroyForcibly();
        }
    }
}
