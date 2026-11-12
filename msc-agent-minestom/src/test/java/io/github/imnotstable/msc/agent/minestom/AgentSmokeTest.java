package io.github.imnotstable.msc.agent.minestom;

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
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real agent in a Minestom test server, talking to a real {@link CoreServer} over a socket. */
@EnvTest
class AgentSmokeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private CoreServer core;
    private MscAgent agent;

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
    }

    @AfterEach
    void tearDown() throws Exception {
        if (agent != null) {
            agent.stop();
        }
        System.clearProperty(CoreAddress.PROPERTY_ADDRESS);
        System.clearProperty(CoreAddress.PROPERTY_TOKEN);
        core.close();
    }

    /** Wait for a call while ticking the server, since the agent runs world work in ticks. */
    private static <T> T tickUntil(Env env, CompletableFuture<T> future) throws Exception {
        env.tickWhile(() -> !future.isDone(), TIMEOUT);
        return future.get(0, TimeUnit.SECONDS);
    }

    @Test
    void connectsAnswersOperationsAndSendsEvents(Env env) throws Exception {
        Instance world = env.createFlatInstance();
        MscAgent.name(world, "world");
        agent = MscAgent.start();

        CoreServer.AgentSession session = sessions.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(session).isNotNull();
        assertThat(session.agent().platform()).isEqualTo("minestom");
        assertThat(session.agent().minecraftVersion()).isEqualTo("26.2");
        assertThat(session.agent().operations()).containsExactlyInAnyOrderElementsOf(Ops.names());

        env.tick();
        Event ready = events.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(ready).isNotNull();
        assertThat(ready.name()).isEqualTo(Events.SERVER_READY);

        Ops.EditResult fill = tickUntil(env, session.connection().call(Ops.WORLD_WRITE,
                new Ops.WriteParams(new BlockGrid(Region.of("world", 0, 64, 0, 3, 64, 3), List.of("minecraft:stone"),
                        new int[16]), false), TIMEOUT));
        assertThat(fill.changed()).isEqualTo(16);

        Ops.ReadResult read = tickUntil(env, session.connection().call(Ops.WORLD_READ,
                new Ops.ReadParams(Region.of("world", 0, 64, 0, 3, 65, 3), true), TIMEOUT));
        assertThat(read.grid().stateAt(2, 64, 2)).isEqualTo("minecraft:stone");
        assertThat(read.grid().stateAt(2, 65, 2)).isEqualTo("minecraft:air");

        Ops.ServerInfo info = tickUntil(env, session.connection().call(Ops.SERVER_INFO, new Ops.Empty(), TIMEOUT));
        assertThat(info.worlds()).containsExactly("world");

        tickUntil(env, session.connection().call(Ops.WORLD_UNDO, new Ops.UndoParams(fill.editId()), TIMEOUT));
        assertThat(world.getBlock(2, 64, 2)).isEqualTo(Block.AIR);

        env.createPlayer(world, new Pos(0, 42, 0));
        Event join = events.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(join).isNotNull();
        assertThat(join.name()).isEqualTo(Events.PLAYER_JOIN);
        assertThat(join.data().get("name").getAsString()).isNotBlank();
    }
}
