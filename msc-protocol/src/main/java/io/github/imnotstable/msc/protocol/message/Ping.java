package io.github.imnotstable.msc.protocol.message;

/** Liveness check; the receiver answers with a {@link Pong} carrying the same nonce. */
public record Ping(long nonce) implements Message {
}
