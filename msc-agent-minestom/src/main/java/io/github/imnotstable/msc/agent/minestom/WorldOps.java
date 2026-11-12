package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * World reads and edits. Every method may be called from any thread except a tick thread: chunks
 * are loaded first (generated if new), then world access goes through {@link MainThread}.
 */
final class WorldOps {
    /** Most entities one read returns. */
    static final int MAX_ENTITIES = 1000;

    private final Worlds worlds;
    private final MainThread main;
    private final EditHistory history;
    private final Duration chunkTimeout;

    WorldOps(Worlds worlds, MainThread main, EditHistory history, Duration chunkTimeout) {
        this.worlds = worlds;
        this.main = main;
        this.history = history;
        this.chunkTimeout = chunkTimeout;
    }

    // ---- reads ----------------------------------------------------------------------------

    Ops.ReadResult read(Ops.ReadParams params) {
        Region region = withinLimit(params.region());
        Instance instance = worlds.get(region.world());
        loadChunks(instance, region.min().x(), region.min().z(), region.max().x(), region.max().z());
        BlockGrid grid = main.run(new ReadJob(instance, region));
        List<Ops.EntitySummary> entities = params.includeEntities() ? main.call(() -> entities(instance, region)) : List.of();
        return new Ops.ReadResult(grid, entities);
    }

