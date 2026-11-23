package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Structure files other than Sponge schematics (ADR 0083): the game's own structure files ({@code .nbt}, as structure
 * blocks and {@code /place template} use them) and Litematica's {@code .litematic}, so builds found online load as they
 * are. Blocks and block entity data are kept; entities are not.
 */
final class StructureFormats {
    private StructureFormats() {
    }

    /** Which format a gzipped NBT root is, or null for a Sponge schematic. */
    static String kind(Map<String, Object> root) {
        if (root.containsKey("Regions")) {
            return "litematic";
        }
        if (root.containsKey("blocks") && (root.containsKey("palette") || root.containsKey("palettes"))) {
            return "structure";
        }
        if (root.get("Blocks") instanceof byte[] || root.containsKey("Materials")) {
            return "legacy";
        }
        return null;
    }

    /** A state string from a palette entry {@code {Name:..., Properties:{...}}}. */
    @SuppressWarnings("unchecked")
    private static String state(Map<String, Object> entry) {
        String name = (String) entry.get("Name");
        Object props = entry.get("Properties");
        if (!(props instanceof Map<?, ?> map) || map.isEmpty()) {
            return name;
        }
        Map<String, Object> sorted = new TreeMap<>((Map<String, Object>) map);
        List<String> parts = new ArrayList<>();
        sorted.forEach((k, v) -> parts.add(k + "=" + v));
        return name + "[" + String.join(",", parts) + "]";
    }

    private static void checkVolume(String name, long volume, int sx, int sy, int sz) {
        if (volume == 0 || volume > Limits.MAX_REGION_VOLUME) {
            throw new ToolException(name + " is " + sx + "x" + sy + "x" + sz + "; the limit is " + Limits.MAX_REGION_VOLUME
                    + " blocks");
        }
    }

    /** A palette with block entity data added per cell as new entries. */
    private static int withData(List<String> palette, Map<String, Integer> index, int id, Map<String, Object> data) {
        Map<String, Object> fields = new LinkedHashMap<>(data);
        fields.remove("id");
        fields.remove("x");
        fields.remove("y");
        fields.remove("z");
        String snbt = Snbt.blockData(Snbt.write(fields));
        if (snbt == null) {
            return id;
        }
        String state = BlockNames.withoutData(palette.get(id)) + snbt;
        return index.computeIfAbsent(state, k -> {
            palette.add(k);
            return palette.size() - 1;
        });
    }

