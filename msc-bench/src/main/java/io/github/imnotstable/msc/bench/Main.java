package io.github.imnotstable.msc.bench;

import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.rcon.RconClient;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.run.BenchRunner;
import io.github.imnotstable.msc.bench.run.RunRecord;
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
 * check &lt;task&gt; &lt;workspace&gt;                 run one task's checks on a folder
 * report &lt;runDir&gt;                         rewrite report.md from results.jsonl
 * common: --tasks-dir DIR (default tasks), --config FILE (default bench.json), --out DIR (default runs)
 * </pre>
 */
public final class Main {

    private static final Set<String> FLAGS = Set.of("--tasks", "--modes", "--repeat", "--tasks-dir", "--config", "--out");

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
            out.println("usage: msc-bench list | run [--tasks a,b] [--modes m1,m2] [--repeat N] | check <task> <workspace> | report <runDir>");
            return 1;
        }
        Path tasksDir = Path.of(flags.getOrDefault("--tasks-dir", "tasks"));
        return switch (positional.getFirst()) {
            case "list" -> list(tasksDir, out);
            case "run" -> runBench(tasksDir, flags, out);
            case "check" -> {
                if (positional.size() != 3) {
                    throw new IllegalArgumentException("usage: check <task> <workspace>");
                }
                yield check(tasksDir, positional.get(1), Path.of(positional.get(2)), flags, out);
            }
            case "report" -> {
                if (positional.size() != 2) {
                    throw new IllegalArgumentException("usage: report <runDir>");
                }
                yield report(Path.of(positional.get(1)), out);
            }
            default -> throw new IllegalArgumentException("unknown command " + positional.getFirst());
        };
    }

    private static int list(Path tasksDir, PrintStream out) throws IOException {
        for (TaskDef task : TaskLoader.loadAll(tasksDir)) {
            out.printf("%-26s %-8s %-8s %d checks  %s%n", task.id(), task.category(), task.platform(),
                    task.checks().size(), task.title());
        }
        return 0;
    }

    private static int runBench(Path tasksDir, Map<String, String> flags, PrintStream out) throws IOException {
        BenchConfig config = BenchConfig.load(Path.of(flags.getOrDefault("--config", "bench.json")));
        List<TaskDef> tasks = select(TaskLoader.loadAll(tasksDir), flags.get("--tasks"));
        List<String> modes = flags.containsKey("--modes")
                ? Arrays.asList(flags.get("--modes").split(","))
                : List.copyOf(config.modes().keySet()).stream().sorted().toList();
        int repeat = Integer.parseInt(flags.getOrDefault("--repeat", "1"));
        Path runDir = Path.of(flags.getOrDefault("--out", "runs"))
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Map<String, String> preambles = new HashMap<>();
        preambles.put(TaskDef.PAPER, readIfPresent(tasksDir.resolve("_preamble.md")));
        preambles.put(TaskDef.MINESTOM, readIfPresent(tasksDir.resolve("_preamble-minestom.md")));

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

    private static String readIfPresent(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private static int check(Path tasksDir, String taskId, Path workspace, Map<String, String> flags, PrintStream out)
            throws IOException {
        TaskDef task = select(TaskLoader.loadAll(tasksDir), taskId).getFirst();
        Path configFile = Path.of(flags.getOrDefault("--config", "bench.json"));
        BenchConfig config = Files.isRegularFile(configFile) ? BenchConfig.load(configFile) : null;
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Map<String, String> values = Map.of(
                "workspace", workspace.toAbsolutePath().toString(),
                "gradlew", workspace.toAbsolutePath().resolve(windows ? "gradlew.bat" : "gradlew").toString(),
                "java", ProcessHandle.current().info().command().orElse("java"),
                "serverPort", Integer.toString(config == null ? 25566 : config.server().serverPort()),
                "scaffoldVersion", Workspaces.SCAFFOLD_VERSION);
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
                    rcon = RconClient.connect("127.0.0.1", config.server().rconPort(), config.server().rconPassword(),
                            Duration.ofSeconds(30));
                }
                return rcon;
            }
        };
        boolean allPassed = true;
        for (Check check : task.checks()) {
            CheckResult result = check.run(context);
            allPassed &= result.passed();
            out.printf("%s %s: %s%n", result.passed() ? "PASS" : "FAIL", result.name(), result.detail());
        }
        return allPassed ? 0 : 3;
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
        for (String id : ids.split(",")) {
            picked.add(all.stream().filter(t -> t.id().equals(id.strip())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no task " + id)));
        }
        return picked;
    }
}
