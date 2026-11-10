package io.github.imnotstable.msc.protocol.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Runs incoming requests. Each request runs on its own virtual thread, so a handler may block, for
 * example while waiting for the server's main thread.
 */
@FunctionalInterface
public interface RequestHandler {
    /** Result JSON for the operation. Throw {@link OpException} to answer with a specific error code. */
    JsonElement handle(String op, JsonObject params) throws Exception;

    /** Answers every request with {@code unknown_op}. */
    static RequestHandler none() {
        return (op, params) -> {
            throw new OpException(io.github.imnotstable.msc.protocol.message.ErrorCodes.UNKNOWN_OP, op);
        };
    }
}
