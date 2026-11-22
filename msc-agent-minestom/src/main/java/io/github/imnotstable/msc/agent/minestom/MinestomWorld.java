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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.tag.Tag;

/**
 * {@link AgentWorld} on a Minestom {@link Instance}. Minestom does not load chunks on access, so
 * the operations load (or generate) every chunk they touch first, off the tick thread; block
 * access then runs on the main thread. Chunks only a read loaded are unloaded after it.
 */
final class MinestomWorld implements AgentWorld<Block> {
    /** On a chunk an edit loaded, so a read never unloads it. */
    private static final Tag<Boolean> KEEP = Tag.Boolean("msc:keep");
    /** On a chunk a read loaded, until the first check once no operation uses it (see {@link #unloadIfUnused}). */
    static final Tag<Boolean> READ_LOADED = Tag.<Boolean>Transient("msc:read-loaded");
    /** On the instance: the chunks operations are using right now. */
    private static final Tag<ChunkReaders> READERS = Tag.<ChunkReaders>Transient("msc:readers");

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

    /**
     * The block itself when it has data: {@link #sameState} looks at the state only, so an undo of a change to data
     * alone (ADR 0042) puts the old block back through {@link #loadBlockEntity}.
     */
    @Override
    public Object blockEntity(int x, int y, int z) {
        Block block = instance.getBlock(x, y, z);
        return block.hasNbt() ? block : null;
    }

    @Override
    public void loadBlockEntity(int x, int y, int z, Object data) {
        instance.setBlock(x, y, z, (Block) data, false);
    }

    @Override
    public boolean holdsData(Block state) {
        return BlockNbt.holds(state);
    }

    @Override
    public String blockData(int x, int y, int z) {
        return BlockNbt.read(instance.getBlock(x, y, z));
    }

    @Override
    public void mergeBlockData(int x, int y, int z, Object data) {
        instance.setBlock(x, y, z, BlockNbt.merge(instance.getBlock(x, y, z), (CompoundBinaryTag) data), false);
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
        return region.containsPoint(p.x(), p.y(), p.z());
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

    /** Loads the area for an edit; its chunks stay loaded, even if a read loaded them first. */
    @Override
    public void loadArea(int minX, int minZ, int maxX, int maxZ) {
        loadAndKeep(area(minX, minZ, maxX, maxZ));
    }

    /**
     * Loads the area for a read. Minestom never unloads chunks by itself, so a read unloads the
     * chunks it loaded. Each one carries {@link #READ_LOADED} until the first moment no operation
     * counts it: that check, on the instance's next tick, removes the tag and unloads the chunk only
     * if no player sees it, no entity is in it and no edit keeps it. Otherwise the chunk stays as
     * an ordinary chunk of the world, which no later read may unload. A read whose chunks fail or
     * time out releases them the same way, and checks each late chunk on the tick after it arrives.
     */
    @Override
    public Runnable loadAreaToRead(int minX, int minZ, int maxX, int maxZ) {
        Set<Long> chunks = area(minX, minZ, maxX, maxZ);
        ChunkReaders readers = readers();
        readers.acquire(chunks);
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                readers.release(chunks);
                checkNextTick(readers, chunks);
            }
        };
        List<CompletableFuture<Chunk>> loads;
        try {
            Set<Long> fresh = chunks.stream().filter(k -> instance.getChunk(chunkX(k), chunkZ(k)) == null)
                    .collect(Collectors.toSet());
            // Tagged as each chunk arrives, so a read that fails or times out can still unload them.
            loads = startLoads(chunks).stream().map(load -> load.thenApply(c -> {
                long key = chunkKey(c.getChunkX(), c.getChunkZ());
                if (fresh.contains(key)) {
                    c.setTag(READ_LOADED, true);
                    // Arrived after the read let go of it: nothing else will check it.
                    if (released.get()) {
                        checkNextTick(readers, Set.of(key));
                    }
                }
                return c;
            })).toList();
        } catch (RuntimeException e) {
            readers.release(chunks);
            throw e;
        }
        try {
            await(loads);
        } catch (RuntimeException e) {
            release.run();
            throw e;
        }
        return release;
    }

    @Override
    public void loadPositions(long[] positions) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (long pos : positions) {
            chunks.add(chunkKey(PackedPos.x(pos) >> 4, PackedPos.z(pos) >> 4));
        }
        loadAndKeep(chunks);
    }

    /** Counts the chunks as in use until they carry {@link #KEEP}, so no read's release unloads them first. */
    private void loadAndKeep(Set<Long> chunks) {
        ChunkReaders readers = readers();
        readers.acquire(chunks);
        try {
            load(chunks).forEach(chunk -> chunk.setTag(KEEP, true));
        } finally {
            readers.release(chunks);
            // A read that loaded one of these chunks may have let go of it while this edit counted it.
            checkNextTick(readers, chunks);
        }
    }

    private void checkNextTick(ChunkReaders readers, Set<Long> chunks) {
        instance.scheduleNextTick(i -> readers.unloadUnused(chunks, this::unloadIfUnused));
    }

    private ChunkReaders readers() {
        return instance.updateAndGetTag(READERS, r -> r == null ? new ChunkReaders() : r);
    }

    private static Set<Long> area(int minX, int minZ, int maxX, int maxZ) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                chunks.add(chunkKey(cx, cz));
            }
        }
        return chunks;
    }

    /**
     * Runs once no operation counts the chunk. The read's right to unload ends here: a chunk kept
     * now loses its tag, since players or game code may change it from now on and an unload drops
     * those changes (Minestom does not save on unload).
     */
    private void unloadIfUnused(long key) {
        Chunk chunk = instance.getChunk(chunkX(key), chunkZ(key));
        if (chunk == null || !chunk.hasTag(READ_LOADED)) {
            return;
        }
        chunk.removeTag(READ_LOADED);
        if (!chunk.hasTag(KEEP) && chunk.getViewers().isEmpty() && instance.getChunkEntities(chunk).isEmpty()) {
            instance.unloadChunk(chunk);
        }
    }

    /** Loads (or generates) chunks and waits for them. Must not run on a tick thread. */
    private List<Chunk> load(Set<Long> chunks) {
        return await(startLoads(chunks));
    }

    private List<CompletableFuture<Chunk>> startLoads(Set<Long> chunks) {
        List<CompletableFuture<Chunk>> loads = new ArrayList<>(chunks.size());
        for (long key : chunks) {
            loads.add(instance.loadChunk(chunkX(key), chunkZ(key)));
        }
        return loads;
    }

    private List<Chunk> await(List<CompletableFuture<Chunk>> loads) {
        try {
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                    .get(chunkTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return loads.stream().map(CompletableFuture::join).toList();
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

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }
}
