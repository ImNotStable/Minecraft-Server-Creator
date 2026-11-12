package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text structure format: one character per block, one grid per Y layer, and a legend. The same
 * format is used to show the world ({@code world_slice}) and to build ({@code build_batch} structure steps), so a
 * model can read a slice, edit it and write it back.
 *
 * <pre>
 * legend: .=air #=stone_bricks g=glass
 * y=64
 * #####
 * #...#
 * #####
 * y=65 same as y=64
 * y=66 all #
 * </pre>
 *
 * Rows run from the lowest Z (top) to the highest; columns from the lowest X (left) to the highest.
 * {@code .} is air and {@code _} means "leave unchanged" unless the legend says otherwise.
 */
public final class TextGrid {
    /** Symbols handed out by frequency, most common block first. */
    static final String SYMBOLS = "#abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@%&*+~^!$";
    private static final Pattern LAYER = Pattern.compile("^y=(-?\\d+)(?:\\s+same as y=(-?\\d+)|\\s+all\\s+(\\S+))?\\s*:?$");

    private TextGrid() {
    }

    // ---- render -----------------------------------------------------------------------------

    /**
     * @param states keep block properties ({@code oak_stairs[facing=north]}) instead of ids only
     */
    public static String render(BlockGrid grid, boolean states, int maxChars) {
        Region r = grid.region();
        int sx = r.sizeX();
        int sy = r.sizeY();
        int sz = r.sizeZ();
        // Palette index -> display name; several states may share one name.
        List<String> names = new ArrayList<>();
        Map<String, Integer> nameIndex = new HashMap<>();
        int[] toName = new int[grid.palette().size()];
        for (int i = 0; i < toName.length; i++) {
            String state = grid.palette().get(i);
            String name = BlockNames.isAir(state) ? "air" : BlockNames.brief(states ? state : BlockNames.id(state));
            Integer idx = nameIndex.get(name);
            if (idx == null) {
                idx = names.size();
                names.add(name);
                nameIndex.put(name, idx);
            }
            toName[i] = idx;
        }
        long[] counts = new long[names.size()];
        for (int b : grid.blocks()) {
            if (b >= 0) {
                counts[toName[b]]++;
            }
        }
        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(counts[b], counts[a]));
        char[] symbol = new char[names.size()];
        StringBuilder legend = new StringBuilder("legend:");
        int next = 0;
        int others = 0;
        for (int idx : order) {
            if (counts[idx] == 0) {
                continue;
            }
            if (names.get(idx).equals("air")) {
                symbol[idx] = '.';
            } else if (next < SYMBOLS.length()) {
                symbol[idx] = SYMBOLS.charAt(next++);
            } else {
                symbol[idx] = '?';
                others++;
                continue;
            }
            legend.append(' ').append(symbol[idx]).append('=').append(names.get(idx));
        }
        if (others > 0) {
            legend.append(" ?=").append(others).append("_other_kinds");
        }
        StringBuilder out = new StringBuilder();
        out.append("region ").append(Coords.show(r)).append(" size ").append(sx).append('x').append(sy).append('x')
                .append(sz).append(" rows=z cols=x\n").append(legend).append('\n');
        Map<String, Integer> seenLayers = new HashMap<>();
        for (int y = 0; y < sy; y++) {
            StringBuilder layer = new StringBuilder(sz * (sx + 1));
            char first = 0;
            boolean uniform = true;
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int b = grid.blocks()[(y * sz + z) * sx + x];
                    char c = b < 0 ? '_' : symbol[toName[b]];
                    if (first == 0) {
                        first = c;
                    } else if (c != first) {
                        uniform = false;
                    }
                    layer.append(c);
                }
                layer.append('\n');
            }
            int worldY = r.min().y() + y;
            out.append("y=").append(worldY);
            String text = layer.toString();
            Integer same = seenLayers.putIfAbsent(text, worldY);
            if (uniform && sx * sz > 1) {
                out.append(" all ").append(first).append('\n');
            } else if (same != null) {
                out.append(" same as y=").append(same).append('\n');
            } else {
                out.append('\n').append(text);
            }
        }
        return Budget.truncate(out.toString().stripTrailing(), maxChars,
                "Ask for fewer layers or a smaller area, or raise maxChars.");
    }

    // ---- parse ------------------------------------------------------------------------------

    private static final class Layer {
        final List<String> rows = new ArrayList<>();
        Integer sameAs;
        String all;
    }

    /** Parse a structure and place its lowest corner (lowest x, y and z) at {@code origin}. */
    public static BlockGrid parse(String text, String world, BlockPos origin) {
        Map<Character, String> legend = new LinkedHashMap<>();
        TreeMap<Integer, Layer> layers = new TreeMap<>();
        Layer current = null;
        for (String raw : text.split("\\R")) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.startsWith("region ") || line.startsWith("[")) {
                continue;
            }
            if (line.startsWith("legend:")) {
                for (String entry : line.substring(7).trim().split("\\s+")) {
                    if (entry.isEmpty()) {
                        continue;
                    }
                    if (entry.length() < 3 || entry.charAt(1) != '=') {
                        throw new ToolException("bad legend entry \"" + entry + "\"; use symbol=block, for example #=stone");
                    }
                    legend.put(entry.charAt(0), entry.substring(2));
                }
                continue;
            }
            Matcher m = LAYER.matcher(line);
            if (m.matches()) {
                int y = Integer.parseInt(m.group(1));
                current = new Layer();
                if (m.group(2) != null) {
                    current.sameAs = Integer.parseInt(m.group(2));
                }
                current.all = m.group(3);
                if (layers.put(y, current) != null) {
                    throw new ToolException("layer y=" + y + " appears twice");
                }
                continue;
            }
            if (current == null) {
                current = new Layer();
                layers.put(0, current);
            }
            if (current.sameAs != null || current.all != null) {
                throw new ToolException("layer with \"same as\" or \"all\" must not have rows");
            }
            current.rows.add(line);
        }
        if (layers.isEmpty()) {
            throw new ToolException("structure has no layers");
        }
        int sy = layers.lastKey() - layers.firstKey() + 1;
        if (sy != layers.size()) {
            throw new ToolException("layers must be consecutive: got " + layers.keySet());
        }
        Layer shaped = layers.values().stream().filter(l -> !l.rows.isEmpty()).findFirst()
                .orElseThrow(() -> new ToolException("at least one layer needs rows to give the structure a size"));
        int sz = shaped.rows.size();
        int sx = shaped.rows.get(0).length();
        Region region = new Region(world, origin, origin.offset(sx - 1, sy - 1, sz - 1));
        if (region.volume() > io.github.imnotstable.msc.protocol.Limits.MAX_REGION_VOLUME) {
            throw new ToolException("structure is larger than 1,048,576 blocks; split it");
        }
        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();
        int[] blocks = new int[(int) region.volume()];
        Map<Integer, int[]> done = new HashMap<>();
        int yi = 0;
        for (Map.Entry<Integer, Layer> e : layers.entrySet()) {
            Layer layer = e.getValue();
            int[] cells = new int[sx * sz];
            if (layer.sameAs != null) {
                int[] source = done.get(layer.sameAs);
                if (source == null) {
                    throw new ToolException("y=" + e.getKey() + " refers to y=" + layer.sameAs
                            + ", which must be a lower layer with rows");
                }
                cells = source;
            } else if (layer.all != null) {
                String state = layer.all.length() == 1 ? resolve(layer.all.charAt(0), legend, e.getKey()) : layer.all;
                Arrays.fill(cells, index(state, palette, paletteIndex));
            } else {
                if (layer.rows.size() != sz) {
                    throw new ToolException("y=" + e.getKey() + " has " + layer.rows.size() + " rows, expected " + sz);
                }
                for (int z = 0; z < sz; z++) {
                    String row = layer.rows.get(z);
                    if (row.length() != sx) {
                        throw new ToolException("y=" + e.getKey() + " row " + (z + 1) + " has " + row.length()
                                + " columns, expected " + sx);
                    }
                    for (int x = 0; x < sx; x++) {
                        cells[z * sx + x] = index(resolve(row.charAt(x), legend, e.getKey()), palette, paletteIndex);
                    }
                }
            }
            done.put(e.getKey(), cells);
            System.arraycopy(cells, 0, blocks, yi * sx * sz, sx * sz);
            yi++;
        }
        return new BlockGrid(region, palette, blocks);
    }

    /** A symbol's block, or null for "leave unchanged". */
    private static String resolve(char c, Map<Character, String> legend, int y) {
        String state = legend.get(c);
        if (state != null) {
            return state;
        }
        if (c == '.') {
            return "air";
        }
        if (c == '_' || c == ' ') {
            return null;
        }
        throw new ToolException("symbol '" + c + "' in y=" + y + " is not in the legend");
    }

    private static int index(String state, List<String> palette, Map<String, Integer> paletteIndex) {
        if (state == null) {
            return -1;
        }
        String full = BlockNames.full(state);
        Integer i = paletteIndex.get(full);
        if (i == null) {
            i = palette.size();
            palette.add(full);
            paletteIndex.put(full, i);
        }
        return i;
    }
}
