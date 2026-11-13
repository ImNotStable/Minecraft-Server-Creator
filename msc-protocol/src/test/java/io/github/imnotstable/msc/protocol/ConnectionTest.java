package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.ConnectionClosedException;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
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

    private static void assertCode(CompletableFuture<?> future, String code) {
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((OpException) e.getCause()).code()).isEqualTo(code));
    }
}
