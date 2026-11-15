package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.StrictJson;
import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values of {@code bench.json} that can never work, refused when the config loads so that no run starts a server
 * or an agent with them (#406: an empty RCON password turned RCON off, and every server task waited out its start
 * timeout). Each problem names its key, such as {@code server.rconPassword}, and why the value cannot work.
 */
final class BenchConfigRules {

    /** Placeholders the runner fills itself; a {@code paths} entry with one of these names would be overwritten. */
    static final Set<String> BUILT_IN_PLACEHOLDERS = Set.of("java", "workspace", "attemptDir", "toolLog", "rconPort",
            "rconPassword", "serverPort", "corePort", "minecraftVersion", "minestomMinecraftVersion", "scaffoldVersion",
            "coreToken", "gradlew", "mcpConfig", "prompt");

    /** Names usable as a mode (folder names, {@code --modes} lists) or Minecraft version (URLs, folder names). */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    /**
     * The number keys of {@code bench.json}, each with the values it takes, read exactly as written (#416,
     * {@link BenchConfig#read}).
     */
    static final Map<String, JsonNumbers.Range> NUMBERS = numbers();

    private BenchConfigRules() {
    }

    private static Map<String, JsonNumbers.Range> numbers() {
        Map<String, JsonNumbers.Range> numbers = new LinkedHashMap<>();
        numbers.put("agent.timeoutSeconds", JsonNumbers.SECONDS);
        numbers.put("server.serverPort", JsonNumbers.PORT);
        numbers.put("server.rconPort", JsonNumbers.PORT);
        numbers.put("server.startTimeoutSeconds", JsonNumbers.SECONDS);
        numbers.put("corePort", JsonNumbers.PORT);
        return Collections.unmodifiableMap(numbers);
    }

    /**
     * Problems visible before defaults are filled in: null or blank entries and lists and maps given empty. Numbers
     * are checked as they are read, by {@link #NUMBERS}.
     */
    static List<String> beforeDefaults(BenchConfig raw) {
        List<String> problems = new ArrayList<>();
        if (raw.agent() != null) {
            BenchConfig.Agent agent = raw.agent();
            command(problems, "agent.command", agent.command());
        }
        if (raw.modes() != null) {
            if (raw.modes().isEmpty()) {
                problems.add("modes: is empty; name at least one mode, or leave it out for a single baseline mode");
            }
            raw.modes().forEach((name, mode) -> mode(problems, name, mode));
        }
        if (raw.server() != null) {
            BenchConfig.Server server = raw.server();
            command(problems, "server.command", server.command());
            notBlank(problems, "server.paperJar", server.paperJar());
            if (server.paperCacheDirs() != null) {
                if (server.paperCacheDirs().isEmpty()) {
                    problems.add("server.paperCacheDirs: is empty; the Paper jar needs a cache folder, so leave it out "
                            + "for the defaults or name one");
                }
                entries(problems, "server.paperCacheDirs", server.paperCacheDirs());
            }
            notBlank(problems, "server.downloadsUrl", server.downloadsUrl());
        }
        notBlank(problems, "gradleWrapperFrom", raw.gradleWrapperFrom());
        if (raw.paths() != null) {
            raw.paths().forEach((name, path) -> {
                if (!NAME.matcher(name).matches()) {
                    problems.add("paths: the name \"" + name + "\" is no placeholder name; use letters, digits, '.', "
                            + "'_' or '-'");
                } else if (BUILT_IN_PLACEHOLDERS.contains(name)) {
                    problems.add("paths." + name + ": {" + name + "} is filled by the runner itself, which would "
                            + "overwrite this path; pick another name");
                }
                notBlank(problems, "paths." + name, path, true);
            });
        }
        if (raw.minecraftVersion() != null && !NAME.matcher(raw.minecraftVersion()).matches()) {
            problems.add("minecraftVersion: \"" + raw.minecraftVersion() + "\" is no Minecraft version, such as 26.3");
        }
        return problems;
    }

