package io.github.imnotstable.msc.protocol;

/** Protocol version. Bump on any incompatible change to messages or operations. */
public final class ProtocolVersion {
    /**
     * 2: the token never crosses the wire; both sides prove they hold it (hello, challenge, proof, welcome; ADR 0043).
     * 3: every frame after the welcome carries an HMAC with its direction and sequence number (ADR 0092).
     */
    public static final int CURRENT = 3;

    private ProtocolVersion() {
    }
}
