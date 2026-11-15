package io.github.imnotstable.msc.protocol.message;

/** Agent to core, answering {@link Challenge}: proof that the agent holds the token (ADR 0043). */
public record Proof(String proof) implements Message {
}
