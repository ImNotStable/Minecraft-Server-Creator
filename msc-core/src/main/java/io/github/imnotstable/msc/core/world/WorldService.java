package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * World views and edits. The agent only reads and writes blocks; everything here shapes that into
 * text a model can reason about and into edits it can undo with one id.
 */
public final class WorldService {
    /** Largest area one view may cover per layer; beyond this a text grid stops being readable. */
    public static final int MAX_VIEW_SIDE = 128;
    /** Largest region {@code world_summary} and {@code build_copy} accept. */
    public static final long MAX_BULK_VOLUME = 8L * Limits.MAX_REGION_VOLUME;

    private final AgentHub hub;
    private final Map<String, List<String>> edits = new LinkedHashMap<>();
    private int editCounter;
    private String worldSession;
    private String worldName;

    public WorldService(AgentHub hub) {
        this.hub = hub;
    }

    /** The world to use when a tool is not given one: the server's first world. */
    public synchronized String world(String requested) throws InterruptedException {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        CoreServer.AgentSession s = hub.require();
        if (!s.sessionId().equals(worldSession)) {
            List<String> worlds = hub.call(Ops.SERVER_INFO, new Ops.Empty()).worlds();
            worldName = worlds == null || worlds.isEmpty() ? "world" : worlds.get(0);
            worldSession = s.sessionId();
        }
        return worldName;
    }

    // ---- views ------------------------------------------------------------------------------

    public BlockGrid read(Region region) throws InterruptedException {
        if (region.volume() > Limits.MAX_REGION_VOLUME) {
            throw new ToolException("region has " + region.volume() + " blocks; the limit per call is "
                    + Limits.MAX_REGION_VOLUME + " (128x64x128). Use a smaller region.");
        }
        return hub.call(Ops.WORLD_READ, new Ops.ReadParams(region, false)).grid();
    }

