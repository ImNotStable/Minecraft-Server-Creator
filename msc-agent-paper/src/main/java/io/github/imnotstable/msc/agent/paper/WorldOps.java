package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.BoundingBox;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** World reads and edits. Every method may be called from any thread; world access goes through {@link MainThread}. */
final class WorldOps {
    /** Most entities one read returns. */
    static final int MAX_ENTITIES = 1000;

    private final Server server;
    private final MainThread main;
    private final EditHistory history;

    WorldOps(Server server, MainThread main, EditHistory history) {
        this.server = server;
        this.main = main;
        this.history = history;
    }

    // ---- reads ----------------------------------------------------------------------------

    Ops.ReadResult read(Ops.ReadParams params) {
        Region region = withinLimit(params.region());
        World world = world(region.world());
        BlockGrid grid = main.run(new ReadJob(world, region));
        List<Ops.EntitySummary> entities = params.includeEntities() ? main.call(() -> entities(world, region)) : List.of();
        List<Ops.SignText> signs = params.includeSigns() ? signs(world, grid) : List.of();
        return new Ops.ReadResult(grid, entities, signs);
    }

    /** Text of the signs in a grid; block entities are looked up only where the grid has a sign. */
    private List<Ops.SignText> signs(World world, BlockGrid grid) {
        List<BlockPos> at = Ops.SignText.positions(grid);
        if (at.isEmpty()) {
            return List.of();
        }
        return main.call(() -> {
            List<Ops.SignText> out = new ArrayList<>();
            for (BlockPos p : at) {
                if (world.getBlockAt(p.x(), p.y(), p.z()).getState() instanceof Sign sign) {
                    Ops.SignText text = Ops.SignText.of(p.x(), p.y(), p.z(), lines(sign.getSide(Side.FRONT)),
                            lines(sign.getSide(Side.BACK)));
                    if (text != null) {
                        out.add(text);
                    }
                }
            }
            return out;
        });
    }

    private static List<String> lines(SignSide side) {
        return side.lines().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private static List<Ops.EntitySummary> entities(World world, Region region) {
        BoundingBox box = new BoundingBox(region.min().x(), region.min().y(), region.min().z(),
                region.max().x() + 1, region.max().y() + 1, region.max().z() + 1);
        List<Ops.EntitySummary> out = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(box)) {
            if (out.size() == MAX_ENTITIES) {
                break;
            }
            out.add(new Ops.EntitySummary(e.getType().key().asString(), e.getX(), e.getY(), e.getZ(), name(e)));
        }
        return out;
    }

    private static String name(Entity e) {
        if (e instanceof Player p) {
            return p.getName();
        }
        Component custom = e.customName();
        if (custom == null && e instanceof TextDisplay display) {
            // A hologram's words are its text, not a name: report them so a render or summary can show them.
            custom = display.text();
        }
        return custom == null ? null : PlainTextComponentSerializer.plainText().serialize(custom);
    }

    /** Entities in a region with name, tags and health, sorted by type and then position. */
    Ops.EntitiesResult entities(Ops.EntitiesParams params) {
        Region region = params.region();
        World world = world(region.world());
        String type = params.type() == null || params.type().isBlank() ? null : params.type();
        return main.call(() -> {
            BoundingBox box = new BoundingBox(region.min().x(), region.min().y(), region.min().z(),
                    region.max().x() + 1, region.max().y() + 1, region.max().z() + 1);
            List<Ops.EntityInfo> all = new ArrayList<>();
            for (Entity e : world.getNearbyEntities(box)) {
                String key = e.getType().key().asString();
                if (type == null || type.equals(key)) {
                    all.add(info(e, key));
                }
            }
            return Ops.EntitiesResult.of(all, params.limit());
        });
    }

    private static Ops.EntityInfo info(Entity e, String type) {
        Double health = null;
        Double maxHealth = null;
        if (e instanceof LivingEntity living) {
            health = living.getHealth();
            AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
            maxHealth = max == null ? null : max.getValue();
        }
        return new Ops.EntityInfo(type, e.getUniqueId().toString(), e.getX(), e.getY(), e.getZ(), name(e),
                List.copyOf(new TreeSet<>(e.getScoreboardTags())), health, maxHealth);
    }

