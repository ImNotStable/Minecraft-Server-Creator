package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Challenge;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Proof;
import io.github.imnotstable.msc.protocol.message.RawJson;
import io.github.imnotstable.msc.protocol.message.Request;
import io.github.imnotstable.msc.protocol.message.Welcome;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.FrameReader;
import io.github.imnotstable.msc.protocol.transport.FrameWriter;
import io.github.imnotstable.msc.protocol.transport.HandshakeProof;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The handshake's authentication on a real loopback socket (ADR 0043): what a stranger on the port can and cannot do. */
class HandshakeAuthTest {
    private static final String TOKEN = "f3a1c0de-session-token";
    private static final Duration DEADLINE = Duration.ofMillis(800);
    private static final AgentInfo INFO = new AgentInfo("paper", "test", "26.3", List.of("server.info"));

    private final BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> rejections = new LinkedBlockingQueue<>();
    private CoreServer core;

    @AfterEach
    void stop() throws IOException {
        if (core != null) {
            core.close();
        }
    }

    private CoreServer core(Duration deadline) throws IOException {
        core = CoreServer.start(0, TOKEN, RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession session) {
                sessions.add(session);
            }

            @Override
            public void rejected(String reason) {
                rejections.add(reason);
            }
        }, deadline);
        return core;
    }

    private Socket raw() throws IOException {
        Socket socket = new Socket("127.0.0.1", core.port());
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static FrameWriter writer(Socket socket) throws IOException {
        return new FrameWriter(socket.getOutputStream(), Limits.MAX_FRAME_BYTES);
    }

    private static FrameReader reader(Socket socket) throws IOException {
        return new FrameReader(socket.getInputStream(), Limits.MAX_FRAME_BYTES);
    }

    @Test
    void theRightTokenConnectsAndTheCoreProvesItself() throws Exception {
        core(Duration.ofSeconds(5));
        AgentClient.Session agent = AgentClient.connect("127.0.0.1", core.port(), TOKEN, INFO, RequestHandler.none(),
                new Connection.Listener() {
                }, Duration.ofSeconds(5));
        assertThat(sessions.poll(5, TimeUnit.SECONDS)).isNotNull();
        agent.connection().close();
    }

    @Test
    void aWrongTokenIsRefusedAndTheChallengeCarriesNoProof() throws Exception {
        core(Duration.ofSeconds(5));
        try (Socket socket = raw()) {
            FrameWriter writer = writer(socket);
            FrameReader reader = reader(socket);
            String agentNonce = HandshakeProof.nonce();
            writer.write(MessageCodec.encode(new Hello(ProtocolVersion.CURRENT, agentNonce, INFO)));
            String challengeLine = reader.read();
            // Nothing a stranger can guess the token against: only the core's nonce.
            assertThat(MessageCodec.decode(challengeLine)).isInstanceOf(Challenge.class);
            assertThat(challengeLine).doesNotContain("proof");
            Challenge challenge = (Challenge) MessageCodec.decode(challengeLine);
            writer.write(MessageCodec.encode(new Proof(HandshakeProof.agent("wrong", agentNonce, challenge.nonce()))));
            assertThat(MessageCodec.decode(reader.read())).isInstanceOfSatisfying(Goodbye.class,
                    g -> assertThat(g.code()).isEqualTo(ErrorCodes.UNAUTHORIZED));
        }
        assertThat(rejections.poll(5, TimeUnit.SECONDS)).startsWith(ErrorCodes.UNAUTHORIZED);
        assertThat(sessions).isEmpty();
    }

    @Test
    void anOpSentInsteadOfTheProofIsNeverRun() throws Exception {
        core(Duration.ofSeconds(5));
        try (Socket socket = raw()) {
            FrameWriter writer = writer(socket);
            FrameReader reader = reader(socket);
            writer.write(MessageCodec.encode(new Hello(ProtocolVersion.CURRENT, HandshakeProof.nonce(), INFO)));
            assertThat(MessageCodec.decode(reader.read())).isInstanceOf(Challenge.class);
            writer.write(MessageCodec.encode(new Request(1, "server.info", new RawJson("{}"))));
            assertThat(MessageCodec.decode(reader.read())).isInstanceOfSatisfying(Goodbye.class,
                    g -> assertThat(g.code()).isEqualTo(ErrorCodes.UNAUTHORIZED));
        }
        assertThat(sessions).isEmpty();
    }

    @Test
    void aProofForAnotherChallengeIsRefused() throws Exception {
        core(Duration.ofSeconds(5));
        try (Socket socket = raw()) {
            FrameWriter writer = writer(socket);
            FrameReader reader = reader(socket);
            String agentNonce = HandshakeProof.nonce();
            writer.write(MessageCodec.encode(new Hello(ProtocolVersion.CURRENT, agentNonce, INFO)));
            reader.read();
            // A proof recorded from an earlier connection: right token, but the core's nonce is new each time.
            writer.write(MessageCodec.encode(new Proof(HandshakeProof.agent(TOKEN, agentNonce, HandshakeProof.nonce()))));
            assertThat(MessageCodec.decode(reader.read())).isInstanceOfSatisfying(Goodbye.class,
                    g -> assertThat(g.code()).isEqualTo(ErrorCodes.UNAUTHORIZED));
        }
        assertThat(sessions).isEmpty();
    }

    @Test
    void anOldAgentIsRefusedWithVersionMismatch() throws Exception {
        core(Duration.ofSeconds(5));
        try (Socket socket = raw()) {
            // Protocol 1's hello carried the token itself.
            writer(socket).write("{\"type\":\"hello\",\"protocolVersion\":1,\"token\":\"" + TOKEN
                    + "\",\"agent\":" + MessageCodec.gson().toJson(INFO) + "}");
            assertThat(MessageCodec.decode(reader(socket).read())).isInstanceOfSatisfying(Goodbye.class,
                    g -> assertThat(g.code()).isEqualTo(ErrorCodes.VERSION_MISMATCH));
        }
        assertThat(sessions).isEmpty();
    }

    @Test
    void noHandshakeWithinTheDeadlineIsClosed() throws Exception {
        core(DEADLINE);
        try (Socket socket = raw()) {
            long start = System.nanoTime();
            assertThat(untilClosedOrReset(socket)).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        }
        assertThat(sessions).isEmpty();
    }

    @Test
    void aHelloTrickledSlowerThanTheDeadlineIsClosed() throws Exception {
        // Each byte comes well within the read timeout; the handshake as a whole does not.
        core(DEADLINE);
        try (Socket socket = raw()) {
            OutputStream out = socket.getOutputStream();
            Thread trickle = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        out.write('{');
                        out.flush();
                        Thread.sleep(100);
                    }
                } catch (IOException e) {
                    // Closed by the core: what this test waits for.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(untilClosedOrReset(socket)).isEmpty();
            trickle.interrupt();
        }
        assertThat(sessions).isEmpty();
    }

    @Test
    void anHttpRequestGetsNoAnswerAndIsClosed() throws Exception {
        core(Duration.ofSeconds(5));
        try (Socket socket = raw()) {
            String body = MessageCodec.encode(new Hello(ProtocolVersion.CURRENT, HandshakeProof.nonce(), INFO));
            // What a web page can make a browser send to the loopback port: the JSON line only in the body.
            socket.getOutputStream().write(("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: " + (body.length() + 1) + "\r\n\r\n" + body + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            assertThat(untilClosedOrReset(socket)).isEmpty();
        }
        assertThat(rejections.poll(5, TimeUnit.SECONDS)).contains("HTTP");
        assertThat(sessions).isEmpty();
    }

    @Test
    void anOversizedHelloIsRefused() throws Exception {
        core(Duration.ofSeconds(5));
        try (Socket socket = raw()) {
            byte[] line = ("{\"type\":\"hello\",\"nonce\":\"" + "x".repeat(Limits.MAX_HANDSHAKE_FRAME_BYTES) + "\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
            try {
                socket.getOutputStream().write(line);
            } catch (IOException e) {
                // The core may close before it has read it all.
            }
            String answer = untilClosedOrReset(socket);
            assertThat(answer).doesNotContain("challenge");
        }
        assertThat(rejections.poll(5, TimeUnit.SECONDS)).contains("frame larger than " + Limits.MAX_HANDSHAKE_FRAME_BYTES);
        assertThat(sessions).isEmpty();
    }

    /** What the peer sent until it closed (or reset). Fails on a read timeout: the peer kept it open. */
    private static String untilClosedOrReset(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder text = new StringBuilder();
        byte[] buffer = new byte[4096];
        try {
            for (int n; (n = in.read(buffer)) != -1; ) {
                text.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
            }
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            // A reset: the core closed with our unread bytes still in its buffer.
        }
        return text.toString();
    }

    @Test
    void connectionsWaitingForTheirHandshakeAreCapped() throws Exception {
        core(Duration.ofSeconds(30));
        List<Socket> idle = new ArrayList<>();
        try {
            for (int i = 0; i < 40; i++) {
                idle.add(raw());
            }
            assertThat(rejections.poll(5, TimeUnit.SECONDS)).isEqualTo("too many connections waiting for their handshake");
        } finally {
            for (Socket s : idle) {
                s.close();
            }
        }
    }

    // ---- the agent's side: a process on the core's port that does not hold the token ----

    /** A fake core: answers the hello with a challenge, then with {@code welcome}, then sends a request. */
    private static CompletableFuture<List<String>> fakeCore(ServerSocket listener, boolean challenge, String proof) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> received = new ArrayList<>();
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(5_000);
                FrameReader reader = reader(socket);
                FrameWriter writer = writer(socket);
                received.add(reader.read());
                if (challenge) {
                    writer.write(MessageCodec.encode(new Challenge(HandshakeProof.nonce())));
                    received.add(reader.read());
                }
                writer.write(MessageCodec.encode(new Welcome(ProtocolVersion.CURRENT, "fake", proof)));
                writer.write(MessageCodec.encode(new Request(1, "server.info", new RawJson("{}"))));
                for (String line; (line = reader.read()) != null; ) {
                    received.add(line);
                }
            } catch (IOException e) {
                // The agent hung up.
            }
            return received;
        }, r -> Thread.ofVirtual().start(r));
    }

    private static void agentRefuses(boolean challenge, String proof) throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        OpRouter router = new OpRouter().on(Ops.SERVER_INFO, p -> {
            ran.set(true);
            return new Ops.ServerInfo("paper", "test", "26.3", List.of(), 0, 20);
        });
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<List<String>> seen = fakeCore(listener, challenge, proof);
            assertThatThrownBy(() -> AgentClient.connect("127.0.0.1", listener.getLocalPort(), TOKEN,
                    new AgentInfo("paper", "test", "26.3", router.operations()), router, new Connection.Listener() {
                    }, Duration.ofSeconds(5)))
                    .isInstanceOf(ProtocolException.class);
            List<String> lines = seen.get(10, TimeUnit.SECONDS);
            assertThat(String.join("\n", lines)).doesNotContain(TOKEN);
            for (String line : lines) {
                Message m = MessageCodec.decode(line);
                assertThat(m).isInstanceOfAny(Hello.class, Proof.class);
            }
        }
        assertThat(ran).as("the fake core's request ran").isFalse();
    }

    @Test
    void anAgentNeverSendsTheTokenAndRefusesACoreWithoutIt() throws Exception {
        agentRefuses(true, "00".repeat(32));
    }

    @Test
    void anAgentRefusesACoreThatSkipsTheChallenge() throws Exception {
        // A protocol-1 style core: welcome at once, then a request.
        agentRefuses(false, null);
    }

    @Test
    void anAgentRefusesACoreThatNeverAnswers() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> AgentClient.connect("127.0.0.1", listener.getLocalPort(), TOKEN, INFO,
                    RequestHandler.none(), new Connection.Listener() {
                    }, DEADLINE)).isInstanceOf(IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        }
    }

    @Test
    void anEmptyTokenIsRefusedAtStart() {
        assertThatThrownBy(() -> CoreServer.start(0, "", RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession session) {
            }
        }, DEADLINE)).isInstanceOf(IllegalArgumentException.class);
    }
}
