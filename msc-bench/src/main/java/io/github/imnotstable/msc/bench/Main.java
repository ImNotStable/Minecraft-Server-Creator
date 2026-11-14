package io.github.imnotstable.msc.bench;

import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.rcon.RconClient;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.run.BenchRunner;
import io.github.imnotstable.msc.bench.run.ChildProcesses;
import io.github.imnotstable.msc.bench.run.RunIsolation;
import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.run.ServerManager;
import io.github.imnotstable.msc.bench.run.Workspaces;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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
 */
public final class Main {

    private static final Set<String> FLAGS = Set.of("--tasks", "--modes", "--repeat", "--tasks-dir", "--config", "--out");
    private static final Set<String> PATH_FLAGS = Set.of("--tasks-dir", "--config", "--out");

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args, System.out));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("msc-bench: " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String[] args, PrintStream out) throws IOException {
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
            return 1;
        }
        Path home = Path.of(System.getProperty("user.home"));
        flags.replaceAll((flag, value) -> PATH_FLAGS.contains(flag) ? expandHome(value, home).toString() : value);
        Path tasksDir = Path.of(flags.getOrDefault("--tasks-dir", "tasks"));
        return switch (positional.getFirst()) {
            case "list" -> list(tasksDir, out);
            case "run" -> runBench(tasksDir, flags, out);
            case "check" -> {
                if (positional.size() != 3) {
                    throw new IllegalArgumentException("usage: check <task> <workspace>");
                }
                yield check(tasksDir, positional.get(1), expandHome(positional.get(2), home), flags, out);
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
        for (TaskDef task : TaskLoader.loadAll(tasksDir)) {
            out.printf("%-26s %-8s %-8s %d checks  %s%n", task.id(), task.category(), task.platform(),
                    task.checks().size(), task.title());
        }
        return 0;
    }

    private static int runBench(Path tasksDir, Map<String, String> flags, PrintStream out) throws IOException {
        // Attempts run in the run folder: inside this repository both modes would load its CLAUDE.md and sources.
        // The checked folder is the resolved one, and the run writes to that same folder (links, .. and all).
        Path home = Path.of(System.getProperty("user.home"));
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        Path runsDir = flags.containsKey("--out")
                ? RunIsolation.requireIsolated(Path.of(flags.get("--out")), home, temp)
                : RunIsolation.defaultRunsDir(home, temp);
        if (!flags.containsKey("--out") && !runsDir.equals(RunIsolation.resolve(RunIsolation.homeRunsDir(home)))) {
            out.println(RunIsolation.DEFAULT_RUNS_SHOWN + " is inside a project (your home folder is a repository or "
                    + "has a CLAUDE.md), so runs go to " + runsDir + "; the system may clear it, so pass --out to keep "
                    + "them elsewhere");
        }
        BenchConfig config = BenchConfig.load(Path.of(flags.getOrDefault("--config", "bench.json")));
        List<TaskDef> tasks = select(TaskLoader.loadAll(tasksDir), flags.get("--tasks"));
        List<String> modes = flags.containsKey("--modes")
                ? names(flags.get("--modes"))
                : List.copyOf(config.modes().keySet()).stream().sorted().toList();
        int repeat = Integer.parseInt(flags.getOrDefault("--repeat", "1"));
        Path runDir = runsDir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Map<String, String> preambles = new HashMap<>();
        preambles.put(TaskDef.PAPER, readIfPresent(tasksDir.resolve("_preamble.md")));
        preambles.put(TaskDef.MINESTOM, readIfPresent(tasksDir.resolve("_preamble-minestom.md")));
        Path noServer = tasksDir.resolve("_preamble-no-server.md");
        if (Files.exists(noServer)) {
            preambles.put(BenchRunner.PAPER_NO_SERVER, Files.readString(noServer));
        }

        BenchRunner runner = new BenchRunner(config, runDir, preambles, out);
        List<RunRecord> records = runner.run(tasks, modes, repeat);
        Path reportFile = runDir.resolve("report.md");
        Files.writeString(reportFile, ReportWriter.render(records));
        out.println("Report: " + reportFile.toAbsolutePath());
        if (runner.stoppedBy() != null) {
            out.println("Stopped early on a usage limit (" + runner.stoppedBy() + "). After it resets, run the rest into "
                    + "a new folder and put the results.jsonl files together before `report`.");
            return 4;
        }
        return records.stream().allMatch(RunRecord::passed) ? 0 : 3;
    }

    /** The comma-separated names of a {@code --tasks} or {@code --modes} value, without surrounding spaces. */
    static List<String> names(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).toList();
    }

    private static String readIfPresent(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private static int check(Path tasksDir, String taskId, Path workspace, Map<String, String> flags, PrintStream out)
            throws IOException {
        TaskDef task = select(TaskLoader.loadAll(tasksDir), taskId).getFirst();
        Path configFile = Path.of(flags.getOrDefault("--config", "bench.json"));
        BenchConfig config = Files.isRegularFile(configFile) ? BenchConfig.load(configFile) : null;
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
            return allPassed ? 0 : 3;
        } finally {
            if (running[0] != null) {
                running[0].close();
            }
        }
    }

    private static int report(Path runDir, PrintStream out) throws IOException {
        List<RunRecord> records = ReportWriter.readResults(runDir.resolve(BenchRunner.RESULTS_FILE));
        Path reportFile = runDir.resolve("report.md");
        Files.writeString(reportFile, ReportWriter.render(records));
        out.println("Report: " + reportFile.toAbsolutePath());
        return 0;
    }

    private static List<TaskDef> select(List<TaskDef> all, String ids) {
        if (ids == null) {
            return all;
        }
        List<TaskDef> picked = new ArrayList<>();
        for (String id : names(ids)) {
            picked.add(all.stream().filter(t -> t.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no task " + id)));
        }
        return picked;
    }
}
