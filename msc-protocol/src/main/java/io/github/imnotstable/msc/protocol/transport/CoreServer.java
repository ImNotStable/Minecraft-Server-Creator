package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import io.github.imnotstable.msc.protocol.ProtocolVersion;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Challenge;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Proof;
import io.github.imnotstable.msc.protocol.message.Welcome;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Core side: listen on the loopback interface and accept agents that prove they hold the token. Nothing an agent
 * sends is acted on before that proof; the handshake has one deadline, a small frame limit and a cap on how many
 * may wait at once, and a first line that is not JSON (an HTTP request) is closed without an answer (ADR 0043).
 */
public final class CoreServer implements AutoCloseable {
    /** A connected, authenticated agent. */
    public record AgentSession(String sessionId, AgentInfo agent, Connection connection) {
    }

    /** What the core does with agents. All methods run on virtual threads. */
    public interface AgentListener {
        void connected(AgentSession session);

        default void event(AgentSession session, Event event) {
        }

        default void disconnected(AgentSession session, Throwable cause) {
        }

        /** An agent failed the handshake (wrong token, wrong version, bad frame). */
        default void rejected(String reason) {
        }
    }

    /** Most connections that may be in their handshake at once; more are closed at once (ADR 0043). */
    static final int MAX_PENDING_HANDSHAKES = 16;

    private final ServerSocket serverSocket;
    /** Every token an agent may prove; the core proves back with the one it proved. */
    private final List<String> tokens;
    private final RequestHandler handler;
    private final AgentListener listener;
    private final Duration handshakeTimeout;
    private final Semaphore handshakes = new Semaphore(MAX_PENDING_HANDSHAKES);
    private Thread acceptor;

    private CoreServer(ServerSocket serverSocket, List<String> tokens, RequestHandler handler, AgentListener listener,
                       Duration handshakeTimeout) {
        this.serverSocket = serverSocket;
        this.tokens = tokens;
        this.handler = handler;
        this.listener = listener;
        this.handshakeTimeout = handshakeTimeout;
    }

    /**
     * Start listening. Port 0 picks a free port; read it back with {@link #port()}.
     *
     * @param handler answers requests an agent sends to the core
     */
    public static CoreServer start(int port, String token, RequestHandler handler, AgentListener listener,
                                   Duration handshakeTimeout) throws IOException {
        return start(port, Collections.singletonList(token), handler, listener, handshakeTimeout);
    }

