package io.github.imnotstable.msc.core;

import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An agent with a world held in memory: stone below y=64, air above, plus whatever was written.
 * Stands in for msc-agent-paper so the core's tools can be tested without a Minecraft server.
 */
public final class FakeAgent {
    static final String AIR = "minecraft:air";
    static final String STONE = "minecraft:stone";
    /** The Minecraft version SERVER_INFO reports. */
    volatile String minecraft = "26.3";

    final Map<BlockPos, String> blocks = new HashMap<>();
    final List<String> commands = new ArrayList<>();
    final List<Ops.PackParams> packs = new ArrayList<>();
    final List<Region> reads = new ArrayList<>();
    final List<Ops.EntityInfo> entities = new ArrayList<>();
    final List<Region> writes = new ArrayList<>();
    private final Map<String, Map<BlockPos, String>> undo = new HashMap<>();
    private int editCounter;

    public synchronized String get(int x, int y, int z) {
        String s = blocks.get(new BlockPos(x, y, z));
        return s != null ? s : y < 64 ? STONE : AIR;
    }

    private long put(Map<BlockPos, String> before, int x, int y, int z, String state) {
        String old = get(x, y, z);
        if (old.equals(state)) {
            return 0;
        }
        BlockPos p = new BlockPos(x, y, z);
        before.putIfAbsent(p, old);
        blocks.put(p, state);
        return 1;
    }

    /** Like a real agent, refuse unknown blocks before placing anything. Ids starting with no_such are unknown. */
    private static String known(String state) {
        if (state.startsWith("minecraft:no_such")) {
            throw new IllegalArgumentException("unknown block " + state.substring(10));
        }
        return state;
    }

    private Ops.EditResult finish(Map<BlockPos, String> before, long changed) {
        String id = "agent-" + (++editCounter);
        undo.put(id, before);
        return new Ops.EditResult(id, changed);
    }

    public OpRouter router() {
        return new OpRouter()
                .on(Ops.SERVER_INFO, p -> new Ops.ServerInfo("paper", "fake", minecraft, List.of("world"), 0, 20.0))
                .on(Ops.SERVER_COMMAND, p -> {
                    synchronized (this) {
                        commands.add(p.command());
                    }
                    if (p.command().startsWith("broadcast ")) {
                        // Like /say on Paper: the text goes to the console log, the sender gets nothing back.
                        System.out.println("[12:00:05 INFO]: [Server] " + p.command().substring(10));
                        return new Ops.CommandResult(true, List.of());
                    }
                    return new Ops.CommandResult(true, List.of("ran " + p.command()));
                })
                .on(Ops.WORLD_READ, this::read)
                .on(Ops.WORLD_ENTITIES, this::entities)
                .on(Ops.WORLD_HEIGHTMAP, this::heightmap)
                .on(Ops.WORLD_WRITE, this::write)
                .on(Ops.WORLD_UNDO, this::undo)
                .on(Ops.PACK_APPLY, p -> {
                    synchronized (this) {
                        packs.add(p);
                    }
                    return new Ops.PackResult(1);
                });
    }

    public AgentClient.Session connect(String address, String token) throws Exception {
        OpRouter router = router();
        AgentInfo info = new AgentInfo("paper", "fake", "26.3", router.operations());
        int colon = address.lastIndexOf(':');
        return AgentClient.connect(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)), token,
                info, router, new Connection.Listener() {
                }, Duration.ofSeconds(5));
    }

    private synchronized Ops.ReadResult read(Ops.ReadParams p) {
        Region r = p.region().requireWithinLimit();
        reads.add(r);
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        int[] out = new int[(int) r.volume()];
        int i = 0;
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String s = get(x, y, z);
                    Integer idx = index.get(s);
                    if (idx == null) {
                        idx = palette.size();
                        palette.add(s);
                        index.put(s, idx);
                    }
                    out[i++] = idx;
                }
            }
        }
        List<Ops.EntitySummary> entities = p.includeEntities()
                ? List.of(new Ops.EntitySummary("minecraft:cow", 1.5, 64, 1.5, null)) : List.of();
        BlockGrid grid = new BlockGrid(r, palette, out);
        // Every sign the fake world holds reads "Welcome" on its front.
        List<Ops.SignText> signs = p.includeSigns() ? Ops.SignText.positions(grid).stream()
                .map(s -> new Ops.SignText(s.x(), s.y(), s.z(), List.of("Welcome"), null)).toList() : List.of();
        return new Ops.ReadResult(grid, entities, signs);
    }

    private synchronized Ops.EntitiesResult entities(Ops.EntitiesParams p) {
        Region r = p.region();
        List<Ops.EntityInfo> found = entities.stream()
                .filter(e -> p.type() == null || p.type().equals(e.type()))
                .filter(e -> e.x() >= r.min().x() && e.x() < r.max().x() + 1 && e.y() >= r.min().y()
                        && e.y() < r.max().y() + 1 && e.z() >= r.min().z() && e.z() < r.max().z() + 1)
                .toList();
        return new Ops.EntitiesResult(found.subList(0, Math.min(p.limit(), found.size())), found.size());
    }

    private synchronized Ops.HeightmapResult heightmap(Ops.HeightmapParams p) {
        Region r = new Region(p.world(), p.min(), p.max());
        int[] heights = new int[r.sizeX() * r.sizeZ()];
        int i = 0;
        for (int z = r.min().z(); z <= r.max().z(); z++) {
            for (int x = r.min().x(); x <= r.max().x(); x++) {
                int top = 63;
                for (Map.Entry<BlockPos, String> e : blocks.entrySet()) {
                    BlockPos b = e.getKey();
                    if (b.x() == x && b.z() == z && !e.getValue().equals(AIR)) {
                        top = Math.max(top, b.y());
                    }
                }
                heights[i++] = top;
            }
        }
        return new Ops.HeightmapResult(r.min().x(), r.min().z(), r.sizeX(), r.sizeZ(), heights);
    }

    private synchronized Ops.EditResult write(Ops.WriteParams p) {
        Region r = p.grid().region().requireWithinLimit();
        p.grid().palette().forEach(FakeAgent::known);
        writes.add(r);
        Map<BlockPos, String> before = new HashMap<>();
        long changed = 0;
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String s = p.grid().stateAt(x, y, z);
                    if (s != null) {
                        changed += put(before, x, y, z, s);
                    }
                }
            }
        }
        return finish(before, changed);
    }

    private synchronized Ops.EditResult undo(Ops.UndoParams p) {
        Map<BlockPos, String> before = undo.remove(p.editId());
        if (before == null) {
            throw new OpException(ErrorCodes.NOT_FOUND, "no edit " + p.editId());
        }
        before.forEach(blocks::put);
        return new Ops.EditResult(p.editId(), before.size());
    }
}
