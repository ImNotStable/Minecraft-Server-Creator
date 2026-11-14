package io.github.imnotstable.msc.bench;

import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.run.BenchRunner;
import io.github.imnotstable.msc.bench.run.ChildProcesses;
import io.github.imnotstable.msc.bench.run.RunIsolation;
import io.github.imnotstable.msc.bench.run.ResultsFile;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.run.ServerManager;
import io.github.imnotstable.msc.bench.run.Workspaces;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command line entry point.
 *
 * <pre>
 * list                                   list tasks
 * run [--tasks a,b] [--modes baseline,toolset] [--repeat N]
 * check &lt;task&gt; &lt;workspace&gt;                 run one task's checks on a folder (starts its server for RCON checks)
 * report &lt;runDir&gt;                         rewrite report.md from results.jsonl
 * common: --tasks-dir DIR (default tasks), --config FILE (default bench.json),
 *         --out DIR (default ~/.msc/bench-runs, or &lt;temp&gt;/msc-bench-runs when the home folder is inside a
 *         repository; must be outside any repository or CLAUDE.md folder)
 * A leading {@code ~} in a path argument is the home folder: Gradle's {@code --args="..."} and quoted shell
 * arguments pass it unexpanded.
 * </pre>
 *
 * <p>Exit codes: {@link #OK}, {@link #USAGE}, {@link #BAD_INPUT}, {@link #FAILED}, {@link #USAGE_LIMIT},
 * {@link #NOT_RECORDED} and {@link #INTERNAL_ERROR}; every refusal and error is one {@code msc-bench: } message on standard error, never a stack
 * trace.
 */
public final class Main {

    private static final Set<String> FLAGS = Set.of("--tasks", "--modes", "--repeat", "--tasks-dir", "--config", "--out");
    private static final Set<String> PATH_FLAGS = Set.of("--tasks-dir", "--config", "--out");

    /** Every attempt or check passed, or the command had nothing to judge ({@code list}, {@code report}). */
    static final int OK = 0;
    /** No command given: the usage is printed. */
    static final int USAGE = 1;
    /** A bad argument or input file, refused before anything was created. */
    static final int BAD_INPUT = 2;
    /** An attempt or check failed. */
    static final int FAILED = 3;
    /** {@code run} stopped early on an account usage limit. */
    static final int USAGE_LIMIT = 4;
    /** {@code run} stopped early: an attempt could not be written to {@code results.jsonl}. */
    static final int NOT_RECORDED = 5;
    /** An error the bench did not expect: a bug to report. */
    static final int INTERNAL_ERROR = 70;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(exec(args, System.out, System.err, Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("java.io.tmpdir"))));
    }

    /**
     * Runs the command and returns its exit code. Whatever goes wrong ends as one line on {@code err}: a refusal of
     * a bad argument or file as {@link #BAD_INPUT}, anything else as {@link #INTERNAL_ERROR}.
     */
    static int exec(String[] args, PrintStream out, PrintStream err, Path home, Path temp) {
        return guarded(err, () -> run(args, out, home, temp));
    }

    /** A command's body: returns its exit code, or throws. */
    @FunctionalInterface
    interface Command {
        int run() throws IOException;
    }

    /** {@code command}'s exit code, with every exception it throws turned into one line on {@code err} and a code. */
    static int guarded(PrintStream err, Command command) {
        try {
            return command.run();
        } catch (IOException | IllegalArgumentException e) {
            err.println("msc-bench: " + message(e));
            return BAD_INPUT;
        } catch (UncheckedIOException e) {
            err.println("msc-bench: " + message(e.getCause()));
            return BAD_INPUT;
        } catch (RuntimeException | Error e) {
            String text = e.getMessage() == null ? "" : e.getMessage().strip().lines().findFirst().orElse("");
            err.println("msc-bench: internal error (" + e.getClass().getName() + (text.isEmpty() ? "" : ": " + text)
                    + "); please report it");
            return INTERNAL_ERROR;
        }
    }

    /** The exception's message, with the path and what went wrong for a file-system error, whose message is bare. */
    static String message(Exception e) {
        if (e instanceof NoSuchFileException missing) {
            return missing.getFile() + " does not exist";
        }
        if (e instanceof AccessDeniedException denied) {
            return denied.getFile() + ": access denied";
        }
        if (e instanceof FileSystemException fs && fs.getReason() == null) {
            return fs.getFile() + ": " + fs.getClass().getSimpleName();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static int run(String[] args, PrintStream out) throws IOException {
        return run(args, out, Path.of(System.getProperty("user.home")), Path.of(System.getProperty("java.io.tmpdir")));
    }

    static int run(String[] args, PrintStream out, Path home, Path temp) throws IOException {
        List<String> positional = new ArrayList<>();
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (FLAGS.contains(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(args[i] + " needs a value");
                }
                flags.put(args[i], args[++i]);
            } else if (args[i].startsWith("--")) {
                throw new IllegalArgumentException("unknown option " + args[i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.isEmpty()) {
            out.println(usage());
            return USAGE;
        }
        flags.replaceAll((flag, value) -> PATH_FLAGS.contains(flag) ? expandHome(value, home).toString() : value);
        Path tasksDir = Path.of(flags.getOrDefault("--tasks-dir", "tasks"));
        return switch (positional.getFirst()) {
            case "list" -> {
                if (positional.size() != 1) {
                    throw new IllegalArgumentException("usage: list");
                }
                yield list(tasksDir, out);
            }
            case "run" -> {
                if (positional.size() != 1) {
                    throw new IllegalArgumentException("usage: run [--tasks a,b] [--modes m1,m2] [--repeat N]; "
                            + "unexpected " + positional.get(1));
                }
                yield runBench(tasksDir, flags, out, home, temp);
            }
            case "check" -> {
                if (positional.size() != 3) {
                    throw new IllegalArgumentException("usage: check <task> <workspace>");
                }
                yield check(tasksDir, positional.get(1), expandHome(positional.get(2), home), flags, home, out);
            }
            case "report" -> {
                if (positional.size() != 2) {
                    throw new IllegalArgumentException("usage: report <runDir>");
                }
                yield report(expandHome(positional.get(1), home), out);
            }
            default -> throw new IllegalArgumentException("unknown command " + positional.getFirst());
        };
    }

    static String usage() {
        return "usage: msc-bench list | run [--tasks a,b] [--modes m1,m2] [--repeat N] [--out DIR, default "
                + RunIsolation.DEFAULT_RUNS_SHOWN + "] | check <task> <workspace> | report <runDir>";
    }

    /**
     * The argument as a path, with a leading {@code ~} (alone or before a separator) as the home folder. Neither
     * Gradle's {@code --args} nor a quoted shell argument expands it, and {@code Path.of} would read it as a folder
     * named {@code ~} in msc-bench/.
     */
    static Path expandHome(String arg, Path home) {
        if (arg.equals("~")) {
            return home;
        }
        if (arg.startsWith("~/") || arg.startsWith("~\\")) {
            String rest = arg.substring(2);
            return rest.isEmpty() ? home : home.resolve(rest);
        }
        return Path.of(arg);
    }

    private static int list(Path tasksDir, PrintStream out) throws IOException {
        for (TaskDef task : RunSelection.all(tasksDir, TaskLoader.loadAll(tasksDir))) {
            out.printf("%-26s %-8s %-8s %d checks  %s%n", task.id(), task.category(), task.platform(),
                    task.checks().size(), task.title());
        }
        return OK;
    }

    private static int runBench(Path tasksDir, Map<String, String> flags, PrintStream out, Path home, Path temp)
            throws IOException {
        // Everything the run works on is read and checked first: a refusal leaves no run folder behind.
        int repeat = repeat(flags.getOrDefault("--repeat", "1"));
        Path configFile = Path.of(flags.getOrDefault("--config", "bench.json"));
        BenchConfig config = loadConfig(configFile, home);
        RunSelection selection = RunSelection.resolve(tasksDir, TaskLoader.loadAll(tasksDir), flags.get("--tasks"),
                config.modes().keySet(), configFile, flags.get("--modes"));
        // Attempts run in the run folder: inside this repository both modes would load its CLAUDE.md and sources.
        // The checked folder is the resolved one, and the run writes to that same folder (links, .. and all).
        Path runsDir = flags.containsKey("--out")
                ? RunIsolation.requireIsolated(Path.of(flags.get("--out")), home, temp)
                : RunIsolation.defaultRunsDir(home, temp);
        if (!flags.containsKey("--out") && !runsDir.equals(RunIsolation.resolve(RunIsolation.homeRunsDir(home)))) {
            out.println(RunIsolation.DEFAULT_RUNS_SHOWN + " is inside a project (your home folder is a repository or "
                    + "has a CLAUDE.md), so runs go to " + runsDir + "; the system may clear it, so pass --out to keep "
                    + "them elsewhere");
        }
        Path runDir = runsDir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Map<String, String> preambles = new HashMap<>();
        preambles.put(TaskDef.PAPER, readIfPresent(tasksDir.resolve("_preamble.md")));
        preambles.put(TaskDef.MINESTOM, readIfPresent(tasksDir.resolve("_preamble-minestom.md")));
        Path noServer = tasksDir.resolve("_preamble-no-server.md");
        if (Files.exists(noServer)) {
            preambles.put(BenchRunner.PAPER_NO_SERVER, Files.readString(noServer));
        }

        BenchRunner runner = new BenchRunner(config, runDir, preambles, out);
        List<RunRecord> records = runner.run(selection, repeat);
        Path reportFile = runDir.resolve("report.md");
        Files.writeString(reportFile, ReportWriter.render(records));
        out.println("Report: " + reportFile.toAbsolutePath());
        if (runner.notRecorded() != null) {
            out.println("Stopped early: an attempt could not be written to " + ResultsFile.NAME + " ("
                    + message(runner.notRecorded()) + "); the report covers the " + records.size() + " attempts "
                    + "recorded. Fix the cause, run the rest into a new folder and put the results.jsonl files "
                    + "together before `report`.");
            return NOT_RECORDED;
        }
        if (runner.stoppedBy() != null) {
            out.println("Stopped early on a usage limit (" + runner.stoppedBy() + "). After it resets, run the rest into "
                    + "a new folder and put the results.jsonl files together before `report`.");
            return USAGE_LIMIT;
        }
        return records.stream().allMatch(RunRecord::passed) ? OK : FAILED;
    }

    /** The {@code --repeat} value: a whole number of attempts per task and mode, at least 1. */
    static int repeat(String value) {
        int repeat;
        try {
            repeat = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--repeat " + value + " is not a whole number of attempts", e);
        }
        if (repeat < 1) {
            throw new IllegalArgumentException("--repeat " + value + ": a run needs at least 1 attempt of each task "
                    + "and mode");
        }
        return repeat;
    }

    /** The bench config {@code file}, refused when there is no such file. */
    private static BenchConfig loadConfig(Path file, Path home) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("config " + file.toAbsolutePath() + (Files.exists(file) ? " is not a file"
                    : " does not exist") + "; give --config a bench.json");
        }
        return BenchConfig.load(file, home);
    }

    private static String readIfPresent(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private static int check(Path tasksDir, String taskId, Path workspace, Map<String, String> flags, Path home,
            PrintStream out) throws IOException {
        TaskDef task = RunSelection.one(tasksDir, TaskLoader.loadAll(tasksDir), taskId);
        if (!Files.isDirectory(workspace)) {
            throw new IOException("workspace " + workspace.toAbsolutePath() + (Files.exists(workspace)
                    ? " is not a folder" : " does not exist") + "; give check an attempt's workspace folder");
        }
        // Without --config, checks that need no server run without bench.json; a --config given must be there.
        Path configFile = Path.of(flags.getOrDefault("--config", "bench.json"));
        BenchConfig config = flags.containsKey("--config") || Files.isRegularFile(configFile)
                ? loadConfig(configFile, home)
                : null;
        return check(task, workspace, config, out);
    }

    /**
     * Runs one task's checks on a workspace. RCON checks use a server already running on the RCON port and leave it
     * running; when none answers and the machine's owner accepted the EULA, the workspace's own {@code server/} is
     * started for the checks and stopped after them, as in a run.
     */
    static int check(TaskDef task, Path workspace, BenchConfig config, PrintStream out) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Map<String, String> values = Map.of(
                "workspace", workspace.toAbsolutePath().toString(),
                "gradlew", workspace.toAbsolutePath().resolve(windows ? "gradlew.bat" : "gradlew").toString(),
                "java", ProcessHandle.current().info().command().orElse("java"),
                "serverPort", Integer.toString(config == null ? 25566 : config.server().serverPort()),
                "scaffoldVersion", Workspaces.SCAFFOLD_VERSION);
        // Ctrl-C skips the finally blocks: the hook kills the server and command processes this check started.
        ChildProcesses children = new ChildProcesses();
        Thread hook = Thread.ofPlatform().name("msc-bench-check-abort").unstarted(children::killAll);
        Runtime.getRuntime().addShutdownHook(hook);
        try (ServerManager server = config != null && task.needsServer() && config.server().acceptEula()
                ? new ServerManager(workspace.resolve("server"), config.server(), Map.of(), children)
                : null) {
            return check(task, workspace, values, config, server, children, out);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // the hook is running or has run
            }
        }
    }

    private static int check(TaskDef task, Path workspace, Map<String, String> values, BenchConfig config,
            ServerManager server, ChildProcesses children, PrintStream out) throws IOException {
        RconClient[] running = new RconClient[1];
        CheckContext context = new CheckContext() {
            private RconClient rcon;

            @Override
            public Path workspace() {
                return workspace;
            }

            @Override
            public Map<String, String> placeholders() {
                return values;
            }

            @Override
            public RconClient rcon() throws IOException {
                if (config == null) {
                    throw new IOException("RCON checks need bench.json for the port and password");
                }
                if (rcon == null) {
                    try {
                        rcon = RconClient.connect("127.0.0.1", config.server().rconPort(),
                                config.server().rconPassword(), Duration.ofSeconds(30));
                        running[0] = rcon;
                    } catch (IOException e) {
                        if (server == null) {
                            throw e;
                        }
                        rcon = server.rcon();
                    }
                }
                return rcon;
            }

            @Override
            public Process start(ProcessBuilder builder) throws IOException {
                return children.start(builder);
            }
        };
        try {
            boolean allPassed = true;
            for (Check check : task.checks()) {
                CheckResult result = check.run(context);
                allPassed &= result.passed();
                out.printf("%s %s: %s%n", result.passed() ? "PASS" : "FAIL", result.name(), result.detail());
            }
            return allPassed ? OK : FAILED;
        } finally {
            if (running[0] != null) {
                running[0].close();
            }
        }
    }

    private static int report(Path runDir, PrintStream out) throws IOException {
        if (!Files.isDirectory(runDir)) {
            throw new IOException("run folder " + runDir.toAbsolutePath() + (Files.exists(runDir) ? " is not a folder"
                    : " does not exist") + ": give the run folder that `run` prints after Report:");
        }
        Path results = runDir.resolve(ResultsFile.NAME);
        if (!Files.isRegularFile(results)) {
            throw new IOException(runDir.toAbsolutePath() + " has no " + BenchRunner.RESULTS_FILE + ": give the run "
                    + "folder that `run` prints after Report:, such as " + RunIsolation.DEFAULT_RUNS_SHOWN
                    + "/<timestamp>");
        }
        List<RunRecord> records = ResultsFile.read(results, out::println);
        Path reportFile = runDir.resolve("report.md");
        Files.writeString(reportFile, ReportWriter.render(records));
        out.println("Report: " + reportFile.toAbsolutePath());
        return OK;
    }
}
