package io.github.imnotstable.msc.bench.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.bench.check.CheckResult;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code results.jsonl}, one {@link RunRecord} as JSON per line: the one writer and the one reader of the file. A run
 * writes it; {@code report} reads it back, also after a user joined the files of two runs (a run stopped on a usage
 * limit and its rest), with whatever tool they joined them.
 *
 * <p>The reader decodes strictly and refuses every line it cannot take as a run with an {@link IOException} naming the
 * file, the line and what is wrong, never a record with holes that the report would trip over: text that is not UTF-8,
 * a line that is not one JSON object, an unknown key, a missing {@code task}, {@code category}, {@code mode},
 * {@code repeat} or {@code passed}, a value of the wrong type, a list entry that is null, a number out of range. Keys a
 * record from an older bench may lack take their empty value. A byte-order mark is taken as what it says: UTF-8, or
 * UTF-16 as Windows PowerShell 5.1's {@code >} and {@code Out-File} write; one at the start of a line (files joined
 * byte for byte) is skipped.
 */
public final class ResultsFile {

    /** The file's name inside a run folder. */
    public static final String NAME = "results.jsonl";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final TypeAdapter<JsonElement> TREE = GSON.getAdapter(JsonElement.class);
    private static final char BOM = '\uFEFF';
    private static final Pattern JSON_PROBLEM = Pattern.compile("^(.*?) at line \\d+ column (\\d+)");

    private ResultsFile() {
    }

