package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.process.ProcessTree;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The processes a run started that are still alive, so they can be killed with their descendants when the run is
 * cut short (Ctrl-C ends the bench JVM without running its {@code finally} blocks). Once {@link #killAll} has run,
 * no new process starts: the main thread keeps running while shutdown hooks do.
 */
public final class ChildProcesses {

    private final Set<Process> live = new LinkedHashSet<>();
    private boolean closed;

    /**
     * Starts {@code builder}'s process and tracks it until it exits. On Windows its command is first encoded so each
     * argument reaches the program whole ({@link WindowsCommandLine}, #624).
     */
    public synchronized Process start(ProcessBuilder builder) throws IOException {
        if (closed) {
            throw new IOException("the bench is shutting down");
        }
        Process process = WindowsCommandLine.prepare(builder).start();
        live.add(process);
        process.onExit().thenRun(() -> forget(process));
        return process;
    }

    private synchronized void forget(Process process) {
        live.remove(process);
    }

    /** True once {@link #killAll} ran. */
    public synchronized boolean closed() {
        return closed;
    }

    /** Kills every tracked process with its descendants, and refuses later starts. */
    public void killAll() {
        List<Process> processes;
        synchronized (this) {
            closed = true;
            processes = new ArrayList<>(live);
            live.clear();
        }
        processes.forEach(ChildProcesses::killTree);
    }

    /**
     * Kills a process with every process proven to descend from it, including a child forked during the kill, and
     * never a process that only records its pid as a parent (msc-process's {@link ProcessTree}, #358). Then waits
     * briefly for it.
     */
    public static void killTree(Process process) {
        ProcessTree.destroy(process.toHandle());
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
