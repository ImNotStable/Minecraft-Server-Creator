package io.github.imnotstable.msc.core.metrics;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.tool.Budget;
import java.io.IOException;
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

    private final Path file;
    private final Map<String, Totals> totals = new TreeMap<>();

    /** @param file where to append, or null to keep totals only */
    public ToolLog(Path file) {
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
            line.addProperty("error", error.length() > 200 ? error.substring(0, 200) : error);
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Logging must never fail a tool call.
        }
    }

    public synchronized Map<String, Totals> totals() {
        return new TreeMap<>(totals);
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
