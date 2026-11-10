package io.github.imnotstable.msc.protocol.message;

/** Sender is closing the connection; {@code code} is one of {@link ErrorCodes}. */
public record Goodbye(String code, String reason) implements Message {
}