    /** The game's structure file: {@code size}, {@code palette} (or the first of {@code palettes}) and {@code blocks}. */
    @SuppressWarnings("unchecked")
    static BlockGrid structure(Map<String, Object> root, String name, String world, BlockPos origin) {
        try {
            List<Object> size = (List<Object>) root.get("size");
            int sx = ((Number) size.get(0)).intValue();
            int sy = ((Number) size.get(1)).intValue();
            int sz = ((Number) size.get(2)).intValue();
            long volume = (long) sx * sy * sz;
            checkVolume(name, volume, sx, sy, sz);
            List<Object> entries = root.get("palette") instanceof List<?> p ? (List<Object>) p
                    : (List<Object>) ((List<Object>) root.get("palettes")).get(0);
            List<String> palette = new ArrayList<>();
            for (Object e : entries) {
                palette.add(state((Map<String, Object>) e));
            }
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < palette.size(); i++) {
                index.putIfAbsent(palette.get(i), i);
            }
            int[] blocks = new int[(int) volume];
            Arrays.fill(blocks, -1); // unlisted cells (structure void) leave the world as it is
            for (Object b : (List<Object>) root.get("blocks")) {
                Map<String, Object> block = (Map<String, Object>) b;
                List<Object> pos = (List<Object>) block.get("pos");
                int x = ((Number) pos.get(0)).intValue();
                int y = ((Number) pos.get(1)).intValue();
                int z = ((Number) pos.get(2)).intValue();
                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                    continue;
                }
                int id = ((Number) block.get("state")).intValue();
                if (palette.get(id).equals("minecraft:structure_void")) {
                    continue;
                }
                if (block.get("nbt") instanceof Map<?, ?> nbt) {
                    id = withData(palette, index, id, (Map<String, Object>) nbt);
                }
                blocks[(y * sz + z) * sx + x] = id;
            }
            return new BlockGrid(new Region(world, origin, origin.offset(sx - 1, sy - 1, sz - 1)), palette, blocks);
        } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException e) {
            throw new ToolException(name + " is not a structure file this tool understands");
        }
    }

    /**
     * A Litematica schematic: each region's packed block states (bits per entry from its palette size, entries running
     * on across longs), placed at its position, all regions in one grid; air in a region is placed, cells no region
     * covers stay as they are.
     */
    @SuppressWarnings("unchecked")
    static BlockGrid litematic(Map<String, Object> root, String name, String world, BlockPos origin) {
        try {
            Map<String, Object> regions = (Map<String, Object>) root.get("Regions");
            record Part(int x, int y, int z, int sx, int sy, int sz, List<String> palette, long[] states,
                        List<Object> tiles) {
            }
            List<Part> parts = new ArrayList<>();
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (Object r : regions.values()) {
                Map<String, Object> region = (Map<String, Object>) r;
                Map<String, Object> pos = (Map<String, Object>) region.get("Position");
                Map<String, Object> size = (Map<String, Object>) region.get("Size");
                int[] p = {((Number) pos.get("x")).intValue(), ((Number) pos.get("y")).intValue(),
                        ((Number) pos.get("z")).intValue()};
                int[] s = {((Number) size.get("x")).intValue(), ((Number) size.get("y")).intValue(),
                        ((Number) size.get("z")).intValue()};
                for (int i = 0; i < 3; i++) {
                    if (s[i] < 0) { // a negative size grows back from the position
                        p[i] += s[i] + 1;
                        s[i] = -s[i];
                    }
                    lo[i] = Math.min(lo[i], p[i]);
                    hi[i] = Math.max(hi[i], p[i] + s[i] - 1);
                }
                List<String> palette = new ArrayList<>();
                for (Object e : (List<Object>) region.get("BlockStatePalette")) {
                    palette.add(state((Map<String, Object>) e));
                }
                Object tiles = region.get("TileEntities");
                parts.add(new Part(p[0], p[1], p[2], s[0], s[1], s[2], palette, (long[]) region.get("BlockStates"),
                        tiles instanceof List<?> l ? (List<Object>) l : List.of()));
            }
            int sx = hi[0] - lo[0] + 1;
            int sy = hi[1] - lo[1] + 1;
            int sz = hi[2] - lo[2] + 1;
            long volume = (long) sx * sy * sz;
            checkVolume(name, volume, sx, sy, sz);
            List<String> palette = new ArrayList<>();
            Map<String, Integer> index = new LinkedHashMap<>();
            int[] blocks = new int[(int) volume];
            Arrays.fill(blocks, -1);
            for (Part part : parts) {
                int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, part.palette().size() - 1)));
                int[] remap = new int[part.palette().size()];
                for (int i = 0; i < remap.length; i++) {
                    remap[i] = index.computeIfAbsent(part.palette().get(i), k -> {
                        palette.add(k);
                        return palette.size() - 1;
                    });
                }
                long mask = (1L << bits) - 1;
                long cells = (long) part.sx() * part.sy() * part.sz();
                for (long i = 0; i < cells; i++) {
                    long bit = i * bits;
                    int word = (int) (bit >>> 6);
                    int offset = (int) (bit & 63);
                    long value = part.states()[word] >>> offset;
                    if (offset + bits > 64) {
                        value |= part.states()[word + 1] << (64 - offset);
                    }
                    int id = (int) (value & mask);
                    int x = (int) (i % part.sx());
                    int z = (int) (i / part.sx() % part.sz());
                    int y = (int) (i / ((long) part.sx() * part.sz()));
                    int cell = ((part.y() - lo[1] + y) * sz + (part.z() - lo[2] + z)) * sx + (part.x() - lo[0] + x);
                    blocks[cell] = remap[id];
                }
                for (Object t : part.tiles()) {
                    Map<String, Object> tile = (Map<String, Object>) t;
                    int x = ((Number) tile.get("x")).intValue();
                    int y = ((Number) tile.get("y")).intValue();
                    int z = ((Number) tile.get("z")).intValue();
                    if (x < 0 || y < 0 || z < 0 || x >= part.sx() || y >= part.sy() || z >= part.sz()) {
                        continue;
                    }
                    int cell = ((part.y() - lo[1] + y) * sz + (part.z() - lo[2] + z)) * sx + (part.x() - lo[0] + x);
                    if (blocks[cell] >= 0) {
                        blocks[cell] = withData(palette, index, blocks[cell], tile);
                    }
                }
            }
            return new BlockGrid(new Region(world, origin, origin.offset(sx - 1, sy - 1, sz - 1)), palette, blocks);
        } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException e) {
            throw new ToolException(name + " is not a .litematic this tool understands");
        }
    }
}
