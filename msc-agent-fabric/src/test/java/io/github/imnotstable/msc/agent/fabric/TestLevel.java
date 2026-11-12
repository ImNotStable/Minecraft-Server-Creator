package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** An in-memory world, overworld height (-64..319), all air until set. */
final class TestLevel implements AgentLevel, Levels {
    final Map<Long, BlockState> blocks = new HashMap<>();
    final List<Ops.EntitySummary> entities = new ArrayList<>();
    final List<Ops.EntityInfo> entityInfos = new ArrayList<>();
    final Map<Long, Ops.SignText> signs = new HashMap<>();
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
    public BlockState get(int x, int y, int z) {
        return blocks.getOrDefault(PackedPos.pack(x, y, z), Blocks.AIR.defaultBlockState());
    }

    @Override
    public void set(int x, int y, int z, BlockState state, boolean physics) {
        writes++;
        lastPhysics = physics;
        if (state.isAir()) {
            blocks.remove(PackedPos.pack(x, y, z));
        } else {
            blocks.put(PackedPos.pack(x, y, z), state);
        }
    }

    @Override
    public int surfaceY(int x, int z) {
        for (int y = maxY() - 1; y >= minY(); y--) {
            if (!get(x, y, z).isAir()) {
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

    @Override
    public Ops.SignText sign(int x, int y, int z) {
        return signs.get(PackedPos.pack(x, y, z));
    }

    // ---- Levels: this is the only world ----

    @Override
    public List<String> names() {
        return List.of(name());
    }

    @Override
    public Optional<AgentLevel> byName(String name) {
        return name.equals(name()) ? Optional.of(this) : Optional.empty();
    }
}
