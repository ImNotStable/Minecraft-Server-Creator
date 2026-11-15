package io.github.imnotstable.msc.bench;

import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.run.PidNamespace;
import io.github.imnotstable.msc.bench.run.BenchRunner;
import io.github.imnotstable.msc.bench.run.ChildProcesses;
import io.github.imnotstable.msc.bench.run.Disk;
import io.github.imnotstable.msc.bench.run.RunFolder;
import io.github.imnotstable.msc.bench.run.RunFolderUnwritable;
import io.github.imnotstable.msc.bench.run.RunIsolation;
import io.github.imnotstable.msc.bench.run.ResultsFile;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.run.ServerManager;
import io.github.imnotstable.msc.bench.run.StepFailed;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Command line entry point.
 *
 * <pre>
 * list [--tasks-dir DIR]                 list tasks
 * run [--tasks a,b] [--modes baseline,toolset] [--repeat N] [--model M] [--isolate] [--out DIR] [--tasks-dir DIR]
 *     [--config FILE]
 * check &lt;task&gt; &lt;workspace&gt; [--tasks-dir DIR] [--config FILE]
 *                                        run one task's checks on a folder (starts its server for RCON checks)
 * report &lt;runDir&gt;                         rewrite report.md from results.jsonl; the report.md path that run
 *                                        prints after Report: names its run folder too
 * --tasks-dir defaults to tasks, --config to bench.json, --out to ~/.msc/bench-runs (or &lt;temp&gt;/msc-bench-runs
 * when the home folder is inside a repository; it must be outside any repository or CLAUDE.md folder). Each command
 * takes only its own flags, each at most once ({@link CommandLine}).
 * A leading {@code ~} in a path argument is the home folder: Gradle's {@code --args="..."} and quoted shell
 * arguments pass it unexpanded.
 * </pre>
 *
 * <p>Exit codes: {@link #OK}, {@link #USAGE}, {@link #BAD_INPUT}, {@link #FAILED}, {@link #USAGE_LIMIT},
 * {@link #NOT_WRITTEN} and {@link #INTERNAL_ERROR}; every refusal and error is one {@code msc-bench: } message on standard error, never a stack
 * trace.
 */
public final class Main {

    /** The report's name inside a run folder. */
    static final String REPORT = "report.md";

    /** Every attempt or check passed, or the command had nothing to judge ({@code list}, {@code report}). */
    static final int OK = 0;
    /** No command given: the usage is printed. */
    static final int USAGE = 1;
    /** A bad argument or input file, refused before anything was created. */
    static final int BAD_INPUT = 2;
    /**
     * An attempt or check failed, or a step of {@code check}'s own work on the inputs it accepted, such as freeing the
     * bench's ports or the reset ({@link StepFailed}), as the same step fails an attempt in a run.
     */
    static final int FAILED = 3;
    /** {@code run} stopped early on an account usage limit. */
    static final int USAGE_LIMIT = 4;
    /**
     * One of the bench's own output files could not be written ({@link RunFolderUnwritable}), such as on a full disk:
     * {@code run} could not create its run folder, or stopped early on an attempt's folder or files or
     * {@code results.jsonl}; {@code report} could not write {@code report.md} (#644). Never a bad argument: the
     * message names the file. A {@code report.md} that {@code run} cannot write at its end is a warning instead
     * ({@link #endRun}).
     */
    static final int NOT_WRITTEN = 5;
    /** An error the bench did not expect: a bug to report. */
    static final int INTERNAL_ERROR = 70;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(exec(args, System.out, System.err, Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("java.io.tmpdir"))));
    }

    /**
     * Runs the command and returns its exit code. Whatever goes wrong ends as one line on {@code err}, with the code
     * {@link #guarded} gives it.
     */
    static int exec(String[] args, PrintStream out, PrintStream err, Path home, Path temp) {
        return guarded(err, () -> run(args, out, home, temp));
    }

    /** A command's body: returns its exit code, or throws. */
    @FunctionalInterface
    interface Command {
        int run() throws IOException;
    }

    /**
     * {@code command}'s exit code, with every exception it throws turned into one line on {@code err} and a code. This
     * is the one place that decides which failure gets which code (#639): a step of the bench's own work that failed
     * ({@link StepFailed}) is {@link #FAILED}, as the same step fails an attempt in a run; one of the bench's own
     * output files that could not be written ({@link RunFolderUnwritable}, naming the file) is {@link #NOT_WRITTEN}
     * (#644); any other IO failure or refused argument is a bad argument or input file, {@link #BAD_INPUT}; anything
     * else is {@link #INTERNAL_ERROR}.
     */
    static int guarded(PrintStream err, Command command) {
        try {
            return command.run();
        } catch (StepFailed e) {
            err.println("msc-bench: " + oneLine(e.getMessage()));
            return FAILED;
        } catch (RunFolderUnwritable e) {
            err.println("msc-bench: " + oneLine(e.getMessage()));
            return NOT_WRITTEN;
        } catch (IOException | IllegalArgumentException e) {
            err.println("msc-bench: " + oneLine(message(e)));
            return BAD_INPUT;
        } catch (UncheckedIOException e) {
            err.println("msc-bench: " + oneLine(message(e.getCause())));
            return BAD_INPUT;
        } catch (RuntimeException | Error e) {
            String text = e.getMessage() == null ? ""
                    : Quoted.message(e.getMessage().strip().lines().findFirst().orElse(""));
            err.println("msc-bench: internal error (" + e.getClass().getName() + (text.isEmpty() ? "" : ": " + text)
                    + "); please report it");
            return INTERNAL_ERROR;
        }
    }

    /**
     * {@code message} on one line, the last guard of the one-line promise (#508) for a message nobody foresaw: a line
     * break after a colon becomes a space, any other one {@code "; "}, so a list such as bench.json's problems keeps
     * every entry.
     */
    static String oneLine(String message) {
        return message.strip().replaceAll(":\\s*\\R\\s*", ": ").replaceAll("\\s*\\R\\s*", "; ");
    }

    /** The exception's message, with the path and what went wrong for a file-system error, whose message is bare. */
    static String message(Exception e) {
        return StepFailed.describe(e);
    }

    static int run(String[] args, PrintStream out) throws IOException {
        return run(args, out, Path.of(System.getProperty("user.home")), Path.of(System.getProperty("java.io.tmpdir")));
    }

    static int run(String[] args, PrintStream out, Path home, Path temp) throws IOException {
        return run(args, out, home, temp, null);
    }

    /**
     * {@link #run(String[], PrintStream, Path, Path)}, judging whether the run folder is inside a project no higher
     * than {@code top} (null: the file-system root), as tests that run inside this repository need.
     */
    static int run(String[] args, PrintStream out, Path home, Path temp, Path top) throws IOException {
        CommandLine line = CommandLine.parse(args, home);
        if (line.command() == null) {
            out.println(usage());
            return USAGE;
        }
        Map<String, String> flags = line.flags();
        List<String> rest = line.positional();
        Path tasksDir = Path.of(flags.getOrDefault("--tasks-dir", "tasks"));
        return switch (line.command()) {
            case "list" -> {
                if (!rest.isEmpty()) {
                    throw new IllegalArgumentException("usage: list " + CommandLine.options("list"));
                }
                yield list(tasksDir, out);
            }
            case "run" -> {
                if (!rest.isEmpty()) {
                    throw new IllegalArgumentException("usage: run " + CommandLine.options("run") + "; unexpected "
                            + rest.getFirst());
                }
                yield runBench(tasksDir, flags, out, home, temp, top);
            }
            case "check" -> {
                if (rest.size() != 2) {
                    throw new IllegalArgumentException("usage: check <task> <workspace> "
                            + CommandLine.options("check"));
                }
                yield check(tasksDir, rest.get(0), expandHome(rest.get(1), home), flags, home, out);
            }
            case "report" -> {
                if (rest.size() != 1) {
                    throw new IllegalArgumentException("usage: report <runDir>");
                }
                yield report(runFolder(expandHome(rest.getFirst(), home)), out, Disk.REAL);
            }
            default -> throw new IllegalStateException("no case for command " + line.command());
        };
    }

    static String usage() {
        return "usage: msc-bench list " + CommandLine.options("list") + " | run " + CommandLine.options("run")
                + " | check <task> <workspace> " + CommandLine.options("check") + " | report <runDir>; --out DIR, default "
                + RunIsolation.DEFAULT_RUNS_SHOWN + "; a flag is given at most once";
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
            // Each text a task.json or a folder name gave is shown as run text is (#677): on one line, nothing hidden.
            out.printf("%-26s %-8s %-8s %d checks  %s%n", Quoted.visible(task.id()), Quoted.visible(task.category()),
                    Quoted.visible(task.platform()), task.checks().size(), Quoted.visible(task.title()));
        }
        return OK;
    }

    private static int runBench(Path tasksDir, Map<String, String> flags, PrintStream out, Path home, Path temp,
            Path top) throws IOException {
        // Everything the run works on is read and checked first: a refusal leaves no run folder behind.
        int repeat = repeat(flags.getOrDefault("--repeat", "1"));
        Path configFile = Path.of(flags.getOrDefault("--config", "bench.json"));
        BenchConfig config = loadConfig(configFile, home);
        if (flags.containsKey("--model")) {
            config = config.withModel(flags.get("--model"));
        }
        if (flags.containsKey("--isolate")) {
            config = config.withAgentPrefix(PidNamespace.require());
        }
        RunSelection selection = RunSelection.resolve(tasksDir, TaskLoader.loadAll(tasksDir), flags.get("--tasks"),
                config.modes().keySet(), configFile, flags.get("--modes"));
        config.requirePaths(configFile, selection.modes());
        Map<String, String> preambles = new HashMap<>();
        preambles.put(TaskDef.PAPER, readIfPresent(tasksDir.resolve("_preamble.md")));
        preambles.put(TaskDef.MINESTOM, readIfPresent(tasksDir.resolve("_preamble-minestom.md")));
        Path noServer = tasksDir.resolve("_preamble-no-server.md");
        if (Files.exists(noServer)) {
            preambles.put(BenchRunner.PAPER_NO_SERVER, Text.readOwn(noServer));
        }
        // Attempts run in the run folder: inside this repository both modes would load its CLAUDE.md and sources.
        // The checked folder is the resolved one, and the run writes to that same folder (links, .. and all).
        Path runsDir = flags.containsKey("--out")
                ? RunIsolation.requireIsolated(Path.of(flags.get("--out")), home, temp, top)
                : RunIsolation.defaultRunsDir(home, temp, top);
        if (!flags.containsKey("--out") && !runsDir.equals(RunIsolation.resolve(RunIsolation.homeRunsDir(home)))) {
            out.println(RunIsolation.DEFAULT_RUNS_SHOWN + " is inside a project (your home folder is a repository or "
                    + "has a CLAUDE.md), so runs go to " + runsDir + "; the system may clear it, so pass --out to keep "
                    + "them elsewhere");
        }
        // A new folder of its own: runs started side by side never share results.jsonl or report.md (#621).
        Path runDir;
        try {
            runDir = RunFolder.claim(runsDir, LocalDateTime.now());
        } catch (IOException e) {
            throw new RunFolderUnwritable(runsDir, e);
        }

        BenchRunner runner = new BenchRunner(config, runDir, preambles, out);
        List<RunRecord> records = runner.run(selection, repeat);
        return endRun(records, runner.notRecorded(), runner.stoppedBy(), runDir, out, Disk.REAL);
    }

    /**
     * The end of a run, in an order that cannot lose its verdict (#476): the exit code is decided and a stop is
     * explained first, then {@code report.md} is written whole or not at all. A report that cannot be written (a full
     * disk) is one warning line naming {@code report <runDir>}, which rebuilds it from {@code results.jsonl} once the
     * cause is fixed, and never changes the exit code: {@link #NOT_WRITTEN} stays, and a run that recorded every
     * attempt keeps {@link #OK}, {@link #FAILED} or {@link #USAGE_LIMIT}, since those speak for the attempts and every
     * one of them is in {@code results.jsonl}. The earlier {@code report.md}, if any, is left as it was.
     */
    static int endRun(List<RunRecord> records, RunFolderUnwritable notRecorded, String stoppedBy, Path runDir,
            PrintStream out, Disk disk) {
        int exit;
        if (notRecorded != null) {
            out.println("Stopped early: " + notRecorded.getMessage() + "; " + ResultsFile.NAME + " holds the "
                    + records.size() + " attempts "
                    + "recorded. Fix the cause, run the rest into a new folder and put the results.jsonl files "
                    + "together before `report`.");
            exit = NOT_WRITTEN;
        } else if (stoppedBy != null) {
            out.println("Stopped early on a usage limit (" + stoppedBy + "). After it resets, run the rest into "
                    + "a new folder and put the results.jsonl files together before `report`.");
            exit = USAGE_LIMIT;
        } else {
            exit = records.stream().allMatch(RunRecord::passed) ? OK : FAILED;
        }
        try {
            out.println("Report: " + Quoted.path(writeReport(runDir, records, disk).toString()));
        } catch (RunFolderUnwritable e) {
            out.println("warning: " + e.getMessage() + "; once that is fixed, `report " + runDir.toAbsolutePath()
                    + "` writes it from " + ResultsFile.NAME);
        }
        return exit;
    }

    /**
     * Writes the report of {@code records} to {@code report.md} in {@code runDir}, whole or not at all: a failed write
     * leaves the file as it was and is a {@link RunFolderUnwritable} naming it. Returns the file's absolute path.
     */
    private static Path writeReport(Path runDir, List<RunRecord> records, Disk disk) throws RunFolderUnwritable {
        Path reportFile = runDir.resolve(REPORT).toAbsolutePath();
        byte[] text = ReportWriter.render(records).getBytes(StandardCharsets.UTF_8);
        try {
            AtomicFile.write(reportFile, to -> {
                try (FileChannel channel = FileChannel.open(to, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    disk.write(reportFile, channel, ByteBuffer.wrap(text));
                }
            });
        } catch (IOException e) {
            throw new RunFolderUnwritable(reportFile, e);
        }
        return reportFile;
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
        return Files.isRegularFile(file) ? Text.readOwn(file) : "";
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
     * started for the checks and stopped after them, as in a run. Its arguments are already accepted: every IO
     * failure from here on is a step of its own work that failed ({@link StepFailed}), a failed check as in a run.
     * Stopping that server is no such step: {@link ServerManager#close} throws nothing, and kills a server that does
     * not stop (#645).
     */
    static int check(TaskDef task, Path workspace, BenchConfig config, PrintStream out) throws IOException {
        // The run's own placeholders (#554); an attempt's workspace sits in its attempt folder.
        Path absolute = workspace.toAbsolutePath();
        Map<String, String> values = Collections.unmodifiableMap(BenchRunner.placeholders(
                config == null ? BenchConfig.defaults() : config, Objects.requireNonNullElse(absolute.getParent(), absolute),
                absolute));
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
            if (task.freesPortsBeforeChecks()) {
                // As in a run: a server answering on the bench's ports holds in memory what a reset deletes, and a
                // check's own server needs the game port, so it is stopped first (#577); the RCON checks then start
                // the workspace's own server. Nothing is killed by its folder: the command that started this check
                // names the workspace too.
                StepFailed.during(StepFailed.FREEING_PORTS_BEFORE_CHECKS, () -> ServerManager.clearPorts(
                        (config == null ? BenchConfig.defaults() : config).server(), null, null));
            }
            if (!task.reset().isEmpty()) {
                StepFailed.during(StepFailed.RESET_BEFORE_CHECKS, () -> task.reset().apply(workspace));
                out.printf("reset before checks: deleted %s%n",
                        String.join(", ", task.reset().delete().stream().map(Quoted::visible).toList()));
            }
            boolean allPassed = true;
            for (Check check : task.checks()) {
                CheckResult result = check.run(context);
                allPassed &= result.passed();
                // The same text run's progress line and report.md show (#677): one line, nothing hidden.
                out.printf("%s %s%n", result.passed() ? "PASS" : "FAIL", result.shown());
            }
            return allPassed ? OK : FAILED;
        } finally {
            if (running[0] != null) {
                StepFailed.during("closing the RCON connection", running[0]::close);
            }
        }
    }

    /**
     * The run folder {@code report} is given (#638): the folder itself, or the {@code report.md} path that {@code run}
     * and {@code report} print after {@code Report:}, which names its folder. That path names it even before a
     * {@code report.md} is written there, as after a report the disk refused.
     */
    static Path runFolder(Path arg) {
        Path parent = arg.toAbsolutePath().getParent();
        boolean reportPath = arg.getFileName() != null && arg.getFileName().toString().equals(REPORT)
                && !Files.isDirectory(arg) && parent != null;
        return reportPath ? parent : arg;
    }

    /**
     * Rewrites {@code report.md} in {@code runDir} from its {@code results.jsonl}, writing through {@code disk}. A
     * report that cannot be written is a {@link RunFolderUnwritable} naming it ({@link #NOT_WRITTEN}, #644), never a
     * bad argument: the arguments and {@code results.jsonl} were fine.
     */
    static int report(Path runDir, PrintStream out, Disk disk) throws IOException {
        if (!Files.isDirectory(runDir)) {
            throw new IOException("run folder " + runDir.toAbsolutePath() + (Files.exists(runDir) ? " is not a folder"
                    : " does not exist") + ": give the run folder, or the path that `run` prints after Report:");
        }
        Path results = runDir.resolve(ResultsFile.NAME);
        if (!Files.isRegularFile(results)) {
            throw new IOException(runDir.toAbsolutePath() + " has no " + BenchRunner.RESULTS_FILE + ": give the run "
                    + "folder, such as " + RunIsolation.DEFAULT_RUNS_SHOWN + "/<timestamp>, or the path that `run` "
                    + "prints after Report:");
        }
        List<RunRecord> records = ResultsFile.read(results, out::println);
        out.println("Report: " + Quoted.path(writeReport(runDir, records, disk).toString()));
        return OK;
    }
}
