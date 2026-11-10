package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import io.github.imnotstable.msc.protocol.ProtocolVersion;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Welcome;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;

/** Core side: listen on the loopback interface and accept agents that present the right token. */
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

    private final ServerSocket serverSocket;
    private final byte[] token;
    private final RequestHandler handler;
    private final AgentListener listener;
    private final Duration handshakeTimeout;

    private CoreServer(ServerSocket serverSocket, String token, RequestHandler handler, AgentListener listener,
                       Duration handshakeTimeout) {
        this.serverSocket = serverSocket;
        this.token = token.getBytes(StandardCharsets.UTF_8);
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
        ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        CoreServer server = new CoreServer(socket, token, handler, listener, handshakeTimeout);
        Thread.ofVirtual().name("msc-core-accept").start(server::acceptLoop);
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
            Message first = MessageCodec.decode(connection.readHandshakeFrame());
            if (!(first instanceof Hello hello)) {
                reject(connection, ErrorCodes.BAD_FRAME, "expected hello");
                return;
            }
            if (hello.protocolVersion() != ProtocolVersion.CURRENT) {
                reject(connection, ErrorCodes.VERSION_MISMATCH,
                        "core speaks protocol " + ProtocolVersion.CURRENT + ", agent speaks " + hello.protocolVersion());
                return;
            }
            byte[] presented = hello.token() == null ? new byte[0] : hello.token().getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(token, presented)) {
                reject(connection, ErrorCodes.UNAUTHORIZED, "bad token");
                return;
            }
            if (hello.agent() == null) {
                reject(connection, ErrorCodes.BAD_FRAME, "hello has no agent info");
                return;
            }
            String sessionId = UUID.randomUUID().toString();
            AgentSession session = new AgentSession(sessionId, hello.agent(), connection);
            connection.send(new Welcome(ProtocolVersion.CURRENT, sessionId));
            socket.setSoTimeout(0);
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

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
