package io.github.imnotstable.msc.protocol.message;

/** Every frame on the wire is exactly one of these. */
public sealed interface Message permits Hello, Challenge, Proof, Welcome, Goodbye, Request, Response, Event, Ping, Pong {
}
