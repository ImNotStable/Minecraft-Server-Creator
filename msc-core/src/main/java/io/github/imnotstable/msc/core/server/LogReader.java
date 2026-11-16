package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a server's output: bytes into lines, lines into entries. {@link LogCondenser} counts and condenses the
 * entries; this class only decides where lines and entries begin and end.
 *
 * <p><b>Lines</b> ({@link #read}). The output is UTF-8; malformed bytes become U+FFFD. A line ends at {@code \n},
 * {@code \r\n} or a bare {@code \r} (a console redrawing a line in place gives one line per redraw). ANSI escape
 * sequences (CSI such as colours, OSC such as window titles, and two-byte escapes) and other control characters
 * except tab are removed, also when a sequence arrives split across reads. A line longer than
 * {@link #MAX_LINE_CHARS} keeps its first {@code MAX_LINE_CHARS} characters and ends with
 * {@code " [N more chars cut]"}; the rest is skipped, never held. At end of input (the process exited, possibly in
 * the middle of a stack trace) the unterminated last line is still delivered. Memory is one line plus a fixed read
 * buffer, whatever the input. The server's stdout and stderr arrive merged in one pipe: a line both streams wrote
 * into at once cannot be told apart and stays one line, but whole lines of one stream between another's are handled
 * by the entry rules below.
 *
 * <p><b>Entries</b> ({@link #entries}). An entry is one thing the server reported, with the lines that belong to it.
 * One starts at
 * <ul>
 * <li>a line with a log prefix: Paper's {@code [12:34:56 INFO]: msg}, vanilla's and the loaders'
 * {@code [12:34:56] [thread/INFO]: msg} (Forge's logger name after the level is dropped), or slf4j-simple's
 * {@code [main] INFO name - msg} (Minestom);</li>
 * <li>a java.util.logging record, two lines: a header with the date, time and source, then {@code WARNING: msg}
 * (Minestom's {@code System.Logger}, the msc agent's own log there). Both lines are one entry, with the level and
 * message of the second (#730);</li>
 * <li>a bare ready line ({@code Done (0.5s)!}) or the JVM's {@code Exception in thread "x" ...};</li>
 * <li>a bare throwable header ({@code java.lang.Exception: msg}, any class ending in Exception, Error or Throwable)
 * when it cannot be the trace of the entry before: that entry is not a warning or an error, or it already has
 * frames. A warning or error followed by a header is {@code logger.warn(msg, e)} and stays one entry;</li>
 * <li>any other line when no entry has started yet.</li>
 * </ul>
 * Other non-blank lines continue the current entry, except Quilt's start-up timings, which are dropped.
 *
 * <p><b>Printed traces with a prefix on every line.</b> Most platforms route {@code System.err} and
 * {@code System.out} through their logger, so {@code e.printStackTrace()} arrives as one prefixed line per header,
 * frame and {@code Caused by:} (#336): Paper and Purpur at WARN, as {@code [12:00:05 WARN]: [MyPlugin] [STDERR] at ...}
 * for a plugin's output or untagged otherwise; vanilla, Fabric and Quilt at INFO, as
 * {@code [12:00:05] [Server thread/INFO]: [STDERR]: at ...}; Forge and NeoForge under the logger {@code STDERR}, with
 * the caller in front, as {@code [STDERR/]: [java.lang.Throwable:printStackTrace:662]: at ...}. The stream tag and
 * the caller are taken off; a prefixed line whose message is then a throwable header starts a <i>printed</i> entry,
 * which counts as an error whatever level the platform logs its streams at (with a stream tag at once, without one
 * once a frame follows). A prefixed line whose message is a trace line (a frame, {@code Caused by:},
 * {@code Suppressed:} or {@code ... N more}) continues the last prefixed entry with the same level and stream tag,
 * also when other prefixed lines came between (two plugins printing at once, a log line from another thread), so the
 * whole trace is one entry whose framework frames {@link LogCondenser} folds. One
  * exception keeps interleaved streams apart: a trace line (a frame, {@code Caused by:}, {@code Suppressed:} or
 * {@code ... N more}) that arrives right after a fresh entry that is neither a warning nor an error and has no trace
 * of its own belongs to the last entry with a trace, not to that entry (stdout printed a log line while stderr was
 * in the middle of a trace). An entry keeps at most {@link #MAX_ENTRY_LINES} continuation lines and counts the rest
 * in {@link Entry#cut()}. There is at most one entry per line given, and the server's {@link LogBuffer} bounds the
 * lines.
 */
public final class LogReader {
    /** Longest line kept, in chars: longer ones are cut. */
    public static final int MAX_LINE_CHARS = 4096;
    /** Continuation lines kept per entry: a longer trace is cut and the cut lines counted. */
    public static final int MAX_ENTRY_LINES = 1000;

    /** The ready line every platform prints: vanilla and the loaders after their prefix, Minestom's template bare. */
    static final Pattern READY = Pattern.compile(".*\\bDone \\(\\d.*\\)!.*");
    /**
     * A stack frame; the class loader and module prefixes ({@code app//}, {@code java.base/}, Forge's
     * {@code TRANSFORMER/mymod@1.0/}) are skipped.
     */
    static final Pattern FRAME = Pattern.compile("^\\s+at (?:[^\\s/()]+/+){0,2}([\\w.$<>]+)\\(.*\\).*$");
    static final Pattern MORE = Pattern.compile("^\\s+\\.\\.\\. \\d+ (more|common frames omitted).*$");
    private static final Pattern CAUSE = Pattern.compile("^\\s*(Caused by|Suppressed): .*");

    /** {@code [12:34:56 INFO]: msg} (Paper) or {@code [12:34:56] [Server thread/INFO]: msg} (vanilla). */
    private static final Pattern LINE = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)(?:\\] \\[[^\\]]*/| )(\\w+)\\]:? ?(.*)$");
    /** slf4j-simple at its defaults, which the Minestom templates log with: no timestamp (#272). */
    private static final Pattern SIMPLE = Pattern.compile("^\\[[^\\]]+\\] (TRACE|DEBUG|INFO|WARN|ERROR) \\S+ - (.*)$");
    /**
     * The first line of a java.util.logging record ({@link #julRecord}): not bracketed, with a clock time somewhere.
     * The date and the AM/PM marker are in the server's locale, so only the time is matched.
     */
    private static final Pattern JUL_HEADER = Pattern.compile("^[^\\[\\s].*\\b\\d{1,2}:\\d\\d:\\d\\d\\b.*$");
    /** The second line of a java.util.logging record: {@code WARNING: msg}, the level as {@link #JUL_LEVELS} knows it. */
    private static final Pattern JUL_LEVEL = Pattern.compile("^([^\\s:]+): (.*)$");
    /** java.util.logging's level names, in English and in this machine's language as a server here writes them. */
    private static final Map<String, String> JUL_LEVELS = julLevels();
    /** The JVM's report of an uncaught exception: printed to stderr without a prefix, never part of the line before. */
    private static final Pattern UNCAUGHT = Pattern.compile("^Exception in thread \".*");
    /** Forge and NeoForge put the logger after the level: {@code [ne.mi.fm.Foo/CORE]: msg}. */
    private static final Pattern LOGGER = Pattern.compile("^\\[([\\w.$]+)/[^\\]]*\\]: (.*)$");
    /** OSHI, the system-report library, under its own name or Forge's shortened one: kept to recognise its noise. */
    private static final Pattern OSHI_LOGGER = Pattern.compile("^(oshi|os)\\..*");
    /** Quilt Loader's start-up timings, printed to stdout without a timestamp. */
    private static final Pattern QUILT_TIMING =
            Pattern.compile("^(mod adding|tmp jar copy|transform-cache|zip sub copy) took \\d+ms$");
    /**
     * A logged stream line: Paper's {@code [Plugin] [STDERR] msg} (the plugin name is optional) and vanilla's
     * {@code [STDERR]: msg}.
     */
    private static final Pattern STREAM = Pattern.compile("^(?:\\[([^\\]]+)\\] )?\\[(STDERR|STDOUT)\\]:? (.*)$");
    /** The caller a debug-level stream logger (Forge, NeoForge) puts first: {@code [java.lang.Throwable:println:9]: }. */
    private static final Pattern CALLER = Pattern.compile("^\\[[\\w.$]+:[\\w$<>]+:\\d+\\]: (.*)$");
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;?]*[A-Za-z]");

    private LogReader() {
    }

    /** One reported thing: its time and level (empty when the line had none), its first line and the lines after. */
    public static final class Entry {
        private final String time;
        private final String level;
        private final String text;
        private final List<String> extra = new ArrayList<>();
        private int cut;
        /** True when the entry's text is a throwable header after a log prefix. */
        private final boolean header;
        /** True when the entry is a throwable printed through a logged stream, so its level says nothing. */
        private boolean printed;
        private boolean frames;
        private boolean trace;

        Entry(String time, String level, String text, boolean trace) {
            this(time, level, text, trace, false);
        }

        private Entry(String time, String level, String text, boolean trace, boolean header) {
            this.time = time;
            this.level = level;
            this.text = text;
            this.trace = trace;
            this.header = header;
            this.printed = header && trace;
        }

        public String time() {
            return time;
        }

        public String level() {
            return level;
        }

        public String text() {
            return text;
        }

        /** Continuation lines, at most {@link #MAX_ENTRY_LINES}. */
        public List<String> extra() {
            return extra;
        }

        /** Continuation lines dropped over {@link #MAX_ENTRY_LINES}. */
        public int cut() {
            return cut;
        }

        /** True when the entry is or holds a printed throwable: a bare header, a header line after it, or frames. */
        public boolean trace() {
            return trace;
        }

        public boolean warnOrError() {
            return level.startsWith("WARN") || error();
        }

        /** An error-level entry, or a stack trace printed through a logged stream (#336). */
        public boolean error() {
            return level.equals("ERROR") || level.equals("FATAL") || level.equals("SEVERE") || (printed && trace);
        }

        void add(String line) {
            if (FRAME.matcher(line).matches()) {
                frames = true;
                trace = true;
            } else if (throwable(line) || CAUSE.matcher(line).matches()) {
                trace = true;
            }
            if (extra.size() < MAX_ENTRY_LINES) {
                extra.add(line);
            } else {
                cut++;
            }
        }
    }

    /**
     * Reads {@code in} to its end, adding each line to {@code log} and calling {@code onReady} after each ready
     * line; see the class comment for what a line is.
     */
    public static void pump(InputStream in, LogBuffer log, Runnable onReady) throws IOException {
        read(in, line -> {
            log.add(line);
            if (READY.matcher(line).matches()) {
                onReady.run();
            }
        });
    }

    /** Reads {@code in} to its end and passes each line to {@code onLine}; see the class comment. */
    public static void read(InputStream in, Consumer<String> onLine) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            new Lines(onLine).feed(reader);
        }
    }

    /** The line splitter: a small state machine over chars, so escapes and CRLF split across reads are handled. */
    private static final class Lines {
        private static final int TEXT = 0;
        private static final int ESC = 1;
        private static final int CSI = 2;
        private static final int OSC = 3;
        private static final int OSC_ESC = 4;

        private final Consumer<String> onLine;
        private final StringBuilder line = new StringBuilder();
        private int cut;
        private int state = TEXT;
        private boolean afterCr;

        Lines(Consumer<String> onLine) {
            this.onLine = onLine;
        }

        void feed(Reader reader) throws IOException {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    accept(buf[i]);
                }
            }
            if (!line.isEmpty() || cut > 0) {
                emit();
            }
        }

        private void accept(char c) {
            if (c == '\n' || c == '\r') {
                state = TEXT;
                if (c == '\n' && afterCr) {
                    afterCr = false;
                    return;
                }
                afterCr = c == '\r';
                emit();
                return;
            }
            afterCr = false;
            switch (state) {
                case ESC -> state = c == '[' ? CSI : c == ']' ? OSC : TEXT;
                case CSI -> state = c >= 0x40 && c <= 0x7e ? TEXT : CSI;
                case OSC -> state = c == 0x07 ? TEXT : c == 0x1b ? OSC_ESC : OSC;
                case OSC_ESC -> state = c == '\\' ? TEXT : OSC;
                default -> {
                    if (c == 0x1b) {
                        state = ESC;
                    } else if ((c >= 0x20 && c != 0x7f) || c == '\t') {
                        if (line.length() < MAX_LINE_CHARS) {
                            line.append(c);
                        } else {
                            cut++;
                        }
                    }
                }
            }
        }

        private void emit() {
            if (cut > 0 && Character.isHighSurrogate(line.charAt(line.length() - 1))) {
                line.setLength(line.length() - 1);
                cut++;
            }
            onLine.accept(cut > 0 ? line + " [" + cut + " more chars cut]" : line.toString());
            line.setLength(0);
            cut = 0;
        }
    }

    /** Groups log lines into entries; see the class comment. */
    public static List<Entry> entries(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        Entry current = null;
        Entry lastTrace = null;
        Map<String, Entry> lastByStream = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripAnsi(lines.get(i));
            Prefixed prefixed = prefixed(line);
            Entry printing = prefixed == null || !traceLine(prefixed.payload()) ? null : lastByStream.get(prefixed.key());
            Entry jul = prefixed == null && i + 1 < lines.size() ? julRecord(line, stripAnsi(lines.get(i + 1))) : null;
            if (jul != null) {
                current = jul;
                entries.add(current);
                i++;
            } else if (printing != null) {
                printing.printed |= printing.header;
                printing.add(prefixed.payload());
            } else if (prefixed != null) {
                current = prefixed.entry();
                entries.add(current);
                lastByStream.put(prefixed.key(), current);
            } else if (start(line, current) instanceof Entry started) {
                current = started;
                entries.add(current);
            } else if (!line.isBlank() && !QUILT_TIMING.matcher(line).matches()) {
                boolean interleaved = lastTrace != null && !current.trace && !current.warnOrError()
                        && current.extra.isEmpty() && traceLine(line);
                (interleaved ? lastTrace : current).add(line);
            }
            if (current != null && current.trace) {
                lastTrace = current;
            }
        }
        return entries;
    }

    /**
     * A line with a time and level prefix: its message, and the message without a stream tag and caller (the
     * payload) when the line is a logged {@code System.err} or {@code System.out} line.
     *
     * @param stream the stream tag, such as {@code MyPlugin/STDERR}; {@code ""} when there is none
     * @param name the plugin that printed, or null
     */
    private record Prefixed(String time, String level, String message, String stream, String name, String payload) {
        /** The level and stream: a trace line continues the last entry with the same key. */
        String key() {
            return level + " " + stream;
        }

        Entry entry() {
            if (throwable(payload)) {
                String text = name == null ? payload : "[" + name + "] " + payload;
                return new Entry(time, level, text, !stream.isEmpty(), true);
            }
            return new Entry(time, level, message, false, false);
        }
    }

    /** The line as a {@link Prefixed} line, or null when it has no time and level prefix. */
    private static Prefixed prefixed(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String message = m.group(3);
        String level = m.group(2).toUpperCase(java.util.Locale.ROOT);
        String stream = "";
        String name = null;
        String payload = message;
        Matcher logger = LOGGER.matcher(message);
        Matcher tagged = STREAM.matcher(message);
        if (logger.matches()) {
            message = OSHI_LOGGER.matcher(logger.group(1)).matches()
                    ? "[" + logger.group(1) + "] " + logger.group(2) : logger.group(2);
            payload = logger.group(2);
            if (logger.group(1).equals("STDERR") || logger.group(1).equals("STDOUT")) {
                stream = logger.group(1);
            }
        } else if (tagged.matches()) {
            name = tagged.group(1);
            stream = name == null ? tagged.group(2) : name + "/" + tagged.group(2);
            payload = tagged.group(3);
        }
        Matcher caller = CALLER.matcher(payload);
        if (!stream.isEmpty() && caller.matches()) {
            payload = caller.group(1);
        }
        return new Prefixed(m.group(1), level, message, stream, name, payload);
    }

    /**
     * The entry a java.util.logging record starts in {@link java.util.logging.SimpleFormatter}'s default two-line
     * format, which {@code System.Logger} also writes on Minestom (#730), or null when {@code header} and {@code next}
     * are not one: a header with the date, time and source ({@code Nov 15, 2026 6:45:31 PM com.example.Foo run}),
     * then {@code LEVEL: message}. Its level and message come from the second line, its header stands as its time.
     */
    private static Entry julRecord(String header, String next) {
        Matcher level = JUL_LEVEL.matcher(next);
        if (!level.matches() || !JUL_HEADER.matcher(header).matches()) {
            return null;
        }
        String name = JUL_LEVELS.get(level.group(1));
        return name == null ? null : new Entry(header, name, level.group(2), false);
    }

    private static Map<String, String> julLevels() {
        Map<String, String> names = new HashMap<>();
        for (java.util.logging.Level l : List.of(java.util.logging.Level.SEVERE, java.util.logging.Level.WARNING,
                java.util.logging.Level.INFO, java.util.logging.Level.CONFIG, java.util.logging.Level.FINE,
                java.util.logging.Level.FINER, java.util.logging.Level.FINEST)) {
            names.put(l.getName(), l.getName());
            names.putIfAbsent(l.getLocalizedName(), l.getName());
        }
        return Map.copyOf(names);
    }

    /** The entry an unprefixed {@code line} starts, or null when it continues {@code current} (or is blank). */
    private static Entry start(String line, Entry current) {
        Matcher simple = SIMPLE.matcher(line);
        if (simple.matches()) {
            return new Entry("", simple.group(1), simple.group(2), false);
        }
        if (line.isBlank() || QUILT_TIMING.matcher(line).matches()) {
            return null;
        }
        boolean throwable = throwable(line);
        boolean uncaught = UNCAUGHT.matcher(line).matches();
        if (current == null || READY.matcher(line).matches() || uncaught
                || (throwable && (!current.warnOrError() || current.frames))) {
            return new Entry("", "", line, throwable || uncaught);
        }
        return null;
    }

    private static boolean traceLine(String line) {
        return FRAME.matcher(line).matches() || MORE.matcher(line).matches() || CAUSE.matcher(line).matches();
    }

    /**
     * The first line of a printed throwable, unindented and without a log prefix: {@code java.lang.Exception: msg},
     * {@code java.lang.IllegalStateException}. Minestom's default exception handler prints exceptions this way
     * (#294); the last name segment may be the suffix itself (#322). A hand-written scan, not a regex: a repeated
     * regex group recurses once per name segment and overflowed the stack on a line of a few thousand segments.
     *
     * <p>That is: two or more non-empty segments of {@code [A-Za-z0-9_$]} joined by dots, the last ending in
     * {@code Exception}, {@code Error} or {@code Throwable}, then nothing or {@code ": "} and the rest of the line.
     */
    static boolean throwable(String line) {
        int end = line.length();
        int i = 0;
        int segmentStart = 0;
        int dots = 0;
        while (i < end && (nameChar(line.charAt(i)) || line.charAt(i) == '.')) {
            if (line.charAt(i) == '.') {
                if (i == segmentStart) {
                    return false;
                }
                dots++;
                segmentStart = i + 1;
            }
            i++;
        }
        if (dots == 0 || i == segmentStart) {
            return false;
        }
        String name = line.substring(segmentStart, i);
        if (!name.endsWith("Exception") && !name.endsWith("Error") && !name.endsWith("Throwable")) {
            return false;
        }
        return i == end || line.startsWith(": ", i) && noLineBreak(line, i + 2, end);
    }

    private static boolean nameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '$';
    }

    /** True when {@code line} has no line break from {@code from} to {@code end}, as a regex {@code .*} needs. */
    private static boolean noLineBreak(String line, int from, int end) {
        for (int i = from; i < end; i++) {
            char c = line.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return false;
            }
        }
        return true;
    }

    /** The line without ANSI colour codes. */
    public static String stripAnsi(String s) {
        return s.indexOf('\u001b') < 0 ? s : ANSI.matcher(s).replaceAll("");
    }
}
