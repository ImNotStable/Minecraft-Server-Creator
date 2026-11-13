package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.tool.Budget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns server logs and build output into the few lines worth a model's attention: warnings and
 * errors, stack traces cut down to the project's own frames, repeats collapsed.
 */
public final class LogCondenser {
    /** {@code [12:34:56 INFO]: msg} (Paper) or {@code [12:34:56] [Server thread/INFO]: msg} (vanilla). */
    private static final Pattern LINE = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)(?:\\] \\[[^\\]]*/| )(\\w+)\\]:? ?(.*)$");
    /**
     * A stack frame; the class loader and module prefixes ({@code app//}, {@code java.base/}, Forge's
     * {@code TRANSFORMER/mymod@1.0/}) are skipped.
     */
    private static final Pattern FRAME = Pattern.compile("^\\s+at (?:[^\\s/()]+/+){0,2}([\\w.$<>]+)\\(.*\\).*$");
    private static final Pattern MORE = Pattern.compile("^\\s+\\.\\.\\. \\d+ (more|common frames omitted).*$");
    /**
     * Forge and NeoForge put the logger after the level: {@code [12:34:56] [main/WARN] [ne.mi.fm.Foo/CORE]: msg}. The
     * logger is dropped from the message (it costs tokens and says little), but kept to recognise known noise.
     */
    private static final Pattern LOGGER = Pattern.compile("^\\[([\\w.$]+)/[^\\]]*\\]: (.*)$");
    /** OSHI, the system-report library, under its own name or Forge's shortened one ({@code os.ha.pl...}). */
    private static final Pattern OSHI_LOGGER = Pattern.compile("^(oshi|os)\\..*");

    /** Frames from these packages are noise unless nothing else is there. */
    private static final List<String> FRAMEWORK = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.minecraft.", "com.mojang.", "org.bukkit.", "org.spigotmc.", "io.papermc.", "com.destroystokyo.",
            "io.netty.", "org.apache.", "com.google.", "it.unimi.", "org.slf4j.", "co.aikar.", "ca.spottedleaf.",
            "org.gradle.", "org.junit.", "org.mockbukkit.", "be.seeseemelk.", "kotlin.", "worker.org.gradle.", "oshi.",
            "net.minestom.", "org.purpurmc.", "net.fabricmc.", "org.quiltmc.", "net.neoforged.", "net.minecraftforge.",
            "cpw.mods.", "org.spongepowered.");

    /**
     * Entries every dev server prints and the AI can do nothing about: Paper's banners for running as root or
     * administrator and for offline mode (the dev server is offline on purpose), and the system report's oshi
     * errors and warnings on some Windows machines. Dropped so a clean start reads {@code no errors or warnings}.
     */
    private static final List<Pattern> EXPECTED = List.of(
            Pattern.compile("^\\*+$"),
            Pattern.compile("^YOU ARE (RUNNING THIS SERVER AS AN ADMINISTRATIVE OR ROOT USER|OPENING YOURSELF UP TO).*"),
            Pattern.compile("^FOR MORE INFORMATION, SEE https://madelinemiller\\.dev/.*"),
            Pattern.compile("^\\*+ SERVER IS RUNNING IN OFFLINE/INSECURE MODE!.*"),
            Pattern.compile("^(The server will make no attempt to authenticate usernames|While this makes the game "
                    + "possible to play without internet access|To change this, set \"online-mode\" to \"true\").*"),
            // Paper's system report on Windows: oshi messages and "Failed to get system info for ..." with an NPE.
            Pattern.compile("^\\[(oshi|os)\\.[\\w.$]+\\] .*"),
            Pattern.compile("^Failed to get system info for .*"),
            // The same oshi message on Fabric and Quilt, whose log lines carry no logger.
            Pattern.compile("^Unable to locate English counter names in registry Perflib.*"),
            // Forge's first start writes its default config files. A key corrected from a value someone set is kept.
            // A mod jar without pack.mcmeta is kept too: the template and the agent ship one, so it is a real mistake.
            Pattern.compile("^Configuration file .* is not correct\\. Correcting.*"),
            Pattern.compile("^Incorrect key .* was corrected from null to its default.*"),
            // Quilt on Minecraft 26.x, which ships unobfuscated: there is no intermediary to remap to, and vanilla's
            // asset lookup does not know Quilt's in-memory file system.
            Pattern.compile("^Not remapping mods because intermediary is missing!$"),
            Pattern.compile("^Assets URL 'quilt\\.zfs://[^']*' uses unexpected schema$"));
    /** Quilt Loader's start-up timings, printed to stdout without a timestamp, so they read as another entry's lines. */
    private static final Pattern QUILT_TIMING =
            Pattern.compile("^(mod adding|tmp jar copy|transform-cache|zip sub copy) took \\d+ms$");
    /**
     * Netty probing the macOS and Linux native transports on another OS (NeoForge and Forge log the failed probe
     * with its trace). Matched anywhere in an entry together with {@link #NETTY_UNSUPPORTED}.
     */
    private static final Pattern NETTY_NATIVE = Pattern.compile("io\\.netty\\.channel\\.(kqueue|epoll|uring)\\.Native");
    private static final Pattern NETTY_UNSUPPORTED = Pattern.compile("Only supported on (OSX/BSD|Linux)");

    private static final List<Pattern> BUILD_NOISE = List.of(
            Pattern.compile("^> Task (?!.*FAILED).*"),
            Pattern.compile("^(Picked up |Download|Starting a Gradle Daemon|Daemon will be stopped|To honour the JVM)"),
            Pattern.compile("^> (Run with|Get more help).*"),
            Pattern.compile("^(Deprecated Gradle features|You can use '--warning-mode|For more on this, please refer|See https://docs.gradle.org).*"),
            Pattern.compile("^\\d+ actionable tasks?:.*"),
            Pattern.compile("^(Calculating task graph|Configuration cache entry|Reusing configuration cache).*"),
            Pattern.compile("^Note: (Some input files|Recompile with).*"),
            Pattern.compile("^(BUILD SUCCESSFUL|Welcome to Gradle|Starting a Gradle Daemon|> Configure project).*"),
            // JDK warnings printed by test libraries (Mockito, ByteBuddy) on every run.
            Pattern.compile("^WARNING: (A terminally deprecated method|sun\\.misc\\.Unsafe::|Please consider reporting|"
                    + "A Java agent has been loaded|If a serviceability tool|Dynamic loading of agents).*"));

    private LogCondenser() {
    }

    private static final class Entry {
        final String time;
        final String level;
        final StringBuilder text = new StringBuilder();
        final List<String> extra = new ArrayList<>();

        Entry(String time, String level, String message) {
            this.time = time;
            this.level = level;
            text.append(message);
        }
    }

    /**
     * @param text the condensed log, starting with a one-line count
     * @param hidden warnings and errors dropped as known-harmless
     */
    public record Condensed(String text, int errors, int warnings, int hidden) {
        public boolean clean() {
            return errors == 0 && warnings == 0;
        }
    }

    /** Errors and warnings from a server log, as text. */
    public static String condense(List<String> lines, int maxChars) {
        return analyze(lines, maxChars).text();
    }

    /** Errors and warnings from a server log. */
    public static Condensed analyze(List<String> lines, int maxChars) {
        List<Entry> entries = new ArrayList<>();
        Entry current = null;
        for (String raw : lines) {
            String line = stripAnsi(raw);
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                String message = m.group(3);
                Matcher logger = LOGGER.matcher(message);
                if (logger.matches()) {
                    message = OSHI_LOGGER.matcher(logger.group(1)).matches()
                            ? "[" + logger.group(1) + "] " + logger.group(2) : logger.group(2);
                }
                current = new Entry(m.group(1), m.group(2).toUpperCase(), message);
                entries.add(current);
            } else if (!line.isBlank() && !QUILT_TIMING.matcher(line).matches()) {
                if (current == null) {
                    current = new Entry("", "", line);
                    entries.add(current);
                } else {
                    current.extra.add(line);
                }
            }
        }
        int errors = 0;
        int warnings = 0;
        int hidden = 0;
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> firstTime = new LinkedHashMap<>();
        for (Entry e : entries) {
            boolean error = e.level.equals("ERROR") || e.level.equals("FATAL") || e.level.equals("SEVERE");
            boolean warn = e.level.startsWith("WARN");
            boolean trace = e.extra.stream().anyMatch(x -> FRAME.matcher(x).matches());
            if (!error && !warn && !trace) {
                continue;
            }
            if (expected(e)) {
                hidden++;
                continue;
            }
            if (error || (trace && !warn)) {
                errors++;
            } else {
                warnings++;
            }
            StringBuilder body = new StringBuilder(e.level.isEmpty() ? "" : e.level + " ").append(e.text);
            for (String x : trimTrace(e.extra)) {
                body.append('\n').append(x);
            }
            String key = body.toString();
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            firstTime.putIfAbsent(key, e.time);
        }
        StringBuilder out = new StringBuilder();
        out.append(lines.size()).append(" lines, ").append(errors).append(" errors, ").append(warnings)
                .append(" warnings");
        if (hidden > 0) {
            out.append(", ").append(hidden).append(" known-harmless hidden");
        }
        for (Map.Entry<String, int[]> c : counts.entrySet()) {
            out.append('\n');
            String time = firstTime.get(c.getKey());
            if (!time.isEmpty()) {
                out.append(time).append(' ');
            }
            int n = c.getValue()[0];
            String body = c.getKey();
            if (n > 1) {
                int nl = body.indexOf('\n');
                body = nl < 0 ? body + " (x" + n + ")" : body.substring(0, nl) + " (x" + n + ")" + body.substring(nl);
            }
            out.append(body);
        }
        return new Condensed(Budget.truncate(out.toString(), maxChars, "Use server_logs with grep to narrow."),
                errors, warnings, hidden);
    }

    private static boolean expected(Entry e) {
        if (EXPECTED.stream().anyMatch(p -> p.matcher(e.text).matches())) {
            return true;
        }
        String all = e.text + "\n" + String.join("\n", e.extra);
        return NETTY_NATIVE.matcher(all).find() && NETTY_UNSUPPORTED.matcher(all).find();
    }

    /**
     * Keep exception headers and project frames; fold runs of framework frames into one line. Drop the
     * "Transformer Audit" block Forge's ModLauncher appends to traces (which classes its transformers touched).
     */
    public static List<String> trimTrace(List<String> extra) {
        List<String> out = new ArrayList<>();
        int folded = 0;
        boolean firstFrameOfBlock = true;
        boolean audit = false;
        for (String line : extra) {
            if (MORE.matcher(line).matches()) {
                continue;
            }
            Matcher f = FRAME.matcher(line);
            if (line.strip().equals("Transformer Audit:")) {
                audit = true;
                continue;
            }
            if (audit && !f.matches() && !line.isEmpty() && Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            audit = false;
            if (f.matches()) {
                String where = f.group(1);
                boolean framework = FRAMEWORK.stream().anyMatch(where::startsWith);
                if (framework && !firstFrameOfBlock) {
                    folded++;
                    continue;
                }
                firstFrameOfBlock = false;
                if (folded > 0) {
                    out.add("\t... " + folded + " framework frames");
                    folded = 0;
                }
                out.add("\tat " + line.strip().substring(3));
            } else {
                if (folded > 0) {
                    out.add("\t... " + folded + " framework frames");
                    folded = 0;
                }
                firstFrameOfBlock = true;
                out.add(line.stripTrailing());
            }
        }
        if (folded > 0) {
            out.add("\t... " + folded + " framework frames");
        }
        return out;
    }

    /** Build tool output without the noise: what failed and where. */
    public static String condenseBuild(String output, int maxChars) {
        Map<String, int[]> seen = new LinkedHashMap<>();
        boolean skipping = false;
        for (String raw : output.split("\\R")) {
            String line = stripAnsi(raw).stripTrailing();
            // Gradle 9 repeats the whole compiler output, indented, after this line.
            if (line.startsWith("* Try:") || line.startsWith("* Get more help") || line.startsWith("* Exception is:")
                    || line.startsWith("> Compilation failed; see the compiler output below")) {
                skipping = true;
                continue;
            }
            if (line.startsWith("* What went wrong") || line.startsWith("BUILD ") || line.startsWith("FAILURE:")) {
                skipping = false;
            }
            if (skipping || line.isBlank() || line.startsWith("FAILURE:")) {
                continue;
            }
            boolean noise = false;
            for (Pattern p : BUILD_NOISE) {
                if (p.matcher(line).find()) {
                    noise = true;
                    break;
                }
            }
            if (noise) {
                continue;
            }
            Matcher f = FRAME.matcher(line);
            if (f.matches() && FRAMEWORK.stream().anyMatch(f.group(1)::startsWith)) {
                continue;
            }
            seen.computeIfAbsent(line, k -> new int[1])[0]++;
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, int[]> e : seen.entrySet()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(e.getKey());
            if (e.getValue()[0] > 1 && e.getKey().length() > 20) {
                out.append(" (x").append(e.getValue()[0]).append(')');
            }
        }
        return Budget.truncate(out.toString(), maxChars, "Fix the first errors and build again.");
    }

    static String stripAnsi(String s) {
        return s.indexOf('\u001b') < 0 ? s : s.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]", "");
    }
}
