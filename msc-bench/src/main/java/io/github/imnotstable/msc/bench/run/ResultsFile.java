package io.github.imnotstable.msc.bench.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.bench.check.CheckResult;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
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
import java.util.function.Consumer;
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
 *
 * <p>One exception: a last line that is half-written, because a run was killed while appending it, is left out with a
 * warning naming it, so the attempts before it still make a report. Such a line has no line break after it and is no
 * complete JSON value (or its bytes end inside a character). The same line anywhere else, or as the file's only line,
 * is refused. So is the one other mark such a line leaves: joined with {@code cat} before the next file, the half line
 * runs straight into that file's first run on one line; the half is left out with a warning and the run is read (see
 * {@link #joinedAt}).
 *
 * <p>The writer cannot fail over a record's content: every string of the line is made writable first
 * ({@link Text#writable}), since a lone surrogate, as a cut through an emoji leaves, is the one thing UTF-8 cannot
 * encode (#462).
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

    /**
     * Adds {@code record} as the file's last line, creating the file. Any lone surrogate in any of its strings is
     * written as U+FFFD, so only the file system can make this fail, never the record.
     */
    public static void append(Path file, RunRecord record) throws IOException {
        append(file, record, Disk.REAL);
    }

    /**
     * {@link #append(Path, RunRecord)} through {@code disk}. The file always ends on a whole line after it: the line
     * starts a new line when the file does not end with one (a run killed while writing its last line), and a write
     * that fails part way (a full disk takes the first bytes and refuses the rest) is cut back to the size the file
     * had, as the "not recorded" stop says (#477). When even that cut fails, the exception says so.
     */
    static void append(Path file, RunRecord record, Disk disk) throws IOException {
        String line = Text.writable(GSON.toJson(record)) + "\n";
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            long size = channel.size();
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(endsOpen(channel, size) ? "\n" + line : line);
            channel.position(size);
            try {
                disk.write(file, channel, bytes);
            } catch (IOException e) {
                try {
                    disk.truncate(file, channel, size);
                } catch (IOException | RuntimeException cut) {
                    IOException both = new IOException(e.getMessage() + "; " + file + " could not be cut back to its "
                            + "last whole line either (" + cut.getMessage() + "), so its last line is half-written", e);
                    both.addSuppressed(cut);
                    throw both;
                }
                throw e;
            }
        }
    }

    /** Whether the file's last byte, of {@code size}, is something other than a line break. */
    private static boolean endsOpen(FileChannel channel, long size) throws IOException {
        if (size == 0) {
            return false;
        }
        ByteBuffer last = ByteBuffer.allocate(1);
        while (last.hasRemaining() && channel.read(last, size - 1) >= 0) {
            // read the one byte
        }
        return last.get(0) != '\n';
    }

    /**
     * Every run in the file, in order; refuses a file with none. A half-written last line (see the class comment) is
     * left out, and {@code warnings} gets one line naming it.
     */
    public static List<RunRecord> read(Path file, Consumer<String> warnings) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        boolean openEnd = bytes.length > 0 && bytes[bytes.length - 1] != '\n';
        String text;
        boolean undecodableTail = false;
        try {
            text = decode(file, bytes);
        } catch (IOException e) {
            // A kill can cut a character's bytes: then the last line is valid UTF-8 that only ends too early, and in
            // UTF-8 no byte of a character is a line break, so the lines before it still decode. Any other bad byte
            // is refused; UTF-16, which only a user's join writes, is not cut this way.
            int lastBreak = lastLineBreak(bytes);
            if (!openEnd || lastBreak < 0 || utf16(bytes) || !endsTooEarly(bytes, lastBreak + 1)) {
                throw e;
            }
            text = decode(file, Arrays.copyOf(bytes, lastBreak + 1));
            undecodableTail = true;
        }
        List<String> lines = text.lines().toList();
        List<RunRecord> records = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String content = !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
            if (content.isBlank()) {
                continue;
            }
            int joined = joinedAt(content);
            if (joined > 0) {
                warnings.accept("warning: " + file + " line " + (i + 1) + " starts with a half-written run (a run was "
                        + "stopped while writing it, and the next file was joined after it); left that part out");
                content = content.substring(content.charAt(joined) == BOM ? joined + 1 : joined);
            }
            if (openEnd && !undecodableTail && i == lines.size() - 1 && !records.isEmpty() && !isJson(content)) {
                warnings.accept(halfWritten(file, i + 1));
                continue;
            }
            try {
                records.add(record(content));
            } catch (IllegalArgumentException e) {
                throw new IOException(file + " line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        if (undecodableTail) {
            if (records.isEmpty()) {
                decode(file, bytes);
            }
            warnings.accept(halfWritten(file, lines.size() + 1));
        }
        if (records.isEmpty()) {
            throw new IOException(file + " holds no runs: each line of a " + NAME + " is one run as JSON");
        }
        return records;
    }

    private static String halfWritten(Path file, int line) {
        return "warning: " + file + " line " + line + " is half-written (the run was stopped while writing it); "
                + "left it out";
    }

    private static int lastLineBreak(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code bytes} from {@code from} are UTF-8 whose only fault is a character cut off at the end. */
    private static boolean endsTooEarly(byte[] bytes, int from) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, from, bytes.length - from);
        return !decoder.decode(in, CharBuffer.allocate(bytes.length - from + 1), false).isError();
    }

    private static boolean utf16(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE
                || bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF);
    }

    /**
     * Where the second part of {@code line} starts when the line is a half-written run with the next file's first run
     * joined straight after it ({@code cat} of a file whose last line a stopped run cut), or -1. The line is that only
     * when it is no JSON value, starts as an object, and ends in a complete JSON object that starts at a {@code {}
     * (after the next file's byte-order mark, if any) with text before it that is no JSON value either. Nothing is
     * guessed: at most one place in a line can start a complete JSON value that runs to the line's end (one starting
     * inside it would have to close the outer value's brace before its own end, and one starting inside a string
     * would meet the string's escaped quotes as bare text), so the split is that place or there is none.
     */
    private static int joinedAt(String line) {
        if (line.isEmpty() || line.charAt(0) != '{' || isJson(line)) {
            return -1;
        }
        for (int i = line.indexOf('{', 1); i > 0; i = line.indexOf('{', i + 1)) {
            int start = line.charAt(i - 1) == BOM ? i - 1 : i;
            String rest = line.substring(i);
            if (isJson(rest) && tree(rest).isJsonObject() && !isJson(line.substring(0, start))) {
                return start;
            }
        }
        return -1;
    }

    /** Whether {@code line} is one complete JSON value, whatever its shape. */
    private static boolean isJson(String line) {
        try {
            tree(line);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
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
        JsonElement tree = tree(line);
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

    /** The line as one strict JSON value, refused with Gson's problem and column. */
    private static JsonElement tree(String line) {
        try {
            JsonReader reader = new JsonReader(new StringReader(line));
            reader.setStrictness(Strictness.STRICT);
            JsonElement tree = TREE.read(reader);
            // In strict mode a second value or other text after the first is a syntax error.
            reader.peek();
            return tree;
        } catch (IOException | JsonParseException e) {
            throw new IllegalArgumentException("is not valid JSON: " + jsonProblem(e.getMessage()), e);
        }
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
