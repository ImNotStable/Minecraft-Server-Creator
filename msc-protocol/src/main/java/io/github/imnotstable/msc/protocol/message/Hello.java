package io.github.imnotstable.msc.protocol.message;

/** First frame, agent to core. {@code nonce} is the agent's random challenge for the core (ADR 0043). */
public record Hello(int protocolVersion, String nonce, AgentInfo agent) implements Message {
}
