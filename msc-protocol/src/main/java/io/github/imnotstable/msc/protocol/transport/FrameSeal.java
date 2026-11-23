package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticates every frame after the handshake (ADR 0092, closing ADR 0043's residual risk): each side derives a
 * session key from the token and both nonces, and prefixes each frame with an HMAC-SHA256 over its direction, its
 * sequence number and its text. A frame injected, replayed, reordered, dropped or sent back the other way fails the
 * check and ends the connection. Frames are not encrypted: the link is loopback, and what it carries is the user's own.
 */
public final class FrameSeal {
    /** Hex characters of a MAC, plus the space after it. */
    public static final int OVERHEAD = 65;
    private static final HexFormat HEX = HexFormat.of();
    private static final String TO_CORE = "agent>core";
    private static final String TO_AGENT = "core>agent";

    private final byte[] key;
    private final String sending;
    private final String receiving;
    private long sent;
    private long received;

    private FrameSeal(byte[] key, String sending, String receiving) {
        this.key = key;
        this.sending = sending;
        this.receiving = receiving;
    }

    /** The agent's seal: it sends to the core and receives from it. */
    public static FrameSeal agent(String token, String agentNonce, String coreNonce) {
        return new FrameSeal(key(token, agentNonce, coreNonce), TO_CORE, TO_AGENT);
    }

    /** The core's seal. */
    public static FrameSeal core(String token, String agentNonce, String coreNonce) {
        return new FrameSeal(key(token, agentNonce, coreNonce), TO_AGENT, TO_CORE);
    }

    private static byte[] key(String token, String agentNonce, String coreNonce) {
        return HEX.parseHex(HandshakeProof.sessionKey(token, agentNonce, coreNonce));
    }

    /** {@code frame} with its MAC in front. Callers keep frames in send order (the writer's lock). */
    public String seal(String frame) {
        return mac(sending, sent++, frame) + " " + frame;
    }

    /** The frame inside {@code line}, after its MAC is checked; a {@link ProtocolException} when it fails. */
    public String open(String line) throws ProtocolException {
        if (line.length() < OVERHEAD || line.charAt(OVERHEAD - 1) != ' ') {
            throw new ProtocolException("frame without a MAC after the handshake");
        }
        String frame = line.substring(OVERHEAD);
        String expected = mac(receiving, received, frame);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                line.substring(0, OVERHEAD - 1).getBytes(StandardCharsets.US_ASCII))) {
            throw new ProtocolException("frame " + received + " failed its MAC: injected, replayed or out of order");
        }
        received++;
        return frame;
    }

    private String mac(String direction, long seq, String frame) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((direction + "\n" + seq + "\n").getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(mac.doFinal(frame.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every Java runtime", e);
        }
    }
}
