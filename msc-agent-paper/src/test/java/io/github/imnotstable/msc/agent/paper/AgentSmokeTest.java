package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.agent.common.CoreAddress;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.event.server.ServerLoadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** The real plugin on MockBukkit, talking to a real {@link CoreServer} over a socket. */
class AgentSmokeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private CoreServer core;
    private ServerMock server;
    private WorldMock world;

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
        System.setProperty(CoreAddress.PROPERTY_ADDRESS, core.address());
        System.setProperty(CoreAddress.PROPERTY_TOKEN, "smoke-token");
        server = MockBukkit.mock();
        world = new WorldMock(Material.AIR, 0);
        world.setName("world");
        server.addWorld(world);
        MockBukkit.load(MscAgentPlugin.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        MockBukkit.unmock();
        System.clearProperty(CoreAddress.PROPERTY_ADDRESS);
        System.clearProperty(CoreAddress.PROPERTY_TOKEN);
        core.close();
    }

    /** Wait for a call while ticking the mock server, since the agent runs world work on the main thread. */
    private <T> T tickUntil(CompletableFuture<T> future) throws Exception {
        long end = System.nanoTime() + TIMEOUT.toNanos();
        while (!future.isDone() && System.nanoTime() < end) {
            server.getScheduler().performOneTick();
            Thread.sleep(2);
        }
        return future.get(0, TimeUnit.SECONDS);
    }

    @Test
    void connectsAnswersOperationsAndSendsEvents() throws Exception {
        CoreServer.AgentSession session = sessions.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(session).isNotNull();
        assertThat(session.agent().platform()).isEqualTo("paper");
        assertThat(session.agent().operations()).containsExactlyInAnyOrderElementsOf(Ops.names());

        server.getPluginManager().callEvent(new ServerLoadEvent(ServerLoadEvent.LoadType.STARTUP));
        Event ready = events.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(ready).isNotNull();
        assertThat(ready.name()).isEqualTo(Events.SERVER_READY);

        Ops.EditResult fill = tickUntil(session.connection().call(Ops.WORLD_WRITE,
                new Ops.WriteParams(new BlockGrid(Region.of("world", 0, 64, 0, 3, 64, 3), List.of("minecraft:stone"),
                        new int[16]), false), TIMEOUT));
        assertThat(fill.changed()).isEqualTo(16);

        Ops.ReadResult read = tickUntil(session.connection().call(Ops.WORLD_READ,
                new Ops.ReadParams(Region.of("world", 0, 64, 0, 3, 65, 3), true), TIMEOUT));
        assertThat(read.grid().stateAt(2, 64, 2)).isEqualTo("minecraft:stone");
        assertThat(read.grid().stateAt(2, 65, 2)).isEqualTo("minecraft:air");

        tickUntil(session.connection().call(Ops.WORLD_UNDO, new Ops.UndoParams(fill.editId()), TIMEOUT));
        assertThat(world.getBlockAt(2, 64, 2).getType()).isEqualTo(Material.AIR);

        server.addPlayer("Steve");
        Event join = events.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(join).isNotNull();
        assertThat(join.name()).isEqualTo(Events.PLAYER_JOIN);
        assertThat(join.data().get("name").getAsString()).isEqualTo("Steve");
    }
}
