package io.github.imnotstable.msc.protocol.message;

import com.google.gson.JsonElement;
import java.util.Objects;

/**
 * One JSON value kept as its text, so an operation result goes from its record to the frame and back without a
 * {@link JsonElement} tree in between (ADR 0030). The codec writes the text into the frame as is.
 *
 * @param json one complete JSON value on one line, as Gson writes it
 */
public record RawJson(String json) {
    public RawJson {
        Objects.requireNonNull(json, "json");
    }

    public static RawJson of(JsonElement tree) {
        return new RawJson(tree.toString());
    }
}
