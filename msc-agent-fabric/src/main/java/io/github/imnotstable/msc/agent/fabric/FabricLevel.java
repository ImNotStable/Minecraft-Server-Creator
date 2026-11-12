package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** {@link AgentLevel} on a {@code ServerLevel}. Main thread only. */
final class FabricLevel implements AgentLevel {
    /**
     * Without physics a block lands exactly as written: clients are told, but neighbours don't
     * react, the block's own placement logic doesn't run, and replaced containers don't spill
     * their items. The Paper agent behaves the same.
     */
    static final int NO_PHYSICS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
    /** Normal block updates, still without spilling the contents of a replaced container. */
    static final int PHYSICS = Block.UPDATE_ALL | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    private final ServerLevel level;
    private final String name;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    FabricLevel(ServerLevel level, String name) {
        this.level = level;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int minY() {
        return level.getMinY();
    }

    @Override
    public int maxY() {
        return level.getMinY() + level.getHeight();
    }

    @Override
    public BlockState get(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    @Override
    public void set(int x, int y, int z, BlockState state, boolean physics) {
        level.setBlock(pos.set(x, y, z), state, physics ? PHYSICS : NO_PHYSICS);
    }

    @Override
    public int surfaceY(int x, int z) {
        // Level.getHeight answers minY for a chunk that is not loaded (26.x keeps no spawn chunks loaded), so load
        // the chunk; on a chunk the answer is already the top block's Y.
        return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    }

    @Override
    public List<Ops.EntitySummary> entities(Region region, int max) {
        AABB box = new AABB(region.min().x(), region.min().y(), region.min().z(),
                region.max().x() + 1, region.max().y() + 1, region.max().z() + 1);
        List<Ops.EntitySummary> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, e -> true)) {
            if (out.size() == max) {
                break;
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            out.add(new Ops.EntitySummary(type, e.getX(), e.getY(), e.getZ(), name(e)));
        }
        return out;
    }

    @Override
    public List<Ops.EntityInfo> entityInfos(Region region, String type) {
        AABB box = new AABB(region.min().x(), region.min().y(), region.min().z(),
                region.max().x() + 1, region.max().y() + 1, region.max().z() + 1);
        List<Ops.EntityInfo> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, e -> true)) {
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (type != null && !type.equals(key)) {
                continue;
            }
            Double health = null;
            Double maxHealth = null;
            if (e instanceof LivingEntity living) {
                health = (double) living.getHealth();
                maxHealth = (double) living.getMaxHealth();
            }
            out.add(new Ops.EntityInfo(key, e.getUUID().toString(), e.getX(), e.getY(), e.getZ(), name(e),
                    List.copyOf(new TreeSet<>(e.entityTags())), health, maxHealth));
        }
        return out;
    }

    private static String name(Entity e) {
        if (e instanceof Player p) {
            return p.getPlainTextName();
        }
        Component custom = e.getCustomName();
        return custom == null ? null : custom.getString();
    }
}
