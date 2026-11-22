package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.world.BlockNames;
import io.github.imnotstable.msc.core.world.Footprint;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The blocks a build will place, held in one dense array over the build's bounds. Later writes win,
 * which is what makes a batch behave like its steps run one after another. {@code -1} means the
 * block is left as it is.
 */
public final class Canvas {
    private final Region bounds;
    private final int[] cells;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    /** Ids by the name a step gave, so a name with block entity data is checked once, not once per block. */
    private final Map<String, Integer> givenIndex = new HashMap<>();
    private final WorldReader worldReader;
    private final List<Region> readAhead = new ArrayList<>();
    private final List<BlockGrid> worldReads = new ArrayList<>();
    private long placed;

    /**
     * @param worldReader reads the current world for steps that only replace some blocks; called at
     *                    most once per such step, with that step's bounds, or once per {@link #readAhead} region
     */
    public Canvas(Region bounds, WorldReader worldReader) {
        this.bounds = bounds;
        this.cells = new int[Math.toIntExact(bounds.volume())];
        Arrays.fill(cells, -1);
        this.worldReader = worldReader;
    }

    public Region bounds() {
        return bounds;
    }

    public List<String> palette() {
        return List.copyOf(palette);
    }

    int stateId(String state) {
        return givenIndex.computeIfAbsent(state, given -> paletteIndex.computeIfAbsent(BlockNames.full(given), s -> {
            palette.add(s);
            return palette.size() - 1;
        }));
    }

    /** Set one block; positions outside the bounds are ignored. {@code state == null} leaves it as it is. */
    public void set(int x, int y, int z, String state) {
        if (state == null || !bounds.contains(x, y, z)) {
            return;
        }
        int i = bounds.index(x, y, z);
        if (cells[i] < 0) {
            placed++;
        }
        cells[i] = stateId(state);
    }

