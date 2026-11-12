package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.agent.common.AgentWorld;
import io.github.imnotstable.msc.agent.common.PackedPos;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * {@link AgentWorld} on a Minestom {@link Instance}. Minestom does not load chunks on access, so
 * the operations load (or generate) every chunk they touch first, off the tick thread; block
 * access then runs on the main thread.
 */
final class MinestomWorld implements AgentWorld<Block> {
    private final Instance instance;
    private final String name;
    private final Duration chunkTimeout;
    private final int minY;
    private final int maxY;

    MinestomWorld(Instance instance, String name, Duration chunkTimeout) {
        this.instance = instance;
        this.name = name;
        this.chunkTimeout = chunkTimeout;
        this.minY = instance.getCachedDimensionType().minY();
        this.maxY = minY + instance.getCachedDimensionType().height();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    /** The full block, NBT included, so undo puts sign text and other block data back. */
    @Override
    public Block get(int x, int y, int z) {
        return instance.getBlock(x, y, z);
    }

    @Override
    public Block peek(int x, int y, int z) {
        return instance.getBlock(x, y, z, Block.Getter.Condition.TYPE);
    }

    @Override
    public void set(int x, int y, int z, Block state, boolean physics) {
        instance.setBlock(x, y, z, state, physics);
    }

    @Override
    public boolean sameState(Block current, Block target) {
        return current.stateId() == target.stateId();
    }

    @Override
    public Object paletteKey(Block state) {
        return state.stateId();
    }

    @Override
    public String serialize(Block state) {
        return BlockStates.format(state);
    }

    /**
     * Scans down from the top of the highest non-empty section of the column's chunk, so flat and
     * low worlds stay cheap. A column with no blocks reports one below the world's minimum height.
     */
    @Override
    public int surfaceY(int x, int z) {
        int start = Math.min(maxY - 1, topOfHighestSection(x, z));
        for (int y = start; y >= minY; y--) {
            if (!instance.getBlock(x, y, z, Block.Getter.Condition.TYPE).air()) {
                return y;
            }
        }
        return minY - 1;
    }

    private int topOfHighestSection(int x, int z) {
        var chunk = instance.getChunkAt(x, z);
        if (chunk == null) {
            return maxY - 1;
        }
        for (int section = chunk.getMaxSection() - 1; section >= chunk.getMinSection(); section--) {
            if (chunk.getSection(section).blockPalette().count() > 0) {
                return section * 16 + 15;
            }
        }
        return minY - 1;
    }

    @Override
    public List<Ops.EntitySummary> entities(Region region, int max) {
        List<Ops.EntitySummary> out = new ArrayList<>();
        for (Entity e : instance.getEntities()) {
            if (out.size() == max) {
                break;
            }
            Pos p = e.getPosition();
            if (inside(region, p)) {
                out.add(new Ops.EntitySummary(e.getEntityType().key().asString(), p.x(), p.y(), p.z(), name(e)));
            }
        }
        return out;
    }

    /** Minestom has no scoreboard tags, so tags are always empty. */
    @Override
    public List<Ops.EntityInfo> entityInfos(Region region, String type) {
        List<Ops.EntityInfo> out = new ArrayList<>();
        for (Entity e : instance.getEntities()) {
            Pos p = e.getPosition();
            String key = e.getEntityType().key().asString();
            if ((type == null || type.equals(key)) && inside(region, p)) {
                Double health = null;
                Double maxHealth = null;
                if (e instanceof LivingEntity living) {
                    health = (double) living.getHealth();
                    maxHealth = living.getAttributeValue(Attribute.MAX_HEALTH);
                }
                out.add(new Ops.EntityInfo(key, e.getUuid().toString(), p.x(), p.y(), p.z(), name(e), List.of(),
                        health, maxHealth));
            }
        }
        return out;
    }

    /** From the sign block's NBT. */
    @Override
    public Ops.SignText sign(int x, int y, int z) {
        return SignNbt.read(x, y, z, instance.getBlock(x, y, z).nbt());
    }

    private static boolean inside(Region region, Pos p) {
        return p.x() >= region.min().x() && p.x() < region.max().x() + 1
                && p.y() >= region.min().y() && p.y() < region.max().y() + 1
                && p.z() >= region.min().z() && p.z() < region.max().z() + 1;
    }

    private static String name(Entity e) {
        if (e instanceof Player p) {
            return p.getUsername();
        }
        Component custom = e.get(DataComponents.CUSTOM_NAME);
        if (custom == null && e.getEntityMeta() instanceof TextDisplayMeta text) {
            custom = text.getText();
        }
        return custom == null ? null : PlainTextComponentSerializer.plainText().serialize(custom);
    }

    // ---- chunk loading ----------------------------------------------------------------------

    @Override
    public void loadArea(int minX, int minZ, int maxX, int maxZ) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                chunks.add(chunkKey(cx, cz));
            }
        }
        load(chunks);
    }

    @Override
    public void loadPositions(long[] positions) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (long pos : positions) {
            chunks.add(chunkKey(PackedPos.x(pos) >> 4, PackedPos.z(pos) >> 4));
        }
        load(chunks);
    }

    /** Loads (or generates) chunks and waits for them. Must not run on a tick thread. */
    private void load(Set<Long> chunks) {
        List<CompletableFuture<?>> loads = new ArrayList<>(chunks.size());
        for (long key : chunks) {
            loads.add(instance.loadChunk((int) (key >> 32), (int) key));
        }
        try {
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                    .get(chunkTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new OpException(ErrorCodes.TIMEOUT, "chunks did not load within " + chunkTimeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpException(ErrorCodes.SHUTDOWN, "interrupted");
        } catch (ExecutionException e) {
            throw new OpException(ErrorCodes.INTERNAL, "chunk load failed: " + e.getCause());
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