    Ops.HeightmapResult heightmap(Ops.HeightmapParams params) {
        World world = world(params.world());
        int minX = Math.min(params.min().x(), params.max().x());
        int minZ = Math.min(params.min().z(), params.max().z());
        int sizeX = Math.abs(params.max().x() - params.min().x()) + 1;
        int sizeZ = Math.abs(params.max().z() - params.min().z()) + 1;
        if ((long) sizeX * sizeZ > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "area " + sizeX + "x" + sizeZ + " exceeds " + Limits.MAX_REGION_VOLUME + " columns");
        }
        int[] heights = main.run(new HeightmapJob(world, minX, minZ, sizeX, sizeZ));
        return new Ops.HeightmapResult(minX, minZ, sizeX, sizeZ, heights);
    }

    // ---- edits ----------------------------------------------------------------------------

    Ops.EditResult write(Ops.WriteParams params) {
        BlockGrid grid = params.grid();
        Region region = withinLimit(grid.region());
        World world = world(region.world());
        requireBuildHeight(world, region.min().y(), region.max().y());
        List<BlockData> palette = grid.palette().stream().map(this::parse).toList();
        return apply(world, new GridCursor(region, grid.blocks(), palette), params.updatePhysics());
    }

    Ops.EditResult undo(Ops.UndoParams params) {
        UndoRecord record = history.take(params.editId())
                .orElseThrow(() -> new OpException(ErrorCodes.NOT_FOUND, "no undo kept for edit " + params.editId()));
        World world = world(record.world());
        return apply(world, record.restore(), false);
    }

    private Ops.EditResult apply(World world, ChangeSource changes, boolean physics) {
        UndoRecord record = main.run(new EditJob(world, changes, physics));
        return new Ops.EditResult(history.add(record), record.size());
    }

    // ---- helpers --------------------------------------------------------------------------

    private World world(String name) {
        World world = server.getWorld(name);
        if (world == null) {
            throw new OpException(ErrorCodes.NOT_FOUND, "no world named " + name);
        }
        return world;
    }

    private static Region withinLimit(Region region) {
        if (region.volume() > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "region volume " + region.volume() + " exceeds " + Limits.MAX_REGION_VOLUME);
        }
        return region;
    }

    private static void requireBuildHeight(World world, int minY, int maxY) {
        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
            throw new IllegalArgumentException("y " + minY + ".." + maxY + " is outside the world's build height "
                    + world.getMinHeight() + ".." + (world.getMaxHeight() - 1));
        }
    }

    private BlockData parse(String state) {
        try {
            return server.createBlockData(state);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(explain(state, e), e);
        }
    }

    /** Why {@code state} failed: bad properties on a real block, or an unknown id with close names. */
    private static String explain(String state, IllegalArgumentException cause) {
        String id = (state.contains("[") ? state.substring(0, state.indexOf('[')) : state).trim()
                .toLowerCase(Locale.ROOT);
        try {
            List<String> blocks = Arrays.stream(Material.values())
                    .filter(m -> !m.name().startsWith("LEGACY_") && m.isBlock())
                    .map(m -> m.getKey().getKey()).toList();
            String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            if (blocks.contains(bare)) {
                return "bad block properties in " + state + ": " + cause.getMessage();
            }
            List<String> close = BlockNames.suggest(bare, blocks);
            return "unknown block state: " + state
                    + (close.isEmpty() ? "" : "; did you mean " + String.join(", ", close) + "?");
        } catch (RuntimeException e) {
            return "unknown block state: " + state; // suggestions are a nicety; never hide the real error
        }
    }

    // ---- cursors --------------------------------------------------------------------------

    /** Grid cells that are not -1. */
    private static final class GridCursor implements ChangeSource {
        private final Region region;
        private final int[] blocks;
        private final List<BlockData> palette;
        private int i = -1;

        GridCursor(Region region, int[] blocks, List<BlockData> palette) {
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
        public BlockData target() {
            return palette.get(blocks[i]);
        }

        @Override
        public long size() {
            return blocks.length;
        }
    }

}