    /** Problems of the filled-in config: values that clash, files that must exist, and the RCON password. */
    static List<String> afterDefaults(BenchConfig full) {
        List<String> problems = new ArrayList<>();
        full.modes().forEach((name, mode) -> {
            if (mode.mcpConfigTemplate() != null && !Files.isRegularFile(Path.of(mode.mcpConfigTemplate()))) {
                problems.add("modes." + name + ".mcpConfigTemplate: no such file " + mode.mcpConfigTemplate());
            }
            for (String plugin : mode.serverPlugins()) {
                Matcher placeholder = PLACEHOLDER.matcher(plugin);
                while (placeholder.find()) {
                    String key = placeholder.group(1);
                    if (!full.paths().containsKey(key) && !BUILT_IN_PLACEHOLDERS.contains(key)) {
                        problems.add("modes." + name + ".serverPlugins: {" + key + "} in \"" + plugin + "\" is no "
                                + "placeholder; name a key of paths, such as {agentJar}");
                    }
                }
            }
        });
        BenchConfig.Server server = full.server();
        if (server.paperJar() != null && !Files.isRegularFile(Path.of(server.paperJar()))) {
            problems.add("server.paperJar: no such file " + server.paperJar());
        }
        downloadsUrl(problems, server.downloadsUrl());
        rconPassword(problems, server.rconPassword());
        distinct(problems, "server.serverPort", server.serverPort(), "server.rconPort", server.rconPort());
        distinct(problems, "corePort", full.corePort(), "server.serverPort", server.serverPort());
        distinct(problems, "corePort", full.corePort(), "server.rconPort", server.rconPort());
        if (full.gradleWrapperFrom() != null && !Files.isRegularFile(Path.of(full.gradleWrapperFrom(), "gradlew"))) {
            problems.add("gradleWrapperFrom: " + full.gradleWrapperFrom() + " has no gradlew, so Gradle workspaces "
                    + "would get no wrapper");
        }
        return problems;
    }

    /** What builds the {@code paths} entries of the shipped {@code bench.json}, named when one is missing. */
    private static final Map<String, String> BUILT_BY = Map.of(
            "coreLib", "./gradlew :msc-core:installDist",
            "agentJar", "./gradlew :msc-agent-paper:shadowJar");