    private static List<Ops.EntitySummary> entities(Instance instance, Region region) {
        List<Ops.EntitySummary> out = new ArrayList<>();
        for (Entity e : instance.getEntities()) {
            if (out.size() == MAX_ENTITIES) {
                break;
            }
            Pos p = e.getPosition();
            if (p.x() >= region.min().x() && p.x() < region.max().x() + 1
                    && p.y() >= region.min().y() && p.y() < region.max().y() + 1
                    && p.z() >= region.min().z() && p.z() < region.max().z() + 1) {
                out.add(new Ops.EntitySummary(e.getEntityType().key().asString(), p.x(), p.y(), p.z(), name(e)));
            }
        }
        return out;
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

    /** Entities in a region with name and health. Minestom has no scoreboard tags, so tags are always empty. */
    Ops.EntitiesResult entities(Ops.EntitiesParams params) {
        Region region = params.region();
        Instance instance = worlds.get(region.world());
        String type = params.type() == null || params.type().isBlank() ? null : params.type();
        return Ops.EntitiesResult.of(main.call(() -> {
            List<Ops.EntityInfo> out = new ArrayList<>();
            for (Entity e : instance.getEntities()) {
                Pos p = e.getPosition();
                String key = e.getEntityType().key().asString();
                if ((type == null || type.equals(key)) && p.x() >= region.min().x() && p.x() < region.max().x() + 1
                        && p.y() >= region.min().y() && p.y() < region.max().y() + 1
                        && p.z() >= region.min().z() && p.z() < region.max().z() + 1) {
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
        }), params.limit());
    }

    Ops.HeightmapResult heightmap(Ops.HeightmapParams params) {
        Instance instance = worlds.get(params.world());
        int minX = Math.min(params.min().x(), params.max().x());
        int minZ = Math.min(params.min().z(), params.max().z());
        int sizeX = Math.abs(params.max().x() - params.min().x()) + 1;
        int sizeZ = Math.abs(params.max().z() - params.min().z()) + 1;
        if ((long) sizeX * sizeZ > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "area " + sizeX + "x" + sizeZ + " exceeds " + Limits.MAX_REGION_VOLUME + " columns");
        }
        loadChunks(instance, minX, minZ, minX + sizeX - 1, minZ + sizeZ - 1);
        int[] heights = main.run(new HeightmapJob(instance, minX, minZ, sizeX, sizeZ));
        return new Ops.HeightmapResult(minX, minZ, sizeX, sizeZ, heights);
    }

    // ---- edits ----------------------------------------------------------------------------

    Ops.EditResult fill(Ops.FillParams params) {
        Region region = withinLimit(params.region());
        Instance instance = worlds.get(region.world());
        requireBuildHeight(instance, region.min().y(), region.max().y());
        Block target = BlockStates.parse(params.state());
        List<BlockStates.Filter> onlyReplace = params.onlyReplace() == null ? List.of()
                : params.onlyReplace().stream().map(BlockStates::filter).toList();
        loadChunks(instance, region.min().x(), region.min().z(), region.max().x(), region.max().z());
        return apply(instance, region.world(), new RegionCursor(region, target), onlyReplace, false);
    }

    Ops.EditResult write(Ops.WriteParams params) {
        BlockGrid grid = params.grid();
        Region region = withinLimit(grid.region());
        Instance instance = worlds.get(region.world());
        requireBuildHeight(instance, region.min().y(), region.max().y());
        List<Block> palette = grid.palette().stream().map(BlockStates::parse).toList();
        loadChunks(instance, region.min().x(), region.min().z(), region.max().x(), region.max().z());
        return apply(instance, region.world(), new GridCursor(region, grid.blocks(), palette), List.of(), params.updatePhysics());
    }

    Ops.EditResult setBlocks(Ops.SetBlocksParams params) {
        if (params.changes().size() > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    params.changes().size() + " changes exceed " + Limits.MAX_REGION_VOLUME);
        }
        Instance instance = worlds.get(params.world());
        Map<String, Block> parsed = new HashMap<>();
        List<Block> targets = new ArrayList<>(params.changes().size());
        Set<Long> chunks = new LinkedHashSet<>();
        for (Ops.BlockChange change : params.changes()) {
            requireBuildHeight(instance, change.y(), change.y());
            targets.add(parsed.computeIfAbsent(change.state(), BlockStates::parse));
            chunks.add(chunkKey(change.x() >> 4, change.z() >> 4));
        }
        loadChunks(instance, chunks);
        return apply(instance, params.world(), new ListCursor(params.changes(), targets), List.of(), params.updatePhysics());
    }

    Ops.EditResult undo(Ops.UndoParams params) {
        UndoRecord record = history.take(params.editId())
                .orElseThrow(() -> new OpException(ErrorCodes.NOT_FOUND, "no undo kept for edit " + params.editId()));
        Instance instance = worlds.get(record.world());
        Set<Long> chunks = new LinkedHashSet<>();
        for (long pos : record.positions()) {
            chunks.add(chunkKey(PackedPos.x(pos) >> 4, PackedPos.z(pos) >> 4));
        }
        loadChunks(instance, chunks);
        return apply(instance, record.world(), record.restore(), List.of(), false);
    }

    private Ops.EditResult apply(Instance instance, String world, ChangeSource changes,
                                 List<BlockStates.Filter> onlyReplace, boolean physics) {
        UndoRecord record = main.run(new EditJob(instance, world, changes, onlyReplace, physics));
        return new Ops.EditResult(history.add(record), record.size());
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Loads (or generates) every chunk of an X-Z area and waits for them. Must not run on a tick thread. */
    private void loadChunks(Instance instance, int minX, int minZ, int maxX, int maxZ) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                chunks.add(chunkKey(cx, cz));
            }
        }
        loadChunks(instance, chunks);
    }

    private void loadChunks(Instance instance, Set<Long> chunks) {
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

    private static Region withinLimit(Region region) {
        if (region.volume() > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "region volume " + region.volume() + " exceeds " + Limits.MAX_REGION_VOLUME);
        }
        return region;
    }

    private static void requireBuildHeight(Instance instance, int minY, int maxY) {
        int bottom = instance.getCachedDimensionType().minY();
        int top = bottom + instance.getCachedDimensionType().height() - 1;
        if (minY < bottom || maxY > top) {
            throw new IllegalArgumentException("y " + minY + ".." + maxY + " is outside the world's build height "
                    + bottom + ".." + top);
        }
    }

    // ---- cursors --------------------------------------------------------------------------

    /** Every block of a region, Y-Z-X order, with one target. */
    private static final class RegionCursor implements ChangeSource {
        private final Region region;
        private final Block target;
        private final int volume;
        private int i = -1;

        RegionCursor(Region region, Block target) {
            this.region = region;
            this.target = target;
            this.volume = Math.toIntExact(region.volume());
        }

        @Override
        public boolean advance() {
            return ++i < volume;
        }

        @Override
        public int x() {
            return region.min().x() + i % region.sizeX();
        }

        @Override
        public int y() {
            return region.min().y() + i / (region.sizeX() * region.sizeZ());
        }

        @Override
        public int z() {
            return region.min().z() + (i % (region.sizeX() * region.sizeZ())) / region.sizeX();
        }

        @Override
        public Block target() {
            return target;
        }

        @Override
        public long size() {
            return volume;
        }
    }

    /** Grid cells that are not -1. */
    private static final class GridCursor implements ChangeSource {
        private final Region region;
        private final int[] blocks;
        private final List<Block> palette;
        private int i = -1;

        GridCursor(Region region, int[] blocks, List<Block> palette) {
            this.region = region;
            this.blocks = blocks;
            this.palette = palette;
        }

        @Override
        public boolean advance() {
            do {
                i++;
            } while (i < blocks.length && blocks[i] < 0);
            return i < blocks.length;
        }

        @Override
        public int x() {
            return region.min().x() + i % region.sizeX();
        }

        @Override
        public int y() {
            return region.min().y() + i / (region.sizeX() * region.sizeZ());
        }

        @Override
        public int z() {
            return region.min().z() + (i % (region.sizeX() * region.sizeZ())) / region.sizeX();
        }

        @Override
        public Block target() {
            return palette.get(blocks[i]);
        }

        @Override
        public long size() {
            return blocks.length;
        }
    }

    /** An explicit list of changes. */
    private static final class ListCursor implements ChangeSource {
        private final List<Ops.BlockChange> changes;
        private final List<Block> targets;
        private int i = -1;

        ListCursor(List<Ops.BlockChange> changes, List<Block> targets) {
            this.changes = changes;
            this.targets = targets;
        }

        @Override
        public boolean advance() {
            return ++i < changes.size();
        }

        @Override
        public int x() {
            return changes.get(i).x();
        }

        @Override
        public int y() {
            return changes.get(i).y();
        }

        @Override
        public int z() {
            return changes.get(i).z();
        }

        @Override
        public Block target() {
            return targets.get(i);
        }

        @Override
        public long size() {
            return changes.size();
        }
    }
}
