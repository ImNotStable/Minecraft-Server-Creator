package io.github.imnotstable.msc.bench.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.imnotstable.msc.bench.RunSelection;
import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.check.Placeholders;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.process.rcon.RconClient;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks with the agent in each mode and records the results. Attempts are interleaved
 * (task 1 baseline, task 1 toolset, task 2 baseline...) so slow drift in model speed hits both
 * modes alike.
 */
public final class BenchRunner {

    /** Results file inside a run folder. */
    public static final String RESULTS_FILE = ResultsFile.NAME;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final BenchConfig config;
    private final Path runDir;
    private final Map<String, String> preambles;
    private final PrintStream log;
    private final Disk disk;
    /** The agent, servers and check processes of the attempt under way, killed if the run is cut short. */
    private final ChildProcesses children = new ChildProcesses();
    /** True while an attempt whose task has a Paper server is under way. */
    private volatile boolean serverTask;
    private String stoppedBy;
    private IOException notRecorded;

    /** A runner whose Paper tasks get {@code preamble}. */
    public BenchRunner(BenchConfig config, Path runDir, String preamble, PrintStream log) {
        this(config, runDir, Map.of(TaskDef.PAPER, preamble == null ? "" : preamble), log);
    }

    /**
     * Preamble key for Paper tasks that need no server: they get no {@code server/} folder, so the Paper preamble's
     * server lines would only send the agent looking for one. Falls back to the {@code paper} preamble when absent.
     */
    public static final String PAPER_NO_SERVER = "paper-no-server";

    /**
     * A runner with a preamble per platform ({@code paper}, {@code minestom}, and {@link #PAPER_NO_SERVER}), put
     * before each task's prompt.
     */
    public BenchRunner(BenchConfig config, Path runDir, Map<String, String> preambles, PrintStream log) {
        this(config, runDir, preambles, log, Disk.REAL);
    }

    /** A runner that writes {@code results.jsonl} through {@code disk}. */
    public BenchRunner(BenchConfig config, Path runDir, Map<String, String> preambles, PrintStream log, Disk disk) {
        this.config = config;
        this.disk = disk;
        this.runDir = runDir;
        this.preambles = new LinkedHashMap<>();
        preambles.forEach((platform, text) -> this.preambles.put(platform, text == null ? "" : text.strip()));
        this.log = log;
    }

    /**
     * Why the last {@link #run} stopped before the end (the agent hit a usage limit), or null when it ran every
     * attempt. The attempt that hit the limit is not recorded.
     */
    public String stoppedBy() {
        return stoppedBy;
    }

    /**
     * Why the last {@link #run} stopped before the end because an attempt could not be written to
     * {@code results.jsonl} (a full disk, a file another program holds), or null. That attempt and every later one are
     * not recorded; the records returned are exactly the ones the file holds.
     */
    public IOException notRecorded() {
        return notRecorded;
    }

