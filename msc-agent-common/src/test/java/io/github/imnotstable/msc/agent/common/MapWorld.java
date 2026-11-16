package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory world whose block states are their own strings, overworld height (-64..319), all air
 * until set. Also the server's only world. Parsing accepts {@code minecraft:<id>[props]} for any id
 * except {@code minecraft:not_a_block}.
 */
final class MapWorld implements AgentWorld<String>, AgentWorlds<String> {
    static final String AIR = "minecraft:air";

    final Map<Long, String> blocks = new HashMap<>();
    final List<Ops.EntitySummary> entities = new ArrayList<>();
    final List<Ops.EntityInfo> entityInfos = new ArrayList<>();
    final Map<Long, Ops.SignText> signs = new HashMap<>();
    /** Block entity data as SNBT; only chests keep it here. A merge replaces it, which is enough for these tests. */
    final Map<Long, String> data = new HashMap<>();
    final List<String> loads = new ArrayList<>();
    int writes;
    boolean lastPhysics;

    @Override
    public String name() {
        return "world";
    }

    @Override
    public int minY() {
        return -64;
    }

    @Override
    public int maxY() {
        return 320;
    }

    @Override
    public String get(int x, int y, int z) {
        return blocks.getOrDefault(PackedPos.pack(x, y, z), AIR);
    }

    @Override
    public void set(int x, int y, int z, String state, boolean physics) {
        writes++;
        lastPhysics = physics;
        signs.remove(PackedPos.pack(x, y, z)); // like a real world: a new block drops the old block entity
        data.remove(PackedPos.pack(x, y, z));
        if (state.equals(AIR)) {
            blocks.remove(PackedPos.pack(x, y, z));
        } else {
            blocks.put(PackedPos.pack(x, y, z), state);
        }
    }

    @Override
    public String serialize(String state) {
        return state;
    }

    @Override
    public int surfaceY(int x, int z) {
        for (int y = maxY() - 1; y >= minY(); y--) {
            if (!get(x, y, z).equals(AIR)) {
                return y;
            }
        }
        return minY() - 1;
    }

    @Override
    public List<Ops.EntitySummary> entities(Region region, int max) {
        return entities.stream().filter(e -> region.contains((int) Math.floor(e.x()), (int) Math.floor(e.y()),
                (int) Math.floor(e.z()))).limit(max).toList();
    }

    @Override
    public List<Ops.EntityInfo> entityInfos(Region region, String type) {
        return entityInfos.stream().filter(e -> type == null || type.equals(e.type()))
                .filter(e -> region.contains((int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z())))
                .toList();
    }

    /** Sign text and chest data live outside the state, as on Paper and the mod loaders. */
    @Override
    public Object blockEntity(int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        return signs.containsKey(p) || data.containsKey(p) ? new Kept(signs.get(p), data.get(p)) : null;
    }

    @Override
    public void loadBlockEntity(int x, int y, int z, Object saved) {
        long p = PackedPos.pack(x, y, z);
        Kept kept = (Kept) saved;
        if (kept.sign() != null) {
            signs.put(p, kept.sign());
        }
        if (kept.data() == null) {
            data.remove(p);
        } else {
            data.put(p, kept.data());
        }
    }

    record Kept(Ops.SignText sign, String data) {
    }

    @Override
    public boolean holdsData(String state) {
        return state.contains("chest");
    }

    @Override
    public String blockData(int x, int y, int z) {
        return data.get(PackedPos.pack(x, y, z));
    }

    @Override
    public void mergeBlockData(int x, int y, int z, Object patch) {
        data.put(PackedPos.pack(x, y, z), (String) patch);
    }

    @Override
    public Object parseData(String state, String snbt) {
        if (!holdsData(state)) {
            throw new IllegalArgumentException(state + " has no block entity data");
        }
        if (!snbt.startsWith("{") || !snbt.endsWith("}")) {
            throw new IllegalArgumentException("bad SNBT " + snbt);
        }
        return snbt;
    }

    @Override
    public Ops.SignText sign(int x, int y, int z) {
        return signs.get(PackedPos.pack(x, y, z));
    }

    @Override
    public void loadArea(int minX, int minZ, int maxX, int maxZ) {
        loads.add("area " + minX + "," + minZ + ".." + maxX + "," + maxZ);
    }

    @Override
    public Runnable loadAreaToRead(int minX, int minZ, int maxX, int maxZ) {
        loadArea(minX, minZ, maxX, maxZ);
        return () -> loads.add("released");
    }

    @Override
    public void loadPositions(long[] positions) {
        loads.add("positions " + positions.length);
    }

    // ---- AgentWorlds: this is the only world ----

    @Override
    public List<String> names() {
        return List.of(name());
    }

    @Override
    public Optional<MapWorld> byName(String name) {
        return name.equals(name()) ? Optional.of(this) : Optional.empty();
    }

    @Override
    public String parse(String state) {
        String s = state.strip();
        if (!s.startsWith("minecraft:") || s.startsWith("minecraft:not_a_block")) {
            throw new IllegalArgumentException("unknown block state: " + state);
        }
        return s;
    }
}
