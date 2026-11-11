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
 * versions 2 and 3. Blocks only: block entities (chest contents, sign text) and entities are not kept.
 */
public final class Schematic {
    /** Data version written to files (26.3's world_version); readers use it only to decide whether to upgrade block names. */
    static final int DATA_VERSION = 5023;

    private Schematic() {
    }

    public static void save(BlockGrid grid, Path file) throws IOException {
        Region r = grid.region();
        Map<String, Object> palette = new LinkedHashMap<>();
        for (int i = 0; i < grid.palette().size(); i++) {
            palette.put(grid.palette().get(i), i);
        }
        int airIndex = -1;
        ByteArrayOutputStream data = new ByteArrayOutputStream(grid.blocks().length);
        for (int b : grid.blocks()) {
            int v = b;
            if (v < 0) {
                if (airIndex < 0) {
                    Object existing = palette.get("minecraft:air");
                    airIndex = existing != null ? (Integer) existing : palette.size();
                    palette.putIfAbsent("minecraft:air", airIndex);
                }
                v = airIndex;
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
        root.put("BlockEntities", List.of());
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            Nbt.write(out, "Schematic", root);
        }
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
            if (root.get("Blocks") instanceof Map<?, ?> blocksTag) {
                paletteTag = (Map<String, Object>) ((Map<String, Object>) blocksTag).get("Palette");
                data = (byte[]) ((Map<String, Object>) blocksTag).get("Data");
            } else {
                paletteTag = (Map<String, Object>) root.get("Palette");
                data = (byte[]) root.get("BlockData");
            }
            long volume = (long) sx * sy * sz;
            if (volume == 0 || volume > Limits.MAX_REGION_VOLUME) {
                throw new ToolException("schematic is " + sx + "x" + sy + "x" + sz + "; the limit is "
                        + Limits.MAX_REGION_VOLUME + " blocks");
            }
            int max = 0;
            for (Object v : paletteTag.values()) {
                max = Math.max(max, ((Number) v).intValue());
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
            Region region = new Region(world, origin, origin.offset(sx - 1, sy - 1, sz - 1));
            return new BlockGrid(region, palette, blocks);
        } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new ToolException(file.getFileName() + " is not a Sponge schematic this tool understands (v2 or v3)");
        }
    }
}
