package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.process.ProcessTree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The processes one bench run started for its attempts, and the only ones it ever kills (#577, #587; ADR 0044).
 *
 * <p>An agent can leave a server behind outside its own process tree: started with {@code nohup}, {@code setsid} or
 * {@code &} from a shell that then exited, through a Gradle daemon, or by msc-core's {@code server_detach}. No tree
 * kill reaches it, and on the bench's ports it fails every later attempt. To find such processes without touching
 * anyone else's, the run puts two variables into the environment of every process it starts for an attempt (the
 * agent, msc-core through the MCP config, the server, a check's process): {@link #RUN}, an id only this run knows,
 * and {@link #ATTEMPT}, the attempt folder. Every process those start inherits them. A process belongs to an attempt
 * folder when its environment, as the system shows it in {@code /proc/<pid>/environ} (Linux), holds this run's id
 * and an attempt inside that folder. A shell or {@code tail -f} the user started in or on an attempt folder and
 * another run's processes are never killed: a port such a process holds is refused, naming it.
 *
 * <p>Where the system shows no environment (Windows, macOS, another user's process), msc-core is still proven by its
 * command line (#595): the run puts {@link #RUN_ARGUMENT} with its id and {@link #ATTEMPT_ARGUMENT} with the attempt
 * folder on the command line of every Java MCP server of the config, and a process whose environment cannot be read
 * is the run's when one of its whole arguments is exactly this run's {@link #RUN_ARGUMENT} and every
 * {@link #ATTEMPT_ARGUMENT} it has (at least one) names an attempt inside the folder. Text that merely contains them
 * (a shell running a command that names them, a substring of a longer argument) never counts. Other processes whose
 * environment the system does not show are never killed.
 *
 * <p>A Gradle daemon an attempt started is left running, as its next build may reuse it; the server it forks gets the
 * attempt's environment of the build that forked it, and is killed.
 */
public final class AttemptProcesses {

    /** The variable that holds the run's id. */
    public static final String RUN = "MSC_BENCH_RUN";
    /** The variable that holds the attempt folder. */
    public static final String ATTEMPT = "MSC_BENCH_ATTEMPT";

    /** The main class of a Gradle daemon, which outlives the build that started it by design. */
    static final String GRADLE_DAEMON = "org.gradle.launcher.daemon.bootstrap.GradleDaemon";

    /** The argument that marks a Java process's command line with the run's id, which follows it. */
    public static final String RUN_ARGUMENT = "-Dmsc.bench.run=";
    /** The argument that marks a Java process's command line with the attempt folder, which follows it. */
    public static final String ATTEMPT_ARGUMENT = "-Dmsc.bench.attempt=";

    private final String run;
    private final LongFunction<Optional<Map<String, String>>> environments;
    private final Supplier<Function<ProcessHandle, Optional<List<String>>>> arguments;

    AttemptProcesses(String run) {
        this(run, AttemptProcesses::environment, ProcessArguments::snapshot);
    }

    /**
     * A run that reads environments with {@code environments} and arguments with a fresh {@code arguments} view per
     * pass (tests stand in for a system that shows no environment).
     */
    AttemptProcesses(String run, LongFunction<Optional<Map<String, String>>> environments,
            Supplier<Function<ProcessHandle, Optional<List<String>>>> arguments) {
        this.run = run;
        this.environments = environments;
        this.arguments = arguments;
    }

    /** The processes of a new run, with an id of its own. */
    public static AttemptProcesses newRun() {
        return new AttemptProcesses(UUID.randomUUID().toString());
    }

    /** The run's id, the value of {@link #RUN}. */
    public String run() {
        return run;
    }

    /** The variables that mark a process as started by this run for the attempt in {@code attempt}. */
    public Map<String, String> marks(Path attempt) {
        return Map.of(RUN, run, ATTEMPT, attempt.toAbsolutePath().normalize().toString());
    }

    /** The arguments that mark a Java process's command line as started by this run for the attempt in {@code attempt}. */
    public List<String> argumentMarks(Path attempt) {
        return List.of(RUN_ARGUMENT + run, ATTEMPT_ARGUMENT + attempt.toAbsolutePath().normalize());
    }

    /** Puts {@link #marks} into {@code environment}, over any value it had. */
    public void mark(Map<String, String> environment, Path attempt) {
        environment.putAll(marks(attempt));
    }

    /** True when this system shows a process's environment, so ownership can be proven at all. */
    public static boolean canProve() {
        return environment(ProcessHandle.current().pid()).isPresent();
    }

    /** Processes still running that this run started for an attempt in {@code folder}, never this bench or above it. */
    List<ProcessHandle> in(Path folder) {
        Path absolute = folder.toAbsolutePath().normalize();
        ProcessHandle self = ProcessHandle.current();
        Function<ProcessHandle, Optional<List<String>>> view = arguments.get();
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self.pid() && !isAncestor(p, self))
                .filter(p -> owns(p, absolute, view))
                .filter(p -> !runs(p, GRADLE_DAEMON))
                .toList();
    }

    /** True when {@code process} carries this run's marks for an attempt inside {@code folder}. */
    boolean owns(ProcessHandle process, Path folder) {
        return owns(process, folder, arguments.get());
    }

    /**
     * True when the environment of {@code process} holds this run's marks for an attempt inside {@code folder}, or,
     * only when the system shows no environment of it, its whole arguments do.
     */
    private boolean owns(ProcessHandle process, Path folder, Function<ProcessHandle, Optional<List<String>>> view) {
        Path inside = folder.toAbsolutePath().normalize();
        Optional<Map<String, String>> environment = environments.apply(process.pid());
        if (environment.isPresent()) {
            return run.equals(environment.get().get(RUN)) && inside(environment.get().get(ATTEMPT), inside);
        }
        Optional<List<String>> given = view.apply(process);
        if (given.isEmpty() || !given.get().contains(RUN_ARGUMENT + run)) {
            return false;
        }
        List<String> attempts = given.get().stream().filter(a -> a.startsWith(ATTEMPT_ARGUMENT))
                .map(a -> a.substring(ATTEMPT_ARGUMENT.length())).toList();
        return !attempts.isEmpty() && attempts.stream().allMatch(a -> inside(a, inside));
    }

    private static boolean inside(String attempt, Path folder) {
        try {
            return attempt != null && !attempt.isEmpty()
                    && Path.of(attempt).toAbsolutePath().normalize().startsWith(folder);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /**
     * Kills every process this run started for an attempt in {@code folder}, with its descendants, waits briefly for
     * each, and returns how many there were.
     */
    public int kill(Path folder) {
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
     * The environment {@code pid} started with, empty when the system does not show it: not Linux, the process is
     * gone, or it is another user's.
     */
    static Optional<Map<String, String>> environment(long pid) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of("/proc", Long.toString(pid), "environ"));
        } catch (IOException | UnsupportedOperationException | SecurityException | InvalidPathException e) {
            return Optional.empty();
        }
        Map<String, String> environment = new HashMap<>();
        for (String entry : new String(bytes, StandardCharsets.UTF_8).split("\0")) {
            int equals = entry.indexOf('=');
            if (equals > 0) {
                environment.putIfAbsent(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
        return Optional.of(environment);
    }

    /** True when {@code process} runs {@code mainClass}, as one of its arguments or on its command line. */
    static boolean runs(ProcessHandle process, String mainClass) {
        ProcessHandle.Info info = process.info();
        if (info.arguments().isPresent()) {
            return List.of(info.arguments().get()).contains(mainClass);
        }
        return info.commandLine().map(line -> line.contains(mainClass)).orElse(false);
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
}
