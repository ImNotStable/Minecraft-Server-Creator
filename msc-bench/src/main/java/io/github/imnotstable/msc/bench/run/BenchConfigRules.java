package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.Quoted;
import io.github.imnotstable.msc.bench.StrictJson;
import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.UserJson;
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
 *
 * <p>A problem shows a value the file holds, or a name it chose, only through {@link Quoted} (#662), so it stays on
 * one line; it never shows a secret ({@code server.rconPassword}, the values under {@code env} and {@code serverEnv})
 * at all (#661). A rule checks each entry of a list or object on its own, and leaves out an entry that was refused
 * when it was read ({@code refused}, as {@link io.github.imnotstable.msc.bench.UserJson#collecting} names it), which
 * reads as null, so it is not named a second time (#663).
 */
final class BenchConfigRules {

    /** Placeholders the runner fills itself; a {@code paths} entry with one of these names would be overwritten. */
    static final Set<String> BUILT_IN_PLACEHOLDERS = Set.of("java", "workspace", "attemptDir", "toolLog", "rconPort",
            "rconPassword", "serverPort", "corePort", "minecraftVersion", "minestomMinecraftVersion", "scaffoldVersion",
            "resourcePackFormat", "coreToken", "gradlew", "mcpConfig", "prompt");

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
     * Problems visible before defaults are filled in: null or blank entries, lists and maps given empty, and the
     * strings checked as the file writes them ({@code server.downloadsUrl}, {@code server.rconPassword}), so that a
     * refusal quotes the value written and not the one the run would use (#652). Numbers are checked as they are read,
     * by {@link #NUMBERS}.
     */
    static List<String> beforeDefaults(BenchConfig raw, Set<String> refused) {
        List<String> problems = new ArrayList<>();
        if (raw.agent() != null) {
            BenchConfig.Agent agent = raw.agent();
            command(problems, "agent.command", agent.command(), refused);
        }
        if (raw.modes() != null) {
            if (raw.modes().isEmpty()) {
                problems.add("modes: is empty; name at least one mode, or leave it out for a single baseline mode");
            }
            raw.modes().forEach((name, mode) -> mode(problems, name, mode, refused));
        }
        if (raw.server() != null) {
            BenchConfig.Server server = raw.server();
            command(problems, "server.command", server.command(), refused);
            notBlank(problems, "server.paperJar", server.paperJar());
            if (server.paperCacheDirs() != null) {
                if (server.paperCacheDirs().isEmpty()) {
                    problems.add("server.paperCacheDirs: is empty; the Paper jar needs a cache folder, so leave it out "
                            + "for the defaults or name one");
                }
                entries(problems, "server.paperCacheDirs", server.paperCacheDirs(), refused);
            }
            notBlank(problems, "server.downloadsUrl", server.downloadsUrl());
            if (server.downloadsUrl() != null && !server.downloadsUrl().isBlank()) {
                downloadsUrl(problems, server.downloadsUrl());
            }
            if (server.rconPassword() != null) {
                rconPassword(problems, server.rconPassword());
            }
        }
        notBlank(problems, "gradleWrapperFrom", raw.gradleWrapperFrom());
        if (raw.paths() != null) {
            raw.paths().forEach((name, path) -> {
                String key = "paths." + Quoted.name(name);
                if (!NAME.matcher(name).matches()) {
                    problems.add("paths: the name " + Quoted.text(name) + " is no placeholder name; use letters, digits, "
                            + "'.', '_' or '-'");
                } else if (BUILT_IN_PLACEHOLDERS.contains(name)) {
                    problems.add(key + ": {" + name + "} is filled by the runner itself, which would "
                            + "overwrite this path; pick another name");
                }
                if (!refused.contains(key)) {
                    notBlank(problems, key, path, true);
                }
            });
        }
        if (raw.minecraftVersion() != null && !NAME.matcher(raw.minecraftVersion()).matches()) {
            problems.add("minecraftVersion: " + Quoted.text(raw.minecraftVersion()) + " is no Minecraft version, such as "
                    + "26.3");
        }
        return problems;
    }

    /**
     * Problems of the filled-in config {@code full}: values that clash and files that must exist. They run whatever
     * {@link #beforeDefaults} and the typed reads found (#653), on every value that is itself valid: a value refused
     * already ({@code unread}, null or blank) is not checked again, and a rule that depends on one is left out (a
     * port clash with a port that could not be read, or with any {@code server} port when {@code server} could not
     * be; the {@code serverPlugins} placeholders when {@code paths} could not be read, and a placeholder naming a
     * refused {@code paths} entry, which stays in {@code raw} as null). A refusal quotes the string from {@code raw},
     * the config as written, followed by the absolute path a missing file was looked for at.
     */
    static List<String> afterDefaults(BenchConfig raw, BenchConfig full, Set<String> unread) {
        List<String> problems = new ArrayList<>();
        Set<String> pathNames = raw.paths() == null ? Set.of() : raw.paths().keySet();
        full.modes().forEach((name, mode) -> {
            BenchConfig.Mode written = raw.modes() == null ? null : raw.modes().get(name);
            if (written == null) {
                return; // the default baseline mode, which has nothing to check
            }
            String key = "modes." + Quoted.name(name);
            if (isSet(mode.mcpConfigTemplate()) && !Files.isRegularFile(Path.of(mode.mcpConfigTemplate()))) {
                problems.add(key + ".mcpConfigTemplate: no such file "
                        + where(written.mcpConfigTemplate(), mode.mcpConfigTemplate()));
            }
            if (unread.contains("paths")) {
                return;
            }
            for (String plugin : written.serverPlugins()) {
                if (plugin == null) {
                    continue;
                }
                Matcher placeholder = PLACEHOLDER.matcher(plugin);
                while (placeholder.find()) {
                    String placeholderName = placeholder.group(1);
                    if (!pathNames.contains(placeholderName) && !BUILT_IN_PLACEHOLDERS.contains(placeholderName)) {
                        problems.add(key + ".serverPlugins: " + placeholder(placeholderName) + " in " + Quoted.text(plugin)
                                + " is no placeholder; name a key of paths, such as {agentJar}");
                    }
                }
            }
        });
        BenchConfig.Server server = full.server();
        BenchConfig.Server writtenServer = raw.server();
        if (isSet(server.paperJar()) && !Files.isRegularFile(Path.of(server.paperJar()))) {
            problems.add("server.paperJar: no such file " + where(writtenServer.paperJar(), server.paperJar()));
        }
        Map<String, Integer> ports = new LinkedHashMap<>();
        if (!unread.contains("server")) {
            ports.put("server.serverPort", server.serverPort());
            ports.put("server.rconPort", server.rconPort());
        }
        ports.put("corePort", full.corePort());
        ports.keySet().removeAll(unread);
        distinct(problems, ports, "server.serverPort", "server.rconPort");
        distinct(problems, ports, "corePort", "server.serverPort");
        distinct(problems, ports, "corePort", "server.rconPort");
        if (isSet(full.gradleWrapperFrom()) && !Files.isRegularFile(Path.of(full.gradleWrapperFrom(), "gradlew"))) {
            problems.add("gradleWrapperFrom: " + where(raw.gradleWrapperFrom(), full.gradleWrapperFrom())
                    + " has no gradlew, so Gradle workspaces would get no wrapper");
        }
        return problems;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * A path as a refusal shows it: quoted as the file writes it, then the absolute path it resolved to when that
     * differs, such as {@code "missing.jar" (/home/me/bench/missing.jar)} (#652).
     */
    private static String where(String written, String resolved) {
        return Quoted.text(written) + (resolved.equals(written) ? "" : " (" + Quoted.path(resolved) + ")");
    }

    /** {@code {name}} as a refusal shows a placeholder: as it is for a plain name, else quoted. */
    private static String placeholder(String name) {
        String placeholder = "{" + name + "}";
        return Quoted.name(name).equals(name) ? placeholder : Quoted.text(placeholder);
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
    static List<String> missingPaths(BenchConfig full, Map<String, String> written, Collection<String> modes)
            throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : modes) {
            BenchConfig.Mode mode = full.modes().get(name);
            if (mode == null) {
                continue;
            }
            String modeKey = "modes." + Quoted.name(name);
            Map<String, String> uses = new LinkedHashMap<>();
            if (mode.mcpConfigTemplate() != null) {
                String template = Text.readOwn(Path.of(mode.mcpConfigTemplate()));
                try {
                    StrictJson.parse(template, Quoted.path(mode.mcpConfigTemplate()) + " ");
                } catch (IllegalArgumentException e) {
                    problems.add(modeKey + ".mcpConfigTemplate: " + e.getMessage());
                }
                for (String key : placeholders(template)) {
                    uses.putIfAbsent(key, modeKey + ".mcpConfigTemplate");
                }
            }
            for (String plugin : mode.serverPlugins()) {
                for (String key : placeholders(plugin)) {
                    uses.putIfAbsent(key, modeKey + ".serverPlugins");
                }
            }
            uses.forEach((key, usedBy) -> {
                String path = full.paths().get(key);
                String pathKey = "paths." + Quoted.name(key) + ":";
                if (path != null && !Files.exists(Path.of(path))
                        && problems.stream().noneMatch(p -> p.startsWith(pathKey))) {
                    String writtenPath = written == null ? null : written.get(key);
                    problems.add(pathKey + " no such file or folder "
                            + (writtenPath == null ? Quoted.path(path) : where(writtenPath, path))
                            + " (used by " + usedBy + "); "
                            + (BUILT_BY.containsKey(key) ? "run " + BUILT_BY.get(key) + " first" : "build it first")
                            + " or fix the path");
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

    private static void mode(List<String> problems, String name, BenchConfig.Mode mode, Set<String> refused) {
        String key = "modes." + Quoted.name(name);
        if (!NAME.matcher(name).matches()) {
            problems.add(key + ": the name is no mode name (it names attempt folders and --modes lists); use letters, "
                    + "digits, '.', '_' or '-'");
        }
        if (mode == null) {
            if (!refused.contains(key)) {
                problems.add(key + ": is null; use {} for a mode with no settings");
            }
            return;
        }
        entries(problems, key + ".extraArgs", mode.extraArgs(), refused);
        environment(problems, key + ".env", mode.env(), refused);
        notBlank(problems, key + ".mcpConfigTemplate", mode.mcpConfigTemplate());
        environment(problems, key + ".serverEnv", mode.serverEnv(), refused);
        entries(problems, key + ".serverPlugins", mode.serverPlugins(), refused);
        notBlank(problems, key + ".paperCacheSeed", mode.paperCacheSeed());
        if (mode.paperCacheSeed() != null && !mode.paperCacheSeed().isBlank()) {
            Path seed = Path.of(mode.paperCacheSeed());
            if (seed.isAbsolute() || seed.getRoot() != null || seed.normalize().startsWith("..")) {
                problems.add(key + ".paperCacheSeed: " + Quoted.text(mode.paperCacheSeed())
                        + " is not a folder inside the workspace");
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
                    problems.add("server.downloadsUrl: " + Quoted.text(url) + " has a query or fragment; give the service's "
                            + "base URL, such as https://fill.papermc.io");
                }
                return;
            }
        } catch (URISyntaxException e) {
            // reported below
        }
        problems.add("server.downloadsUrl: " + Quoted.text(url) + " is no http or https URL");
    }

    private static void command(List<String> problems, String key, List<String> command, Set<String> refused) {
        if (command == null) {
            return;
        }
        if (command.isEmpty()) {
            problems.add(key + ": is empty; leave it out for the default or name a program");
        }
        entries(problems, key, command, refused);
    }

    /** Refuses each null or blank entry of {@code entries}, except one refused when it was read. */
    private static void entries(List<String> problems, String key, List<String> entries, Set<String> refused) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == null ? !refused.contains(key + "[" + i + "]") : entries.get(i).isBlank()) {
                problems.add(key + ": entry " + i + " is " + (entries.get(i) == null ? "null" : "blank"));
            }
        }
    }

    /**
     * Refuses each name of {@code env} no environment can hold and each value that is null or holds a NUL, except a
     * value refused when it was read. Its values are secret, and so is a name's text after an {@code =}
     * ({@link Quoted#secretName}): neither is ever shown.
     */
    private static void environment(List<String> problems, String key, Map<String, String> env,
            Set<String> refused) {
        env.forEach((name, value) -> {
            String entry = key + "." + Quoted.secretName(name);
            if (name.isEmpty() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
                problems.add(key + ": " + Quoted.secretName(name) + " is no environment variable name");
            }
            if (value == null) {
                if (!refused.contains(entry)) {
                    problems.add(entry + ": is null");
                }
            } else if (value.indexOf('\0') >= 0) {
                problems.add(entry + ": holds a NUL character, which no environment can hold");
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

    /** Refuses {@code key} and {@code otherKey} sharing a port, when {@code ports} holds both (both could be read). */
    private static void distinct(List<String> problems, Map<String, Integer> ports, String key, String otherKey) {
        Integer port = ports.get(key);
        if (port != null && port.equals(ports.get(otherKey))) {
            problems.add(key + ": " + port + " is also " + otherKey + "; each needs its own port");
        }
    }
}
