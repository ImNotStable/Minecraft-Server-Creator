package io.github.imnotstable.msc.bench.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.bench.RunSelection;
import io.github.imnotstable.msc.bench.StrictJson;
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
import java.nio.file.FileAlreadyExistsException;
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
    /** The processes this run starts for its attempts, the only ones it kills besides its own children (#587). */
    private final AttemptProcesses processes = AttemptProcesses.newRun();
    /** True while an attempt whose task uses the bench's ports is under way ({@link TaskDef#usesBenchPorts}). */
    private volatile boolean portsTask;
    private String stoppedBy;
    private RunFolderUnwritable notRecorded;

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
     * Why the last {@link #run} stopped before the end because one of the run's own files could not be written (a
     * full disk, a file another program holds; {@link #own}), or null. That attempt and every later one are not
     * recorded; the records returned are exactly the ones {@code results.jsonl} holds.
     */
    public RunFolderUnwritable notRecorded() {
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
     * Stops the run for good: kills the agent, server and check processes under way with their descendants, sends
     * {@code stop} to a server on the bench's RCON port, and kills what this run started for its attempts and is still
     * running (the agent may have started a server outside its own process tree, through a Gradle daemon or detached,
     * #577; {@link AttemptProcesses}). Runs from the shutdown hook; later attempts do not start.
     */
    public void abort() {
        children.killAll();
        if (portsTask) {
            ServerManager.sendStop(config.server());
        }
        processes.kill(attemptsDir());
    }

    /** The folder holding every attempt folder of this run. */
    private Path attemptsDir() {
        return runDir.resolve("attempts").toAbsolutePath();
    }

    private List<RunRecord> runAll(List<TaskDef> tasks, List<String> modes, int repeats) {
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
                        if (children.closed() || Thread.currentThread().isInterrupted()) {
                            // Cut short: the attempt's agent was killed, so its record would score nothing real.
                            log.printf("[%s] %s #%d: stopped, the run was interrupted%n", mode, task.id(), repeat);
                            return records;
                        }
                        Path results = runDir.resolve(RESULTS_FILE);
                        RunRecord done = record;
                        own(results, () -> ResultsFile.append(results, done, disk));
                    } catch (UsageLimitReached e) {
                        stoppedBy = e.getMessage();
                        log.printf("[%s] %s #%d: stopped, the agent hit a usage limit: %s%n",
                                mode, task.id(), repeat, stoppedBy);
                        return records;
                    } catch (RunFolderUnwritable e) {
                        // Later attempts would be lost the same way, so the run stops with the records results.jsonl
                        // holds, and the caller still writes their report.
                        notRecorded = e;
                        log.printf("[%s] %s #%d: stopped, %s%n", mode, task.id(), repeat, e.getMessage());
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

    private RunRecord runWithMcpRetries(TaskDef task, String modeName, int repeat) throws RunFolderUnwritable {
        String required = config.modes().get(modeName).requireMcp();
        RunRecord record = null;
        String problem = null;
        for (int attempt = 1; attempt <= MCP_TRIES; attempt++) {
            record = runOne(task, modeName, repeat, attempt);
            // Claude Code connects MCP servers in the background, so the init event can list none even though the
            // tools arrived a moment later: a logged tool call proves the server was there. Without a parsed result
            // (a time-out, a crash) no init event says the server was missing, so the attempt stands as it is.
            problem = required == null ? null : record.agent().mcpProblem(required);
            if (problem == null || record.error() != null || record.timedOut() || !record.agent().parsed()
                    || record.tools().calls() > 0) {
                return record;
            }
            log.printf("[%s] %s #%d: %s, and no tool call was logged (try %d of %d)%n",
                    modeName, task.id(), repeat, problem, attempt, MCP_TRIES);
        }
        return record.failed("MCP server " + required + " was not connected when the agent started, "
                + MCP_TRIES + " tries: " + problem);
    }

    /** A write of one of the run's own files. */
    @FunctionalInterface
    private interface OwnWrite {
        void run() throws IOException;
    }

    /**
     * The one rule for an IO failure in an attempt (#615). The run's own files, each attempt's folder, its
     * {@code mcp.json}, {@code prompt.md} and agent output files, and {@code results.jsonl}, are written only through
     * this, and a failure to write one (a full disk, a file another program holds) is the machine's: every later
     * attempt would fail the same way, so it stops the run as {@link RunFolderUnwritable}, and the run ends with its
     * verdict and report. Every other failure in an attempt (reading the MCP config template, building the workspace
     * from the task, the server, the agent, the checks) is the attempt's own: {@link #runOne} records it as that
     * attempt's failure, under the step it happened in, and the run goes on.
     */
    private static void own(Path file, OwnWrite write) throws RunFolderUnwritable {
        try {
            write.run();
        } catch (RunFolderUnwritable e) {
            throw e;
        } catch (IOException e) {
            throw new RunFolderUnwritable(file, e);
        }
    }

    /**
     * One attempt. It throws only when one of the run's own files cannot be written ({@link #own}); any other failure
     * is recorded in the returned record.
     */
    RunRecord runOne(TaskDef task, String modeName, int repeat, int attempt) throws RunFolderUnwritable {
        BenchConfig.Mode mode = config.modes().get(modeName);
        String name = modeName + "-" + repeat + (attempt > 1 ? "-try" + attempt : "");
        Path attemptDir = attemptsDir().resolve(task.id()).resolve(name);
        own(attemptDir, () -> {
            Files.createDirectories(attemptDir.getParent());
            try {
                disk.createDirectory(attemptDir);
            } catch (FileAlreadyExistsException e) {
                // Its workspace and tool log hold an earlier attempt's work, which this attempt's checks would score.
                throw new IOException("it already exists, with an earlier attempt's work in it", e);
            }
        });
        Path workspace = attemptDir.resolve("workspace");
        String startedAt = Instant.now().toString();
        Map<String, String> values = placeholders(config, attemptDir, workspace);

        long wallMs = 0;
        boolean timedOut = false;
        int exitCode = -1;
        AgentResult agent = AgentResult.UNKNOWN;
        List<CheckResult> results = new ArrayList<>();
        String error = null;

        ServerManager server = null;
        portsTask = task.usesBenchPorts();
        // The step a failure before the agent starts is named after; null once the agent has started.
        String setupStep = "making the MCP config";
        try {
            if (mode.mcpConfigTemplate() != null) {
                Path mcpConfig = attemptDir.resolve("mcp.json");
                String text = withMarks(fillJson(Text.readOwn(Path.of(mode.mcpConfigTemplate())), values),
                        processes.marks(attemptDir), processes.argumentMarks(attemptDir));
                own(mcpConfig, () -> PrivateFile.write(mcpConfig, text, disk));
                values.put("mcpConfig", slashes(mcpConfig));
            }
            Map<String, String> serverEnv = new LinkedHashMap<>();
            mode.serverEnv().forEach((k, v) -> serverEnv.put(k, Placeholders.fill(v, values)));
            processes.mark(serverEnv, attemptDir);

            setupStep = "freeing the bench's ports";
            if (task.usesBenchPorts()) {
                // A server an earlier attempt of this run left there is killed; anything else is refused here, before
                // the agent works for nothing.
                ServerManager.clearPorts(config.server(), processes, attemptsDir());
            }
            setupStep = "preparing the workspace";
            if (mode.mcpConfigTemplate() != null) {
                setupStep = "checking the core port";
                CoreProcesses.requireFreePort(config.corePort(), () -> processes.kill(attemptsDir()),
                        p -> processes.owns(p, attemptsDir()));
                setupStep = "preparing the workspace";
            }
            Workspaces.prepare(task, workspace, config, mode, task.needsServer() ? paperJar() : null, values);
            if (task.needsServer()) {
                setupStep = "preparing the server files";
                ServerFiles.restore(sharedServerFiles(), workspace.resolve("server"));
                server = new ServerManager(workspace.resolve("server"), config.server(), serverEnv, children);
            }
            if (task.server().startBefore()) {
                // The server starts here, in its first rcon(), not in the constructor above.
                setupStep = "starting the server";
                RconClient rcon = server.rcon();
                setupStep = "running the server setup commands";
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
            Path stdoutFile = attemptDir.resolve("agent-stdout.json");
            Path stderrFile = attemptDir.resolve("agent-stderr.log");
            Path promptFile = attemptDir.resolve("prompt.md");
            // The agent's output files are made here, so the run's rule covers them rather than the agent's start.
            own(stdoutFile, () -> disk.writeString(stdoutFile, ""));
            own(stderrFile, () -> disk.writeString(stderrFile, ""));
            own(promptFile, () -> disk.writeString(promptFile, values.get("prompt")));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile());
            AgentEnvironment.dropParentSession(builder.environment());
            mode.env().forEach((k, v) -> builder.environment().put(k, Placeholders.fill(v, values)));
            processes.mark(builder.environment(), attemptDir);

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
                    processes.mark(builder.environment(), attemptDir);
                    return children.start(builder);
                }
            };
            if (task.freesPortsBeforeChecks()) {
                // A plugin holds what a reset deletes in memory, and a check's own server needs the game port: a
                // server still running (the bench's, or one the agent left) is stopped first.
                if (server != null) {
                    server.close();
                }
                freePortsBeforeChecks(attemptDir);
            }
            if (!task.reset().isEmpty()) {
                task.reset().apply(workspace);
            }
            for (Check check : task.checks()) {
                results.add(check.run(context));
            }
        } catch (UsageLimitReached | RunFolderUnwritable e) {
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
            portsTask = false;
            // What the attempt left running, such as a server the agent detached (#577) or the msc-core Claude Code
            // did not stop: the attempt is over, and the next one must not meet it on a port.
            int left = processes.kill(attemptDir);
            if (left > 0) {
                log.printf("[%s] %s #%d: stopped %d process(es) the attempt left running%n",
                        modeName, task.id(), repeat, left);
            }
        }

        ToolStats tools = ToolLogReader.read(attemptDir.resolve("tool-calls.jsonl"), log::println);
        boolean passed = error == null && !timedOut && !results.isEmpty()
                && results.size() == task.checks().size()
                && results.stream().allMatch(CheckResult::passed);
        return new RunRecord(task.id(), task.category(), modeName, repeat, startedAt, passed, wallMs, timedOut,
                exitCode, error, agent, tools, results);
    }

    private void freePortsBeforeChecks(Path attemptDir) throws IOException {
        try {
            ServerManager.clearPorts(config.server(), processes, attemptDir);
        } catch (IOException e) {
            throw new IOException("freeing the bench's ports before the checks: " + e.getMessage(), e);
        }
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

    /**
     * The placeholders of one attempt: every {@code paths} key and the names of
     * {@link BenchConfigRules#BUILT_IN_PLACEHOLDERS} but {@code mcpConfig} and {@code prompt}, which the run adds once
     * it has written them. The one source of these values, for {@code run} and for {@code check} (#554), so a check
     * scores a kept attempt with the values the run gave it.
     */
    public static Map<String, String> placeholders(BenchConfig config, Path attemptDir, Path workspace) {
        Map<String, String> values = new LinkedHashMap<>(config.paths().size() + 14);
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

    /**
     * The MCP config {@code json} with {@code marks} in the {@code env} of each of its servers, so msc-core carries the
     * run's marks however Claude Code passes its own environment on (#587), and with {@code argumentMarks} first in
     * the {@code args} of each server whose command is Java, so msc-core is proven the run's by its command line where
     * the system shows no environment (#595). A config without servers, or that is no JSON (Claude Code refuses it
     * and says why), stays as it is.
     */
    static String withMarks(String json, Map<String, String> marks, List<String> argumentMarks) {
        JsonElement tree;
        try {
            tree = StrictJson.parse(json, "");
        } catch (IllegalArgumentException e) {
            return json;
        }
        if (!tree.isJsonObject() || !(tree.getAsJsonObject().get("mcpServers") instanceof JsonObject servers)) {
            return json;
        }
        for (Map.Entry<String, JsonElement> server : servers.entrySet()) {
            if (server.getValue() instanceof JsonObject definition) {
                JsonObject env = definition.get("env") instanceof JsonObject given ? given : new JsonObject();
                definition.add("env", env);
                marks.forEach(env::addProperty);
                if (definition.get("command") instanceof JsonElement command && command.isJsonPrimitive()
                        && isJava(command.getAsString())) {
                    JsonArray args = new JsonArray();
                    argumentMarks.forEach(args::add);
                    if (definition.get("args") instanceof JsonArray given) {
                        args.addAll(given);
                    }
                    definition.add("args", args);
                }
            }
        }
        return GSON.toJson(tree);
    }

    /** True when {@code command} runs the Java launcher ({@code java} or {@code javaw}, in a folder or not). */
    static boolean isJava(String command) {
        String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1)
                .toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - ".exe".length());
        }
        return name.equals("java") || name.equals("javaw");
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
