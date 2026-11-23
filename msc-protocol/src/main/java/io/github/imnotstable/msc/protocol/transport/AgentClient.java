package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import io.github.imnotstable.msc.protocol.ProtocolVersion;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Challenge;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Proof;
import io.github.imnotstable.msc.protocol.message.Welcome;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Agent side: connect to the core and complete the handshake. Agents find the core and reconnect
 * through {@code AgentLink} and {@code CoreAddress} in msc-agent-common.
 *
 * <p>The agent never sends the token. It proves it holds it, and handles no request until the core has proved the
 * same (ADR 0043): a process that took the core's port gets neither the token nor a server to drive. A core that
 * fails its proof is a {@link ProtocolException}, so {@code AgentLink} tries again, and may reach the real core.
 */
public final class AgentClient {
    private AgentClient() {
    }

    /** An open connection plus the session id the core assigned. */
    public record Session(String sessionId, Connection connection) {
    }

    /** {@code timeout} bounds the connect and the whole handshake. */
    public static Session connect(String host, int port, String token, AgentInfo info, RequestHandler handler,
                                  Connection.Listener listener, Duration timeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            HandshakeDeadline deadline = new HandshakeDeadline(socket, timeout);
            try {
                Connection connection = Connection.over(socket);
                String agentNonce = HandshakeProof.nonce();
                connection.send(new Hello(ProtocolVersion.CURRENT, agentNonce, info));
                Challenge challenge = expect(Challenge.class, connection);
                if (!HandshakeProof.validNonce(challenge.nonce())) {
                    throw new ProtocolException("the core's challenge has no usable nonce");
                }
                connection.send(new Proof(HandshakeProof.agent(token, agentNonce, challenge.nonce())));
                Welcome welcome = expect(Welcome.class, connection);
                if (!HandshakeProof.matches(HandshakeProof.core(token, agentNonce, challenge.nonce()), welcome.proof())) {
                    throw new ProtocolException("the process on " + host + ":" + port
                            + " could not prove it is msc-core (it does not hold the token); not taking its requests");
                }
                deadline.finishOrThrow("msc-core on " + host + ":" + port);
                socket.setSoTimeout(0);
                connection.seal(FrameSeal.agent(token, agentNonce, challenge.nonce()));
                connection.start(handler, listener);
                return new Session(welcome.sessionId(), connection);
            } finally {
                deadline.finish();
            }
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** The next handshake frame as {@code type}; a goodbye becomes the core's {@link OpException}. */
    private static <T extends Message> T expect(Class<T> type, Connection connection) throws IOException {
        Message reply = MessageCodec.decode(connection.readHandshakeFrame());
        if (reply instanceof Goodbye goodbye) {
            throw new OpException(goodbye.code(), goodbye.reason());
        }
        if (!type.isInstance(reply)) {
            throw new ProtocolException("expected " + type.getSimpleName().toLowerCase(java.util.Locale.ROOT) + ", got "
                    + reply.getClass().getSimpleName());
        }
        return type.cast(reply);
    }
}
