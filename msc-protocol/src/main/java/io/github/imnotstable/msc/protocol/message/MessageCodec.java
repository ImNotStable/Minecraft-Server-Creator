package io.github.imnotstable.msc.protocol.message;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.protocol.ProtocolException;
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

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MessageCodec() {
    }

    /** Shared Gson instance, configured the same way as the codec. */
    public static Gson gson() {
        return GSON;
    }

    public static String encode(Message message) {
        JsonObject body = GSON.toJsonTree(message).getAsJsonObject();
        JsonObject out = new JsonObject();
        out.addProperty("type", typeOf(message));
        for (Map.Entry<String, JsonElement> e : body.entrySet()) {
            out.add(e.getKey(), e.getValue());
        }
        return GSON.toJson(out);
    }

    public static Message decode(String line) throws ProtocolException {
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
