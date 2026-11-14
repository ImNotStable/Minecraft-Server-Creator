package io.github.imnotstable.msc.core.metrics;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.protocol.Limits;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Records every tool call: one JSON line per call for msc-bench (format in the bench ADR in msc-docs), plus running
 * totals for the {@code msc_status stats=true}.
 */
public final class ToolLog {
    /** Totals for one tool. */
    public record Totals(int calls, int errors, long durationMs, long resultChars) {
        Totals plus(boolean ok, long ms, int chars) {
            return new Totals(calls + 1, errors + (ok ? 0 : 1), durationMs + ms, resultChars + chars);
        }
    }

    /** Longest error text a line keeps, cut with {@link Limits#cut}. */
    static final int MAX_ERROR_CHARS = 200;

    private Path file;
    private final Map<String, Totals> totals = new TreeMap<>();
    private final PrintStream errors;
    private boolean warned;

    /** @param file where to append, or null to keep totals only */
    public ToolLog(Path file) {
        this(file, System.err);
    }

    /** @param errors where a failed write is reported, once */
    ToolLog(Path file, PrintStream errors) {
        this.file = file;
        this.errors = errors;
    }

    /** Appends to {@code file} from now on (a workspace switch); the session's totals are kept. */
    public synchronized void moveTo(Path file) {
        this.file = file;
    }

    public synchronized void record(String tool, boolean ok, long durationMs, int argsChars, int resultChars,
                                    String error, int imageTokens) {
        totals.merge(tool, new Totals(0, 0, 0, 0).plus(ok, durationMs, resultChars),
                (a, b) -> a.plus(ok, durationMs, resultChars));
        if (file == null) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("v", 1);
        line.addProperty("ts", Instant.now().toString());
        line.addProperty("tool", tool);
        line.addProperty("ok", ok);
        line.addProperty("durationMs", durationMs);
        line.addProperty("argsChars", argsChars);
        line.addProperty("resultChars", resultChars);
        line.addProperty("resultTokens", Budget.tokens(resultChars));
        if (imageTokens > 0) {
            line.addProperty("imageTokens", imageTokens);
        }
        if (error != null) {
            line.addProperty("error", Limits.cut(error, MAX_ERROR_CHARS));
        }
        append(line + "\n");
    }

    /**
     * Writes one line. Its content can never drop it: {@link String#getBytes} replaces what UTF-8 cannot encode (half
     * a surrogate pair) instead of throwing. A failed write must not fail the tool call either, but it is not
     * silent: the first one is reported on stderr (stdout carries the MCP messages), so a bench report that misses
     * lines says why.
     */
    private void append(String text) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) {
                warned = true;
                errors.println("msc-core: could not write the tool log " + file + ": " + e
                        + " (later failures are not reported)");
            }
        }
    }

    /** A short table of this session's tool use. */
    public synchronized String summary() {
        if (totals.isEmpty()) {
            return "no tool calls yet";
        }
        StringBuilder sb = new StringBuilder("tool calls errors ms resultTokens\n");
        int calls = 0;
        int errors = 0;
        long ms = 0;
        long chars = 0;
        for (Map.Entry<String, Totals> e : totals.entrySet()) {
            Totals t = e.getValue();
            sb.append(e.getKey()).append(' ').append(t.calls()).append(' ').append(t.errors()).append(' ')
                    .append(t.durationMs()).append(' ').append(Budget.tokens((int) t.resultChars())).append('\n');
            calls += t.calls();
            errors += t.errors();
            ms += t.durationMs();
            chars += t.resultChars();
        }
        sb.append("total ").append(calls).append(' ').append(errors).append(' ').append(ms).append(' ')
                .append(Budget.tokens((int) chars));
        if (file != null) {
            sb.append("\nlog: ").append(file);
        }
        return sb.toString();
    }
}
