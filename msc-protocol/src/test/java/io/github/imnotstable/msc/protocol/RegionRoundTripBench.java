package io.github.imnotstable.msc.protocol;

import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Times a {@code world.read} of one full-size region (128 x 64 x 128) from core to agent and back over loopback,
 * the path every large read pays. Not a test, so the normal build stays fast. Run it after
 * {@code ./gradlew :msc-protocol:testClasses} with the test and main classes plus Gson on the class path:
 * {@code java -cp ... io.github.imnotstable.msc.protocol.RegionRoundTripBench [rounds]}.
 */
public final class RegionRoundTripBench {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Region FULL = Region.of("world", 0, 0, 0, 127, 63, 127);

    private RegionRoundTripBench() {
    }

    public static void main(String[] args) throws Exception {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
        try (CoreServer server = CoreServer.start(0, "t", RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession session) {
                sessions.add(session);
            }
        }, TIMEOUT)) {
            Ops.ReadResult reply = fullRegion();
            OpRouter router = new OpRouter().on(Ops.WORLD_READ, p -> reply);
            AgentClient.connect("127.0.0.1", server.port(), "t", new AgentInfo("bench", "1", "26.3", router.operations()),
                    router, new Connection.Listener() {
                    }, TIMEOUT);
            Connection core = sessions.poll(10, TimeUnit.SECONDS).connection();
            Ops.ReadParams params = new Ops.ReadParams(FULL, false);
            for (int i = 0; i < 10; i++) {
                core.call(Ops.WORLD_READ, params, TIMEOUT).get();
            }
            long[] millis = new long[rounds];
            for (int i = 0; i < rounds; i++) {
                long start = System.nanoTime();
                Ops.ReadResult got = core.call(Ops.WORLD_READ, params, TIMEOUT).get();
                millis[i] = (System.nanoTime() - start) / 1_000_000;
                if (got.grid().blocks().length != FULL.volume()) {
                    throw new IllegalStateException("short reply");
                }
            }
            Arrays.sort(millis);
            System.out.printf("full-region world.read round trip, %d rounds: median %d ms, min %d ms, max %d ms%n",
                    rounds, millis[rounds / 2], millis[0], millis[rounds - 1]);
        }
    }

    static Ops.ReadResult fullRegion() {
        int[] blocks = new int[(int) FULL.volume()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = (i * 31) % 7;
        }
        List<String> palette = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block",
                "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:water");
        return new Ops.ReadResult(new BlockGrid(FULL, palette, blocks), List.of());
    }
}