    /** Adds {@code record} as the file's last line, creating the file. */
    public static void append(Path file, RunRecord record) throws IOException {
        Files.writeString(file, GSON.toJson(record) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Every run in the file, in order; refuses a file with none. */
    public static List<RunRecord> read(Path file) throws IOException {
        String text = decode(file, Files.readAllBytes(file));
        List<RunRecord> records = new ArrayList<>();
        int lineNo = 0;
        for (String line : text.lines().toList()) {
            lineNo++;
            String content = !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
            if (content.isBlank()) {
                continue;
            }
            try {
                records.add(record(content));
            } catch (IllegalArgumentException e) {
                throw new IOException(file + " line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        if (records.isEmpty()) {
            throw new IOException(file + " holds no runs: each line of a " + NAME + " is one run as JSON");
        }
        return records;
    }

    /** The file's text, from UTF-8 or the encoding its byte-order mark names; refuses bytes that do not decode. */
    static String decode(Path file, byte[] bytes) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            skip = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, skip, bytes.length - skip);
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        CoderResult result = decoder.decode(in, out, true);
        if (!result.isError()) {
            result = decoder.flush(out);
        }
        if (result.isError()) {
            out.flip();
            long line = 1 + out.chars().filter(c -> c == '\n').count();
            throw new IOException(file + " line " + line + ": the byte at offset " + in.position() + " is not " + charset.name()
                    + " text; save the file as UTF-8, for example joined with `cat a/" + NAME + " b/" + NAME + " > "
                    + NAME + "` or in PowerShell `Get-Content -Encoding UTF8 a\\" + NAME + ", b\\" + NAME
                    + " | Set-Content -Encoding UTF8 " + NAME + "`");
        }
        out.flip();
        return out.toString();
    }

    /** The run on one line, refused with what is wrong. */
    static RunRecord record(String line) {
        if (line.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("holds a NUL character; is the file UTF-16 without a byte-order mark? "
                    + "Save it as UTF-8");
        }
        JsonElement tree;
        try {
            JsonReader reader = new JsonReader(new StringReader(line));
            reader.setStrictness(Strictness.STRICT);
            tree = TREE.read(reader);
            // In strict mode a second value or other text after the first is a syntax error.
            reader.peek();
        } catch (IOException | JsonParseException e) {
            throw new IllegalArgumentException("is not valid JSON: " + jsonProblem(e.getMessage()), e);
        }
        if (tree == null || !tree.isJsonObject()) {
            throw new IllegalArgumentException("is " + (tree == null || tree.isJsonNull() ? "null" : tree.isJsonArray()
                    ? "a list" : "a single value") + ", not a JSON object; each line is one run");
        }
        UserJson json = UserJson.of(tree.getAsJsonObject(), "");
        json.onlyKeys(keys(RunRecord.class));
        json.require("task", "category", "mode", "repeat", "passed");
        return new RunRecord(
                nonBlank(json, "task"),
                json.exactString("category", ""),
                nonBlank(json, "mode"),
                json.wholeNumber("repeat", JsonNumbers.POSITIVE, 1),
                json.exactString("startedAt", ""),
                json.exactBool("passed", false),
                json.wholeLong("wallMs", JsonNumbers.AMOUNT, 0),
                json.exactBool("timedOut", false),
                json.wholeNumber("exitCode", JsonNumbers.EXIT_CODE, -1),
                json.exactString("error", null),
                agent(json.exactObject("agent")),
                tools(json.exactObject("tools")),
                json.exactObjects("checks").stream().map(ResultsFile::check).toList());
    }

    private static AgentResult agent(UserJson json) {
        if (json == null) {
            return AgentResult.UNKNOWN;
        }
        json.onlyKeys(keys(AgentResult.class));
        return new AgentResult(
                json.exactBool("parsed", false),
                json.exactBool("isError", false),
                json.wholeNumber("turns", JsonNumbers.COUNT, 0),
                json.wholeLong("apiDurationMs", JsonNumbers.AMOUNT, 0),
                json.wholeLong("inputTokens", JsonNumbers.AMOUNT, 0),
                json.wholeLong("cacheCreationTokens", JsonNumbers.AMOUNT, 0),
                json.wholeLong("cacheReadTokens", JsonNumbers.AMOUNT, 0),
                json.wholeLong("outputTokens", JsonNumbers.AMOUNT, 0),
                json.number("costUsd", JsonNumbers.COST, 0),
                json.exactString("model", ""),
                json.exactStrings("mcpConnected"));
    }

    private static ToolStats tools(UserJson json) {
        if (json == null) {
            return ToolStats.NONE;
        }
        json.onlyKeys(keys(ToolStats.class));
        Map<String, ToolStats.PerTool> perTool = new LinkedHashMap<>();
        json.exactObjectEntries("perTool").forEach((tool, t) -> {
            t.onlyKeys(keys(ToolStats.PerTool.class));
            t.require("calls");
            perTool.put(tool, new ToolStats.PerTool(
                    t.wholeNumber("calls", JsonNumbers.CALLS, 1),
                    t.wholeNumber("errors", JsonNumbers.COUNT, 0),
                    t.wholeLong("tokens", JsonNumbers.AMOUNT, 0),
                    t.wholeLong("durationMs", JsonNumbers.AMOUNT, 0)));
        });
        return new ToolStats(
                json.wholeNumber("calls", JsonNumbers.COUNT, 0),
                json.wholeNumber("errors", JsonNumbers.COUNT, 0),
                json.wholeLong("resultTokens", JsonNumbers.AMOUNT, 0),
                json.wholeLong("imageTokens", JsonNumbers.AMOUNT, 0),
                json.wholeLong("resultChars", JsonNumbers.AMOUNT, 0),
                json.wholeLong("durationMs", JsonNumbers.AMOUNT, 0),
                perTool);
    }

    private static CheckResult check(UserJson json) {
        json.onlyKeys(keys(CheckResult.class));
        json.require("name", "passed");
        return new CheckResult(json.exactString("name", ""), json.exactBool("passed", false),
                json.exactString("detail", ""));
    }

    private static String nonBlank(UserJson json, String field) {
        String value = json.exactString(field, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(json.key(field) + ": is blank");
        }
        return value;
    }

    /** The JSON keys of a record type: the names Gson writes its components under. */
    private static List<String> keys(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** Gson's message without its advice to read leniently or its path: what is wrong and the column. */
    private static String jsonProblem(String message) {
        if (message == null) {
            return "it ends too early";
        }
        String first = message.lines().findFirst().orElse("");
        Matcher at = JSON_PROBLEM.matcher(first);
        if (!at.find()) {
            return first;
        }
        return (at.group(1).startsWith("Use JsonReader") ? "unexpected text" : at.group(1)) + " at column "
                + at.group(2);
    }
}
