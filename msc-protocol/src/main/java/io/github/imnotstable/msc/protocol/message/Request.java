package io.github.imnotstable.msc.protocol.message;

import com.google.gson.JsonObject;

/** Ask the other side to run an operation. {@code id} is unique per sender and connection. */
public record Request(long id, String op, JsonObject params) implements Message {
    public Request {
        params = params == null ? new JsonObject() : params;
    }
}
