package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Ping;
import io.github.imnotstable.msc.protocol.message.Pong;
import io.github.imnotstable.msc.protocol.message.RawJson;
import io.github.imnotstable.msc.protocol.message.Request;
import io.github.imnotstable.msc.protocol.message.Response;
import io.github.imnotstable.msc.protocol.message.Welcome;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.ConnectionClosedException;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.FrameReader;
import io.github.imnotstable.msc.protocol.transport.FrameWriter;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String TOKEN = "secret-token";

    private final BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> rejections = new LinkedBlockingQueue<>();
    private CoreServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = CoreServer.start(0, TOKEN, RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession session) {
                sessions.add(session);
            }

            @Override
            public void event(CoreServer.AgentSession session, Event event) {
                events.add(event);
            }

            @Override
            public void rejected(String reason) {
                rejections.add(reason);
            }
        }, TIMEOUT);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
    }

    private static OpRouter fakeAgent() {
        return new OpRouter()
                .on(Ops.SERVER_INFO, p -> new Ops.ServerInfo("paper", "test", "26.3", List.of("world"), 0, 20.0))
                .on(Ops.WORLD_READ, p -> {
                    Region r = p.region().requireWithinLimit();
                    int[] blocks = new int[(int) r.volume()];
                    return new Ops.ReadResult(new BlockGrid(r, List.of("minecraft:stone"), blocks), List.of());
                })
                .on(Ops.SERVER_COMMAND, p -> {
                    throw new IllegalStateException("boom");
                });
    }

    private AgentClient.Session connect(String token, OpRouter router) throws Exception {
        AgentInfo info = new AgentInfo("paper", "test", "26.3", router.operations());
        return AgentClient.connect("127.0.0.1", server.port(), token, info, router, new Connection.Listener() {
        }, TIMEOUT);
    }

    @Test
    void handshakeThenTypedCalls() throws Exception {
        AgentClient.Session agent = connect(TOKEN, fakeAgent());
        CoreServer.AgentSession session = sessions.poll(5, TimeUnit.SECONDS);
        assertThat(session).isNotNull();
        assertThat(session.sessionId()).isEqualTo(agent.sessionId());
        assertThat(session.agent().operations()).contains("server.info", "world.read");

        Ops.ServerInfo info = session.connection().call(Ops.SERVER_INFO, new Ops.Empty(), TIMEOUT).get();
        assertThat(info.minecraftVersion()).isEqualTo("26.3");

        Ops.ReadResult read = session.connection()
                .call(Ops.WORLD_READ, new Ops.ReadParams(Region.of("world", 0, 0, 0, 3, 3, 3), false), TIMEOUT).get();
        assertThat(read.grid().blocks()).hasSize(64);
        assertThat(read.grid().stateAt(1, 2, 3)).isEqualTo("minecraft:stone");

        assertThat(session.connection().ping(TIMEOUT).get()).isPositive();
        agent.connection().close();
    }

    @Test
    void errorsComeBackAsCodes() throws Exception {
        connect(TOKEN, fakeAgent());
        Connection core = sessions.poll(5, TimeUnit.SECONDS).connection();

        assertCode(core.call(Ops.WORLD_UNDO, new Ops.UndoParams("x"), TIMEOUT), ErrorCodes.UNKNOWN_OP);
        assertCode(core.call(Ops.SERVER_COMMAND, new Ops.CommandParams("x"), TIMEOUT), ErrorCodes.INTERNAL);
        Region huge = Region.of("world", 0, 0, 0, 999, 255, 999);
        assertCode(core.call(Ops.WORLD_READ, new Ops.ReadParams(huge, false), TIMEOUT), ErrorCodes.INVALID_PARAMS);
        assertCode(core.request("world.read", new JsonObject(), TIMEOUT)
                .thenApply(r -> {
                    throw new OpException(r.error());
                }), ErrorCodes.INVALID_PARAMS);
    }

    @Test
    void handlerErrorIsAnsweredAsInternal() throws Exception {
        OpRouter broken = new OpRouter().on(Ops.SERVER_INFO, p -> {
            throw new NoSuchMethodError("gone");
        });
        connect(TOKEN, broken);
        Connection core = sessions.poll(5, TimeUnit.SECONDS).connection();
        CompletableFuture<Ops.ServerInfo> call = core.call(Ops.SERVER_INFO, new Ops.Empty(), TIMEOUT);
        assertCode(call, ErrorCodes.INTERNAL);
        assertThat(call.exceptionNow()).hasMessageContaining("NoSuchMethodError");
    }

    @Test
    void malformedResultFailsOnlyThatCall() throws Exception {
        RequestHandler handler = (op, params) -> op.equals("server.info")
                ? Ops.SERVER_INFO.encodeResult(new Ops.ServerInfo("paper", "1", "26.3", List.of(), 0, 20))
                : new RawJson("{\"grid\":}");
        AgentInfo info = new AgentInfo("paper", "test", "26.3", List.of("server.info", "world.read"));
        AgentClient.connect("127.0.0.1", server.port(), TOKEN, info, handler, new Connection.Listener() {
        }, TIMEOUT);
        Connection core = sessions.poll(5, TimeUnit.SECONDS).connection();
        Region small = Region.of("world", 0, 0, 0, 0, 0, 0);
        assertCode(core.call(Ops.WORLD_READ, new Ops.ReadParams(small, false), TIMEOUT), ErrorCodes.BAD_FRAME);
        assertThat(core.call(Ops.SERVER_INFO, new Ops.Empty(), TIMEOUT).get().platform()).isEqualTo("paper");
    }

    @Test
    void badFrameAfterHandshakeGetsAGoodbye() throws Exception {
        AgentInfo info = new AgentInfo("paper", "test", "26.3", List.of());
        for (int i = 0; i < 10; i++) {
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5_000);
                FrameWriter writer = new FrameWriter(socket.getOutputStream(), Limits.MAX_FRAME_BYTES);
                FrameReader reader = new FrameReader(socket.getInputStream(), Limits.MAX_FRAME_BYTES);
                writer.write(MessageCodec.encode(new Hello(ProtocolVersion.CURRENT, TOKEN, info)));
                assertThat(MessageCodec.decode(reader.read())).isInstanceOf(Welcome.class);
                writer.write("this is not json");
                String frame = reader.read();
                assertThat(frame).as("goodbye before close").isNotNull();
                assertThat(MessageCodec.decode(frame)).isInstanceOfSatisfying(Goodbye.class,
                        g -> assertThat(g.code()).isEqualTo(ErrorCodes.BAD_FRAME));
            }
        }
    }

    @Test
    void eventsReachTheCore() throws Exception {
        AgentClient.Session agent = connect(TOKEN, fakeAgent());
        agent.connection().sendEvent(Events.SERVER_READY, new JsonObject());
        Event event = events.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.name()).isEqualTo(Events.SERVER_READY);
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        assertThatThrownBy(() -> connect("wrong", fakeAgent()))
                .isInstanceOf(OpException.class)
                .satisfies(e -> assertThat(((OpException) e).code()).isEqualTo(ErrorCodes.UNAUTHORIZED));
        assertThat(rejections.poll(5, TimeUnit.SECONDS)).startsWith(ErrorCodes.UNAUTHORIZED);
        assertThat(sessions).isEmpty();
    }

    @Test
    void closingFailsPendingCalls() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        OpRouter slow = new OpRouter().on(Ops.SERVER_INFO, p -> {
            entered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        AgentClient.Session agent = connect(TOKEN, slow);
        Connection core = sessions.poll(5, TimeUnit.SECONDS).connection();
        CompletableFuture<Ops.ServerInfo> call = core.call(Ops.SERVER_INFO, new Ops.Empty(), TIMEOUT);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        agent.connection().close();
        assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ConnectionClosedException.class);
    }

    @Test
    void answersJustBeforeAGoodbyeStillArrive() throws Exception {
        AgentInfo info = new AgentInfo("paper", "test", "26.3", List.of("server.info"));
        for (int i = 0; i < 300; i++) {
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5_000);
                FrameWriter writer = new FrameWriter(socket.getOutputStream(), Limits.MAX_FRAME_BYTES);
                FrameReader reader = new FrameReader(socket.getInputStream(), Limits.MAX_FRAME_BYTES);
                writer.write(MessageCodec.encode(new Hello(ProtocolVersion.CURRENT, TOKEN, info)));
                assertThat(MessageCodec.decode(reader.read())).isInstanceOf(Welcome.class);
                Connection core = sessions.poll(5, TimeUnit.SECONDS).connection();
                CompletableFuture<Response> call = core.request("server.info", new JsonObject(), TIMEOUT);
                CompletableFuture<Duration> ping = core.ping(TIMEOUT);
                Request request = (Request) MessageCodec.decode(reader.read());
                Ping sent = (Ping) MessageCodec.decode(reader.read());
                // Response, pong and goodbye in one write, so the core reads them in one go.
                String burst = MessageCodec.encode(Response.ok(request.id(), new JsonObject())) + "\n"
                        + MessageCodec.encode(new Pong(sent.nonce())) + "\n"
                        + MessageCodec.encode(new Goodbye(ErrorCodes.SHUTDOWN, "bye")) + "\n";
                socket.getOutputStream().write(burst.getBytes(StandardCharsets.UTF_8));
                assertThat(call.get(5, TimeUnit.SECONDS).isOk()).as("response %d", i).isTrue();
                assertThat(ping.get(5, TimeUnit.SECONDS)).as("pong %d", i).isNotNull();
            }
        }
    }

    private static void assertCode(CompletableFuture<?> future, String code) {
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((OpException) e.getCause()).code()).isEqualTo(code));
    }
}
