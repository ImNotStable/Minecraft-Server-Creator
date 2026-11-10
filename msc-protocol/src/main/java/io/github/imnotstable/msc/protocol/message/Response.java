package io.github.imnotstable.msc.protocol.message;

import com.google.gson.JsonElement;

/** Answer to a {@link Request} with the same id: exactly one of {@code result} or {@code error} is set. */
public record Response(long id, JsonElement result, ProtocolError error) implements Message {
    public static Response ok(long id, JsonElement result) {
        return new Response(id, result, null);
    }

    public static Response fail(long id, String code, String message) {
        return new Response(id, null, new ProtocolError(code, message));
    }

    public boolean isOk() {
        return error == null;
    }
}