    /**
     * #519: the {@code paths} entries the selected modes use, in their MCP config template or server plugins, that do
     * not exist. Checked by {@code run} only, before the run folder is made, so {@code list}, {@code check} and
     * {@code report} still load a config whose toolset parts are not built yet; with such a path every toolset attempt
     * would run the agent without its tools. A selected mode's MCP config template is read here too, by the bench's
     * one strict JSON reader (#601): a template that is no strict JSON, or that gives a key twice, is refused naming
     * the file and the key, since Claude Code would read it its own way. Placeholders go inside JSON strings.
     */
    static List<String> missingPaths(BenchConfig full, Collection<String> modes) throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : modes) {
            BenchConfig.Mode mode = full.modes().get(name);
            if (mode == null) {
                continue;
            }
            Map<String, String> uses = new LinkedHashMap<>();
            if (mode.mcpConfigTemplate() != null) {
                String template = Text.readOwn(Path.of(mode.mcpConfigTemplate()));
                try {
                    StrictJson.parse(template, mode.mcpConfigTemplate() + " ");
                } catch (IllegalArgumentException e) {
                    problems.add("modes." + name + ".mcpConfigTemplate: " + e.getMessage());
                }
                for (String key : placeholders(template)) {
                    uses.putIfAbsent(key, "modes." + name + ".mcpConfigTemplate");
                }
            }
            for (String plugin : mode.serverPlugins()) {
                for (String key : placeholders(plugin)) {
                    uses.putIfAbsent(key, "modes." + name + ".serverPlugins");
                }
            }
            uses.forEach((key, usedBy) -> {
                String path = full.paths().get(key);
                String problem = "paths." + key + ": no such file or folder " + path + " (used by " + usedBy + "); "
                        + (BUILT_BY.containsKey(key) ? "run " + BUILT_BY.get(key) + " first" : "build it first")
                        + " or fix the path";
                if (path != null && !Files.exists(Path.of(path)) && problems.stream().noneMatch(p -> p.startsWith("paths." + key + ":"))) {
                    problems.add(problem);
                }
            });
        }
        return problems;
    }

    private static List<String> placeholders(String text) {
        List<String> keys = new ArrayList<>();
        Matcher placeholder = PLACEHOLDER.matcher(text);
        while (placeholder.find()) {
            keys.add(placeholder.group(1));
        }
        return keys;
    }

    /**
     * The RCON password must be one a vanilla server turns RCON on for and a client can log in with: not empty or
     * blank (the server turns RCON off without one), at most {@link RconClient#MAX_BODY} bytes in UTF-8 (the server
     * drops a longer request), no NUL (the server reads the body up to the first one) and no lone surrogate (it
     * becomes '?' in UTF-8, so the password sent differs from the one written).
     */
    static void rconPassword(List<String> problems, String password) {
        String key = "server.rconPassword: ";
        if (password.isBlank()) {
            problems.add(key + "is " + (password.isEmpty() ? "empty" : "blank") + "; the server turns RCON off without "
                    + "a password, so leave it out for the default or set one");
            return;
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > RconClient.MAX_BODY) {
            problems.add(key + "is " + bytes + " bytes in UTF-8; a Minecraft server reads at most "
                    + RconClient.MAX_BODY + " bytes per RCON request");
        }
        if (password.indexOf('\0') >= 0) {
            problems.add(key + "holds a NUL character; the server reads the password only up to it");
        }
        if (Text.hasLoneSurrogate(password)) {
            problems.add(key + "holds a lone surrogate, which cannot be sent in UTF-8");
        }
    }

    private static void mode(List<String> problems, String name, BenchConfig.Mode mode) {
        String key = "modes." + name;
        if (!NAME.matcher(name).matches()) {
            problems.add(key + ": the name is no mode name (it names attempt folders and --modes lists); use letters, "
                    + "digits, '.', '_' or '-'");
        }
        if (mode == null) {
            problems.add(key + ": is null; use {} for a mode with no settings");
            return;
        }
        entries(problems, key + ".extraArgs", mode.extraArgs());
        environment(problems, key + ".env", mode.env());
        notBlank(problems, key + ".mcpConfigTemplate", mode.mcpConfigTemplate());
        environment(problems, key + ".serverEnv", mode.serverEnv());
        entries(problems, key + ".serverPlugins", mode.serverPlugins());
        notBlank(problems, key + ".paperCacheSeed", mode.paperCacheSeed());
        if (mode.paperCacheSeed() != null && !mode.paperCacheSeed().isBlank()) {
            Path seed = Path.of(mode.paperCacheSeed());
            if (seed.isAbsolute() || seed.getRoot() != null || seed.normalize().startsWith("..")) {
                problems.add(key + ".paperCacheSeed: " + mode.paperCacheSeed() + " is not a folder inside the workspace");
            }
        }
        notBlank(problems, key + ".requireMcp", mode.requireMcp());
    }

    /**
     * A base URL as the bench joins paths to it, without its trailing slashes (#649): {@code https://fill.papermc.io/}
     * becomes {@code https://fill.papermc.io}, as msc-core's {@code Downloads.trim} makes it, so a join never gives
     * {@code //v3}.
     */
    static String baseUrl(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    private static void downloadsUrl(List<String> problems, String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    // The bench puts /v3/... after the base, so a query or fragment would swallow that path (#649).
                    problems.add("server.downloadsUrl: \"" + url + "\" has a query or fragment; give the service's "
                            + "base URL, such as https://fill.papermc.io");
                }
                return;
            }
        } catch (URISyntaxException e) {
            // reported below
        }
        problems.add("server.downloadsUrl: \"" + url + "\" is no http or https URL");
    }

    private static void command(List<String> problems, String key, List<String> command) {
        if (command == null) {
            return;
        }
        if (command.isEmpty()) {
            problems.add(key + ": is empty; leave it out for the default or name a program");
        }
        entries(problems, key, command);
    }

    private static void entries(List<String> problems, String key, List<String> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == null || entries.get(i).isBlank()) {
                problems.add(key + ": entry " + i + " is " + (entries.get(i) == null ? "null" : "blank"));
            }
        }
    }

    private static void environment(List<String> problems, String key, Map<String, String> env) {
        env.forEach((name, value) -> {
            if (name.isEmpty() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
                problems.add(key + ": \"" + name + "\" is no environment variable name");
            }
            if (value == null) {
                problems.add(key + "." + name + ": is null");
            } else if (value.indexOf('\0') >= 0) {
                problems.add(key + "." + name + ": holds a NUL character, which no environment can hold");
            }
        });
    }

    private static void notBlank(List<String> problems, String key, String value) {
        notBlank(problems, key, value, false);
    }

    private static void notBlank(List<String> problems, String key, String value, boolean required) {
        if (value == null ? required : value.isBlank()) {
            problems.add(key + ": is " + (value == null ? "null" : "blank") + "; leave it out or give a value");
        }
    }

    private static void distinct(List<String> problems, String key, int port, String otherKey, int otherPort) {
        if (port == otherPort) {
            problems.add(key + ": " + port + " is also " + otherKey + "; each needs its own port");
        }
    }
}
