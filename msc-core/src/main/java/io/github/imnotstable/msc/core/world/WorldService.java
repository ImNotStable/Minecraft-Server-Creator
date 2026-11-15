package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
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
    /** Largest region {@code world_summary} and {@code build_batch} accept. */
    public static final long MAX_BULK_VOLUME = 8L * Limits.MAX_REGION_VOLUME;

    private final AgentHub hub;
    /** The edits still applied, oldest first: each one's agent parts and the positions it set. */
    private final Map<String, Edit> edits = new LinkedHashMap<>();
    /**
     * Held by every call that changes the world under an edit id, from its first write or check to its last change of
     * {@link #edits}, so they run one at a time (ADR 0049). Never taken while this object's monitor is held.
     */
    private final java.util.concurrent.locks.ReentrantLock editing = new java.util.concurrent.locks.ReentrantLock(true);
    /** The agents' undo budget ({@code AgentDefaults.UNDO_BLOCKS}), for messages. */
    private static final String UNDO_BLOCKS_TEXT = String.format(java.util.Locale.ROOT, "%,d", 8 * Limits.MAX_REGION_VOLUME);
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
        return read(region, null, null, false);
    }

    /** As {@link #read(Region)}, with block entity data on the blocks that have some (ADR 0042). */
    public BlockGrid readWithData(Region region) throws InterruptedException {
        return read(region, null, null, true);
    }

    /**
     * As {@link #read(Region)}, adding the region's entities to {@code entities} and its signs with text to
     * {@code signs}, each when not null.
     */
    private BlockGrid read(Region region, List<Ops.EntitySummary> entities, List<Ops.SignText> signs, boolean data)
            throws InterruptedException {
        if (region.volume() > Limits.MAX_REGION_VOLUME) {
            throw new ToolException("region has " + region.volume() + " blocks; the limit per call is "
                    + Limits.MAX_REGION_VOLUME + " (128x64x128). Use a smaller region.");
        }
        Ops.ReadResult result = hub.call(Ops.WORLD_READ,
                new Ops.ReadParams(region, entities != null, signs != null, data));
        if (entities != null) {
            entities.addAll(result.entities());
        }
        if (signs != null) {
            signs.addAll(result.signs());
        }
        return data ? withCoreData(result.grid()) : result.grid();
    }

    /**
     * The grid with its block entity data in the core's one spelling ({@link BlockNames#full}): each platform's agent
     * writes SNBT its own way, and Paper's keeps the id and position.
     */
    static BlockGrid withCoreData(BlockGrid grid) {
        List<String> palette = new ArrayList<>(grid.palette().size());
        for (String state : grid.palette()) {
            palette.add(BlockNames.data(state) == null ? state : BlockNames.full(state));
        }
        return new BlockGrid(grid.region(), palette, grid.blocks());
    }

    /** Read a region of up to {@code max} blocks as one grid, stitching protocol-sized reads together. */
    public BlockGrid readAll(Region region, long max, String tool) throws InterruptedException {
        return readAll(region, max, tool, null, null, false);
    }

    /** As {@link #readAll(Region, long, String)}, with block entity data (ADR 0042). */
    public BlockGrid readAllWithData(Region region, long max, String tool) throws InterruptedException {
        return readAll(region, max, tool, null, null, true);
    }

    /**
     * As {@link #readAll(Region, long, String)}, adding the region's entities to {@code entities} and its signs with
     * text to {@code signs}, each unless null.
     */
    public BlockGrid readAll(Region region, long max, String tool, List<Ops.EntitySummary> entities,
                             List<Ops.SignText> signs) throws InterruptedException {
        return readAll(region, max, tool, entities, signs, false);
    }

    private BlockGrid readAll(Region region, long max, String tool, List<Ops.EntitySummary> entities,
                              List<Ops.SignText> signs, boolean data) throws InterruptedException {
        if (region.volume() > max) {
            throw new ToolException("region has " + region.volume() + " blocks; " + tool + " takes at most " + max);
        }
        List<Region> parts = Coords.split(region);
        if (parts.size() == 1) {
            return read(region, entities, signs, data);
        }
        int[] blocks = new int[(int) region.volume()];
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (Region part : parts) {
            BlockGrid grid = read(part, entities, signs, data);
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

    /** @param data add a {@code data X,Y,Z {SNBT}} line per block with block entity data (ADR 0042) */
    public String slice(Region region, boolean states, boolean data, int maxChars) throws InterruptedException {
        if (region.sizeX() > MAX_VIEW_SIDE || region.sizeZ() > MAX_VIEW_SIDE) {
            throw new ToolException("slice is at most " + MAX_VIEW_SIDE + " blocks wide; use world_summary or "
                    + "world_heightmap for larger areas");
        }
        return TextGrid.render(data ? readWithData(region) : read(region), states, maxChars);
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
            out.append("each char = height ").append(min < 0 ? "+ " + -(long) min : "- " + min)
                    .append(" (0-9 then a-z)\n");
            for (int z = 0; z < h.sizeZ(); z++) {
                for (int x = 0; x < h.sizeX(); x++) {
                    out.append(Character.forDigit(heights[z * h.sizeX() + x] - min, 36));
                }
                out.append('\n');
            }
        } else {
            // Range too wide for one char per column: heights as numbers, runs as v*n, repeated rows as (xN).
            out.append("each row: heights by column, v*n = n columns at v, (xN) = row repeated N times\n");
            String previous = null;
            int repeats = 0;
            for (int z = 0; z < h.sizeZ(); z++) {
                String row = numberRow(heights, z * h.sizeX(), h.sizeX());
                if (row.equals(previous)) {
                    repeats++;
                    continue;
                }
                appendRow(out, previous, repeats);
                previous = row;
                repeats = 1;
            }
            appendRow(out, previous, repeats);
        }
        return Budget.truncate(out.toString().stripTrailing(), maxChars, "Ask for a smaller area.");
    }

    private static String numberRow(int[] heights, int start, int length) {
        StringBuilder row = new StringBuilder();
        int x = 0;
        while (x < length) {
            int v = heights[start + x];
            int run = 1;
            while (x + run < length && heights[start + x + run] == v) {
                run++;
            }
            if (!row.isEmpty()) {
                row.append(' ');
            }
            row.append(v);
            if (run > 1) {
                row.append('*').append(run);
            }
            x += run;
        }
        return row.toString();
    }

    private static void appendRow(StringBuilder out, String row, int repeats) {
        if (row == null) {
            return;
        }
        out.append(row);
        if (repeats > 1) {
            out.append(" (x").append(repeats).append(')');
        }
        out.append('\n');
    }

    /** Block kinds with at most this many blocks in a summary are listed with their positions. */
    static final int RARE = 3;

    public String summary(Region region, int maxChars) throws InterruptedException {
        if (region.volume() > MAX_BULK_VOLUME) {
            throw new ToolException("region has " + region.volume() + " blocks; world_summary takes at most "
                    + MAX_BULK_VOLUME);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        // Where each block kind is, up to RARE + 1 places: rare kinds are listed with their positions.
        Map<String, List<String>> places = new HashMap<>();
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
            List<List<String>> localPlaces = new ArrayList<>();
            for (int p = 0; p < ids.length; p++) {
                localPlaces.add(new ArrayList<>());
            }
            int i = 0;
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        int b = grid.blocks()[i++];
                        if (b < 0 || isAir[b]) {
                            air++;
                            continue;
                        }
                        if (local[b]++ <= RARE) {
                            localPlaces.get(b).add(x + "," + y + "," + z);
                        }
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
                    List<String> at = places.computeIfAbsent(ids[p], k -> new ArrayList<>());
                    at.addAll(localPlaces.get(p).subList(0, Math.min(localPlaces.get(p).size(), RARE + 1 - at.size())));
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
                Map.Entry<String, Long> kind = sorted.get(i);
                out.append(i == 0 ? " " : ", ").append(kind.getKey()).append(' ').append(kind.getValue());
                if (kind.getValue() <= RARE) {
                    out.append(" at ").append(String.join(" ", places.get(kind.getKey())));
                }
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

    /** Widest X-Z extent world_entities searches: an entity lookup walks every chunk section in the box. */
    public static final int MAX_ENTITY_SIDE = 2048;

    /**
     * One line per entity: type, name, position, health and tags, so wave spawns, NPCs and holograms can be checked
     * without a player. {@code type} narrows to one entity type; {@code ids} adds the UUID for commands.
     */
    public String entities(Region region, String type, int limit, boolean ids, int maxChars) throws InterruptedException {
        if (region.sizeX() > MAX_ENTITY_SIDE || region.sizeZ() > MAX_ENTITY_SIDE) {
            throw new ToolException("region is " + region.sizeX() + "x" + region.sizeZ() + " wide; world_entities takes at most "
                    + MAX_ENTITY_SIDE + "x" + MAX_ENTITY_SIDE + ". Use a smaller region.");
        }
        String full = type == null || type.isBlank() ? null : BlockNames.full(type.strip().toLowerCase(java.util.Locale.ROOT));
        // Minestom lists every entity of the instance and has no forceload command (ADR 0010).
        boolean loadedChunksOnly = !"minestom".equals(hub.require().agent().platform());
        Ops.EntitiesResult r = hub.call(Ops.WORLD_ENTITIES, new Ops.EntitiesParams(region, full, Math.max(1, limit)));
        return Budget.truncate(formatEntities(r, full, ids, region, loadedChunksOnly), maxChars, "Pass type, a smaller region or a lower limit.");
    }

    /**
     * The listing: a count line with totals per type, then one line per entity. {@code loadedChunksOnly} says the
     * agent searches only loaded chunks, so an empty answer suggests forceloading them.
     */
    static String formatEntities(Ops.EntitiesResult r, String type, boolean ids, Region region, boolean loadedChunksOnly) {
        if (r.total() == 0) {
            String none = "no " + (type == null ? "entities" : BlockNames.brief(type) + " entities") + " in the region";
            if (!loadedChunksOnly) {
                return none;
            }
            // With no player online the region's chunks are usually unloaded, and their entities with them.
            return none + ". Only loaded chunks are searched, and with no player nearby they are unloaded: "
                    + "server_command \"forceload add " + region.min().x() + " " + region.min().z() + " "
                    + region.max().x() + " " + region.max().z() + "\" loads them, then ask again";
        }
        Map<String, Integer> perType = new TreeMap<>();
        for (Ops.EntityInfo e : r.entities()) {
            perType.merge(BlockNames.brief(e.type()), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder().append(r.total()).append(r.total() == 1 ? " entity" : " entities");
        if (r.entities().size() < r.total()) {
            out.append(", first ").append(r.entities().size()).append(" shown");
        }
        if (type == null) {
            out.append(':');
            perType.forEach((t, n) -> out.append(' ').append(n).append(' ').append(t));
        }
        for (Ops.EntityInfo e : r.entities()) {
            out.append('\n').append(BlockNames.brief(e.type()));
            if (e.name() != null && !e.name().isBlank()) {
                out.append(" \"").append(e.name().replace('\n', ' ')).append('"');
            }
            out.append(' ').append(num(e.x())).append(',').append(num(e.y())).append(',').append(num(e.z()));
            if (e.health() != null) {
                out.append(" hp ").append(num(e.health()));
                if (e.maxHealth() != null) {
                    out.append('/').append(num(e.maxHealth()));
                }
            }
            if (!e.tags().isEmpty()) {
                out.append(" tags ").append(String.join(",", e.tags()));
            }
            if (ids && e.uuid() != null) {
                out.append(" id ").append(e.uuid());
            }
        }
        return out.toString();
    }

    /** One decimal, without a trailing ".0". */
    private static String num(double v) {
        double rounded = Math.round(v * 10) / 10.0;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
    }

    // ---- edits ------------------------------------------------------------------------------

    /** Forget every edit id: after a world rollback, they name blocks of a world that is gone. */
    public synchronized void forgetEdits() {
        edits.clear();
    }

    private record Edit(List<String> parts, Footprint where) {
    }

    /**
     * Keep agent edit ids under one new edit id for {@code build_undo}, with the positions the edit set, and return
     * that id.
     */
    public synchronized String remember(List<String> agentEdits, Footprint where) {
        String id = "e" + (++editCounter);
        edits.put(id, new Edit(new ArrayList<>(agentEdits), where));
        return id;
    }

    /**
     * Undo one edit, or the most recent one when {@code id} is null, newest part first. Each agent part is dropped
     * only once the agent has undone it, so after a failure the same id undoes the rest. A part the agent no
     * longer keeps (its undo history is bounded) is skipped and reported. An edit is refused while a later edit,
     * still applied, set any of its positions (ADR 0049): its undo would wipe that edit's blocks there, and that
     * edit's own undo would then bring back blocks of the edit already undone.
     */
    public String undo(String id) throws InterruptedException {
        return oneAtATime(() -> undoNow(id));
    }

    /** Work that changes the world under an edit id: {@link #oneAtATime} runs it. */
    @FunctionalInterface
    public interface EditWork<T> {
        T run() throws InterruptedException;
    }

    /**
     * Run {@code work} while no other call writes or undoes an edit (ADR 0049): a build from its first write to its
     * {@link #remember}, an undo from its overlap check to its edit leaving the list. Interleaved, an undo's check
     * would miss a build not remembered yet, and wipe its blocks or bring back blocks of an edit already undone.
     */
    public <T> T oneAtATime(EditWork<T> work) throws InterruptedException {
        editing.lockInterruptibly();
        try {
            return work.run();
        } finally {
            editing.unlock();
        }
    }

    private String undoNow(String id) throws InterruptedException {
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
            Edit edit = edits.get(target);
            if (edit == null) {
                throw new ToolException("unknown edit " + target + " (already undone, or from an earlier session)");
            }
            List<String> over = new ArrayList<>();
            boolean later = false;
            for (Map.Entry<String, Edit> e : edits.entrySet()) {
                if (later && e.getValue().where().overlaps(edit.where())) {
                    over.addFirst(e.getKey());
                }
                later |= e.getKey().equals(target);
            }
            if (!over.isEmpty()) {
                throw new ToolException(target + " not undone: later edit" + (over.size() == 1 ? " " : "s ")
                        + String.join(", ", over) + " set some of the same blocks, which undoing " + target
                        + " would wipe. Undo " + (over.size() == 1 ? "it" : "them, newest first,")
                        + " first (build_undo edit=" + over.getFirst() + "), then " + target + ".");
            }
            agentEdits = edit.parts();
        }
        // Only an undo changes an edit's parts, and undos run one at a time.
        long changed = 0;
        int lost = 0;
        int parts = agentEdits.size();
        for (int i = parts - 1; i >= 0; i--) {
            try {
                changed += hub.call(Ops.WORLD_UNDO, new Ops.UndoParams(agentEdits.get(i))).changed();
            } catch (OpException e) {
                if (!e.code().equals(ErrorCodes.NOT_FOUND)) {
                    throw stopped(target, changed, e);
                }
                lost++;
            } catch (RuntimeException e) {
                throw stopped(target, changed, e);
            }
            agentEdits.remove(i);
        }
        synchronized (this) {
            edits.remove(target);
        }
        if (lost == 0) {
            return "undid " + target + ": " + changed + " blocks restored";
        }
        return "undid " + target + " partly: " + changed + " blocks restored, " + lost + " of " + parts
                + " parts no longer kept by the server (it keeps the last " + UNDO_BLOCKS_TEXT + " changed blocks)";
    }

    private static RuntimeException stopped(String target, long changed, RuntimeException e) {
        if (changed == 0) {
            return e;
        }
        return new ToolException("undo of " + target + " stopped after " + changed + " blocks restored: "
                + (e instanceof OpException op ? op.detail() : e.getMessage()) + "; build_undo edit=" + target
                + " again undoes the rest");
    }
}