    /** The block this build will leave at a position, or null when it does not touch it. */
    public String planned(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return null;
        }
        int c = cells[bounds.index(x, y, z)];
        return c < 0 ? null : palette.get(c);
    }

    /** A mask for steps with {@code replace=}: the block there after earlier steps, or in the world. */
    public Mask mask(Region stepBounds, List<String> replace) throws InterruptedException {
        if (replace == null || replace.isEmpty()) {
            return (x, y, z) -> true;
        }
        BlockGrid world = world(stepBounds);
        List<String> wanted = replace.stream().map(BlockNames::full).toList();
        Map<String, Boolean> judged = new HashMap<>();
        return (x, y, z) -> {
            String current = planned(x, y, z);
            if (current == null) {
                current = world.region().contains(x, y, z) ? world.stateAt(x, y, z) : null;
            }
            return current != null && judged.computeIfAbsent(current, s -> matches(s, wanted));
        };
    }

    /**
     * What each position in {@code region} holds once the earlier steps are drawn: their block, else the world's. For
     * steps that sit on the ground, such as {@code path} and {@code scatter} (ADR 0075).
     */
    public Lookup current(Region region) throws InterruptedException {
        BlockGrid world = world(region);
        return (x, y, z) -> {
            String p = planned(x, y, z);
            return p != null ? p : world.region().contains(x, y, z) ? world.stateAt(x, y, z) : "minecraft:air";
        };
    }

    /** A block by position. */
    @FunctionalInterface
    public interface Lookup {
        String at(int x, int y, int z);
    }

    /**
     * Read all of {@code region} at once when a later step inside it first needs the world, so the copies
     * of a repeated step share one read. The world does not change while the canvas is drawn. Lasts until
     * {@link #endReadAhead}, so steps after the group read only their own bounds.
     */
    void readAhead(Region region) {
        readAhead.add(region);
    }

    /** End the latest {@link #readAhead} of {@code region}. */
    void endReadAhead(Region region) {
        readAhead.remove(readAhead.lastIndexOf(region));
    }

    private BlockGrid world(Region stepBounds) throws InterruptedException {
        for (BlockGrid g : worldReads) {
            if (encloses(g.region(), stepBounds)) {
                return g;
            }
        }
        Region read = readAhead.stream().filter(r -> encloses(r, stepBounds)).findFirst().orElse(stepBounds);
        BlockGrid g = worldReader.read(read);
        worldReads.add(g);
        return g;
    }

    private static boolean encloses(Region outer, Region inner) {
        return outer.contains(inner.min().x(), inner.min().y(), inner.min().z())
                && outer.contains(inner.max().x(), inner.max().y(), inner.max().z());
    }

    /**
     * Whether a state is one of {@code wanted}. An entry matches a state with the same id that has every property
     * the entry lists, in any order; an entry without properties matches any properties.
     */
    static boolean matches(String state, List<String> wanted) {
        String id = BlockNames.id(state);
        Set<String> has = properties(state);
        for (String w : wanted) {
            if (BlockNames.id(w).equals(id) && has.containsAll(properties(w))) {
                return true;
            }
            if (BlockNames.isAir(w) && BlockNames.isAir(state)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code key=value} pairs in a state's brackets. */
    private static Set<String> properties(String name) {
        String state = BlockNames.withoutData(name); // data may hold brackets of its own
        int open = state.indexOf('[');
        if (open < 0) {
            return Set.of();
        }
        int close = state.lastIndexOf(']');
        Set<String> out = new HashSet<>();
        for (String p : state.substring(open + 1, close > open ? close : state.length()).split(",")) {
            String pair = p.replace(" ", "");
            if (!pair.isEmpty()) {
                out.add(pair);
            }
        }
        return out;
    }

    /** Reads the world as it is now. */
    @FunctionalInterface
    public interface WorldReader {
        BlockGrid read(Region region) throws InterruptedException;
    }

    /** Positions a step may change. */
    @FunctionalInterface
    public interface Mask {
        boolean allows(int x, int y, int z);
    }

    /** Blocks this build sets, counting each position once. */
    public long placed() {
        return placed;
    }

    /** Count per block, most common first; a block with block entity data counts as its block. */
    public Map<String, Long> counts() {
        long[] per = new long[palette.size()];
        for (int c : cells) {
            if (c >= 0) {
                per[c]++;
            }
        }
        Map<String, Long> byName = new java.util.LinkedHashMap<>(); // palette order breaks ties, as before
        for (int i = 0; i < per.length; i++) {
            if (per[i] > 0) {
                byName.merge(BlockNames.brief(BlockNames.withoutData(palette.get(i))), per[i], Long::sum);
            }
        }
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        byName.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** The smallest box holding every block this build sets, or null when it sets none. */
    public Region used() {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        int i = 0;
        for (int y = bounds.min().y(); y <= bounds.max().y(); y++) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                for (int x = bounds.min().x(); x <= bounds.max().x(); x++) {
                    if (cells[i++] >= 0) {
                        lo[0] = Math.min(lo[0], x);
                        lo[1] = Math.min(lo[1], y);
                        lo[2] = Math.min(lo[2], z);
                        hi[0] = Math.max(hi[0], x);
                        hi[1] = Math.max(hi[1], y);
                        hi[2] = Math.max(hi[2], z);
                    }
                }
            }
        }
        return lo[0] == Integer.MAX_VALUE ? null : Region.of(bounds.world(), lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /** The positions this build sets, within {@code used}, the box {@link #used()} returned. */
    public Footprint footprint(Region used) {
        BitSet set = new BitSet(Math.toIntExact(used.volume()));
        int i = 0;
        for (int y = used.min().y(); y <= used.max().y(); y++) {
            for (int z = used.min().z(); z <= used.max().z(); z++) {
                int base = bounds.index(used.min().x(), y, z);
                for (int x = 0; x < used.sizeX(); x++, i++) {
                    if (cells[base + x] >= 0) {
                        set.set(i);
                    }
                }
            }
        }
        return new Footprint(used, set);
    }

    /**
     * The part of the canvas inside {@code part}, as a grid. Every grid carries the whole palette, so
     * the agent checks every block name on the first write, before it places anything. Returns null
     * when the part sets no block.
     */
    public BlockGrid grid(Region part) {
        int[] blocks = new int[Math.toIntExact(part.volume())];
        boolean any = false;
        int i = 0;
        for (int y = part.min().y(); y <= part.max().y(); y++) {
            for (int z = part.min().z(); z <= part.max().z(); z++) {
                int base = bounds.index(part.min().x(), y, z);
                for (int x = 0; x < part.sizeX(); x++) {
                    int c = cells[base + x];
                    blocks[i++] = c;
                    any |= c >= 0;
                }
            }
        }
        return any ? new BlockGrid(part, palette(), blocks) : null;
    }
}