    /**
     * As {@link #start(int, String, RequestHandler, AgentListener, Duration)}, accepting an agent that proves any one
     * of {@code tokens}: the core keeps a token per workspace for the servers it starts besides the shared one
     * (ADR 0050). An agent sees no difference.
     */
    public static CoreServer start(int port, List<String> tokens, RequestHandler handler, AgentListener listener,
                                   Duration handshakeTimeout) throws IOException {
        if (tokens.isEmpty() || tokens.stream().anyMatch(t -> t == null || t.isEmpty())) {
            throw new IllegalArgumentException("the msc token is empty");
        }
        // Loopback only, whatever host MSC_CORE_ADDRESS names: agents on other machines are not supported (ADR 0043).
        ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        CoreServer server = new CoreServer(socket, List.copyOf(tokens), handler, listener, handshakeTimeout);
        server.acceptor = Thread.ofVirtual().name("msc-core-accept").start(server::acceptLoop);
        return server;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Value for {@code MSC_CORE_ADDRESS}. */
    public String address() {
        return serverSocket.getInetAddress().getHostAddress() + ":" + port();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().name("msc-core-handshake").start(() -> handshake(socket));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handshake(Socket socket) {
        // Each waiting handshake holds a thread and a buffer: a flood of connections that never say hello gets no more.
        if (!handshakes.tryAcquire()) {
            listener.rejected("too many connections waiting for their handshake");
            closeQuietly(socket);
            return;
        }
        HandshakeDeadline deadline = new HandshakeDeadline(socket, handshakeTimeout);
        try {
            handshake(socket, deadline);
        } finally {
            deadline.finish();
            handshakes.release();
        }
    }

    private void handshake(Socket socket, HandshakeDeadline deadline) {
        Connection connection;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) handshakeTimeout.toMillis());
            connection = Connection.over(socket);
        } catch (IOException e) {
            closeQuietly(socket);
            return;
        }
        try {
            String line = connection.readHandshakeFrame();
            if (!line.startsWith("{")) {
                // An HTTP request (a browser page aiming at the loopback port) or any other stranger: no answer at all.
                listener.rejected(ErrorCodes.BAD_FRAME + ": first line is not JSON" + (looksLikeHttp(line)
                        ? " (an HTTP request)" : ""));
                closeQuietly(socket);
                return;
            }
            Message first = MessageCodec.decode(line);
            if (!(first instanceof Hello hello)) {
                reject(connection, ErrorCodes.BAD_FRAME, "expected hello");
                return;
            }
            if (hello.protocolVersion() != ProtocolVersion.CURRENT) {
                reject(connection, ErrorCodes.VERSION_MISMATCH,
                        "core speaks protocol " + ProtocolVersion.CURRENT + ", agent speaks " + hello.protocolVersion());
                return;
            }
            if (hello.agent() == null || !HandshakeProof.validNonce(hello.nonce())) {
                reject(connection, ErrorCodes.BAD_FRAME, "hello needs agent info and a nonce");
                return;
            }
            String coreNonce = HandshakeProof.nonce();
            connection.send(new Challenge(coreNonce));
            Message answer = MessageCodec.decode(connection.readHandshakeFrame());
            String token = answer instanceof Proof proof ? proved(proof, hello.nonce(), coreNonce) : null;
            if (token == null) {
                reject(connection, ErrorCodes.UNAUTHORIZED, "bad token");
                return;
            }
            deadline.finishOrThrow("agent " + hello.agent().platform());
            String sessionId = UUID.randomUUID().toString();
            AgentSession session = new AgentSession(sessionId, hello.agent(), connection);
            socket.setSoTimeout(0);
            connection.send(new Welcome(ProtocolVersion.CURRENT, sessionId,
                    HandshakeProof.core(token, hello.nonce(), coreNonce)));
            connection.start(handler, new Connection.Listener() {
                @Override
                public void onEvent(Event event) {
                    listener.event(session, event);
                }

                @Override
                public void onClose(Throwable cause) {
                    listener.disconnected(session, cause);
                }
            });
            listener.connected(session);
        } catch (ProtocolException e) {
            reject(connection, ErrorCodes.BAD_FRAME, e.getMessage());
        } catch (IOException e) {
            listener.rejected(e.toString());
            closeQuietly(socket);
        }
    }

    /** The token {@code proof} proves, or null. Every token is checked, so the time says nothing about which. */
    private String proved(Proof proof, String agentNonce, String coreNonce) {
        String proved = null;
        for (String token : tokens) {
            if (HandshakeProof.matches(HandshakeProof.agent(token, agentNonce, coreNonce), proof.proof())) {
                proved = token;
            }
        }
        return proved;
    }

    /** A request line such as {@code GET / HTTP/1.1}. */
    static boolean looksLikeHttp(String line) {
        return line.matches("[A-Z]{3,10} \\S+ HTTP/\\d(\\.\\d)?\\r?");
    }

    private void reject(Connection connection, String code, String reason) {
        listener.rejected(code + ": " + reason);
        connection.close(code, reason);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    /**
     * Stops listening. Returns once the port is let go, so it can be bound again at once (a workspace switch with a
     * fixed MSC_CORE_ADDRESS, #721): a socket closed while a thread is blocked in its accept is only really closed
     * when that thread wakes up, so this waits for the accept loop to end.
     */
    @Override
    public void close() throws IOException {
        serverSocket.close();
        try {
            acceptor.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
