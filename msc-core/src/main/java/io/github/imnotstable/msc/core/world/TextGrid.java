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
 * legend: .=air #=stone_bricks g=glass@2,66,0
 * y=64
 * #####
 * #...# (x3)
 * #####
 * y=65..66 same as y=64
 * y=67 all #
 * </pre>
 *
 * Rows run from the lowest Z (top) to the highest; columns from the lowest X (left) to the highest.
 * {@code .} is air and {@code _} means "leave unchanged" unless the legend says otherwise. To keep results small,
 * a row followed by {@code (xN)} stands for N equal rows, {@code y=a..b} covers a run of equal layers, and a block
 * that occurs once says where it is ({@code @x,y,z}, ignored when building).
 */
public final class TextGrid {
    /** Symbols handed out by frequency, most common block first. */
    static final String SYMBOLS = "#abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@%&*+~^!$";
    private static final Pattern LAYER = Pattern.compile("^y=(-?\\d+)(?:\\.\\.(-?\\d+))?(?:\\s+same as y=(-?\\d+)|\\s+all\\s+(\\S+))?\\s*:?$");

    /** A row written once for N equal rows: {@code #...# (x3)}. */
    private static final Pattern REPEAT = Pattern.compile("^(.*\\S) \\(x(\\d{1,4})\\)$");

    /** Most layers a structure may have: taller than any world. */
    static final int MAX_LAYERS = 4096;

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
        int[] lastAt = new int[names.size()];
        for (int i = 0; i < grid.blocks().length; i++) {
            int b = grid.blocks()[i];
            if (b >= 0) {
                counts[toName[b]]++;
                lastAt[toName[b]] = i;
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
            if (counts[idx] == 1 && symbol[idx] != '.') {
                // A single block says where it is, so finding it needs no column counting.
                int i = lastAt[idx];
                legend.append('@').append(r.min().x() + i % sx).append(',').append(r.min().y() + i / (sx * sz))
                        .append(',').append(r.min().z() + i / sx % sz);
            }
        }
        if (others > 0) {
            legend.append(" ?=").append(others).append("_other_kinds");
        }
        StringBuilder out = new StringBuilder();
        out.append("region ").append(Coords.show(r)).append(" size ").append(sx).append('x').append(sy).append('x')
                .append(sz).append(" rows=z cols=x\n").append(legend).append('\n');
        // Each layer is "all c", "same as y=N" or rows; runs of equal "all"/"same as" layers share one line.
        Map<String, Integer> seenLayers = new HashMap<>();
        String pending = null;
        int runStart = 0;
        int runEnd = 0;
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
            String text = layer.toString();
            Integer same = seenLayers.putIfAbsent(text, worldY);
            String shortForm = uniform && sx * sz > 1 ? " all " + first : same != null ? " same as y=" + same : null;
            if (pending != null && pending.equals(shortForm) && runEnd == worldY - 1) {
                runEnd = worldY;
                continue;
            }
            appendRun(out, pending, runStart, runEnd);
            pending = shortForm;
            runStart = worldY;
            runEnd = worldY;
            if (shortForm == null) {
                out.append("y=").append(worldY).append('\n').append(repeatRows(text));
            }
        }
        appendRun(out, pending, runStart, runEnd);
        return Budget.truncate(out.toString().stripTrailing(), maxChars,
                "Ask for fewer layers or a smaller area, or raise maxChars.");
    }

    private static void appendRun(StringBuilder out, String shortForm, int from, int to) {
        if (shortForm != null) {
            out.append("y=").append(from).append(from == to ? "" : ".." + to).append(shortForm).append('\n');
        }
    }

    /** A layer's rows with runs of equal rows written once, followed by {@code (xN)}. */
    private static String repeatRows(String layer) {
        StringBuilder out = new StringBuilder(layer.length());
        String previous = null;
        int count = 0;
        for (String row : layer.split("\n")) {
            if (row.equals(previous)) {
                count++;
                continue;
            }
            appendRow(out, previous, count);
            previous = row;
            count = 1;
        }
        appendRow(out, previous, count);
        return out.toString();
    }

    private static void appendRow(StringBuilder out, String row, int count) {
        if (row != null) {
            out.append(row).append(count > 1 ? " (x" + count + ")" : "").append('\n');
        }
    }

    // ---- parse ------------------------------------------------------------------------------

    private static final class Layer {
        final List<String> rows = new ArrayList<>();
        Integer sameAs;
        String all;
    }

    private static int layerNumber(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new ToolException("layer number " + digits + " is too large");
        }
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
                    String state = entry.substring(2);
                    int at = state.lastIndexOf('@');
                    legend.put(entry.charAt(0), at > 0 ? state.substring(0, at) : state);
                }
                continue;
            }
            Matcher m = LAYER.matcher(line);
            if (m.matches()) {
                int from = layerNumber(m.group(1));
                int to = m.group(2) == null ? from : layerNumber(m.group(2));
                if (to < from || (to > from && m.group(3) == null && m.group(4) == null)) {
                    throw new ToolException("y=" + from + ".." + to + " needs a rising range and \"same as\" or \"all\"");
                }
                if (layers.size() + ((long) to - from + 1) > MAX_LAYERS) {
                    throw new ToolException("structure has more than " + MAX_LAYERS + " layers (y=" + from + ".." + to
                            + "); split it");
                }
                Integer sameAs = m.group(3) == null ? null : layerNumber(m.group(3));
                for (int y = from; y <= to; y++) {
                    current = new Layer();
                    current.sameAs = sameAs;
                    current.all = m.group(4);
                    if (layers.put(y, current) != null) {
                        throw new ToolException("layer y=" + y + " appears twice");
                    }
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
            Matcher repeat = REPEAT.matcher(line);
            int times = repeat.matches() ? Integer.parseInt(repeat.group(2)) : 1;
            for (int i = 0; i < times; i++) {
                current.rows.add(repeat.matches() ? repeat.group(1) : line);
            }
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
