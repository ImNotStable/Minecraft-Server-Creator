package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.process.ProcessTree;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The msc-core processes of toolset attempts. Claude Code starts core as its MCP server, and a core it leaves running
 * after it exits (no longer its child, so no tree kill reaches it) keeps the core port, which breaks every later
 * toolset attempt. The bench's MCP config puts {@link #MARKER} with the attempt folder first on core's command line,
 * so the bench finds and stops exactly the cores it started, and never another program's (ADR 0044).
 */
public final class CoreProcesses {

    /** The system property the toolset MCP config template gives core; its value is the attempt folder. */
    public static final String MARKER = "-Dmsc.bench.attempt=";

    /** The main class every msc-core process runs. */
    static final String CORE_MAIN = "io.github.imnotstable.msc.core.Main";

    private CoreProcesses() {
    }

    /** The marker argument of the attempt in {@code attemptDir} (the {@code {attemptDir}} placeholder's value). */
    static String marker(String attemptDir) {
        return MARKER + attemptDir;
    }

    /** Stops the processes carrying exactly {@code marker}, with their descendants, and returns how many. */
    static int stop(String marker) {
        List<ProcessHandle> found = ProcessHandle.allProcesses().filter(p -> hasArgument(p, marker)).toList();
        found.forEach(ProcessTree::destroy);
        return found.size();
    }

    /**
     * Makes sure {@code port} is free before a toolset attempt starts core on it. When it is taken, the cores of
     * earlier bench attempts are stopped first (a run cut short can leave them, and two runs on the same ports cannot
     * work anyway); when it is still taken, the attempt is refused naming the msc-core processes the bench did not
     * start, such as the one of the Claude Code session the bench runs in.
     */
    static void requireFreePort(int port) throws IOException {
        requireFreePort(port, () -> ProcessHandle.allProcesses().filter(p -> line(p).contains(MARKER)).toList());
    }

    /** {@link #requireFreePort(int)} with the bench's earlier cores from {@code benchCores}. */
    static void requireFreePort(int port, Supplier<List<ProcessHandle>> benchCores) throws IOException {
        if (!listening(port)) {
            return;
        }
        List<ProcessHandle> ours = benchCores.get();
        ours.forEach(ProcessTree::destroy);
        for (int i = 0; !ours.isEmpty() && i < 50 && listening(port); i++) {
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
        String others = ProcessHandle.allProcesses()
                .filter(p -> line(p).contains(CORE_MAIN) && !line(p).contains(MARKER))
                .map(p -> Long.toString(p.pid()))
                .collect(Collectors.joining(", "));
        throw new IOException("core port " + port + " is in use by a program the bench did not start"
                + (others.isEmpty() ? "" : " (msc-core running as pid " + others + ")")
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

    /**
     * True when {@code argument} is one of the process's arguments. Where the system gives no argument list
     * (Windows), the command line must hold it followed by a space or at its end, so the marker of
     * {@code toolset-1} does not match {@code toolset-1-try2}.
     */
    static boolean hasArgument(ProcessHandle process, String argument) {
        ProcessHandle.Info info = process.info();
        if (info.arguments().isPresent()) {
            return List.of(info.arguments().get()).contains(argument);
        }
        String line = info.commandLine().orElse("");
        return line.contains(argument + " ") || line.endsWith(argument);
    }

    private static String line(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        return info.commandLine().orElseGet(() -> String.join(" ", info.arguments().orElse(new String[0])));
    }
}