    /**
     * Runs {@code selection}, which {@link RunSelection#resolve} checked against this runner's config: its modes are
     * modes of the config, and no task or mode is listed twice (a second attempt with the same task, mode and repeat
     * number would run in the first one's folder, on its work).
     */
    public List<RunRecord> run(RunSelection selection, int repeats) throws IOException {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats is " + repeats + "; a run needs at least 1 attempt");
        }
        Files.createDirectories(runDir);
        stoppedBy = null;
        notRecorded = null;
        // Ctrl-C ends the bench JVM without running the finally blocks that stop the agent and the server: left
        // running, they would keep working unattended and the next run's setup and checks would reach their world.
        Thread hook = Thread.ofPlatform().name("msc-bench-abort").unstarted(this::abort);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return runAll(selection.tasks(), selection.modes(), repeats);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // the hook is running or has run
            }
        }
    }

    /**
     * Stops the run for good: kills the agent, server and check processes under way with their descendants, and
     * sends {@code stop} to a server on the bench's RCON port (the agent may have started one outside its own
     * process tree, through a Gradle daemon). Runs from the shutdown hook; later attempts do not start.
     */
    public void abort() {
        children.killAll();
        if (serverTask) {
            ServerManager.sendStop(config.server());
        }
    }

    private List<RunRecord> runAll(List<TaskDef> tasks, List<String> modes, int repeats) throws IOException {
        List<RunRecord> records = new ArrayList<>();
        int skipped = 0;
        for (TaskDef task : tasks) {
            if (task.needsServer() && !config.server().acceptEula()) {
                skipped++;
                log.printf("skipped %s: it runs a Paper server, and the Minecraft EULA is not accepted on this machine "
                        + "(%s)%n", task.id(), MachineEula.location(Path.of(System.getProperty("user.home"))));
                continue;
            }
            for (int repeat = 1; repeat <= repeats; repeat++) {
                for (String mode : modes) {
                    log.printf("[%s] %s #%d ...%n", mode, task.id(), repeat);
                    RunRecord record;
                    try {
                        record = runWithMcpRetries(task, mode, repeat);
                    } catch (UsageLimitReached e) {
                        stoppedBy = e.getMessage();
                        log.printf("[%s] %s #%d: stopped, the agent hit a usage limit: %s%n",
                                mode, task.id(), repeat, stoppedBy);
                        return records;
                    }
                    if (children.closed() || Thread.currentThread().isInterrupted()) {
                        // Cut short: the attempt's agent was killed, so its record would score nothing real.
                        log.printf("[%s] %s #%d: stopped, the run was interrupted%n", mode, task.id(), repeat);
                        return records;
                    }
                    try {
                        ResultsFile.append(runDir.resolve(RESULTS_FILE), record, disk);
                    } catch (IOException e) {
                        // The file system refused the write: later attempts would be lost the same way, so the run
                        // stops with the records the file holds, and the caller still writes their report.
                        notRecorded = e;
                        log.printf("[%s] %s #%d: stopped, the attempt could not be written to %s (%s)%n",
                                mode, task.id(), repeat, RESULTS_FILE, e);
                        return records;
                    }
                    records.add(record);
                    log.println(progressLine(record));
                }
            }
        }
        if (!tasks.isEmpty() && skipped == tasks.size()) {
            log.println("Nothing ran: every selected task was skipped (see above), so the report is empty.");
        }
        return records;
    }

    /** Tries per attempt when the mode's required MCP server was not connected at start and no tool was called. */
    static final int MCP_TRIES = 3;

    private RunRecord runWithMcpRetries(TaskDef task, String modeName, int repeat) throws IOException {
        String required = config.modes().get(modeName).requireMcp();
        RunRecord record = null;
        for (int attempt = 1; attempt <= MCP_TRIES; attempt++) {
            record = runOne(task, modeName, repeat, attempt);
            // Claude Code connects MCP servers in the background, so the init event can list none even though the
            // tools arrived a moment later: a logged tool call proves the server was there. Without a parsed result
            // (a time-out, a crash) no init event says the server was missing, so the attempt stands as it is.
            if (required == null || record.error() != null || record.timedOut() || !record.agent().parsed()
                    || record.agent().mcpConnected().contains(required) || record.tools().calls() > 0) {
                return record;
            }
            log.printf("[%s] %s #%d: MCP server %s was not connected when the agent started (try %d of %d)%n",
                    modeName, task.id(), repeat, required, attempt, MCP_TRIES);
        }
        return record.failed("MCP server " + required + " was not connected when the agent started, "
                + MCP_TRIES + " tries");
    }

    RunRecord runOne(TaskDef task, String modeName, int repeat, int attempt) throws IOException {
        BenchConfig.Mode mode = config.modes().get(modeName);
        String name = modeName + "-" + repeat + (attempt > 1 ? "-try" + attempt : "");
        Path attemptDir = runDir.resolve("attempts").resolve(task.id()).resolve(name).toAbsolutePath();
        if (Files.exists(attemptDir)) {
            // Its workspace and tool log hold an earlier attempt's work, which this attempt's checks would score.
            throw new IOException("attempt folder " + attemptDir + " already exists");
        }
        Path workspace = attemptDir.resolve("workspace");
        String startedAt = Instant.now().toString();
        Map<String, String> values = placeholders(attemptDir, workspace);

        Path mcpTemplate = mode.mcpConfigTemplate() == null ? null : Path.of(mode.mcpConfigTemplate());
        if (mcpTemplate != null) {
            Path mcpConfig = attemptDir.resolve("mcp.json");
            Files.createDirectories(attemptDir);
            Files.writeString(mcpConfig, fillJson(Files.readString(mcpTemplate), values));
            values.put("mcpConfig", slashes(mcpConfig));
        }

        Map<String, String> serverEnv = new LinkedHashMap<>();
        mode.serverEnv().forEach((k, v) -> serverEnv.put(k, Placeholders.fill(v, values)));

        long wallMs = 0;
        boolean timedOut = false;
        int exitCode = -1;
        AgentResult agent = AgentResult.UNKNOWN;
        List<CheckResult> results = new ArrayList<>();
        String error = null;

        ServerManager server = null;
        serverTask = task.needsServer();
        // The step a failure before the agent starts is named after; null once the agent has started.
        String setupStep = "preparing the workspace";
        try {
            if (task.needsServer()) {
                ServerManager.clearPorts(config.server());
            }
            Workspaces.prepare(task, workspace, config, mode, task.needsServer() ? paperJar() : null, values);
            if (task.needsServer()) {
                setupStep = "starting the server";
                ServerFiles.restore(sharedServerFiles(), workspace.resolve("server"));
                server = new ServerManager(workspace.resolve("server"), config.server(), serverEnv, children);
            }
            if (task.server().startBefore()) {
                setupStep = "running the server setup commands";
                RconClient rcon = server.rcon();
                for (String command : task.server().setup()) {
                    rcon.command(Placeholders.fill(command, values));
                }
                // The agent may stop or restart the server; the checks then connect afresh.
                server.disconnect();
            }

            setupStep = "starting the agent";
            values.put("prompt", fullPrompt(task, values));
            List<String> command = new ArrayList<>(Placeholders.fill(config.agent().command(), values));
            command.addAll(Placeholders.fill(mode.extraArgs(), values));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .redirectOutput(attemptDir.resolve("agent-stdout.json").toFile())
                    .redirectError(attemptDir.resolve("agent-stderr.log").toFile());
            mode.env().forEach((k, v) -> builder.environment().put(k, Placeholders.fill(v, values)));
            Files.writeString(attemptDir.resolve("prompt.md"), values.get("prompt"));

            int timeout = task.timeoutSeconds() > 0 ? task.timeoutSeconds() : config.agent().timeoutSeconds();
            long start = System.nanoTime();
            Process process = children.start(builder);
            setupStep = null;
            try {
                if (process.waitFor(timeout, TimeUnit.SECONDS)) {
                    exitCode = process.exitValue();
                } else {
                    timedOut = true;
                    ChildProcesses.killTree(process);
                }
            } catch (InterruptedException e) {
                ChildProcesses.killTree(process);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            // At least 1: a wallMs of 0 means the agent never started (RunRecord.agentRan).
            wallMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            // Lenient UTF-8 (Text.read): one stray byte in the agent's output must not cost the whole attempt.
            String stdout = Text.read(attemptDir.resolve("agent-stdout.json"));
            String limit = ClaudeResultParser.usageLimit(stdout);
            if (limit != null) {
                throw new UsageLimitReached(limit);
            }
            agent = ClaudeResultParser.parse(stdout);

            ServerManager checkServer = server;
            CheckContext context = new CheckContext() {
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
                    if (checkServer == null) {
                        throw new IOException("task has no server");
                    }
                    return checkServer.rcon();
                }

                @Override
                public Process start(ProcessBuilder builder) throws IOException {
                    return children.start(builder);
                }
            };
            if (!task.reset().isEmpty()) {
                if (server != null) {
                    // A plugin holds what the reset deletes in memory: a server still running (the bench's, or one
                    // the agent left) is stopped first, so the checks start one that loads the reset files.
                    server.close();
                    ServerManager.clearPorts(config.server());
                }
                task.reset().apply(workspace);
            }
            for (Check check : task.checks()) {
                results.add(check.run(context));
            }
        } catch (UsageLimitReached e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            error = setupStep == null ? message : setupStep + ": " + message;
        } finally {
            if (server != null) {
                server.close();
                if (server.reachedRcon()) {
                    try {
                        ServerFiles.save(workspace.resolve("server"), sharedServerFiles());
                    } catch (IOException e) {
                        log.println("could not save server files for later runs: " + e.getMessage());
                    }
                }
            }
            serverTask = false;
        }

        ToolStats tools = ToolLogReader.read(attemptDir.resolve("tool-calls.jsonl"), log::println);
        boolean passed = error == null && !timedOut && !results.isEmpty()
                && results.size() == task.checks().size()
                && results.stream().allMatch(CheckResult::passed);
        return new RunRecord(task.id(), task.category(), modeName, repeat, startedAt, passed, wallMs, timedOut,
                exitCode, error, agent, tools, results);
    }

    /** The terminal line for one finished attempt; a failed one ends with {@link RunRecord#failureReason()}. */
    static String progressLine(RunRecord record) {
        String reason = record.failureReason();
        return String.format(Locale.ROOT, "[%s] %s #%d %s in %ds, %d tokens, %d/%d checks%s",
                record.mode(), record.task(), record.repeat(), record.passed() ? "PASS" : "FAIL",
                record.wallMs() / 1000, record.agent().totalTokens(), record.checksPassed(), record.checks().size(),
                reason == null ? "" : " (" + reason + ")");
    }

    /** The agent's run ended on an account usage limit; carries its error text. */
    static final class UsageLimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageLimitReached(String message) {
            super(message);
        }
    }

    private Path paperJar;

    private Path sharedServerFiles() {
        return Path.of(config.server().paperCacheDirs().getFirst()).resolve("paper-server-" + config.minecraftVersion());
    }

    /** The Paper jar for this run, found or downloaded once. */
    private Path paperJar() throws IOException {
        if (paperJar == null) {
            BenchConfig.Server server = config.server();
            paperJar = server.paperJar() != null
                    ? Path.of(server.paperJar())
                    : PaperJars.resolve(server.paperCacheDirs().stream().map(Path::of).toList(),
                            config.minecraftVersion(), server.downloadsUrl());
            log.println("Paper jar: " + paperJar);
        }
        return paperJar;
    }

    private Map<String, String> placeholders(Path attemptDir, Path workspace) {
        Map<String, String> values = new LinkedHashMap<>(config.paths().size() + 12);
        config.paths().forEach((k, v) -> values.put(k, slashes(Path.of(v))));
        values.put("java", ProcessHandle.current().info().command().map(c -> c.replace('\\', '/')).orElse("java"));
        values.put("workspace", slashes(workspace));
        values.put("attemptDir", slashes(attemptDir));
        values.put("toolLog", slashes(attemptDir.resolve("tool-calls.jsonl")));
        values.put("rconPort", Integer.toString(config.server().rconPort()));
        values.put("rconPassword", config.server().rconPassword());
        values.put("serverPort", Integer.toString(config.server().serverPort()));
        values.put("corePort", Integer.toString(config.corePort()));
        values.put("minecraftVersion", config.minecraftVersion());
        values.put("minestomMinecraftVersion", Templates.versions().getOrDefault("minestomMcVersion", ""));
        values.put("scaffoldVersion", Workspaces.SCAFFOLD_VERSION);
        values.put("coreToken", newToken());
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        values.put("gradlew", slashes(workspace.resolve(windows ? "gradlew.bat" : "gradlew")));
        return values;
    }

    private String fullPrompt(TaskDef task, Map<String, String> values) {
        String body = Placeholders.fill(task.prompt(), values);
        String preamble = preambleFor(task);
        return preamble.isEmpty() ? body : Placeholders.fill(preamble, values) + "\n\n" + body;
    }

    private String preambleFor(TaskDef task) {
        if (task.platform().equals(TaskDef.PAPER) && !task.needsServer() && preambles.containsKey(PAPER_NO_SERVER)) {
            return preambles.get(PAPER_NO_SERVER);
        }
        return preambles.getOrDefault(task.platform(), "");
    }

    /** Fills placeholders in a JSON template, escaping values so paths with backslashes stay valid. */
    static String fillJson(String template, Map<String, String> values) {
        Map<String, String> escaped = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            String json = GSON.toJson(v);
            escaped.put(k, json.substring(1, json.length() - 1));
        });
        return Placeholders.fill(template, escaped);
    }

    /** Forward slashes work on every OS and need no escaping in JSON or shells. */
    static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static java.io.File nullDevice() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        return new java.io.File(windows ? "NUL" : "/dev/null");
    }
}
