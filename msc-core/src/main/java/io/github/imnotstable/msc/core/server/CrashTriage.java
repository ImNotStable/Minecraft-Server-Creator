package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Why the server crashed or did not start, in a few lines (ADR 0040): what died, the culprit, the key frames and a
 * likely fix. Reads the log lines given (or the server's {@code logs/latest.log} when there are none), the newest
 * crash report in {@code crash-reports/} and the newest JVM crash file ({@code hs_err_pid*.log}) in the server folder.
 * Known causes are recognised by the messages Paper, Purpur, Fabric, Quilt, NeoForge, Forge and Minestom print:
 * the EULA, a port in use, out of memory, a wrong Java version, a missing or wrong dependency, a plugin or mod that
 * failed to load, a mixin that failed, the watchdog. Otherwise the last fatal or uncaught exception is shown.
 */
public final class CrashTriage {
    /** Findings shown at most; the rest are counted. */
    static final int MAX_FINDINGS = 3;
    /** Key frames shown per finding. */
    static final int MAX_FRAMES = 3;
    private static final int MAX_CAUSE_CHARS = 200;
    private static final int MAX_FILE_LINES = 2000;
    private static final int LATEST_LOG_LINES = 20_000;
    private static final long LATEST_LOG_CHARS = 4_000_000;
    private static final String AGENT_PACKAGE = "io.github.imnotstable.msc.";

    private static final Pattern FRAME = Pattern.compile("^\\s*at (?:[^\\s/()]+/+){0,2}([\\w.$<>]+)\\(([^)]*)\\).*$");
    /** {@code Caused by:}, and NeoForge's numbered {@code Caused by 1:} in crash reports. */
    private static final Pattern CAUSED_BY = Pattern.compile("^\\s*Caused by(?: \\d+)?: (.*)$");
    private static final Pattern SUPPRESSED = Pattern.compile("^\\s*Suppressed: .*");
    private static final Pattern UNCAUGHT = Pattern.compile("^Exception in thread \"[^\"]*\" (.*)$");
    private static final Pattern PORT = Pattern.compile("Starting (?:Minecraft )?server on \\S*:(\\d+)");
    private static final Pattern FATAL = Pattern.compile(
            "^(Exception in thread |Encountered an unexpected exception|Exception stopping the server|"
                    + "Failed to start the minecraft server|Crash report saved to|This crash report has been saved to|"
                    + "Preparing crash report).*");

    private CrashTriage() {
    }

    /**
     * One thing that went wrong.
     *
     * @param what    what died, in a few words
     * @param culprit the plugin, mod or class to blame, or empty
     * @param cause   the root exception or the message that says what happened, or empty
     * @param frames  key frames, already rendered as {@code at Class.method(File.java:1)}
     * @param fix     the likely fix, one line
     * @param source  where it was read: {@code log 12:00:05}, a crash report file
     * @param times   how many log entries said it
     */
    public record Finding(String what, String culprit, String cause, List<String> frames, String fix, String source,
                          int times) {
        Finding(String what, String culprit, String cause, List<String> frames, String fix, String source) {
            this(what, culprit, cause, frames, fix, source, 1);
        }

        /**
         * The same finding said again by another log entry at {@code more} (counted, whether or not the log has
         * times), or read from another file (named). One entry gives a finding once ({@link #match}).
         */
        Finding seenAt(String more) {
            if (fromLog(more) && List.of(source.split(", ")).stream().anyMatch(Finding::fromLog)) {
                return new Finding(what, culprit, cause, frames, fix, source, times + 1);
            }
            return source.contains(more) ? this : new Finding(what, culprit, cause, frames, fix, source + ", " + more,
                    times);
        }

        private static boolean fromLog(String source) {
            return source.startsWith(LOG);
        }
    }

    /**
     * What a triage found and what it read.
     *
     * @param lines  the log lines read
     * @param source where they came from: the server's output since the start ({@code log}) or {@code logs/latest.log}
     */
    public record Result(List<Finding> findings, int lines, String source) {
        public boolean isEmpty() {
            return findings.isEmpty();
        }

        /** The findings as a few lines, or a one-line all-clear ending in {@code hint} when it is not null. */
        public String render(String hint) {
            if (findings.isEmpty()) {
                return "no crash or startup failure found in " + lines
                        + (source.equals(LOG) ? " log lines" : " lines of " + source) + " and no new crash report"
                        + (hint == null ? "" : "; " + hint);
            }
            return CrashTriage.render(findings);
        }
    }

    private static final String LOG = "log";
    private static final String LATEST_LOG = "logs/latest.log";

    /**
     * A known cause: {@code what} and {@code fix} from the match (null fix: from the exception), culprit {@code who},
     * when the entry's trace passes {@code when}.
     */
    private record Rule(Pattern pattern, Function<Matcher, String> what, Function<Matcher, String> fix,
                        Predicate<Trace> when) {
        static Rule of(String regex, Function<Matcher, String> what, Function<Matcher, String> fix) {
            return new Rule(Pattern.compile(regex), what, fix, t -> true);
        }

        Rule when(Predicate<Trace> condition) {
            return new Rule(pattern, what, fix, condition);
        }
    }

    private static final String PORT_IN_USE = "server port already in use";
    private static final List<String> JDK = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private static final String DEP_FIX = "install it into plugins/ or mods/, or make the dependency optional";

    /** Checked in this order; an entry gets the matches of the first rule that matches it. */
    private static final List<Rule> RULES = List.of(
            Rule.of("You need to agree to the EULA", m -> "EULA not accepted",
                    m -> "ask the user to accept the Minecraft EULA, then server_start acceptEula=true"),
            // The server's own port (the fix depends on the platform: portInUse). Vanilla and every loader on it
            // say FAILED TO BIND; elsewhere (Minestom) a bind whose first frame past the JDK is the server's. A bind
            // under plugin or mod frames is that code's own socket: the exception finding.
            Rule.of("FAILED TO BIND TO PORT", m -> PORT_IN_USE, m -> ""),
            Rule.of("BindException|Address already in use", m -> PORT_IN_USE, m -> "")
                    .when(t -> t.bindingCode() != null && LogCondenser.framework(t.bindingCode())),
            Rule.of("(?<who>[\\w/$]+) has been compiled by a more recent version of the Java Runtime \\(class file "
                            + "version (?<cls>\\d+)\\.\\d+\\), this version of the Java Runtime only recognizes class "
                            + "file versions up to (?<run>\\d+)",
                    m -> "wrong Java: " + m.group("who").replace('/', '.') + " needs Java "
                            + (Integer.parseInt(m.group("cls")) - 44) + ", the server runs Java "
                            + (Integer.parseInt(m.group("run")) - 44),
                    m -> "set MSC_JAVA to a Java " + (Integer.parseInt(m.group("cls")) - 44)
                            + "+ install, or build the project for Java " + (Integer.parseInt(m.group("run")) - 44)),
            Rule.of("requires at least Java (?<need>\\d+)", m -> "wrong Java: the server needs Java " + m.group("need"),
                    m -> "set MSC_JAVA to a Java " + m.group("need") + "+ install"),
            Rule.of("\\((?<who>[\\w.-]+)\\) [^\\n]*?requires (?<want>[^\\n]*?) of '?(?:java|Java)'?, but only the "
                            + "wrong version is present: (?<have>[^!\\n]+)",
                    m -> "wrong Java: mod " + m.group("who") + " needs Java " + m.group("want") + ", the server runs "
                            + m.group("have"),
                    m -> "set MSC_JAVA to a newer Java install"),
            Rule.of("java\\.lang\\.OutOfMemoryError", m -> "out of memory",
                    m -> "the dev server has -Xmx2G (Minestom 1G): find what keeps growing (caches, loaded chunks, "
                            + "entities, a leak) or load less at once"),
            // Paper and Purpur, both plugin loaders.
            Rule.of("Unknown/missing dependency plugins: \\[(?<dep>[^\\]]+)\\]\\. Please download and install these "
                            + "plugins to run '(?<who>[^']+)'",
                    m -> "missing dependency " + m.group("dep") + " of plugin " + m.group("who"), m -> DEP_FIX),
            // Fabric and Quilt.
            Rule.of("\\((?<who>[\\w.-]+)\\) [^\\n]*?requires [^\\n]*? of (?:mod )?'?(?<dep>[\\w.-]+)'?[^\\n]*?, "
                            + "which is missing",
                    m -> "missing dependency " + m.group("dep") + " of mod " + m.group("who"), m -> DEP_FIX),
            Rule.of("\\((?<who>[\\w.-]+)\\) [^\\n]*?requires (?<want>[^\\n]*?) of '?(?<dep>[\\w.-]+)'?, but only the "
                            + "wrong version is present: (?<have>[^!\\n]+)",
                    m -> "mod " + m.group("who") + " needs " + m.group("dep") + " " + m.group("want") + ", found "
                            + m.group("have"),
                    m -> "use the version it needs, or change the dependency range in the mod's metadata"),
            // NeoForge and Forge.
            Rule.of("Mod ID: '(?<dep>[\\w.-]+)', Requested by: '(?<who>[\\w.-]+)', Expected range: '(?<want>[^']*)', "
                            + "Actual version: '(?<have>[^']*)'",
                    m -> m.group("have").equals("[MISSING]")
                            ? "missing dependency " + m.group("dep") + " of mod " + m.group("who")
                            : "mod " + m.group("who") + " needs " + m.group("dep") + " " + m.group("want") + ", found "
                                    + m.group("have"),
                    m -> m.group("have").equals("[MISSING]") ? DEP_FIX
                            : "use a version in range, or change the range in the mod's mods.toml"),
            Rule.of("Mod (?<who>[\\w.-]+) requires (?<dep>[\\w.-]+) [^\\n]*\\n\\s*Currently, [\\w.-]+ is not installed",
                    m -> "missing dependency " + m.group("dep") + " of mod " + m.group("who"), m -> DEP_FIX),
            Rule.of("Error occurred while enabling (?<who>\\S+)", m -> "plugin " + m.group("who") + " failed to enable",
                    null),
            Rule.of("Could not load '(?:plugins[/\\\\])?(?<who>[^']+)' in (?:folder )?'", m -> "plugin jar "
                    + m.group("who") + " failed to load", null),
            Rule.of("Could not execute entrypoint stage '(?<stage>\\w+)' due to errors, provided by '(?<who>[\\w.-]+)'",
                    m -> "mod " + m.group("who") + " failed in its " + m.group("stage") + " entrypoint", null),
            Rule.of("Mixin apply (?:for mod (?<who>[\\w.-]+) )?failed", m -> "mixin "
                    + (m.group("who") == null ? "" : "of mod " + m.group("who") + " ") + "failed to apply", null),
            Rule.of("Failed to create mod instance\\. ModID: (?<who>[\\w.-]+)", m -> "mod " + m.group("who")
                    + " failed to construct", null),
            Rule.of("\\((?<who>[\\w.-]+)\\) has failed to load correctly", m -> "mod " + m.group("who")
                    + " failed to load", null),
            Rule.of("Mod resolution encountered an incompatible mod set", m -> "incompatible mod set",
                    m -> "read the loader's explanation below it in server_logs mode=errors"),
            Rule.of("The server has stopped responding|A single server tick took",
                    m -> "server hung: the watchdog stopped a tick that ran too long",
                    m -> "something blocks the server thread (an endless loop, blocking I/O, a sleep): see "
                            + "server_logs mode=errors for the thread dump"));

    /**
     * The triage of {@code lines} (when empty: {@code logs/latest.log}) and the crash files in {@code serverDir}
     * written at or after {@code since}, for a server of {@code platform}.
     */
    public static Result triage(List<String> lines, Path serverDir, Instant since, Platform platform) {
        String logSource = LOG;
        if (lines.isEmpty()) {
            lines = latestLog(serverDir);
            logSource = LATEST_LOG;
        }
        String port = null;
        for (String line : lines) {
            Matcher p = PORT.matcher(line);
            if (p.find()) {
                port = p.group(1);
            }
        }
        List<Finding> found = new ArrayList<>();
        List<LogReader.Entry> fatal = new ArrayList<>();
        LogReader.Entry lastError = null;
        for (LogReader.Entry e : LogReader.entries(lines)) {
            if (LogCondenser.expected(e)) {
                continue;
            }
            List<String> all = new ArrayList<>();
            all.add(e.text());
            all.addAll(e.extra());
            String source = e.time().isEmpty() ? logSource : logSource + " " + e.time();
            List<Finding> matched = match(String.join("\n", all), all, source);
            if (!matched.isEmpty()) {
                for (Finding f : matched) {
                    add(found, f.what().equals(PORT_IN_USE) ? portInUse(f, port, platform) : f);
                }
            } else if (FATAL.matcher(e.text()).matches() || e.level().equals("FATAL")) {
                fatal.add(e);
            } else if (e.trace() && (e.error() || !e.level().startsWith("WARN"))) {
                lastError = e;
            }
        }
        Finding report = crashReport(serverDir, since);
        if (report != null) {
            add(found, report);
        }
        Finding jvm = jvmCrash(serverDir, since);
        if (jvm != null) {
            add(found, jvm);
        }
        List<LogReader.Entry> exceptions = new ArrayList<>(fatal);
        if (found.isEmpty() && exceptions.stream().noneMatch(LogReader.Entry::trace) && lastError != null) {
            exceptions.add(lastError);
        }
        for (LogReader.Entry e : exceptions) {
            List<String> all = new ArrayList<>();
            all.add(e.text());
            all.addAll(e.extra());
            Trace t = Trace.of(all);
            if (t.cause().isEmpty() && !e.trace()) {
                continue;
            }
            String source = e.time().isEmpty() ? logSource : logSource + " " + e.time();
            String what = fatal.contains(e) ? "fatal exception" : "last logged exception (not fatal)";
            add(found, new Finding(what + (t.inProject() ? " in plugin or mod code" : " in server code"),
                    t.culprit(), t.cause().isEmpty() ? e.text() : t.cause(), t.frames(), exceptionFix(t), source));
        }
        return new Result(found, lines.size(), logSource);
    }

    /**
     * The server's own port is taken: the port from the log's start line when it has one, and where the port is set,
     * which on Minestom is the server's main class, as it has no server.properties.
     */
    private static Finding portInUse(Finding f, String port, Platform platform) {
        String setIn = platform == Platform.MINESTOM ? "the port in the main class's server.start(...)"
                : "server-port in " + ServerPropertiesFile.NAME;
        return new Finding(port == null ? PORT_IN_USE : "server port " + port + " already in use", f.culprit(),
                f.cause(), f.frames(), "another process holds the port: server_stop stops a leftover server, else "
                        + "change " + setIn, f.source());
    }

    /** Some findings as a few lines each. */
    private static String render(List<Finding> findings) {
        StringBuilder out = new StringBuilder();
        for (Finding f : findings.subList(0, Math.min(MAX_FINDINGS, findings.size()))) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(f.what()).append(f.times() > 1 ? " (x" + f.times() + ")" : "").append(" [")
                    .append(f.source()).append(']');
            if (!f.culprit().isEmpty()) {
                out.append("\n  culprit: ").append(f.culprit());
            }
            if (!f.cause().isEmpty()) {
                out.append("\n  cause: ").append(f.cause());
            }
            for (String frame : f.frames()) {
                out.append("\n  ").append(frame);
            }
            out.append("\n  fix: ").append(f.fix());
        }
        if (findings.size() > MAX_FINDINGS) {
            out.append("\n+").append(findings.size() - MAX_FINDINGS).append(" more: server_logs mode=errors");
        }
        return out.toString();
    }

    /** Adds {@code f}, or only its source to an earlier finding with the same cause, or the same what and culprit. */
    private static void add(List<Finding> found, Finding f) {
        for (int i = 0; i < found.size(); i++) {
            Finding g = found.get(i);
            // One entry can name several dependencies under one exception; a crash report repeats the log's.
            boolean sameCause = !f.cause().isEmpty() && f.cause().equals(g.cause()) && !f.source().equals(g.source());
            boolean sameWhat = f.what().equals(g.what()) && f.culprit().equals(g.culprit());
            if (sameCause || sameWhat) {
                found.set(i, g.seenAt(f.source()));
                return;
            }
        }
        found.add(f);
    }

    private static List<Finding> match(String text, List<String> lines, String source) {
        Trace t = Trace.of(lines);
        for (Rule rule : RULES) {
            if (!rule.when().test(t)) {
                continue;
            }
            Matcher m = rule.pattern().matcher(text);
            List<Finding> out = new ArrayList<>();
            boolean names = rule.pattern().pattern().contains("(?<who>");
            while (m.find()) {
                // The plugin or mod a rule names is in its what; the culprit adds the frame to blame.
                boolean named = names && m.group("who") != null;
                String culprit = t.inProject() || !named ? t.culprit() : "";
                String fix = rule.fix() != null ? rule.fix().apply(m) : exceptionFix(t);
                if (rule.fix() != null && !t.inProject()) {
                    // A known cause with no plugin or mod frames: the what says it all, the trace is the loader's.
                    out.add(new Finding(rule.what().apply(m), "", "", List.of(), fix, source));
                    continue;
                }
                String cause = t.cause().isEmpty() ? clip(lineAt(text, m.start())) : t.cause();
                out.add(new Finding(rule.what().apply(m), culprit, cause, t.frames(), fix, source));
            }
            // An entry says each thing once: a wrapper and its cause quoting one message are one finding.
            out = new ArrayList<>(out.stream().collect(Collectors.toMap(Finding::what, f -> f, (a, b) -> a,
                    LinkedHashMap::new)).values());
            if (!out.isEmpty()) {
                return out;
            }
        }
        return List.of();
    }

    private static String lineAt(String text, int at) {
        int start = text.lastIndexOf('\n', at) + 1;
        int end = text.indexOf('\n', at);
        return text.substring(start, end < 0 ? text.length() : end).strip();
    }

    /** The likely fix for an exception, from its class and where it was thrown. */
    static String exceptionFix(Trace t) {
        String c = t.cause();
        if (c.contains("ClassNotFoundException") || c.contains("NoClassDefFoundError")) {
            return "a class is missing at run time: shade the library into the jar, or install the plugin or mod "
                    + "that provides it";
        }
        if (c.contains("NoSuchMethodError") || c.contains("NoSuchFieldError") || c.contains("AbstractMethodError")
                || c.contains("IncompatibleClassChangeError")) {
            return "compiled against another version than the server runs: match the API or dependency version to "
                    + "the server";
        }
        if (c.contains("InvalidDescriptionException") || c.contains("InvalidPluginException")) {
            return "plugin.yml or paper-plugin.yml is wrong: check name, version, main and api-version";
        }
        if (c.contains("Mixin") || c.contains("InvalidInjectionException")) {
            return "the mixin does not fit this Minecraft version: check its target class, method and descriptor";
        }
        if (c.contains("BindException") || c.contains("Address already in use")) {
            // Not the server's port: that is a rule. A port this code opens itself.
            return "the port this code binds is taken: pick another port, or stop what holds it";
        }
        if (c.contains("StackOverflowError")) {
            return "endless recursion" + (t.at().isEmpty() ? "" : " around " + t.at());
        }
        if (t.inProject()) {
            return "fix the code at " + t.at();
        }
        return "no plugin or mod frames: check what your code passed to the server, and that the server, loader "
                + "and mod versions match";
    }

    /**
     * The root cause of a printed throwable and its key frames.
     *
     * @param culprit   the first frame outside framework packages, or {@code server or loader code}, or empty
     * @param at        that frame as {@code File.java:42 (Class.method)}, or empty
     * @param inProject   whether a frame outside framework packages was found
     * @param bindingCode the first frame past the JDK, from the root cause out (whose code called the failing JDK
     *                    method, such as a socket bind), or null when there are only JDK frames
     */
    record Trace(String cause, List<String> frames, String culprit, String at, boolean inProject,
                 String bindingCode) {
        static Trace of(List<String> lines) {
            List<String> causes = new ArrayList<>();
            List<List<Matcher>> blocks = new ArrayList<>();
            boolean suppressed = false;
            for (String raw : lines) {
                String line = LogReader.stripAnsi(raw);
                Matcher frame = FRAME.matcher(line);
                if (frame.matches()) {
                    if (!suppressed && !blocks.isEmpty()) {
                        blocks.getLast().add(frame);
                    }
                    continue;
                }
                String header = header(line);
                if (header != null) {
                    suppressed = false;
                    causes.add(header);
                    blocks.add(new ArrayList<>());
                } else if (SUPPRESSED.matcher(line).matches()) {
                    suppressed = true;
                }
            }
            if (causes.isEmpty()) {
                return new Trace("", List.of(), "", "", false, null);
            }
            List<Matcher> root = blocks.getLast();
            List<Matcher> own = new ArrayList<>();
            for (int i = blocks.size() - 1; i >= 0 && own.isEmpty(); i--) {
                for (Matcher f : blocks.get(i)) {
                    if (!LogCondenser.framework(f.group(1))) {
                        own.add(f);
                    }
                }
            }
            Set<String> frames = new LinkedHashSet<>();
            for (Matcher f : own.isEmpty() ? root.stream().limit(1).toList() : own) {
                if (frames.size() < MAX_FRAMES) {
                    frames.add("at " + f.group(1) + "(" + f.group(2) + ")");
                }
            }
            String cause = clip(causes.getLast());
            String bindingCode = null;
            for (int i = blocks.size() - 1; i >= 0 && bindingCode == null; i--) {
                bindingCode = blocks.get(i).stream().map(f -> f.group(1)).filter(w -> !jdk(w)).findFirst()
                        .orElse(null);
            }
            if (own.isEmpty()) {
                return new Trace(cause, List.copyOf(frames), root.isEmpty() ? "" : "server or loader code", "",
                        false, bindingCode);
            }
            Matcher first = own.getFirst();
            String where = first.group(1);
            String culprit = where.startsWith(AGENT_PACKAGE) ? where + " (the msc agent, not your code)" : where;
            return new Trace(cause, List.copyOf(frames), culprit, first.group(2) + " (" + where + ")", true,
                    bindingCode);
        }

        private static boolean jdk(String where) {
            return JDK.stream().anyMatch(where::startsWith);
        }

        /** The throwable a line starts, without {@code Caused by:} or the thread name, or null. */
        private static String header(String line) {
            Matcher caused = CAUSED_BY.matcher(line);
            if (caused.matches()) {
                return caused.group(1).strip();
            }
            Matcher uncaught = UNCAUGHT.matcher(line);
            if (uncaught.matches()) {
                return uncaught.group(1).strip();
            }
            // Unindented only: an indented one is a quote, such as NeoForge's crash report's failure message. A
            // printed trace's first line keeps the plugin's name in front: [MyPlugin] java.lang.Exception: msg.
            String bare = line.startsWith("[") && line.indexOf("] ") > 0 ? line.substring(line.indexOf("] ") + 2) : line;
            return LogReader.throwable(bare) ? bare.strip() : null;
        }
    }

    private static String clip(String s) {
        return s.length() <= MAX_CAUSE_CHARS ? s : s.substring(0, MAX_CAUSE_CHARS) + "...";
    }

    /** The newest crash report written at or after {@code since} (any when null), triaged. */
    static Finding crashReport(Path serverDir, Instant since) {
        Path file = newest(serverDir.resolve("crash-reports"), "crash-*.txt", since);
        if (file == null) {
            return null;
        }
        List<String> lines = head(file);
        String description = "";
        String suspected = "";
        List<String> trace = new ArrayList<>();
        Pattern suspectedLine = Pattern.compile("^\\s*Suspected Mods?:\\s*(.*)$");
        Pattern modIssue = Pattern.compile("^-- Mod loading issue for: (\\S+) --$");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("-- System Details --")) {
                break;
            }
            Matcher s = suspectedLine.matcher(line);
            Matcher issue = modIssue.matcher(line);
            if (line.startsWith("Description: ")) {
                description = line.substring("Description: ".length()).strip();
            } else if (s.matches() && suspected.isEmpty()) {
                String value = s.group(1).isBlank() && i + 1 < lines.size() ? lines.get(i + 1) : s.group(1);
                value = value.strip().replaceFirst(",? ?Version: .*$", "");
                if (!value.equalsIgnoreCase("NONE") && !value.isEmpty()) {
                    suspected = "mod " + value;
                }
            } else if (issue.matches() && suspected.isEmpty()) {
                suspected = "mod " + issue.group(1);
            } else {
                trace.add(line);
            }
        }
        Trace t = Trace.of(trace);
        String culprit = t.inProject() || suspected.isEmpty() ? t.culprit() : suspected;
        String source = serverDir.relativize(file).toString().replace('\\', '/');
        return new Finding("crash report: " + (description.isEmpty() ? "server crashed" : clip(description)),
                culprit, t.cause(), t.frames(), exceptionFix(t), source);
    }

    /** The newest JVM crash file written at or after {@code since} (any when null), triaged. */
    static Finding jvmCrash(Path serverDir, Instant since) {
        Path file = newest(serverDir, "hs_err_pid*.log", since);
        if (file == null) {
            return null;
        }
        List<String> lines = head(file);
        String signal = "";
        String frame = "";
        String memory = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("#")) {
                continue;
            }
            String s = line.substring(1).strip();
            if (s.matches("^(SIG[A-Z]+|EXCEPTION_[A-Z_]+|Internal Error) .*")) {
                signal = s.split(" ")[0];
            } else if (s.equals("Problematic frame:") && i + 1 < lines.size()) {
                frame = lines.get(i + 1).substring(1).strip();
            } else if (s.startsWith("Native memory allocation") || s.startsWith("Out of Memory Error")) {
                memory = clip(s);
            }
        }
        String source = file.getFileName() + " (" + modified(file) + ")";
        if (!memory.isEmpty()) {
            return new Finding("JVM out of native memory", "", memory, List.of(),
                    "the machine ran out of memory: close other programs, or lower -Xmx so the JVM fits", source);
        }
        String culprit = frame.isEmpty() ? "" : frame.replaceFirst("^[CjJVv]\\s+", "");
        return new Finding("JVM crash" + (signal.isEmpty() ? "" : " (" + signal + ")"), clip(culprit), "", List.of(),
                frame.startsWith("C") ? "a native library crashed: update Java, or remove the plugin or mod that "
                        + "loads native code" : "a JVM bug: update Java (MSC_JAVA); the file has the details", source);
    }

    private static Path newest(Path dir, String glob, Instant since) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            stream.forEach(files::add);
        } catch (IOException e) {
            return null;
        }
        return files.stream().filter(p -> since == null || !modifiedAt(p).isBefore(since))
                .max(Comparator.comparing(CrashTriage::modifiedAt)).orElse(null);
    }

    private static Instant modifiedAt(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static String modified(Path p) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(modifiedAt(p));
    }

    /** The first lines of a file, read as server output is. */
    private static List<String> head(Path file) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            LogReader.read(in, line -> {
                if (lines.size() < MAX_FILE_LINES) {
                    lines.add(line);
                }
            });
        } catch (IOException e) {
            return lines;
        }
        return lines;
    }

    /** The last lines of {@code logs/latest.log}, or none. */
    static List<String> latestLog(Path serverDir) {
        Path file = serverDir.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        LogBuffer tail = new LogBuffer(LATEST_LOG_LINES, LATEST_LOG_CHARS);
        try (InputStream in = Files.newInputStream(file)) {
            LogReader.read(in, tail::add);
        } catch (IOException e) {
            return List.of();
        }
        return tail.all();
    }
}
