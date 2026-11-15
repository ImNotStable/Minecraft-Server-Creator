package io.github.imnotstable.msc.bench;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one way msc-bench reads JSON that a person writes or may edit: each {@code task.json}, {@code bench.json}, a
 * mode's MCP config template and each line of {@code results.jsonl} (#601). Gson's {@code JsonParser} reads leniently
 * and keeps the last value of a key given twice, so {@code "contains":"needle","contains":""} became a check that
 * passes for any file, a second {@code "checks"} list dropped the first, and unquoted keys and {@code //} comments
 * were taken as JSON. Here the text must be exactly one JSON value under RFC 8259 (no comments, no unquoted or
 * single-quoted text, no trailing commas, no escaped lone surrogate, nothing after the value; a
 * byte-order mark at the very start is skipped), and no object may give a key twice, at any depth. A refusal is an
 * {@link IllegalArgumentException} that names the problem and where it is: the line, column and key of a syntax
 * error, or the path to a repeated key such as {@code checks[0].contains}.
 */
public final class StrictJson {

    private static final TypeAdapter<JsonElement> TREE = new Gson().getAdapter(JsonElement.class);
    private static final Pattern PROBLEM = Pattern.compile("^(.*?) at line (\\d+) column (\\d+)(?: path \\$\\.?(\\S+))?");

    private StrictJson() {
    }

    /**
     * The one JSON value of {@code text}, read strictly and with every key given once. {@code at} is put in front of
     * each refusal, as {@link UserJson#of} names keys: with {@code "task.json "} a repeated key is refused as
     * {@code task.json checks[0].contains: given twice; give each key once} and a comment as
     * {@code task.json is not valid JSON: ... at line 3 column 5}.
     */
    public static JsonElement parse(String text, String at) {
        JsonElement tree = syntax(text, at);
        try {
            JsonReader reader = reader(text);
            keysOnce(reader, at, "");
        } catch (IOException e) {
            // syntax() has read the same text without a problem
            throw new IllegalStateException(e);
        }
        return tree;
    }

    /**
     * The one JSON value of {@code text}, read strictly but without the check for repeated keys: for a reader that
     * only asks whether text is complete JSON, such as {@code results.jsonl}'s test for a half-written line.
     */
    public static JsonElement syntax(String text) {
        return syntax(text, "");
    }

    private static JsonElement syntax(String text, String at) {
        try {
            JsonReader reader = reader(text);
            JsonElement tree = TREE.read(reader);
            // In strict mode peek() refuses any text after the value; the check is for a reader that would not.
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("unexpected text after the value");
            }
            return tree;
        } catch (IOException | JsonParseException e) {
            throw new IllegalArgumentException(at + "is not valid JSON: " + problem(e.getMessage(), text), e);
        }
    }

    private static JsonReader reader(String text) {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }

    /** Walks the value at the reader, refusing a key that its object gives twice. */
    private static void keysOnce(JsonReader reader, String at, String path) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> seen = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    String keyPath = path.isEmpty() ? key : path + "." + key;
                    if (!seen.add(key)) {
                        throw new IllegalArgumentException(at + keyPath + ": given twice; give each key once");
                    }
                    keysOnce(reader, at, keyPath);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                for (int i = 0; reader.hasNext(); i++) {
                    keysOnce(reader, at, path + "[" + i + "]");
                }
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }

    /**
     * Gson's message without its advice to read leniently: what is wrong, and where, with the key it is in, such as
     * {@code (in server.rconPassword)} for a lone surrogate. The line is left out for text of one line, such as a line
     * of {@code results.jsonl}.
     */
    private static String problem(String message, String text) {
        if (message == null) {
            return "it ends too early";
        }
        String first = message.lines().findFirst().orElse("");
        Matcher at = PROBLEM.matcher(first);
        if (!at.find()) {
            return first;
        }
        String what = at.group(1).startsWith("Use JsonReader") ? "unexpected text" : at.group(1);
        boolean oneLine = text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
        String key = at.group(4) == null || what.equals("unexpected text") ? "" : " (in " + at.group(4) + ")";
        return what + " at " + (oneLine ? "" : "line " + at.group(2) + " ") + "column " + at.group(3) + key;
    }
}
