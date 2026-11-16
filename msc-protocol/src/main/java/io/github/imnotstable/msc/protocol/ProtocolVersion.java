package io.github.imnotstable.msc.protocol;

/** Protocol version. Bump on any incompatible change to messages or operations. */
public final class ProtocolVersion {
    /** 2: the token never crosses the wire; both sides prove they hold it (hello, challenge, proof, welcome; ADR 0043). */
    public static final int CURRENT = 2;

    private ProtocolVersion() {
    }
}
