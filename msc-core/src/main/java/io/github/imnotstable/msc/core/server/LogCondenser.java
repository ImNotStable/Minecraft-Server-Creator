package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.tool.Budget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns server logs and build output into the few lines worth a model's attention: warnings and
 * errors, stack traces cut down to the project's own frames, repeats collapsed.
 */
public final class LogCondenser {
    /**
     * Frames from these packages are noise unless nothing else is there: the JDK, the libraries servers and builds
     * ship, Geyser and Floodgate (server_setup installs them, ADR 0046), and every {@link Platform}'s own packages, so a
     * new platform's frames are framework without anyone having to remember this list (#592).
     */
    private static final List<String> FRAMEWORK = Stream.concat(Stream.of("java.", "javax.",
            "jdk.", "sun.", "com.sun.", "io.netty.", "org.apache.", "com.google.", "it.unimi.", "org.slf4j.",
            "org.gradle.", "org.junit.", "org.mockbukkit.", "be.seeseemelk.", "kotlin.", "worker.org.gradle.", "oshi.",
            "org.geysermc."), Arrays.stream(Platform.values()).flatMap(p -> p.packages().stream()))
            .distinct().toList();

    /**
     * Entries every dev server prints and the AI can do nothing about: Paper's banners for running as root or
     * administrator, the offline-mode banners that MSC's own configuration causes (every dev server and the Velocity dev
     * proxy are offline on purpose: vanilla's and Paper's, Paper's variant for a backend behind Velocity, and
     * Velocity's, #656), and the system report's oshi errors and warnings on some Windows machines. Dropped so a clean
     * start reads {@code no errors or warnings}. Minestom logs its offline note at INFO.
     */
    private static final List<Pattern> EXPECTED = List.of(
            Pattern.compile("^\\*+$"),
            Pattern.compile("^YOU ARE (RUNNING THIS SERVER AS AN ADMINISTRATIVE OR ROOT USER|OPENING YOURSELF UP TO).*"),
            Pattern.compile("^FOR MORE INFORMATION, SEE https://madelinemiller\\.dev/.*"),
            Pattern.compile("^\\*+ SERVER IS RUNNING IN OFFLINE/INSECURE MODE!.*"),
            Pattern.compile("^(The server will make no attempt to authenticate usernames|While this makes the game "
                    + "possible to play without internet access|To change this, set \"online-mode\" to \"true\").*"),
            // Paper, Purpur and Folia behind MSC's Velocity proxy (proxies.velocity.enabled), instead of "While ...".
            Pattern.compile("^(Whilst this makes it possible to use Velocity, unless access to your server is properly "
                    + "restricted|Please see https://docs\\.papermc\\.io/velocity/security for further information).*"),
            // Velocity with MSC's velocity.toml, which sets online-mode = false.
            Pattern.compile("^The proxy is running in offline mode! This is a security risk and you will NOT receive "
                    + "any support!$"),
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
            Pattern.compile("^Assets URL 'quilt\\.zfs://[^']*' uses unexpected schema$"),
            // Waterfall's banner on every start (ADR 0088), and Geyser replacing Velocity's channel initializers.
            Pattern.compile("^Waterfall has reached end of life and is no longer maintained\\..*"),
            Pattern.compile("^The (server|backend) channel initializer has been replaced by .*"));
    /**
     * Netty probing the macOS and Linux native transports on another OS (NeoForge and Forge log the failed probe
     * with its trace). Matched anywhere in an entry together with {@link #NETTY_UNSUPPORTED}.
     */
    private static final Pattern NETTY_NATIVE = Pattern.compile("io\\.netty\\.channel\\.(kqueue|epoll|uring)\\.Native");
    private static final Pattern NETTY_UNSUPPORTED = Pattern.compile("Only supported on (OSX/BSD|Linux)");

    /** Gradle 9 names the plugin that registered a failed task: no help in fixing it. */
    private static final Pattern REGISTERED_BY = Pattern.compile(" \\(registered by plugin (class )?'[^']*'\\)");
    private static final List<Pattern> BUILD_NOISE = List.of(
            Pattern.compile("^> Task (?!.*FAILED).*"),
            Pattern.compile("^(Picked up |Download|Starting a Gradle Daemon|Daemon will be stopped|To honour the JVM)"),
            Pattern.compile("^> (Run with|Get more help).*"),
            Pattern.compile("^(Deprecated Gradle features|You can use '--warning-mode|For more on this, please refer|See https://docs.gradle.org).*"),
            Pattern.compile("^\\d+ actionable tasks?:.*"),
            // dev_cycle leads with its own "BUILD FAILED in" line; the problems report is an HTML file for people.
            Pattern.compile("^(BUILD FAILED in |\\[Incubating] Problems report is available).*"),
            Pattern.compile("^(Calculating task graph|Configuration cache entry|Reusing configuration cache).*"),
            Pattern.compile("^Note: (Some input files|Recompile with).*"),
            Pattern.compile("^(BUILD SUCCESSFUL|Welcome to Gradle|Starting a Gradle Daemon|> Configure project).*"),
            // JDK warnings printed by test libraries (Mockito, ByteBuddy) on every run.
            Pattern.compile("^WARNING: (A terminally deprecated method|sun\\.misc\\.Unsafe::|Please consider reporting|"
                    + "A Java agent has been loaded|If a serviceability tool|Dynamic loading of agents).*"));

    private LogCondenser() {
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
        int errors = 0;
        int warnings = 0;
        int hidden = 0;
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> firstTime = new LinkedHashMap<>();
        for (LogReader.Entry e : LogReader.entries(lines)) {
            boolean error = e.error();
            boolean warn = e.level().startsWith("WARN");
            boolean trace = e.trace();
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
            StringBuilder body = new StringBuilder(e.level().isEmpty() ? "" : e.level() + " ").append(e.text());
            for (String x : trimTrace(e.extra())) {
                body.append('\n').append(x);
            }
            if (e.cut() > 0) {
                body.append("\n\t... ").append(e.cut()).append(" more lines cut");
            }
            String key = body.toString();
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            firstTime.putIfAbsent(key, e.time());
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

    /** True when {@code where}, a frame's class and method, is in a framework package (JDK, server, loader). */
    static boolean framework(String where) {
        return FRAMEWORK.stream().anyMatch(where::startsWith);
    }

    /** True when {@code e} is an entry every dev server prints and the AI can do nothing about. */
    static boolean expected(LogReader.Entry e) {
        if (EXPECTED.stream().anyMatch(p -> p.matcher(e.text()).matches())) {
            return true;
        }
        String all = e.text() + "\n" + String.join("\n", e.extra());
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
            if (LogReader.MORE.matcher(line).matches()) {
                continue;
            }
            Matcher f = LogReader.FRAME.matcher(line);
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
            String line = REGISTERED_BY.matcher(LogReader.stripAnsi(raw).stripTrailing()).replaceFirst("");
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
            Matcher f = LogReader.FRAME.matcher(line);
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
}
