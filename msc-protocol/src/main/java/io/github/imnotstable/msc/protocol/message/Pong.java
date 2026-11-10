package io.github.imnotstable.msc.protocol.message;

/** Answer to a {@link Ping}. */
public record Pong(long nonce) implements Message {
}
