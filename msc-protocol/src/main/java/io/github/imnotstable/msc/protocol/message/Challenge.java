package io.github.imnotstable.msc.protocol.message;

/** Core to agent, answering {@link Hello}: the core's random challenge, which the agent answers with a {@link Proof}. */
public record Challenge(String nonce) implements Message {
}
