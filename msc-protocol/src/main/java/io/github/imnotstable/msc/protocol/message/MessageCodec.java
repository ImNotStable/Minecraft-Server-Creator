package io.github.imnotstable.msc.protocol.message;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.github.imnotstable.msc.protocol.ProtocolException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * Turns messages into single-line JSON and back. The {@code type} field names the message kind;
 * the record's components sit beside it.
 */
public final class MessageCodec {
    private static final Map<String, Class<? extends Message>> TYPES = Map.of(
            "hello", Hello.class,
            "welcome", Welcome.class,
            "goodbye", Goodbye.class,
            "request", Request.class,
            "response", Response.class,
            "event", Event.class,
            "ping", Ping.class,
            "pong", Pong.class);

    private static final String TYPE_PREFIX = "{\"type\":\"";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .registerTypeAdapter(RawJson.class, new RawJsonAdapter().nullSafe())
            .registerTypeAdapter(int[].class, new IntArrayAdapter().nullSafe())
            .create();

    private MessageCodec() {
    }

    /** Shared Gson instance, configured the same way as the codec. */
    public static Gson gson() {
        return GSON;
    }

    /**
     * The message as one line with {@code type} first. Written straight from the record: building a tree first
     * would copy a large result (a million blocks) once more.
     */
    public static String encode(Message message) {
        String body = GSON.toJson(message);
        String type = "{\"type\":\"" + typeOf(message) + '"';
        return body.equals("{}") ? type + '}' : type + ',' + body.substring(1);
    }

    /**
     * The message on a line. A line that starts with its {@code type}, as {@link #encode} writes it, is read straight
     * into its record; any other line, or one that fails that way, goes through a JSON tree, which also names what
     * is wrong.
     */
    public static Message decode(String line) throws ProtocolException {
        if (line.startsWith(TYPE_PREFIX)) {
            int close = line.indexOf('"', TYPE_PREFIX.length());
            Class<? extends Message> cls = close < 0 ? null : TYPES.get(line.substring(TYPE_PREFIX.length(), close));
            char next = cls == null || close + 1 >= line.length() ? 0 : line.charAt(close + 1);
            if (cls == Response.class || cls == Request.class) {
                // Params and results can be a million blocks: keep them as text, never as a tree (ADR 0030).
                Message message = cls == Response.class ? RawFrameReader.response(line, close + 1)
                        : RawFrameReader.request(line, close + 1);
                if (message != null) {
                    return message;
                }
            } else if (next == ',' || next == '}') {
                try {
                    String body = "{" + line.substring(next == '}' ? close + 1 : close + 2);
                    Message message = GSON.fromJson(body, cls);
                    if (message != null) {
                        return message;
                    }
                } catch (RuntimeException e) {
                    // fall through: the tree path gives the precise error
                }
            }
        }
        return decodeTree(line);
    }

    private static Message decodeTree(String line) throws ProtocolException {
        JsonObject obj;
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                throw new ProtocolException("frame is not a JSON object");
            }
            obj = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ProtocolException("frame is not valid JSON", e);
        }
        JsonElement type = obj.remove("type");
        if (type == null || !type.isJsonPrimitive()) {
            throw new ProtocolException("frame has no type");
        }
        Class<? extends Message> cls = TYPES.get(type.getAsString());
        if (cls == null) {
            throw new ProtocolException("unknown frame type: " + type.getAsString());
        }
        try {
            Message message = GSON.fromJson(obj, cls);
            if (message == null) {
                throw new ProtocolException("empty frame");
            }
            return message;
        } catch (RuntimeException e) {
            throw new ProtocolException("malformed " + type.getAsString() + " frame: " + e.getMessage(), e);
        }
    }

    /** Writes a {@link RawJson} into the frame as is; reads one through a tree, which only the slow path needs. */
    private static final class RawJsonAdapter extends TypeAdapter<RawJson> {
        @Override
        public void write(JsonWriter out, RawJson value) throws IOException {
            out.jsonValue(value.json());
        }

        @Override
        public RawJson read(JsonReader in) {
            return RawJson.of(JsonParser.parseReader(in));
        }
    }

    /**
     * An {@code int[]} (a grid's blocks, a heightmap) as the same JSON array Gson writes, without a boxed
     * {@code Integer} per element on the way in or a {@code String} per element on the way out.
     */
    private static final class IntArrayAdapter extends TypeAdapter<int[]> {
        @Override
        public void write(JsonWriter out, int[] value) throws IOException {
            if (out.getClass() != JsonWriter.class) {
                // A tree writer (toJsonTree) takes no raw JSON.
                out.beginArray();
                for (int v : value) {
                    out.value(v);
                }
                out.endArray();
                return;
            }
            StringBuilder json = new StringBuilder(value.length * 2 + 2).append('[');
            for (int i = 0; i < value.length; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(value[i]);
            }
            out.jsonValue(json.append(']').toString());
        }

        @Override
        public int[] read(JsonReader in) throws IOException {
            int[] values = new int[16];
            int size = 0;
            in.beginArray();
            while (in.hasNext()) {
                if (size == values.length) {
                    values = Arrays.copyOf(values, size * 2);
                }
                values[size++] = in.nextInt();
            }
            in.endArray();
            return size == values.length ? values : Arrays.copyOf(values, size);
        }
    }

    private static String typeOf(Message message) {
        return switch (message) {
            case Hello h -> "hello";
            case Welcome w -> "welcome";
            case Goodbye g -> "goodbye";
            case Request r -> "request";
            case Response r -> "response";
            case Event e -> "event";
            case Ping p -> "ping";
            case Pong p -> "pong";
        };
    }
}
