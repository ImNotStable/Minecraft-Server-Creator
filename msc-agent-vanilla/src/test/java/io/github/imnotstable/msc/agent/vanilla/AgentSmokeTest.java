package io.github.imnotstable.msc.agent.vanilla;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.agent.common.AgentDefaults;
import io.github.imnotstable.msc.agent.common.AgentLink;
import io.github.imnotstable.msc.agent.common.AgentLog;
import io.github.imnotstable.msc.agent.common.AgentRoutes;
import io.github.imnotstable.msc.agent.common.CoreAddress;
import io.github.imnotstable.msc.agent.common.EditHistory;
import io.github.imnotstable.msc.agent.common.ServerOps;
import io.github.imnotstable.msc.agent.common.WorldOps;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import java.util.List;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent's real wiring (link, routes, operations, tick-driven main thread) against a real
 * {@link CoreServer} over a socket, with an in-memory world and a thread standing in for the server tick.
 */
class AgentSmokeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private CoreServer core;
    private TestLevel world;
    private TickMainThread main;
    private Thread ticker;
    private AgentLink link;

    @BeforeAll
    static void boot() {
        Minecraft.bootstrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        core = CoreServer.start(0, "smoke-token", RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession session) {
                sessions.add(session);
            }

            @Override
            public void event(CoreServer.AgentSession session, Event event) {
                events.add(event);
            }
        }, TIMEOUT);
        world = new TestLevel();
        main = new TickMainThread(() -> Thread.currentThread() == ticker, AgentDefaults.TICK_BUDGET, TIMEOUT);
        ticker = Thread.ofPlatform().daemon().name("test-server-tick").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                main.tick();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        TestServer server = new TestServer();
        ServerOps serverOps = new ServerOps(server, main);
        OpRouter router = AgentRoutes.router(serverOps, new WorldOps<>(world, main, new EditHistory<>(AgentDefaults.UNDO_BLOCKS)));
        AgentInfo info = new AgentInfo(server.platform(),
                server.platformVersion(), server.minecraftVersion(), router.operations());
        Optional<CoreAddress> address = CoreAddress.resolve(
                k -> k.equals("MSC_CORE_ADDRESS") ? core.address() : k.equals("MSC_TOKEN") ? "smoke-token" : null, k -> null);
        link = new AgentLink(info, router, () -> address, AgentLog.of(System.getLogger("msc-agent-test")));
        link.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        link.stop();
        main.stop();
        ticker.interrupt();
        core.close();
    }

    @Test
    void connectsAnswersOperationsAndSendsEvents() throws Exception {
        CoreServer.AgentSession session = sessions.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(session).isNotNull();
        assertThat(session.agent().platform()).isEqualTo("fabric");
        assertThat(session.agent().operations()).containsExactlyInAnyOrderElementsOf(Ops.names());

        link.serverLoaded();
        Event ready = events.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(ready).isNotNull();
        assertThat(ready.name()).isEqualTo(Events.SERVER_READY);

        Ops.EditResult fill = session.connection().call(Ops.WORLD_WRITE,
                new Ops.WriteParams(new BlockGrid(Region.of("world", 0, 64, 0, 3, 64, 3), List.of("minecraft:stone"),
                        new int[16]), false), TIMEOUT)
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(fill.changed()).isEqualTo(16);

        Ops.ReadResult read = session.connection().call(Ops.WORLD_READ,
                new Ops.ReadParams(Region.of("world", 0, 64, 0, 3, 65, 3), true), TIMEOUT)
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(read.grid().stateAt(2, 64, 2)).isEqualTo("minecraft:stone");
        assertThat(read.grid().stateAt(2, 65, 2)).isEqualTo("minecraft:air");

        session.connection().call(Ops.WORLD_UNDO, new Ops.UndoParams(fill.editId()), TIMEOUT)
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(world.get(2, 64, 2).is(Blocks.AIR)).isTrue();

        JsonObject steve = new JsonObject();
        steve.addProperty("name", "Steve");
        link.send(Events.PLAYER_JOIN, steve);
        Event join = events.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(join).isNotNull();
        assertThat(join.name()).isEqualTo(Events.PLAYER_JOIN);
        assertThat(join.data().get("name").getAsString()).isEqualTo("Steve");
    }
}
