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
    private static final Pattern FRAME = Pattern.compile("^\\s+at (?:[\\w.]+//?)?([\\w.$<>]+)\\(.*\\).*$");
    private static final Pattern MORE = Pattern.compile("^\\s+\\.\\.\\. \\d+ (more|common frames omitted).*$");

    /** Frames from these packages are noise unless nothing else is there. */
    private static final List<String> FRAMEWORK = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.minecraft.", "com.mojang.", "org.bukkit.", "org.spigotmc.", "io.papermc.", "com.destroystokyo.",
            "io.netty.", "org.apache.", "com.google.", "it.unimi.", "org.slf4j.", "co.aikar.", "ca.spottedleaf.",
            "org.gradle.", "org.junit.", "kotlin.", "worker.org.gradle.");

    private static final List<Pattern> BUILD_NOISE = List.of(
            Pattern.compile("^> Task (?!.*FAILED).*"),
            Pattern.compile("^(Picked up |Download|Starting a Gradle Daemon|Daemon will be stopped|To honour the JVM)"),
            Pattern.compile("^> (Run with|Get more help).*"),
            Pattern.compile("^(Deprecated Gradle features|You can use '--warning-mode|For more on this, please refer|See https://docs.gradle.org).*"),
            Pattern.compile("^\\d+ actionable tasks?:.*"),
            Pattern.compile("^(Calculating task graph|Configuration cache entry|Reusing configuration cache).*"),
            Pattern.compile("^Note: (Some input files|Recompile with).*"));

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

    /** @param text the condensed log, starting with a one-line count */
    public record Condensed(String text, int errors, int warnings) {
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
                current = new Entry(m.group(1), m.group(2).toUpperCase(), m.group(3));
                entries.add(current);
            } else if (!line.isBlank()) {
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
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> firstTime = new LinkedHashMap<>();
        for (Entry e : entries) {
            boolean error = e.level.equals("ERROR") || e.level.equals("FATAL") || e.level.equals("SEVERE");
            boolean warn = e.level.startsWith("WARN");
            boolean trace = e.extra.stream().anyMatch(x -> FRAME.matcher(x).matches());
            if (!error && !warn && !trace) {
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
                errors, warnings);
    }

    /** Keep exception headers and project frames; fold runs of framework frames into one line. */
    static List<String> trimTrace(List<String> extra) {
        List<String> out = new ArrayList<>();
        int folded = 0;
        boolean firstFrameOfBlock = true;
        for (String line : extra) {
            if (MORE.matcher(line).matches()) {
                continue;
            }
            Matcher f = FRAME.matcher(line);
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
            if (line.startsWith("* Try:") || line.startsWith("* Get more help") || line.startsWith("* Exception is:")) {
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
