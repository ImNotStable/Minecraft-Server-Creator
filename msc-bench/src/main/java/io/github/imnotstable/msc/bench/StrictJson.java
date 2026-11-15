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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 *
 * <p>A syntax refusal never shows Gson's own text, which may quote the file (#671): its {@code Malformed Unicode
 * escape} is followed by the next four characters of the string, which may be part of a password or a token. The
 * refusal shows only the kind of problem, from a fixed list, its line and column, and the key path as this class
 * tracks it, every name as {@link Quoted#secretName} shows it: nothing after an {@code =} in a name is ever shown,
 * whatever characters follow it.
 */
public final class StrictJson {

    private static final TypeAdapter<JsonElement> TREE = new Gson().getAdapter(JsonElement.class);
    /**
     * The kinds of syntax problem a refusal names: each is the fixed text that starts one of Gson's messages. The rest
     * of a message, such as the characters after {@code Malformed Unicode escape}, is never shown (#671).
     */
    private static final List<String> KINDS = List.of(
            "Unterminated array",
            "Unterminated object",
            "Unterminated string",
            "Unterminated comment",
            "Unterminated escape sequence",
            "Expected name",
            "Expected ':'",
            "Expected value",
            "Unexpected value",
            "End of input",
            "Malformed Unicode escape",
            "Invalid escape sequence",
            "Invalid escaped character \"'\" in strict mode",
            "Cannot escape a newline character in strict mode",
            "Unescaped control characters (\\u0000-\\u001F) are not allowed in strict mode",
            "Unpaired surrogate characters are not allowed in strict mode");
    /** The start of Gson's message for text only a lenient reader takes, such as a comment or an unquoted name. */
    private static final String LENIENT_ONLY = "Use JsonReader.setStrictness";
    private static final String UNEXPECTED = "unexpected text";
    private static final Pattern NESTING = Pattern.compile("^Nesting limit \\d+ reached");
    /** The start of {@link JsonReader#toString}: where the reader stands, which never holds the file's own text. */
    private static final Pattern POSITION = Pattern.compile("^JsonReader at line (\\d+) column (\\d+) ");

    private StrictJson() {
    }

    /**
     * The one JSON value of {@code text}, read strictly and with every key given once. {@code at} is put in front of
     * each refusal, as {@link UserJson#of} names keys: with {@code "task.json "} a repeated key is refused as
     * {@code task.json checks[0].contains: given twice; give each key once} and a comment as
     * {@code task.json is not valid JSON: ... at line 3 column 5}.
     */
    public static JsonElement parse(String text, String at) {
        return parse(text, at, null);
    }

    /**
     * {@link #parse}, except that every key given twice is added to {@code repeated}, at any depth, instead of
     * refused at the first, and the tree is returned for the rest of the file to be checked too (#663): with it, a
     * reader lists every problem of the file in one refusal. Text that is no JSON is still refused at once, since
     * nothing after the problem can be read.
     */
    public static JsonElement parse(String text, String at, List<String> repeated) {
        JsonElement tree = syntax(text, at);
        try {
            JsonReader reader = reader(text);
            keysOnce(reader, at, "", repeated);
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
                throw new JsonParseException(UNEXPECTED);
            }
            return tree;
        } catch (IOException | JsonParseException e) {
            // Neither Gson's message nor its exception goes on as the cause: both may quote the file (#671).
            throw new IllegalArgumentException(at + "is not valid JSON: " + problem(text));
        }
    }

    private static JsonReader reader(String text) {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }

    /** Walks the value at the reader, refusing a key that its object gives twice. */
    private static void keysOnce(JsonReader reader, String at, String path, List<String> repeated)
            throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> seen = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    String keyPath = path + step(path.isEmpty(), key);
                    if (!seen.add(key)) {
                        String problem = at + keyPath + ": given twice; give each key once";
                        if (repeated == null) {
                            throw new IllegalArgumentException(problem);
                        }
                        if (!repeated.contains(problem)) {
                            repeated.add(problem);
                        }
                    }
                    keysOnce(reader, at, keyPath, repeated);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                for (int i = 0; reader.hasNext(); i++) {
                    keysOnce(reader, at, path + "[" + i + "]", repeated);
                }
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }

    /**
     * What is wrong with {@code text}, and where, with the key it is in, such as {@code Unpaired surrogate characters
     * are not allowed in strict mode at line 3 column 30 (in server.rconPassword)}. The text is read again, step by
     * step, up to the problem, so the key path is this class's own and Gson's message gives only the kind. The line is
     * left out for text of one line, such as a line of {@code results.jsonl}.
     */
    private static String problem(String text) {
        JsonReader reader = reader(text);
        List<String> steps = new ArrayList<>();
        String kind;
        try {
            walk(reader, steps);
            if (reader.peek() == JsonToken.END_DOCUMENT) {
                // Gson's tree reader refused what the walk takes: nothing is known but that.
                return "a syntax error";
            }
            kind = UNEXPECTED;
        } catch (IOException | RuntimeException e) {
            kind = kind(e.getMessage());
        }
        Matcher position = POSITION.matcher(reader.toString());
        if (!position.find()) {
            return kind;
        }
        boolean oneLine = text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
        String key = steps.isEmpty() || kind.equals(UNEXPECTED) ? "" : " (in " + String.join("", steps) + ")";
        return kind + " at " + (oneLine ? "" : "line " + position.group(1) + " ") + "column " + position.group(2) + key;
    }

    /** The kind of problem Gson's {@code message} names: one of {@link #KINDS}, and nothing else of the message. */
    private static String kind(String message) {
        if (message == null) {
            return "it ends too early";
        }
        if (message.startsWith(LENIENT_ONLY)) {
            return UNEXPECTED;
        }
        Matcher nesting = NESTING.matcher(message);
        if (nesting.find()) {
            return nesting.group();
        }
        return KINDS.stream().filter(message::startsWith).findFirst().orElse("a syntax error");
    }

    /**
     * Reads the value at the reader as Gson's tree reader does, keeping in {@code steps} the key path to where it is,
     * one step per name or index. As in Gson's own path, a name stays the last step until the next name is read, and
     * an index names the entry about to be read; a problem stops the walk with its steps in place.
     */
    private static void walk(JsonReader reader, List<String> steps) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                boolean named = false;
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (named) {
                        steps.removeLast();
                    }
                    steps.add(step(steps.isEmpty(), name));
                    named = true;
                    walk(reader, steps);
                }
                if (named) {
                    steps.removeLast();
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                for (int i = 0; ; i++) {
                    steps.add("[" + i + "]");
                    if (!reader.hasNext()) {
                        steps.removeLast();
                        break;
                    }
                    walk(reader, steps);
                    steps.removeLast();
                }
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new JsonParseException("Expected value");
        }
    }

    /**
     * {@code name} as a step of a key path, after a dot unless {@code first}: as {@link Quoted#secretName} shows it, so
     * a name holding an {@code =}, as an env entry written {@code "API_KEY=sk-..."} does, is shown only up to it (#661).
     */
    private static String step(boolean first, String name) {
        return (first ? "" : ".") + Quoted.secretName(name);
    }
}
