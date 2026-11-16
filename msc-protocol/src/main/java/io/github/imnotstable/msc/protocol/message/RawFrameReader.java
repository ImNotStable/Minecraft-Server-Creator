package io.github.imnotstable.msc.protocol.message;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads a request or response frame without parsing its payload: the request's {@code params} and the response's
 * {@code result} are only skipped over and kept as text, so {@link io.github.imnotstable.msc.protocol.op.OpSpec}
 * parses them once, straight into their record (ADR 0030). Accepts any field order and whitespace. Returns
 * {@code null} for anything unusual; the caller then takes the slower tree path, which also names what is wrong.
 */
final class RawFrameReader {
    private final String line;
    private int i;

    private RawFrameReader(String line, int start) {
        this.line = line;
        this.i = start;
    }

    /** The response whose fields follow {@code start}, which points just after the type value, or {@code null}. */
    static Response response(String line, int start) {
        try {
            Map<String, String> fields = new RawFrameReader(line, start).fields();
            if (fields == null) {
                return null;
            }
            String error = fields.get("error");
            return new Response(id(fields), raw(fields.get("result")),
                    error == null ? null : MessageCodec.gson().fromJson(error, ProtocolError.class));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The request whose fields follow {@code start}, which points just after the type value, or {@code null}. */
    static Request request(String line, int start) {
        try {
            Map<String, String> fields = new RawFrameReader(line, start).fields();
            if (fields == null) {
                return null;
            }
            String op = fields.get("op");
            return new Request(id(fields), op == null ? null : MessageCodec.gson().fromJson(op, String.class),
                    raw(fields.get("params")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long id(Map<String, String> fields) {
        String id = fields.get("id");
        return id == null ? 0 : Long.parseLong(id);
    }

    private static RawJson raw(String value) {
        return value == null || value.equals("null") ? null : new RawJson(value);
    }

    /** Each field's value as its text, or {@code null} for a second {@code type} or text after the object. */
    private Map<String, String> fields() {
        Map<String, String> fields = new HashMap<>();
        skipSpace();
        while (peek() == ',') {
            i++;
            skipSpace();
            int keyStart = i;
            skipString();
            String key = line.substring(keyStart + 1, i - 1);
            skipSpace();
            expect(':');
            skipSpace();
            int valueStart = i;
            skipValue();
            if (key.equals("type")) {
                return null;
            }
            // Unknown fields are kept but unused, as Gson ignores them.
            fields.put(key, line.substring(valueStart, i));
            skipSpace();
        }
        expect('}');
        skipSpace();
        return i == line.length() ? fields : null;
    }

    private char peek() {
        return line.charAt(i);
    }

    private void expect(char c) {
        if (line.charAt(i++) != c) {
            throw new IllegalArgumentException("expected " + c);
        }
    }

    private void skipSpace() {
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
    }

    private void skipString() {
        expect('"');
        while (true) {
            char c = line.charAt(i++);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return;
            }
        }
    }

    /** Skips one value. Brackets are only counted, not matched: the payload's own parse catches a mismatch. */
    private void skipValue() {
        char first = peek();
        if (first == '"') {
            skipString();
        } else if (first == '{' || first == '[') {
            int depth = 0;
            do {
                char c = peek();
                if (c == '"') {
                    skipString();
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                }
                i++;
            } while (depth > 0);
        } else {
            int start = i;
            while (i < line.length() && ",}] \t\r\n".indexOf(line.charAt(i)) < 0) {
                i++;
            }
            if (i == start) {
                throw new IllegalArgumentException("missing value");
            }
        }
    }
}
