package io.github.imnotstable.msc.protocol.message;

/** Core accepts the agent. */
public record Welcome(int protocolVersion, String sessionId) implements Message {
}
