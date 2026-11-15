package io.github.imnotstable.msc.protocol.message;

/**
 * Core accepts the agent. {@code proof} shows the core holds the token: the agent accepts no request before it
 * has checked it (ADR 0043).
 */
public record Welcome(int protocolVersion, String sessionId, String proof) implements Message {
}
