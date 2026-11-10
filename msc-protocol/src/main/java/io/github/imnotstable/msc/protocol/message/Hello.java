package io.github.imnotstable.msc.protocol.message;

/** First frame, agent to core. */
public record Hello(int protocolVersion, String token, AgentInfo agent) implements Message {
}
