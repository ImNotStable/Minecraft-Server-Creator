package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.transport.HandshakeProof;
import org.junit.jupiter.api.Test;

class HandshakeProofTest {
    @Test
    void proofsDependOnTokenNoncesAndSide() {
        String agent = HandshakeProof.agent("t", "a", "c");
        assertThat(agent).hasSize(64).isEqualTo(HandshakeProof.agent("t", "a", "c"));
        assertThat(agent).isNotEqualTo(HandshakeProof.core("t", "a", "c"))
                .isNotEqualTo(HandshakeProof.agent("u", "a", "c"))
                .isNotEqualTo(HandshakeProof.agent("t", "b", "c"))
                .isNotEqualTo(HandshakeProof.agent("t", "a", "d"));
        // The separator keeps the nonces apart: "a" then "bc" is not "ab" then "c".
        assertThat(HandshakeProof.agent("t", "a", "bc")).isNotEqualTo(HandshakeProof.agent("t", "ab", "c"));
    }

    @Test
    void matchingAndNonces() {
        assertThat(HandshakeProof.matches("abc", "abc")).isTrue();
        assertThat(HandshakeProof.matches("abc", "abd")).isFalse();
        assertThat(HandshakeProof.matches("abc", null)).isFalse();
        assertThat(HandshakeProof.nonce()).hasSize(64).isNotEqualTo(HandshakeProof.nonce());
        assertThat(HandshakeProof.validNonce(HandshakeProof.nonce())).isTrue();
        assertThat(HandshakeProof.validNonce(null)).isFalse();
        assertThat(HandshakeProof.validNonce(" ")).isFalse();
        assertThat(HandshakeProof.validNonce("x".repeat(HandshakeProof.MAX_NONCE_CHARS + 1))).isFalse();
        assertThatThrownBy(() -> HandshakeProof.agent("", "a", "c")).isInstanceOf(IllegalArgumentException.class);
    }
}
