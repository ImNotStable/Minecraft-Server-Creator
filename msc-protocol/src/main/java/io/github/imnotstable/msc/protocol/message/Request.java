package io.github.imnotstable.msc.protocol.message;

import com.google.gson.JsonObject;

/**
 * Ask the other side to run an operation. {@code id} is unique per sender and connection. The params are kept as JSON
 * text (see {@link RawJson}), like a response's result: a {@code world.write} carries a million blocks in them, and
 * {@link io.github.imnotstable.msc.protocol.op.OpSpec} turns them into the operation's params type with no tree in
 * between (ADR 0030). Missing params are an empty object.
 */
public record Request(long id, String op, RawJson params) implements Message {
    private static final RawJson EMPTY = new RawJson("{}");

    public Request {
        params = params == null ? EMPTY : params;
    }

    public Request(long id, String op, JsonObject params) {
        this(id, op, params == null ? null : RawJson.of(params));
    }
}
