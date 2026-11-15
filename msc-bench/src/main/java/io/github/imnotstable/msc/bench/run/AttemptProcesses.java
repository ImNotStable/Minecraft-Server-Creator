package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.process.ProcessTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The processes an attempt left running after its agent exited (#577). An agent can leave a server behind outside its
 * own process tree: started with {@code nohup}, {@code setsid} or {@code &} from a shell that then exited, or through
 * msc-core's {@code server_detach}. Such a process is no descendant of anything the bench started any more, so no tree
 * kill reaches it, and on the bench's game port it fails every later attempt that needs that port.
 *
 * <p>A process is proven to belong to an attempt folder when it runs inside it (its working folder, where the system
 * shows it: Linux) or names it on its command line (a jar or classpath in the workspace, msc-core's
 * {@code -Dmsc.bench.attempt=} marker). A Gradle daemon the attempt used runs in Gradle's own folder and stays.
 */
public final class AttemptProcesses {

    private AttemptProcesses() {
    }

    /** Processes still running that belong to {@code folder}, never this bench or a process above it. */
    static List<ProcessHandle> in(Path folder) {
        Path absolute = folder.toAbsolutePath().normalize();
        Path real = realPath(absolute);
        Pattern named = named(absolute);
        ProcessHandle self = ProcessHandle.current();
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self.pid() && !isAncestor(p, self))
                .filter(p -> runsIn(p, absolute, real) || namedOnCommandLine(p, named))
                .toList();
    }

    /**
     * Kills every process that belongs to {@code folder} with its descendants, waits briefly for each, and returns how
     * many there were.
     */
    public static int kill(Path folder) {
        List<ProcessHandle> found = in(folder);
        for (ProcessHandle process : found) {
            ProcessTree.destroy(process);
        }
        for (ProcessHandle process : found) {
            try {
                process.onExit().get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException e) {
                // killed forcibly; a process that outlives the wait is reported by the ports step that follows
            }
        }
        return found.size();
    }

    /**
     * The folder written on a command line, followed by its end, a path separator or a character that ends an
     * argument, so {@code toolset-1} does not match {@code toolset-1-try2}. Either slash, as the runner writes paths
     * with {@code /} on every system.
     */
    private static Pattern named(Path folder) {
        String native_ = folder.toString();
        String slashes = native_.replace('\\', '/');
        return Pattern.compile("(?:" + Pattern.quote(native_) + "|" + Pattern.quote(slashes) + ")(?=$|[/\\\\\\s\"';:,*])");
    }

    private static boolean namedOnCommandLine(ProcessHandle process, Pattern named) {
        ProcessHandle.Info info = process.info();
        Optional<String[]> arguments = info.arguments();
        if (arguments.isPresent()) {
            for (String argument : arguments.get()) {
                if (named.matcher(argument).find()) {
                    return true;
                }
            }
            return info.command().map(c -> named.matcher(c).find()).orElse(false);
        }
        return info.commandLine().map(c -> named.matcher(c).find()).orElse(false);
    }

    /** True when the system shows {@code process}'s working folder (Linux) and it is inside {@code folder}. */
    private static boolean runsIn(ProcessHandle process, Path folder, Path real) {
        Path link = Path.of("/proc", Long.toString(process.pid()), "cwd");
        try {
            Path cwd = Files.readSymbolicLink(link);
            return cwd.startsWith(folder) || cwd.startsWith(real);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false; // not Linux, gone, or another user's process
        }
    }

    private static boolean isAncestor(ProcessHandle candidate, ProcessHandle of) {
        Optional<ProcessHandle> parent = of.parent();
        while (parent.isPresent()) {
            if (parent.get().pid() == candidate.pid()) {
                return true;
            }
            parent = parent.get().parent();
        }
        return false;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }
}
