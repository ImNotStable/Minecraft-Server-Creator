package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import io.github.imnotstable.msc.bench.StrictJson;
import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.UserJson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bench settings, read from {@code bench.json}. Relative paths are resolved against the folder
 * that holds the config file. Missing fields get the defaults below; a value that can never work (an empty RCON
 * password, a port out of range or shared, a timeout that is not positive, a missing file...) is refused when the
 * config loads, naming its key ({@link BenchConfigRules}).
 *
 * @param agent             how to start the AI agent
 * @param modes             named ways to run each task, such as {@code baseline} and {@code toolset}
 * @param server            how to run the Paper dev server
 * @param gradleWrapperFrom folder whose {@code gradlew}, {@code gradlew.bat} and {@code gradle/wrapper}
 *                          are copied into workspaces that hold a Gradle build
 * @param paths             extra named files (such as {@code coreJar}); each becomes a placeholder
 *                          holding its absolute path
 * @param corePort          loopback port msc-core listens on for its agent in toolset mode
 * @param minecraftVersion  version named in prompts; must match the Paper jar
 */
public record BenchConfig(
        Agent agent,
        Map<String, Mode> modes,
        Server server,
        String gradleWrapperFrom,
        Map<String, String> paths,
        int corePort,
        String minecraftVersion) {

    /** The placeholder names the runner fills itself, such as {@code {workspace}} and {@code {scaffoldVersion}}. */
    public static final Set<String> RUNNER_PLACEHOLDERS = BenchConfigRules.BUILT_IN_PLACEHOLDERS;

    /**
     * @param command        program and arguments; the agent reads the full prompt on stdin, unless {@code {prompt}}
     *                       is here or in the mode's {@code extraArgs} or {@code env}, where it is filled in instead
     *                       ({@link #promptOnStdin})
     * @param timeoutSeconds default limit per run
     */
    public record Agent(List<String> command, int timeoutSeconds) {
    }

    /**
     * True when the agent gets the prompt on stdin: when neither {@code agent.command} nor {@code mode}'s
     * {@code extraArgs} or {@code env} holds {@code {prompt}} (#624). On Windows a command line cannot carry every
     * prompt through every launcher (cmd.exe ends a command at a line break), so the shipped config uses stdin, which
     * {@code claude -p} reads when it is given no prompt argument.
     */
    public boolean promptOnStdin(Mode mode) {
        return !mentionsPrompt(agent.command()) && !mentionsPrompt(mode.extraArgs())
                && !mentionsPrompt(mode.env().values());
    }

    private static boolean mentionsPrompt(Iterable<String> texts) {
        for (String text : texts) {
            if (text != null && text.contains("{prompt}")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param extraArgs          appended to the agent command
     * @param env                extra environment for the agent process
     * @param mcpConfigTemplate  JSON file with placeholders, filled per run and passed as {@code {mcpConfig}}
     * @param serverEnv          extra environment for the dev server process
     * @param serverPlugins      jars copied into {@code server/plugins} before the run; placeholders allowed
     * @param paperCacheSeed     workspace folder that gets a copy of the Paper jar under its cache name, so
     *                           msc-core finds it instead of downloading it during the timed run
     * @param requireMcp         MCP server that must be connected when the agent starts; Claude Code in
     *                           {@code -p} mode sometimes starts before its MCP servers connect, and such an
     *                           attempt is retried instead of scored as a toolset run without tools
     */
    public record Mode(
            List<String> extraArgs,
            Map<String, String> env,
            String mcpConfigTemplate,
            Map<String, String> serverEnv,
            List<String> serverPlugins,
            String paperCacheSeed,
            String requireMcp) {

        /** Keeps null entries, so that loading reports them by key ({@link BenchConfigRules}) instead of failing. */
        public Mode {
            extraArgs = extraArgs == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(extraArgs));
            env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
            serverEnv = serverEnv == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(serverEnv));
            serverPlugins = serverPlugins == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(serverPlugins));
        }
    }

    /**
     * @param command             program and arguments, run in the workspace's {@code server/} folder
     * @param paperJar            Paper server jar copied into each workspace as {@code server/paper.jar};
     *                            when null, the newest cached jar for {@code minecraftVersion} is used, or one
     *                            is downloaded into the first cache folder
     * @param paperCacheDirs      folders holding {@code paper-<version>-<build>.jar}; default msc-core's
     *                            cache in the repo ({@code ../.msc/cache}) and {@code .cache}
     * @param downloadsUrl        PaperMC downloads service, its trailing slashes trimmed when the config loads (#649)
     * @param acceptEula          writes {@code eula=true}; true only when the machine's owner accepted the Minecraft
     *                            EULA through msc ({@code ~/.msc/eula.json}), whatever the config file says
     * @param serverPort          game port, kept off 25565 so a normal server can keep running
     * @param rconPort            RCON port used by checks and setup
     * @param rconPassword        RCON password written into {@code server.properties}; never empty or blank, since
     *                            the server then turns RCON off
     * @param startTimeoutSeconds how long to wait for RCON after starting the server
     */
    public record Server(
            List<String> command,
            String paperJar,
            List<String> paperCacheDirs,
            String downloadsUrl,
            boolean acceptEula,
            int serverPort,
            int rconPort,
            String rconPassword,
            int startTimeoutSeconds) {
    }

    /**
     * The keys bench.json may have, and those of each object in it (#502): a misspelt key at any depth is refused
     * instead of left out silently. The names under {@code modes}, {@code paths}, {@code env} and {@code serverEnv}
     * are the file's own.
     */
    static final UserJson.Keys KEYS = UserJson.Keys.of("minecraftVersion", "agent", "modes", "paths",
            "gradleWrapperFrom", "corePort", "server");
    static final UserJson.Keys AGENT = UserJson.Keys.of("command", "timeoutSeconds");
    static final UserJson.Keys MODE = UserJson.Keys.of("extraArgs", "env", "mcpConfigTemplate", "serverEnv",
            "serverPlugins", "paperCacheSeed", "requireMcp");
    static final UserJson.Keys SERVER = UserJson.Keys.of("command", "paperJar", "paperCacheDirs", "downloadsUrl",
            "acceptEula", "serverPort", "rconPort", "rconPassword", "startTimeoutSeconds");

    /** Reads the config; the EULA choice comes from the owner's machine-wide acceptance in {@code ~/.msc/eula.json}. */
    public static BenchConfig load(Path file) throws IOException {
        return load(file, Path.of(System.getProperty("user.home")));
    }

    /**
     * Reads the config, taking {@code server.acceptEula} from {@code <home>/.msc/eula.json} (see {@link MachineEula})
     * and never from the file, so no config can accept the EULA for anyone. Refuses, naming each key and why, every
     * value that can never work ({@link BenchConfigRules}), so a run fails here and not after a server's start timeout.
     * Every key is read by its type through {@link UserJson}'s exact reads (#416, #462): a value of another type, such
     * as {@code "acceptEula": "yes"} or {@code "command": "claude"}, is refused naming the key and the type it takes.
     */
    public static BenchConfig load(Path file, Path home) throws IOException {
        BenchConfig raw;
        List<String> problems;
        String text = Text.readOwn(file);
        if (text.isBlank()) {
            throw new IOException("Empty bench config " + file);
        }
        try {
            JsonElement parsed = StrictJson.parse(text, "");
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("is not a JSON object");
            }
            problems = new ArrayList<>();
            UserJson root = null;
            try {
                root = UserJson.of(parsed.getAsJsonObject(), "", KEYS);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
            raw = root == null ? null : read(root, problems);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException("Bad bench config " + file + ": " + e.getMessage(), e);
        }
        if (raw != null) {
            problems.addAll(BenchConfigRules.beforeDefaults(raw));
        }
        BenchConfig full = null;
        if (problems.isEmpty()) {
            full = raw.withDefaults(file.toAbsolutePath().getParent(), MachineEula.accepted(home));
            problems = BenchConfigRules.afterDefaults(full);
        }
        if (!problems.isEmpty()) {
            // One problem stays one line, as every other refusal of the command line is.
            throw new IOException("Bad bench config " + file + ":" + (problems.size() == 1 ? " " : "\n  ")
                    + String.join("\n  ", problems));
        }
        return full;
    }

    /**
     * Refuses, in one line naming {@code file} and each key, a {@code paths} entry that {@code modes} use in their MCP
     * config template or server plugins and that does not exist (#519), so {@code run} stops before its run folder
     * instead of running every toolset attempt without its tools. Only {@code run} asks this, so {@code list},
     * {@code check} and {@code report} work before the toolset is built.
     */
    public void requirePaths(Path file, Collection<String> modes) throws IOException {
        List<String> problems = BenchConfigRules.missingPaths(this, modes);
        if (!problems.isEmpty()) {
            throw new IOException("Bad bench config " + file + ": " + String.join("; ", problems));
        }
    }

    /**
     * The config as the file writes it, every key read by its type; a refused key goes to {@code problems} and reads
     * as absent, so the rest of the file is still read and checked. Null entries of lists and maps are kept for
     * {@link BenchConfigRules} to name.
     */
    static BenchConfig read(UserJson json, List<String> problems) {
        UserJson a = field(problems, () -> json.exactObject("agent", AGENT));
        Agent agent = a == null ? null : new Agent(
                field(problems, () -> a.stringsOrNulls("command")),
                number(problems, a, "agent.", "timeoutSeconds"));
        Map<String, UserJson> m = field(problems, () -> json.objectMapOrNulls("modes", MODE));
        Map<String, Mode> modes = null;
        if (m != null) {
            modes = new LinkedHashMap<>();
            for (Map.Entry<String, UserJson> e : m.entrySet()) {
                UserJson mode = e.getValue();
                modes.put(e.getKey(), mode == null ? null : new Mode(
                        field(problems, () -> mode.stringsOrNulls("extraArgs")),
                        field(problems, () -> mode.stringMapOrNulls("env")),
                        field(problems, () -> mode.path("mcpConfigTemplate", null)),
                        field(problems, () -> mode.stringMapOrNulls("serverEnv")),
                        field(problems, () -> mode.pathsOrNulls("serverPlugins")),
                        field(problems, () -> mode.path("paperCacheSeed", null)),
                        field(problems, () -> mode.exactString("requireMcp", null))));
            }
        }
        UserJson s = field(problems, () -> json.exactObject("server", SERVER));
        Server server = s == null ? null : new Server(
                field(problems, () -> s.stringsOrNulls("command")),
                field(problems, () -> s.path("paperJar", null)),
                field(problems, () -> s.pathsOrNulls("paperCacheDirs")),
                field(problems, () -> s.exactString("downloadsUrl", null)),
                // Read only for its type: the EULA choice comes from the machine, never from this file.
                Boolean.TRUE.equals(field(problems, () -> s.exactBool("acceptEula", false))),
                number(problems, s, "server.", "serverPort"),
                number(problems, s, "server.", "rconPort"),
                field(problems, () -> s.exactString("rconPassword", null)),
                number(problems, s, "server.", "startTimeoutSeconds"));
        return new BenchConfig(
                agent,
                modes,
                server,
                field(problems, () -> json.path("gradleWrapperFrom", null)),
                field(problems, () -> json.pathMapOrNulls("paths")),
                number(problems, json, "", "corePort"),
                field(problems, () -> json.exactString("minecraftVersion", null)));
    }

    /** The value {@code read} gives, or null with its refusal added to {@code problems}. */
    private static <T> T field(List<String> problems, Supplier<T> read) {
        try {
            return read.get();
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return null;
        }
    }

    /** The whole number {@code field} of {@code holder} in its {@link BenchConfigRules#NUMBERS} range, 0 when absent. */
    private static int number(List<String> problems, UserJson holder, String prefix, String field) {
        Integer value = field(problems, () -> holder.wholeNumber(field, BenchConfigRules.NUMBERS.get(prefix + field), 0));
        return value == null ? 0 : value;
    }

    /**
     * The config an empty {@code bench.json} in the working folder gives, the EULA not accepted: what {@code check}
     * fills its placeholders from when there is no bench.json, so no default is written twice.
     */
    public static BenchConfig defaults() {
        return new BenchConfig(null, null, null, null, null, 0, null)
                .withDefaults(Path.of("").toAbsolutePath(), false);
    }

    /** Fills defaults and turns relative paths into absolute ones. */
    BenchConfig withDefaults(Path base, boolean eulaAccepted) {
        Agent a = agent == null ? new Agent(null, 0) : agent;
        Agent fullAgent = new Agent(
                a.command() == null || a.command().isEmpty()
                        ? List.of("claude", "-p", "--output-format", "stream-json", "--verbose")
                        : List.copyOf(a.command()),
                a.timeoutSeconds() > 0 ? a.timeoutSeconds() : 1800);

        Map<String, Mode> fullModes = new LinkedHashMap<>();
        if (modes == null || modes.isEmpty()) {
            fullModes.put("baseline", new Mode(null, null, null, null, null, null, null));
        } else {
            modes.forEach((name, mode) -> fullModes.put(name, new Mode(
                    mode.extraArgs(),
                    mode.env(),
                    mode.mcpConfigTemplate() == null ? null : resolve(base, mode.mcpConfigTemplate()),
                    mode.serverEnv(),
                    mode.serverPlugins().stream().map(p -> p.startsWith("{") ? p : resolve(base, p)).toList(),
                    mode.paperCacheSeed(),
                    mode.requireMcp())));
        }

        Server s = server == null ? new Server(null, null, null, null, false, 0, 0, null, 0) : server;
        Server fullServer = new Server(
                s.command() == null || s.command().isEmpty()
                        ? List.of("java", "-Xmx2G", "-jar", "paper.jar", "--nogui")
                        : List.copyOf(s.command()),
                s.paperJar() == null ? null : resolve(base, s.paperJar()),
                (s.paperCacheDirs() == null ? List.of(".cache", "../.msc/cache") : s.paperCacheDirs())
                        .stream().map(d -> resolve(base, d)).toList(),
                s.downloadsUrl() == null ? "https://fill.papermc.io" : BenchConfigRules.baseUrl(s.downloadsUrl()),
                eulaAccepted,
                s.serverPort() > 0 ? s.serverPort() : 25566,
                s.rconPort() > 0 ? s.rconPort() : 25576,
                s.rconPassword() == null ? "msc-bench" : s.rconPassword(),
                s.startTimeoutSeconds() > 0 ? s.startTimeoutSeconds() : 180);

        Map<String, String> fullPaths = new LinkedHashMap<>();
        if (paths != null) {
            paths.forEach((k, v) -> fullPaths.put(k, resolve(base, v)));
        }

        return new BenchConfig(
                fullAgent,
                Map.copyOf(fullModes),
                fullServer,
                gradleWrapperFrom == null ? null : resolve(base, gradleWrapperFrom),
                Map.copyOf(fullPaths),
                corePort > 0 ? corePort : 25590,
                minecraftVersion == null ? "26.3" : minecraftVersion);
    }

    /**
     * This config with the agent run on {@code model}: the value after {@code --model} in {@code agent.command} is
     * replaced, or {@code --model model} is added when the command has none (the {@code run --model} option).
     */
    public BenchConfig withModel(String model) {
        if (model == null || model.isBlank() || model.startsWith("-")) {
            throw new IllegalArgumentException("--model \"" + model + "\" is no model name, such as claude-sonnet-4-6");
        }
        List<String> command = new ArrayList<>(agent.command());
        int at = command.indexOf("--model");
        if (at >= 0 && at + 1 < command.size()) {
            command.set(at + 1, model);
        } else {
            command.remove("--model");
            command.addAll(List.of("--model", model));
        }
        return new BenchConfig(new Agent(List.copyOf(command), agent.timeoutSeconds()), modes, server,
                gradleWrapperFrom, paths, corePort, minecraftVersion);
    }

    /** This config with {@code prefix} put in front of {@code agent.command} (the {@code run --isolate} option). */
    public BenchConfig withAgentPrefix(List<String> prefix) {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(agent.command());
        return new BenchConfig(new Agent(List.copyOf(command), agent.timeoutSeconds()), modes, server,
                gradleWrapperFrom, paths, corePort, minecraftVersion);
    }

    private static String resolve(Path base, String path) {
        return base.resolve(path).normalize().toString();
    }
}
