package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sponge schematics ({@code .schem}), the format WorldEdit reads and writes. Writes version 2; reads
 * versions 2 and 3. Blocks and their block entity data (chest contents, sign text; ADR 0042) are kept, entities are
 * not. A block entity's {@code Id} is written as its block's id, which is the block entity's own id for most blocks
 * (not for signs, beds or banners, whose block entity types are shared); readers place the data on the block anyway.
 */
public final class Schematic {
    /** Data version written to files (26.3's world_version); readers use it only to decide whether to upgrade block names. */
    static final int DATA_VERSION = 5023;

    private Schematic() {
    }

    /** Longest side a Sponge schematic can hold: Width, Height and Length are unsigned shorts. */
    public static final int MAX_SIDE = 0xFFFF;

    public static void save(BlockGrid grid, Path file) throws IOException {
        Region r = grid.region();
        if (r.sizeX() > MAX_SIDE || r.sizeY() > MAX_SIDE || r.sizeZ() > MAX_SIDE) {
            throw new ToolException("region is " + r.sizeX() + "x" + r.sizeY() + "x" + r.sizeZ() + "; a .schem side is at most "
                    + MAX_SIDE + " blocks. Save it in parts.");
        }
        // Equal states share one file index: a map keyed by state would otherwise drop the earlier index.
        Map<String, Integer> palette = new LinkedHashMap<>();
        int[] remap = new int[grid.palette().size()];
        boolean anyData = false;
        for (int i = 0; i < remap.length; i++) {
            remap[i] = palette.computeIfAbsent(BlockNames.withoutData(grid.palette().get(i)), k -> palette.size());
            anyData |= BlockNames.data(grid.palette().get(i)) != null;
        }
        int airIndex = -1;
        ByteArrayOutputStream data = new ByteArrayOutputStream(grid.blocks().length);
        for (int b : grid.blocks()) {
            int v;
            if (b < 0) {
                if (airIndex < 0) {
                    airIndex = palette.computeIfAbsent("minecraft:air", k -> palette.size());
                }
                v = airIndex;
            } else {
                v = remap[b];
            }
            while ((v & ~0x7F) != 0) {
                data.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            data.write(v);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 2);
        root.put("DataVersion", DATA_VERSION);
        root.put("Width", (short) r.sizeX());
        root.put("Height", (short) r.sizeY());
        root.put("Length", (short) r.sizeZ());
        root.put("PaletteMax", palette.size());
        root.put("Palette", palette);
        root.put("BlockData", data.toByteArray());
        root.put("BlockEntities", anyData ? blockEntities(grid) : List.of());
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            Nbt.write(out, "Schematic", root);
        }
    }

    /** Each block with data as {@code {Pos:[I;x,y,z],Id:"...",<data>}}, Sponge version 2's layout. */
    @SuppressWarnings("unchecked")
    private static List<Object> blockEntities(BlockGrid grid) {
        Region r = grid.region();
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < grid.blocks().length; i++) {
            int b = grid.blocks()[i];
            String data = b < 0 ? null : BlockNames.data(grid.palette().get(b));
            if (data != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("Pos", new int[] {i % r.sizeX(), i / (r.sizeX() * r.sizeZ()), i / r.sizeX() % r.sizeZ()});
                entry.put("Id", BlockNames.id(grid.palette().get(b)));
                entry.putAll((Map<String, Object>) Snbt.parse(data));
                out.add(entry);
            }
        }
        return out;
    }

    /** Load a schematic and place its lowest corner at {@code origin}. */
    @SuppressWarnings("unchecked")
    public static BlockGrid load(Path file, String world, BlockPos origin) throws IOException {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = Nbt.read(in);
        } catch (IOException e) {
            throw new ToolException("cannot read " + file + " as a schematic: " + e.getMessage());
        }
        if (root.get("Schematic") instanceof Map<?, ?> v3) {
            root = (Map<String, Object>) v3;
        }
        try {
            int sx = ((Number) root.get("Width")).intValue() & 0xFFFF;
            int sy = ((Number) root.get("Height")).intValue() & 0xFFFF;
            int sz = ((Number) root.get("Length")).intValue() & 0xFFFF;
            Map<String, Object> paletteTag;
            byte[] data;
            Object entities;
            if (root.get("Blocks") instanceof Map<?, ?> blocksTag) {
                paletteTag = (Map<String, Object>) ((Map<String, Object>) blocksTag).get("Palette");
                data = (byte[]) ((Map<String, Object>) blocksTag).get("Data");
                entities = ((Map<String, Object>) blocksTag).get("BlockEntities");
            } else {
                paletteTag = (Map<String, Object>) root.get("Palette");
                data = (byte[]) root.get("BlockData");
                entities = root.get("BlockEntities");
            }
            long volume = (long) sx * sy * sz;
            if (volume == 0 || volume > Limits.MAX_REGION_VOLUME) {
                throw new ToolException("schematic is " + sx + "x" + sy + "x" + sz + "; the limit is "
                        + Limits.MAX_REGION_VOLUME + " blocks");
            }
            int max = 0;
            for (Object v : paletteTag.values()) {
                int index = ((Number) v).intValue();
                // Indices are dense in practice; a huge one would size the lookup array past memory.
                if (index < 0 || index >= Limits.MAX_REGION_VOLUME) {
                    throw new IllegalArgumentException("palette index " + index);
                }
                max = Math.max(max, index);
            }
            String[] byIndex = new String[max + 1];
            paletteTag.forEach((state, index) -> byIndex[((Number) index).intValue()] = state);
            List<String> palette = new ArrayList<>();
            for (String s : byIndex) {
                palette.add(s == null ? "minecraft:air" : s);
            }
            int[] blocks = new int[(int) volume];
            int pos = 0;
            for (int i = 0; i < blocks.length; i++) {
                int value = 0;
                int shift = 0;
                while (true) {
                    int b = data[pos++];
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                blocks[i] = value;
            }
            if (entities instanceof List<?> list) {
                addData(list, sx, sy, sz, palette, blocks);
            }
            Region region = new Region(world, origin, origin.offset(sx - 1, sy - 1, sz - 1));
            return new BlockGrid(region, palette, blocks);
        } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new ToolException(file.getFileName() + " is not a Sponge schematic this tool understands (v2 or v3)");
        }
    }

    /**
     * Puts each block entity's data on its block: version 2 keeps the data beside {@code Pos} and {@code Id},
     * version 3 under {@code Data}. Data on a position outside the schematic is skipped.
     */
    @SuppressWarnings("unchecked")
    private static void addData(List<?> entities, int sx, int sy, int sz, List<String> palette, int[] blocks) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            index.putIfAbsent(palette.get(i), i);
        }
        for (Object e : entities) {
            Map<String, Object> entry = (Map<String, Object>) e;
            int[] pos = (int[]) entry.get("Pos");
            if (pos.length != 3 || pos[0] < 0 || pos[0] >= sx || pos[1] < 0 || pos[1] >= sy || pos[2] < 0
                    || pos[2] >= sz) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>(entry.get("Data") instanceof Map<?, ?> v3
                    ? (Map<String, Object>) v3 : entry);
            fields.remove("Pos");
            fields.remove("Id");
            String snbt = Snbt.blockData(Snbt.write(fields));
            if (snbt != null) {
                int cell = (pos[1] * sz + pos[2]) * sx + pos[0];
                String state = BlockNames.withoutData(palette.get(blocks[cell])) + snbt;
                blocks[cell] = index.computeIfAbsent(state, k -> {
                    palette.add(k);
                    return palette.size() - 1;
                });
            }
        }
    }
}
