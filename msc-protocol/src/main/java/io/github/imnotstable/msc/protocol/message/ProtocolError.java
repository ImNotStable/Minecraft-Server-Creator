package io.github.imnotstable.msc.protocol.message;

/** Machine-readable {@code code} (see {@link ErrorCodes}) plus a short human message. */
public record ProtocolError(String code, String message) {
}
