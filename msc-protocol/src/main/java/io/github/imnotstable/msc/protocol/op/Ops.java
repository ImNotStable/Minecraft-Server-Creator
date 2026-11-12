package io.github.imnotstable.msc.protocol.op;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import java.util.Objects;

/**
 * Every operation the core can ask an agent to run, with its parameter and result types.
 *

 * <p>Required parameters are checked when the request is decoded, so a missing one comes back
 * as {@code invalid_params} before any handler runs.
 *
 * <p>Agents do the minimum inside the server (read and write blocks, run commands). Everything that
 * shapes output for the AI, such as text slices, summaries and log condensing, happens in the core.
 * Block states are vanilla strings such as {@code minecraft:stone} or
 * {@code minecraft:oak_stairs[facing=north]}.
 */
public final class Ops {
    private Ops() {
    }

    /** Parameters for operations that take none. */
    public record Empty() {
    }

    // ---- server ---------------------------------------------------------------------------

    /** Basic facts about the running server. */
    public static final OpSpec<Empty, ServerInfo> SERVER_INFO =
            new OpSpec<>("server.info", Empty.class, ServerInfo.class);

    public record ServerInfo(String platform, String platformVersion, String minecraftVersion,
                             List<String> worlds, int onlinePlayers, double tps) {
    }

    /** Run a console command and capture what it printed. */
    public static final OpSpec<CommandParams, CommandResult> SERVER_COMMAND =
            new OpSpec<>("server.command", CommandParams.class, CommandResult.class);

    public record CommandParams(String command) {
        public CommandParams {
            Objects.requireNonNull(command, "command");
        }
    }

    public record CommandResult(boolean success, List<String> output) {
    }

    // ---- world: read ----------------------------------------------------------------------

    /** Read the blocks (and optionally entities) of a region, at most {@code Limits.MAX_REGION_VOLUME}. */
    public static final OpSpec<ReadParams, ReadResult> WORLD_READ =
            new OpSpec<>("world.read", ReadParams.class, ReadResult.class);

    public record ReadParams(Region region, boolean includeEntities) {
        public ReadParams {
            Objects.requireNonNull(region, "region");
        }
    }

    public record ReadResult(BlockGrid grid, List<EntitySummary> entities) {
    }

    public record EntitySummary(String type, double x, double y, double z, String name) {
    }

    /**
     * List the entities in a region with what a game check needs: type, name, scoreboard tags, health and position.
     * Unlike {@link #WORLD_READ} it reads no blocks, so the region may be far larger than
     * {@code Limits.MAX_REGION_VOLUME}. Only loaded chunks are searched.
     */
    public static final OpSpec<EntitiesParams, EntitiesResult> WORLD_ENTITIES =
            new OpSpec<>("world.entities", EntitiesParams.class, EntitiesResult.class);

    /**
     * @param type  only entities of this type, such as {@code minecraft:zombie}; null for all
     * @param limit most entities to return; {@code total} still counts them all
     */
    public record EntitiesParams(Region region, String type, int limit) {
        public EntitiesParams {
            Objects.requireNonNull(region, "region");
        }
    }

    public record EntitiesResult(List<EntityInfo> entities, int total) {
        /** Most entities one answer carries. */
        public static final int MAX_ENTITIES = 1000;

        /**
         * What every agent answers: {@code found} sorted by type, then x, z and y, cut to {@code limit} (at most
         * {@link #MAX_ENTITIES}; 0 or less means that maximum), with {@code total} counting them all.
         */
        public static EntitiesResult of(List<EntityInfo> found, int limit) {
            int max = limit <= 0 ? MAX_ENTITIES : Math.min(limit, MAX_ENTITIES);
            List<EntityInfo> sorted = found.stream().sorted(java.util.Comparator.comparing(EntityInfo::type)
                    .thenComparingDouble(EntityInfo::x).thenComparingDouble(EntityInfo::z)
                    .thenComparingDouble(EntityInfo::y)).limit(max).toList();
            return new EntitiesResult(sorted, found.size());
        }
    }

    /**
     * One entity.
     *
     * @param name      player name, custom name or a text display's text; null when it has none
     * @param tags      scoreboard tags, empty when none
     * @param health    current health; null for entities that are not alive (items, displays, minecarts)
     * @param maxHealth maximum health; null like {@code health}
     */
    public record EntityInfo(String type, String uuid, double x, double y, double z, String name,
                             List<String> tags, Double health, Double maxHealth) {
        public EntityInfo {
            Objects.requireNonNull(type, "type");
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** Highest non-air block per column over an X-Z area. */
    public static final OpSpec<HeightmapParams, HeightmapResult> WORLD_HEIGHTMAP =
            new OpSpec<>("world.heightmap", HeightmapParams.class, HeightmapResult.class);

    /** {@code min} and {@code max} use only x and z; y is ignored. */
    public record HeightmapParams(String world, BlockPos min, BlockPos max) {
        public HeightmapParams {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
        }
    }

    /** Heights in Z-then-X order (X changes fastest), one per column. */
    public record HeightmapResult(int minX, int minZ, int sizeX, int sizeZ, int[] heights) {
    }

    // ---- world: write ---------------------------------------------------------------------

    /** Write a palette grid; cells set to -1 are left as they are. The one block-writing op: every build is a grid. */
    public static final OpSpec<WriteParams, EditResult> WORLD_WRITE =
            new OpSpec<>("world.write", WriteParams.class, EditResult.class);

    public record WriteParams(BlockGrid grid, boolean updatePhysics) {
        public WriteParams {
            Objects.requireNonNull(grid, "grid");
        }
    }

    /** Undo an earlier edit by the id it returned. */
    public static final OpSpec<UndoParams, EditResult> WORLD_UNDO =
            new OpSpec<>("world.undo", UndoParams.class, EditResult.class);

    public record UndoParams(String editId) {
        public UndoParams {
            Objects.requireNonNull(editId, "editId");
        }
    }

    /**
     * Result of every write.
     *
     * @param editId  id to pass to {@link #WORLD_UNDO}
     * @param changed number of blocks whose state actually changed
     */
    public record EditResult(String editId, long changed) {
    }

    // ---- resource pack --------------------------------------------------------------------

    /** Send a resource pack to online players and make it the default for new joins. */
    public static final OpSpec<PackParams, PackResult> PACK_APPLY =
            new OpSpec<>("pack.apply", PackParams.class, PackResult.class);

    /** {@code sha1} is the lowercase hex SHA-1 of the zip. */
    public record PackParams(String url, String sha1, boolean required) {
        public PackParams {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(sha1, "sha1");
        }
    }

    public record PackResult(int playersSent) {
    }

    /** All operations, for capability lists and dispatch tables. */
    public static final List<OpSpec<?, ?>> ALL = List.of(
            SERVER_INFO, SERVER_COMMAND, WORLD_READ, WORLD_ENTITIES, WORLD_HEIGHTMAP,
            WORLD_WRITE, WORLD_UNDO, PACK_APPLY);

    /** Operation names, in the same order as {@link #ALL}. */
    public static List<String> names() {
        return ALL.stream().map(OpSpec::name).toList();
    }
}
