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

    /** Fill a region with one block, optionally only where the current block matches {@code onlyReplace}. */
    public static final OpSpec<FillParams, EditResult> WORLD_FILL =
            new OpSpec<>("world.fill", FillParams.class, EditResult.class);

    /** {@code onlyReplace} may be null or empty to replace everything. */
    public record FillParams(Region region, String state, List<String> onlyReplace) {
        public FillParams {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(state, "state");
        }
    }

    /** Write a palette grid; cells set to -1 are left as they are. Used for paste, schematics and text structures. */
    public static final OpSpec<WriteParams, EditResult> WORLD_WRITE =
            new OpSpec<>("world.write", WriteParams.class, EditResult.class);

    public record WriteParams(BlockGrid grid, boolean updatePhysics) {
        public WriteParams {
            Objects.requireNonNull(grid, "grid");
        }
    }

    /** Set individual blocks. */
    public static final OpSpec<SetBlocksParams, EditResult> WORLD_SET_BLOCKS =
            new OpSpec<>("world.setBlocks", SetBlocksParams.class, EditResult.class);

    public record SetBlocksParams(String world, List<BlockChange> changes, boolean updatePhysics) {
        public SetBlocksParams {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(changes, "changes");
        }
    }

    public record BlockChange(int x, int y, int z, String state) {
        public BlockChange {
            Objects.requireNonNull(state, "state");
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
            SERVER_INFO, SERVER_COMMAND, WORLD_READ, WORLD_HEIGHTMAP,
            WORLD_FILL, WORLD_WRITE, WORLD_SET_BLOCKS, WORLD_UNDO, PACK_APPLY);

    /** Operation names, in the same order as {@link #ALL}. */
    public static List<String> names() {
        return ALL.stream().map(OpSpec::name).toList();
    }
}
