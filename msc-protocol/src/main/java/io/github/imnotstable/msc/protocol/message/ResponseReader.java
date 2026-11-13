package io.github.imnotstable.msc.protocol.message;

/**
 * Reads a response frame without parsing its result: the result value is only skipped over and kept as text, so
 * {@link io.github.imnotstable.msc.protocol.op.OpSpec#decodeResult} parses it once, straight into its record. Accepts
 * any field order and whitespace. Returns {@code null} for anything unusual; the caller then takes the slower tree path,
 * which also names what is wrong.
 */
final class ResponseReader {
    private final String line;
    private int i;

    private ResponseReader(String line, int start) {
        this.line = line;
        this.i = start;
    }

    /** The response whose fields follow {@code start}, which points just after the type value, or {@code null}. */
    static Response read(String line, int start) {
        try {
            return new ResponseReader(line, start).fields();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Response fields() {
        long id = 0;
        RawJson result = null;
        ProtocolError error = null;
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
            String value = line.substring(valueStart, i);
            switch (key) {
                case "id" -> id = Long.parseLong(value);
                case "result" -> result = value.equals("null") ? null : new RawJson(value);
                case "error" -> error = MessageCodec.gson().fromJson(value, ProtocolError.class);
                case "type" -> {
                    return null;
                }
                default -> {
                    // Unknown fields are ignored, as Gson ignores them.
                }
            }
            skipSpace();
        }
        expect('}');
        skipSpace();
        return i == line.length() ? new Response(id, result, error) : null;
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

    /** Skips one value. Brackets are only counted, not matched: the result's own parse catches a mismatch. */
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
