package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Challenge;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Proof;
import io.github.imnotstable.msc.protocol.message.Welcome;
import io.github.imnotstable.msc.protocol.transport.FrameReader;
import io.github.imnotstable.msc.protocol.transport.FrameSeal;
import io.github.imnotstable.msc.protocol.transport.FrameWriter;
import io.github.imnotstable.msc.protocol.transport.HandshakeProof;
import java.io.IOException;

/** The agent's side of the handshake done by hand on a raw socket, for tests that then send frames themselves. */
final class RawHandshake {
    private RawHandshake() {
    }

    /** The core's welcome, and the agent's seal for every frame after it (ADR 0092). */
    record Raw(Welcome welcome, FrameSeal seal) {
    }

    static Raw agent(FrameWriter writer, FrameReader reader, String token, AgentInfo info) throws IOException {
        String agentNonce = HandshakeProof.nonce();
        writer.write(MessageCodec.encode(new Hello(ProtocolVersion.CURRENT, agentNonce, info)));
        Challenge challenge = (Challenge) MessageCodec.decode(reader.read());
        writer.write(MessageCodec.encode(new Proof(HandshakeProof.agent(token, agentNonce, challenge.nonce()))));
        Welcome welcome = (Welcome) MessageCodec.decode(reader.read());
        assertThat(welcome.proof()).isEqualTo(HandshakeProof.core(token, agentNonce, challenge.nonce()));
        return new Raw(welcome, FrameSeal.agent(token, agentNonce, challenge.nonce()));
    }
}