    /** Read a region of up to {@code max} blocks as one grid, stitching protocol-sized reads together. */
    public BlockGrid readAll(Region region, long max, String tool) throws InterruptedException {
        if (region.volume() > max) {
            throw new ToolException("region has " + region.volume() + " blocks; " + tool + " takes at most " + max);
        }
        List<Region> parts = Coords.split(region);
        if (parts.size() == 1) {
            return read(region);
        }
        int[] blocks = new int[(int) region.volume()];
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (Region part : parts) {
            BlockGrid grid = read(part);
            int[] remap = new int[grid.palette().size()];
            for (int i = 0; i < remap.length; i++) {
                remap[i] = index.computeIfAbsent(grid.palette().get(i), s -> {
                    palette.add(s);
                    return palette.size() - 1;
                });
            }
            Region r = grid.region();
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        int b = grid.blocks()[r.index(x, y, z)];
                        blocks[region.index(x, y, z)] = b < 0 ? -1 : remap[b];
                    }
                }
            }
        }
        return new BlockGrid(region, palette, blocks);
    }

    public String slice(Region region, boolean states, int maxChars) throws InterruptedException {
        if (region.sizeX() > MAX_VIEW_SIDE || region.sizeZ() > MAX_VIEW_SIDE) {
            throw new ToolException("slice is at most " + MAX_VIEW_SIDE + " blocks wide; use world_summary or "
                    + "world_heightmap for larger areas");
        }
        return TextGrid.render(read(region), states, maxChars);
    }

    public String heightmap(Region area, int maxChars) throws InterruptedException {
        if (area.sizeX() > 256 || area.sizeZ() > 256) {
            throw new ToolException("heightmap is at most 256x256");
        }
        Ops.HeightmapResult h = hub.call(Ops.WORLD_HEIGHTMAP,
                new Ops.HeightmapParams(area.world(), area.min(), area.max()));
        int[] heights = h.heights();
        if (heights == null || heights.length != h.sizeX() * h.sizeZ() || heights.length == 0) {
            throw new ToolException("agent returned a malformed heightmap");
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        for (int v : heights) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        StringBuilder out = new StringBuilder();
        out.append("heightmap ").append(area.world()).append(" x ").append(h.minX()).append("..")
                .append(h.minX() + h.sizeX() - 1).append(" z ").append(h.minZ()).append("..")
                .append(h.minZ() + h.sizeZ() - 1).append(" min ").append(min).append(" max ").append(max)
                .append(" mean ").append(Math.round((double) sum / heights.length)).append(" rows=z cols=x\n");
        if (min == max) {
            out.append("flat at y=").append(min);
            return out.toString();
        }
        boolean compact = max - min < 36;
        if (compact) {
            out.append("each char = height - ").append(min).append(" (0-9 then a-z)\n");
        }
        for (int z = 0; z < h.sizeZ(); z++) {
            for (int x = 0; x < h.sizeX(); x++) {
                int v = heights[z * h.sizeX() + x];
                if (compact) {
                    out.append(Character.forDigit(v - min, 36));
                } else {
                    if (x > 0) {
                        out.append(' ');
                    }
                    out.append(v);
                }
            }
            out.append('\n');
        }
        return Budget.truncate(out.toString().stripTrailing(), maxChars, "Ask for a smaller area.");
    }

    public String summary(Region region, int maxChars) throws InterruptedException {
        if (region.volume() > MAX_BULK_VOLUME) {
            throw new ToolException("region has " + region.volume() + " blocks; world_summary takes at most "
                    + MAX_BULK_VOLUME);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Integer> entities = new TreeMap<>();
        long[] perLayer = new long[region.sizeY()];
        long air = 0;
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Region part : Coords.split(region)) {
            Ops.ReadResult result = hub.call(Ops.WORLD_READ, new Ops.ReadParams(part, true));
            BlockGrid grid = result.grid();
            Region r = grid.region();
            String[] ids = new String[grid.palette().size()];
            boolean[] isAir = new boolean[ids.length];
            for (int i = 0; i < ids.length; i++) {
                isAir[i] = BlockNames.isAir(grid.palette().get(i));
                ids[i] = BlockNames.brief(BlockNames.id(grid.palette().get(i)));
            }
            long[] local = new long[ids.length];
            int i = 0;
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        int b = grid.blocks()[i++];
                        if (b < 0 || isAir[b]) {
                            air++;
                            continue;
                        }
                        local[b]++;
                        perLayer[y - region.min().y()]++;
                        lo[0] = Math.min(lo[0], x);
                        lo[1] = Math.min(lo[1], y);
                        lo[2] = Math.min(lo[2], z);
                        hi[0] = Math.max(hi[0], x);
                        hi[1] = Math.max(hi[1], y);
                        hi[2] = Math.max(hi[2], z);
                    }
                }
            }
            for (int p = 0; p < ids.length; p++) {
                if (local[p] > 0) {
                    counts.merge(ids[p], local[p], Long::sum);
                }
            }
            if (result.entities() != null) {
                for (Ops.EntitySummary e : result.entities()) {
                    entities.merge(BlockNames.brief(e.type()), 1, Integer::sum);
                }
            }
        }
        long volume = region.volume();
        StringBuilder out = new StringBuilder();
        out.append("region ").append(Coords.show(region)).append(" size ").append(region.sizeX()).append('x')
                .append(region.sizeY()).append('x').append(region.sizeZ()).append(" = ").append(volume)
                .append(" blocks, ").append(Math.round(100.0 * air / volume)).append("% air\n");
        if (counts.isEmpty()) {
            out.append("empty: only air");
        } else {
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            out.append("blocks:");
            int shown = Math.min(15, sorted.size());
            for (int i = 0; i < shown; i++) {
                out.append(i == 0 ? " " : ", ").append(sorted.get(i).getKey()).append(' ').append(sorted.get(i).getValue());
            }
            if (sorted.size() > shown) {
                out.append(" (+").append(sorted.size() - shown).append(" more kinds)");
            }
            out.append("\nnon-air bounds: ").append(lo[0]).append(',').append(lo[1]).append(',').append(lo[2])
                    .append(' ').append(hi[0]).append(',').append(hi[1]).append(',').append(hi[2]);
            out.append("\nnon-air per layer (y:count):");
            int start = 0;
            while (start < perLayer.length) {
                int end = start;
                while (end + 1 < perLayer.length && perLayer[end + 1] == perLayer[start]) {
                    end++;
                }
                int y0 = region.min().y() + start;
                out.append(' ').append(y0);
                if (end > start) {
                    out.append("..").append(region.min().y() + end);
                }
                out.append(':').append(perLayer[start]);
                start = end + 1;
            }
        }
        if (!entities.isEmpty()) {
            out.append("\nentities:");
            entities.forEach((type, n) -> out.append(' ').append(n).append(' ').append(type));
        }
        return Budget.truncate(out.toString(), maxChars, "Ask for a smaller region.");
    }

    // ---- edits ------------------------------------------------------------------------------

    private synchronized String remember(List<String> agentEdits) {
        String id = "e" + (++editCounter);
        edits.put(id, agentEdits);
        return id;
    }

    private static String done(String id, long changed) {
        return "edit " + id + ": " + changed + " blocks changed";
    }

    public String fill(Region region, String block, List<String> onlyReplace) throws InterruptedException {
        if (region.volume() > MAX_BULK_VOLUME) {
            throw new ToolException("region has " + region.volume() + " blocks; build_fill takes at most " + MAX_BULK_VOLUME);
        }
        List<String> replace = onlyReplace.isEmpty() ? null : onlyReplace.stream().map(BlockNames::full).toList();
        List<String> ids = new ArrayList<>();
        long changed = 0;
        for (Region part : Coords.split(region)) {
            Ops.EditResult r = hub.call(Ops.WORLD_FILL, new Ops.FillParams(part, BlockNames.full(block), replace));
            ids.add(r.editId());
            changed += r.changed();
        }
        return done(remember(ids), changed);
    }

    public String set(String world, List<Ops.BlockChange> changes, boolean physics) throws InterruptedException {
        Ops.EditResult r = hub.call(Ops.WORLD_SET_BLOCKS, new Ops.SetBlocksParams(world, changes, physics));
        return done(remember(List.of(r.editId())), r.changed());
    }

    public String write(BlockGrid grid, boolean physics) throws InterruptedException {
        Ops.EditResult r = hub.call(Ops.WORLD_WRITE, new Ops.WriteParams(grid, physics));
        Region g = grid.region();
        return done(remember(List.of(r.editId())), r.changed()) + ", region " + Coords.show(g);
    }

    public String copy(Region source, BlockPos to, boolean copyAir) throws InterruptedException {
        if (source.volume() > MAX_BULK_VOLUME) {
            throw new ToolException("region has " + source.volume() + " blocks; build_copy takes at most " + MAX_BULK_VOLUME);
        }
        int dx = to.x() - source.min().x();
        int dy = to.y() - source.min().y();
        int dz = to.z() - source.min().z();
        // Read everything before writing so overlapping source and target work.
        List<BlockGrid> parts = new ArrayList<>();
        for (Region part : Coords.split(source)) {
            parts.add(hub.call(Ops.WORLD_READ, new Ops.ReadParams(part, false)).grid());
        }
        List<String> ids = new ArrayList<>();
        long changed = 0;
        for (BlockGrid grid : parts) {
            Region r = grid.region();
            int[] blocks = grid.blocks();
            if (!copyAir) {
                blocks = blocks.clone();
                for (int i = 0; i < blocks.length; i++) {
                    if (blocks[i] >= 0 && BlockNames.isAir(grid.palette().get(blocks[i]))) {
                        blocks[i] = -1;
                    }
                }
            }
            Region target = new Region(r.world(), r.min().offset(dx, dy, dz), r.max().offset(dx, dy, dz));
            Ops.EditResult e = hub.call(Ops.WORLD_WRITE,
                    new Ops.WriteParams(new BlockGrid(target, grid.palette(), blocks), false));
            ids.add(e.editId());
            changed += e.changed();
        }
        Region all = new Region(source.world(), source.min().offset(dx, dy, dz), source.max().offset(dx, dy, dz));
        return done(remember(ids), changed) + ", region " + Coords.show(all);
    }

    /** Undo one edit, or the most recent one when {@code id} is null. */
    public String undo(String id) throws InterruptedException {
        List<String> agentEdits;
        String target = id;
        synchronized (this) {
            if (target == null) {
                if (edits.isEmpty()) {
                    throw new ToolException("nothing to undo");
                }
                for (String key : edits.keySet()) {
                    target = key;
                }
            }
            agentEdits = edits.remove(target);
        }
        if (agentEdits == null) {
            throw new ToolException("unknown edit " + target + " (already undone, or from an earlier session)");
        }
        long changed = 0;
        for (int i = agentEdits.size() - 1; i >= 0; i--) {
            changed += hub.call(Ops.WORLD_UNDO, new Ops.UndoParams(agentEdits.get(i))).changed();
        }
        return "undid " + target + ": " + changed + " blocks restored";
    }
}
