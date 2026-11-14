package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Sums msc-core's tool log: JSON Lines, one object per finished MCP tool call, format version 1 (ADR 0004). The log
 * is read leniently ({@link Text#read}) and unknown fields are ignored. A line that cannot be read as a call (cut by a
 * crash mid-write, cut inside a character, a field of the wrong type such as {@code "tool":null}) is left out, and
 * the reader says so in one warning; nothing in the log can end a run.
 */
public final class ToolLogReader {

    public static final int FORMAT_VERSION = 1;

    private ToolLogReader() {
    }

    /** The calls in {@code log}; {@code warnings} gets at most one line, naming how many lines were left out. */
    public static ToolStats read(Path log, Consumer<String> warnings) {
        if (!Files.isRegularFile(log)) {
            return ToolStats.NONE;
        }
        String text;
        try {
            text = Text.read(log);
        } catch (IOException e) {
            warnings.accept("warning: tool log " + log + " could not be read, its calls are not counted: "
                    + e.getMessage());
            return ToolStats.NONE;
        }
        Sum sum = new Sum();
        int skipped = 0;
        for (String line : text.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            try {
                sum.add(JsonParser.parseString(line).getAsJsonObject());
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            warnings.accept("warning: tool log " + log + ": " + skipped + (skipped == 1 ? " line" : " lines")
                    + " could not be read as a tool call and " + (skipped == 1 ? "is" : "are") + " not counted");
        }
        return sum.stats();
    }

    /** The running totals; {@link #add} reads every field before it counts any, so a bad line counts nothing. */
    private static final class Sum {
        private int calls;
        private int errors;
        private long tokens;
        private long images;
        private long chars;
        private long duration;
        private final Map<String, ToolStats.PerTool> perTool = new TreeMap<>();

        void add(JsonObject o) {
            if (o.has("v") && o.get("v").getAsInt() != FORMAT_VERSION) {
                return;
            }
            boolean failed = o.has("ok") && !o.get("ok").getAsBoolean();
            long resultChars = o.has("resultChars") ? o.get("resultChars").getAsLong() : 0;
            long textTokens = o.has("resultTokens") ? o.get("resultTokens").getAsLong() : (resultChars + 3) / 4;
            long imageTokens = o.has("imageTokens") ? o.get("imageTokens").getAsLong() : 0;
            long ms = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0;
            String tool = o.has("tool") ? string(o.get("tool")) : "?";
            calls++;
            errors += failed ? 1 : 0;
            chars += resultChars;
            tokens += textTokens;
            images += imageTokens;
            duration += ms;
            perTool.merge(tool, new ToolStats.PerTool(1, failed ? 1 : 0, textTokens + imageTokens, ms),
                    ToolStats.PerTool::plus);
        }

        ToolStats stats() {
            return new ToolStats(calls, errors, tokens, images, chars, duration, perTool);
        }

        /** A JSON string's value; anything else (null, a number) is no tool name and throws. */
        private static String string(JsonElement e) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("tool is not a string: " + e);
            }
            return e.getAsString();
        }
    }
}
