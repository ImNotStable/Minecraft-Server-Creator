package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.ProtocolException;
import io.github.imnotstable.msc.protocol.ProtocolVersion;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Welcome;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/** Agent side: connect to the core and complete the handshake. */
public final class AgentClient {
    private AgentClient() {
    }

    /** An open connection plus the session id the core assigned. */
    public record Session(String sessionId, Connection connection) {
    }

    /**
     * Connect using {@code MSC_CORE_ADDRESS} and {@code MSC_TOKEN}, which the core sets when it
     * starts the server. Returns {@code null} if they are not set (the server was started by hand).
     */
    public static Session connectFromEnvironment(AgentInfo info, RequestHandler handler, Connection.Listener listener,
                                                 Duration timeout) throws IOException {
        String address = System.getenv(Limits.ENV_CORE_ADDRESS);
        String token = System.getenv(Limits.ENV_TOKEN);
        if (address == null || token == null) {
            return null;
        }
        int colon = address.lastIndexOf(':');
        if (colon < 0) {
            throw new ProtocolException(Limits.ENV_CORE_ADDRESS + " must be host:port, got " + address);
        }
        return connect(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)),
                token, info, handler, listener, timeout);
    }

    public static Session connect(String host, int port, String token, AgentInfo info, RequestHandler handler,
                                  Connection.Listener listener, Duration timeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            Connection connection = Connection.over(socket);
            connection.send(new Hello(ProtocolVersion.CURRENT, token, info));
            String frame = connection.readHandshakeFrame();
            Message reply = MessageCodec.decode(frame);
            switch (reply) {
                case Welcome welcome -> {
                    socket.setSoTimeout(0);
                    connection.start(handler, listener);
                    return new Session(welcome.sessionId(), connection);
                }
                case Goodbye goodbye -> throw new OpException(goodbye.code(), goodbye.reason());
                default -> throw new ProtocolException("expected welcome, got " + reply.getClass().getSimpleName());
            }
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }
}
