package io.github.imnotstable.msc.bench.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.check.Placeholders;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.process.rcon.RconClient;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks with the agent in each mode and records the results. Attempts are interleaved
 * (task 1 baseline, task 1 toolset, task 2 baseline...) so slow drift in model speed hits both
 * modes alike.
 */
public final class BenchRunner {

    /** Results file inside a run folder. */
    public static final String RESULTS_FILE = "results.jsonl";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final BenchConfig config;
    private final Path runDir;
    private final Map<String, String> preambles;
    private final PrintStream log;
    /** The agent, servers and check processes of the attempt under way, killed if the run is cut short. */
    private final ChildProcesses children = new ChildProcesses();
    /** True while an attempt whose task has a Paper server is under way. */
    private volatile boolean serverTask;
    private String stoppedBy;

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
        this.config = config;
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

    public List<RunRecord> run(List<TaskDef> tasks, List<String> modes, int repeats) throws IOException {
        for (String mode : modes) {
            if (!config.modes().containsKey(mode)) {
                throw new IOException("Unknown mode \"" + mode + "\"; bench.json defines " + config.modes().keySet());
            }
        }
        // A second attempt with the same task, mode and repeat number would run in the first one's folder, on its work.
        refuseRepeats("task", tasks.stream().map(TaskDef::id).toList());
        refuseRepeats("mode", modes);
        Files.createDirectories(runDir);
        stoppedBy = null;
        // Ctrl-C ends the bench JVM without running the finally blocks that stop the agent and the server: left
        // running, they would keep working unattended and the next run's setup and checks would reach their world.
        Thread hook = Thread.ofPlatform().name("msc-bench-abort").unstarted(this::abort);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return runAll(tasks, modes, repeats);
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

    private static void refuseRepeats(String kind, List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException(kind + " \"" + name + "\" is listed twice; use --repeat N for more "
                        + "attempts of each task and mode");
            }
        }
    }

    private List<RunRecord> runAll(List<TaskDef> tasks, List<String> modes, int repeats) throws IOException {
        List<RunRecord> records = new ArrayList<>();
        for (TaskDef task : tasks) {
            if (task.needsServer() && !config.server().acceptEula()) {
                log.printf("skipped %s: it runs a Paper server, and the Minecraft EULA is not accepted on this machine "
                        + "(%s)%n", task.id(), MachineEula.file(Path.of(System.getProperty("user.home"))));
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
                    records.add(record);
                    Files.writeString(runDir.resolve(RESULTS_FILE), GSON.toJson(record) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    log.printf("[%s] %s #%d %s in %ds, %d tokens, %d/%d checks%s%n",
                            mode, task.id(), repeat, record.passed() ? "PASS" : "FAIL",
                            record.wallMs() / 1000, record.agent().totalTokens(),
                            record.checksPassed(), record.checks().size(),
                            record.error() == null ? "" : " (" + record.error() + ")");
                }
            }
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
        try {
            if (task.needsServer()) {
                ServerManager.clearPorts(config.server());
            }
            Workspaces.prepare(task, workspace, config, mode, task.needsServer() ? paperJar() : null, values);
            if (task.needsServer()) {
                ServerFiles.restore(sharedServerFiles(), workspace.resolve("server"));
                server = new ServerManager(workspace.resolve("server"), config.server(), serverEnv, children);
            }
            if (task.server().startBefore()) {
                RconClient rcon = server.rcon();
                for (String command : task.server().setup()) {
                    rcon.command(Placeholders.fill(command, values));
                }
                // The agent may stop or restart the server; the checks then connect afresh.
                server.disconnect();
            }

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
            wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // Lenient UTF-8: one stray byte in the agent's output must not cost the whole attempt.
            String stdout = new String(Files.readAllBytes(attemptDir.resolve("agent-stdout.json")), StandardCharsets.UTF_8);
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
            for (Check check : task.checks()) {
                results.add(check.run(context));
            }
        } catch (UsageLimitReached e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
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

        ToolStats tools = ToolLogReader.read(attemptDir.resolve("tool-calls.jsonl"));
        boolean passed = error == null && !timedOut && !results.isEmpty()
                && results.size() == task.checks().size()
                && results.stream().allMatch(CheckResult::passed);
        return new RunRecord(task.id(), task.category(), modeName, repeat, startedAt, passed, wallMs, timedOut,
                exitCode, error, agent, tools, results);
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
