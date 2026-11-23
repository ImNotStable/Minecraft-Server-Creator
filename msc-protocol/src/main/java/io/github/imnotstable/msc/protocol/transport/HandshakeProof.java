package io.github.imnotstable.msc.protocol.transport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The handshake's mutual proof (ADR 0043): each side shows it holds the session token by an HMAC-SHA256, keyed by
 * the token, over both sides' random nonces. The token itself never crosses the wire, so whoever listens on the
 * core's port (a fake core) learns nothing it can replay, and a fresh core nonce per connection means an agent proof
 * cannot be replayed either. The agent proves first, so a process that merely connects to the core gets no proof
 * to guess the token against.
 */
public final class HandshakeProof {
    /** Longest nonce either side accepts, in characters: a nonce is 64 hex characters. */
    public static final int MAX_NONCE_CHARS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private HandshakeProof() {
    }

    /** 32 random bytes as hex. */
    public static String nonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    /** What the agent sends in its {@code proof}. */
    public static String agent(String token, String agentNonce, String coreNonce) {
        return hmac(token, "msc-agent-proof\n" + agentNonce + "\n" + coreNonce);
    }

    /** What the core sends in its {@code welcome}: a different label, so one side's proof never passes as the other's. */
    public static String core(String token, String agentNonce, String coreNonce) {
        return hmac(token, "msc-core-proof\n" + agentNonce + "\n" + coreNonce);
    }

    /**
     * The key both sides seal frames with after the handshake ({@link FrameSeal}, ADR 0092): a third label, so it is
     * neither side's proof.
     */
    public static String sessionKey(String token, String agentNonce, String coreNonce) {
        return hmac(token, "msc-session-key\n" + agentNonce + "\n" + coreNonce);
    }

    /** Whether {@code presented} is {@code expected}, in time that does not depend on where they differ. */
    public static boolean matches(String expected, String presented) {
        return presented != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether a peer's nonce is usable: present, not blank and not over {@link #MAX_NONCE_CHARS}. */
    public static boolean validNonce(String nonce) {
        return nonce != null && !nonce.isBlank() && nonce.length() <= MAX_NONCE_CHARS;
    }

    private static String hmac(String token, String data) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("the msc token is empty");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every Java runtime", e);
        }
    }
}
