package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The core port of toolset attempts. Claude Code starts msc-core as its MCP server, and a core it leaves running after
 * it exits (no longer its child, so no tree kill reaches it) keeps the core port, which breaks every later toolset
 * attempt. Such a core carries the run's marks ({@link AttemptProcesses}; the runner adds them to the MCP config, in
 * its {@code env} and, for a Java server, on its command line), so the run's leftovers step stops it, and never a core
 * the run did not start (ADR 0044).
 */
public final class CoreProcesses {

    /** The main class every msc-core process runs. */
    static final String CORE_MAIN = "io.github.imnotstable.msc.core.Main";

    private CoreProcesses() {
    }

    /**
     * Makes sure {@code port} is free before a toolset attempt starts core on it. When it is taken, the processes the
     * run's earlier attempts left are killed first ({@code killLeftovers}, which returns how many); when it is still
     * taken, the attempt is refused naming the msc-core processes the run did not start ({@code ours} tells them
     * apart), such as the one of the Claude Code session the bench runs in, or another run's.
     */
    static void requireFreePort(int port, IntSupplier killLeftovers, Predicate<ProcessHandle> ours)
            throws IOException {
        if (!listening(port)) {
            return;
        }
        boolean killed = killLeftovers.getAsInt() > 0;
        for (int i = 0; killed && i < 50 && listening(port); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        if (!listening(port)) {
            return;
        }
        Function<ProcessHandle, Optional<List<String>>> arguments = ProcessArguments.snapshot();
        String others = ProcessHandle.allProcesses()
                .filter(p -> arguments.apply(p).map(a -> a.contains(CORE_MAIN)).orElse(false) && !ours.test(p))
                .map(p -> Long.toString(p.pid()))
                .collect(Collectors.joining(", "));
        throw new IOException("core port " + port + " is in use by a program this bench run did not start"
                + (others.isEmpty() ? "" : " (msc-core running as pid " + others + ")")
                + (AttemptProcesses.canProve() ? "" : "; this system shows no process's environment, so the "
                        + "bench stops only the msc-core processes this run marked on their command line")
                + "; stop it, or set corePort in bench.json to a free port");
    }

    /** True when something accepts connections on the loopback {@code port}. */
    static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
