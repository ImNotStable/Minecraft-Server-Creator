package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The game's structure files and Litematica schematics load through the one structure file rule (ADR 0083). */
class StructureFormatsTest {
    @TempDir
    Path dir;

    private Path write(String name, Map<String, Object> root) throws Exception {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            Nbt.write(out, "", root);
        }
        return file;
    }

    private static Map<String, Object> entry(String name, Map<String, Object> props) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("Name", name);
        if (props != null) {
            e.put("Properties", props);
        }
        return e;
    }

    @Test
    void vanillaStructureFilesKeepStatesDataAndVoid() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4000);
        root.put("size", List.of(2, 1, 1));
        root.put("palette", List.of(entry("minecraft:chest", Map.of("facing", "west", "type", "single")),
                entry("minecraft:structure_void", null)));
        Map<String, Object> chest = new LinkedHashMap<>();
        chest.put("pos", List.of(0, 0, 0));
        chest.put("state", 0);
        chest.put("nbt", Map.of("id", "minecraft:chest", "Lock", "key"));
        root.put("blocks", List.of(chest, Map.of("pos", List.of(1, 0, 0), "state", 1)));
        BlockGrid g = StructureFile.load(write("house.nbt", root), "world", new BlockPos(10, 64, 10));
        assertThat(g.stateAt(10, 64, 10)).isEqualTo("minecraft:chest[facing=west,type=single]{Lock:\"key\"}");
        assertThat(g.blocks()[1]).isEqualTo(-1);
    }

    @Test
    void litematicsUnpackStatesAcrossLongsAndNegativeSizes() throws Exception {
        // 3 states -> 2 bits each; 40 cells span more than one long, so entries run across the boundary.
        int sx = 5;
        int sy = 2;
        int sz = 4;
        int cells = sx * sy * sz;
        long[] packed = new long[(cells * 2 + 63) / 64];
        int[] want = new int[cells];
        for (int i = 0; i < cells; i++) {
            want[i] = i % 3;
            long bit = (long) i * 2;
            packed[(int) (bit >>> 6)] |= (long) want[i] << (bit & 63);
        }
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", Map.of("x", 0, "y", 0, "z", 0));
        region.put("Size", Map.of("x", sx, "y", sy, "z", -sz)); // grows back toward -z
        region.put("BlockStatePalette", List.of(entry("minecraft:air", null), entry("minecraft:stone", null),
                entry("minecraft:oak_log", Map.of("axis", "x"))));
        region.put("BlockStates", packed);
        region.put("TileEntities", List.of());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 6);
        root.put("Regions", Map.of("main", region));
        BlockGrid g = StructureFile.load(write("tower.litematic", root), "world", new BlockPos(0, 0, 0));
        assertThat(g.region().sizeX()).isEqualTo(sx);
        assertThat(g.region().sizeZ()).isEqualTo(sz);
        for (int i = 0; i < cells; i++) {
            assertThat(g.palette().get(g.blocks()[i])).isEqualTo(List.of("minecraft:air", "minecraft:stone",
                    "minecraft:oak_log[axis=x]").get(want[i]));
        }
    }

    @Test
    void legacySchematicsAreNamedNotMisread() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Width", (short) 1);
        root.put("Height", (short) 1);
        root.put("Length", (short) 1);
        root.put("Materials", "Alpha");
        root.put("Blocks", new byte[] {1});
        Path file = write("old.schematic", root);
        assertThatThrownBy(() -> StructureFile.load(file, "world", new BlockPos(0, 0, 0)))
                .isInstanceOf(ToolException.class).hasMessageContaining("save it as .schem");
    }
}
