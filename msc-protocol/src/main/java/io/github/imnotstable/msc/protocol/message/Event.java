package io.github.imnotstable.msc.protocol.message;

import com.google.gson.JsonObject;

/** One-way notification; no response. Names are listed in {@code Events}. */
public record Event(String name, JsonObject data) implements Message {
    public Event {
        data = data == null ? new JsonObject() : data;
    }
}
