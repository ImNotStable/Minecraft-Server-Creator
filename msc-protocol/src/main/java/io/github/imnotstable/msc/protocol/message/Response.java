package io.github.imnotstable.msc.protocol.message;

import com.google.gson.JsonElement;

/**
 * Answer to a {@link Request} with the same id: exactly one of {@code result} or {@code error} is set. The result is
 * kept as JSON text (see {@link RawJson}); {@link io.github.imnotstable.msc.protocol.op.OpSpec} turns it into the
 * operation's result type.
 */
public record Response(long id, RawJson result, ProtocolError error) implements Message {
    public static Response ok(long id, RawJson result) {
        return new Response(id, result, null);
    }

    public static Response ok(long id, JsonElement result) {
        return ok(id, RawJson.of(result));
    }

    public static Response fail(long id, String code, String message) {
        return new Response(id, null, new ProtocolError(code, message));
    }

    public boolean isOk() {
        return error == null;
    }
}
