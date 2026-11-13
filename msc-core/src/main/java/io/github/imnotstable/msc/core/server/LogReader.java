package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * <li>a bare ready line ({@code Done (0.5s)!}) or the JVM's {@code Exception in thread "x" ...};</li>
 * <li>a bare throwable header ({@code java.lang.Exception: msg}, any class ending in Exception, Error or Throwable)
 * when it cannot be the trace of the entry before: that entry is not a warning or an error, or it already has
 * frames. A warning or error followed by a header is {@code logger.warn(msg, e)} and stays one entry;</li>
 * <li>any other line when no entry has started yet.</li>
 * </ul>
 * Other non-blank lines continue the current entry, except Quilt's start-up timings, which are dropped. One
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
    /** The JVM's report of an uncaught exception: printed to stderr without a prefix, never part of the line before. */
    private static final Pattern UNCAUGHT = Pattern.compile("^Exception in thread \".*");
    /**
     * The first line of a printed throwable, unindented and without a log prefix: {@code java.lang.Exception: msg},
     * {@code java.lang.IllegalStateException}. Minestom's default exception handler prints exceptions this way
     * (#294); the last name segment may be the suffix itself (#322).
     */
    private static final Pattern THROWABLE =
            Pattern.compile("^[\\w$]+(?:\\.[\\w$]+)*\\.[\\w$]*(?:Exception|Error|Throwable)(?:: .*)?$");
    /** Forge and NeoForge put the logger after the level: {@code [ne.mi.fm.Foo/CORE]: msg}. */
    private static final Pattern LOGGER = Pattern.compile("^\\[([\\w.$]+)/[^\\]]*\\]: (.*)$");
    /** OSHI, the system-report library, under its own name or Forge's shortened one: kept to recognise its noise. */
    private static final Pattern OSHI_LOGGER = Pattern.compile("^(oshi|os)\\..*");
    /** Quilt Loader's start-up timings, printed to stdout without a timestamp. */
    private static final Pattern QUILT_TIMING =
            Pattern.compile("^(mod adding|tmp jar copy|transform-cache|zip sub copy) took \\d+ms$");
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
        private boolean frames;
        private boolean trace;

        Entry(String time, String level, String text, boolean trace) {
            this.time = time;
            this.level = level;
            this.text = text;
            this.trace = trace;
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

        public boolean error() {
            return level.equals("ERROR") || level.equals("FATAL") || level.equals("SEVERE");
        }

        void add(String line) {
            if (FRAME.matcher(line).matches()) {
                frames = true;
                trace = true;
            } else if (THROWABLE.matcher(line).matches() || CAUSE.matcher(line).matches()) {
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
        for (String raw : lines) {
            String line = stripAnsi(raw);
            Entry started = start(line, current);
            if (started != null) {
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

    /** The entry {@code line} starts, or null when it continues {@code current} (or is blank). */
    private static Entry start(String line, Entry current) {
        Matcher m = LINE.matcher(line);
        if (m.matches()) {
            String message = m.group(3);
            Matcher logger = LOGGER.matcher(message);
            if (logger.matches()) {
                message = OSHI_LOGGER.matcher(logger.group(1)).matches()
                        ? "[" + logger.group(1) + "] " + logger.group(2) : logger.group(2);
            }
            return new Entry(m.group(1), m.group(2).toUpperCase(java.util.Locale.ROOT), message, false);
        }
        Matcher simple = SIMPLE.matcher(line);
        if (simple.matches()) {
            return new Entry("", simple.group(1), simple.group(2), false);
        }
        if (line.isBlank() || QUILT_TIMING.matcher(line).matches()) {
            return null;
        }
        boolean throwable = THROWABLE.matcher(line).matches();
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

    /** The line without ANSI colour codes. */
    public static String stripAnsi(String s) {
        return s.indexOf('\u001b') < 0 ? s : ANSI.matcher(s).replaceAll("");
    }
}
