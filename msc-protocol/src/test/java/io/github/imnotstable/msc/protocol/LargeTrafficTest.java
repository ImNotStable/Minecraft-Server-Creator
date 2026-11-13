package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Many multi-megabyte frames in both directions over a real loopback socket. Guards against the
 * read loop ever stopping to drain: a regression here shows up as a hang, so every test has a timeout.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LargeTrafficTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String TOKEN = "t";
    /**
     * 128 x 64 x 128 = the region limit; about 2 to 4 MB of JSON per reply. A few such frames in flight
     * at once are more than the loopback socket buffers hold, which is all these tests need; more
     * frames only add codec time.
     */
    private static final Region BIG = Region.of("world", 0, 0, 0, 127, 63, 127);

    private final BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch slowListener = new CountDownLatch(0);
    private CoreServer server;

    @BeforeEach
    void start() throws Exception {
        server = CoreServer.start(0, TOKEN, RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession session) {
                sessions.add(session);
            }

            @Override
            public void event(CoreServer.AgentSession session, Event event) {
                try {
                    slowListener.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                events.add(event);
            }
        }, TIMEOUT);
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private static Ops.ReadResult bigRead(Ops.ReadParams p) {
        Region r = p.region().requireWithinLimit();
        int[] blocks = new int[(int) r.volume()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = (i * 31) % 7;
        }
        List<String> palette = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block",
                "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:water");
        return new Ops.ReadResult(new BlockGrid(r, palette, blocks), List.of());
    }

    private AgentClient.Session agent() throws Exception {
        OpRouter router = new OpRouter()
                .on(Ops.WORLD_READ, LargeTrafficTest::bigRead)
                .on(Ops.WORLD_WRITE, p -> new Ops.EditResult("e", p.grid().blocks().length))
                .on(Ops.SERVER_INFO, p -> new Ops.ServerInfo("test", "1", "26.3", List.of("world"), 0, 20));
        return AgentClient.connect("127.0.0.1", server.port(), TOKEN,
                new AgentInfo("test", "1", "26.3", router.operations()), router, new Connection.Listener() {
                }, TIMEOUT);
    }

    @Test
    void manyLargeRepliesBackToBack() throws Exception {
        agent();
        Connection core = sessions.poll(10, TimeUnit.SECONDS).connection();
        for (int i = 0; i < 3; i++) {
            Ops.ReadResult r = core.call(Ops.WORLD_READ, new Ops.ReadParams(BIG, false), TIMEOUT).get();
            assertThat(r.grid().blocks()).hasSize((int) BIG.volume());
        }
    }

    @Test
    void largeFramesBothWaysAtOnce() throws Exception {
        AgentClient.Session agent = agent();
        Connection core = sessions.poll(10, TimeUnit.SECONDS).connection();
        Ops.ReadResult sample = bigRead(new Ops.ReadParams(BIG, false));
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            all.add(core.call(Ops.WORLD_READ, new Ops.ReadParams(BIG, false), TIMEOUT));
            all.add(core.call(Ops.WORLD_WRITE, new Ops.WriteParams(sample.grid(), false), TIMEOUT));
            all.add(core.ping(TIMEOUT));
            all.add(agent.connection().ping(TIMEOUT));
        }
        CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(100, TimeUnit.SECONDS);
    }

    @Test
    void blockingCallInsideACallbackDoesNotStallTheConnection() throws Exception {
        agent();
        Connection core = sessions.poll(10, TimeUnit.SECONDS).connection();
        // The dependent stage blocks on a second call on the same connection. If it ran on the read
        // thread, the second response could never be read.
        Ops.ServerInfo second = core.call(Ops.SERVER_INFO, new Ops.Empty(), TIMEOUT)
                .thenApply(first -> core.call(Ops.SERVER_INFO, new Ops.Empty(), TIMEOUT).join())
                .get(30, TimeUnit.SECONDS);
        assertThat(second.platform()).isEqualTo("test");
    }

    @Test
    void slowEventListenerDoesNotBlockReplies() throws Exception {
        slowListener = new CountDownLatch(1);
        AgentClient.Session agent = agent();
        Connection core = sessions.poll(10, TimeUnit.SECONDS).connection();
        JsonObject data = new JsonObject();
        data.addProperty("padding", "x".repeat(1024 * 1024));
        for (int i = 0; i < 10; i++) {
            data.addProperty("n", i);
            agent.connection().sendEvent("test.big", data.deepCopy());
        }
        // Events are stuck behind the listener, yet replies still arrive.
        Ops.ReadResult r = core.call(Ops.WORLD_READ, new Ops.ReadParams(BIG, false), TIMEOUT).get(60, TimeUnit.SECONDS);
        assertThat(r.grid().blocks()).hasSize((int) BIG.volume());
        slowListener.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (events.size() < 10 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(events).extracting(e -> e.data().get("n").getAsInt()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }
}
