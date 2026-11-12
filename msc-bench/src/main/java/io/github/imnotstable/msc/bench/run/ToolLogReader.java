package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sums msc-core's tool log: JSON Lines, one object per finished MCP tool call, format version 1
 * (ADR 0004). Unknown fields are ignored and broken lines are skipped, so a crash mid-write
 * cannot fail a run.
 */
public final class ToolLogReader {

    public static final int FORMAT_VERSION = 1;

    private ToolLogReader() {
    }

    public static ToolStats read(Path log) throws IOException {
        if (!Files.isRegularFile(log)) {
            return ToolStats.NONE;
        }
        int calls = 0;
        int errors = 0;
        long tokens = 0;
        long images = 0;
        Map<String, ToolStats.PerTool> perTool = new TreeMap<>();
        long chars = 0;
        long duration = 0;
        for (String line : Files.readAllLines(log)) {
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            if (o.has("v") && o.get("v").getAsInt() != FORMAT_VERSION) {
                continue;
            }
            boolean failed = o.has("ok") && !o.get("ok").getAsBoolean();
            long resultChars = o.has("resultChars") ? o.get("resultChars").getAsLong() : 0;
            long textTokens = o.has("resultTokens") ? o.get("resultTokens").getAsLong() : (resultChars + 3) / 4;
            long imageTokens = o.has("imageTokens") ? o.get("imageTokens").getAsLong() : 0;
            long ms = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0;
            calls++;
            errors += failed ? 1 : 0;
            chars += resultChars;
            tokens += textTokens;
            images += imageTokens;
            duration += ms;
            String tool = o.has("tool") ? o.get("tool").getAsString() : "?";
            perTool.merge(tool, new ToolStats.PerTool(1, failed ? 1 : 0, textTokens + imageTokens, ms),
                    ToolStats.PerTool::plus);
        }
        return new ToolStats(calls, errors, tokens, images, chars, duration, perTool);
    }
}
