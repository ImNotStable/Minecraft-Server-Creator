package io.github.imnotstable.msc.agent.vanilla;

import io.github.imnotstable.msc.agent.common.AgentWorld;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

/** {@link AgentWorld} on a {@code ServerLevel}. Main thread only. */
final class VanillaLevel implements AgentWorld<BlockState> {
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

    VanillaLevel(ServerLevel level, String name) {
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

    /** The block entity's data (sign text, container items), which a {@code BlockState} leaves out. */
    @Override
    public Object blockEntity(int x, int y, int z) {
        BlockEntity entity = level.getBlockEntity(pos.set(x, y, z));
        return entity == null ? null : save(entity, level.registryAccess());
    }

    @Override
    public void loadBlockEntity(int x, int y, int z, Object data) {
        BlockEntity entity = level.getBlockEntity(pos.set(x, y, z));
        if (entity != null) {
            load(entity, (CompoundTag) data, level.registryAccess());
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public boolean holdsData(BlockState state) {
        return state.hasBlockEntity();
    }

    @Override
    public String blockData(int x, int y, int z) {
        BlockEntity entity = level.getBlockEntity(pos.set(x, y, z));
        return entity == null ? null : data(entity, level.registryAccess());
    }

    @Override
    public void mergeBlockData(int x, int y, int z, Object patch) {
        BlockEntity entity = level.getBlockEntity(pos.set(x, y, z));
        if (entity == null) {
            throw new IllegalArgumentException("no block entity at " + x + "," + y + "," + z);
        }
        merge(entity, (CompoundTag) patch, level.registryAccess());
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    /** The data as SNBT without id and position, or null when the block entity saves nothing (an empty chest). */
    static String data(BlockEntity entity, HolderLookup.Provider registries) {
        CompoundTag tag = entity.saveWithoutMetadata(registries);
        return tag.isEmpty() ? null : tag.toString();
    }

    /** What {@code /data merge block} does: the saved data with the patch merged in, loaded back. */
    static void merge(BlockEntity entity, CompoundTag patch, HolderLookup.Provider registries) {
        CompoundTag merged = save(entity, registries).copy().merge(patch);
        load(entity, merged, registries);
    }

    static CompoundTag save(BlockEntity entity, HolderLookup.Provider registries) {
        return entity.saveWithFullMetadata(registries);
    }

    static void load(BlockEntity entity, CompoundTag data, HolderLookup.Provider registries) {
        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, data));
        entity.setChanged();
    }

    /** Block states are interned singletons, so identity is equality. */
    @Override
    public boolean sameState(BlockState current, BlockState target) {
        return current == target;
    }

    @Override
    public String serialize(BlockState state) {
        return BlockStates.serialize(state);
    }

    @Override
    public int surfaceY(int x, int z) {
        // Level.getHeight answers minY for a chunk that is not loaded (26.x keeps no spawn chunks loaded), so load
        // the chunk; on a chunk the answer is already the top block's Y.
        return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    }

    @Override
    public List<Ops.EntitySummary> entities(Region region, int max) {
        List<Ops.EntitySummary> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box(region), e -> inside(region, e))) {
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
        List<Ops.EntityInfo> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box(region), e -> inside(region, e))) {
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

    @Override
    public Ops.SignText sign(int x, int y, int z) {
        if (!(level.getBlockEntity(pos.set(x, y, z)) instanceof SignBlockEntity sign)) {
            return null;
        }
        return Ops.SignText.of(x, y, z, lines(sign.getText(SignTextSlot.FRONT)), lines(sign.getText(SignTextSlot.BACK)));
    }

    /** Candidates whose hitbox meets the region; {@link #inside} then keeps those whose position is in it. */
    private static AABB box(Region region) {
        return new AABB(region.min().x(), region.min().y(), region.min().z(),
                region.max().x() + 1, region.max().y() + 1, region.max().z() + 1);
    }

    /** An entity is in a region by its position, as on every platform, so split reads count it once. */
    private static boolean inside(Region region, Entity e) {
        return region.containsPoint(e.getX(), e.getY(), e.getZ());
    }

    private static List<String> lines(SignText text) {
        List<String> out = new ArrayList<>(SignText.LINES);
        for (Component line : text.getMessages(false)) {
            out.add(line.getString());
        }
        return out;
    }

    private static String name(Entity e) {
        if (e instanceof Player p) {
            return p.getPlainTextName();
        }
        Component custom = e.getCustomName();
        if (custom == null && e instanceof Display.TextDisplay) {
            // A hologram's words are its text, not a name: report them so a render or summary can show them.
            return displayText(e.getEntityData().getNonDefaultValues());
        }
        return custom == null ? null : custom.getString();
    }

    /**
     * The text of a text display from its synched data ({@code getText()} is private). Its text is the only
     * {@code COMPONENT} value a text display has; the custom name is an optional component.
     *
     * @param data the entity's non-default values, null when all are default (empty text)
     */
    static String displayText(List<SynchedEntityData.DataValue<?>> data) {
        if (data != null) {
            for (SynchedEntityData.DataValue<?> value : data) {
                if (value.serializer() == EntityDataSerializers.COMPONENT && value.value() instanceof Component text) {
                    return text.getString();
                }
            }
        }
        return null;
    }
}
