package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchematicTest {
    @TempDir
    Path dir;

    @Test
    void savedFilesCarryTheDataVersionOf26_3() throws Exception {
        Path file = dir.resolve("one.schem");
        Schematic.save(new BlockGrid(Region.of("world", 0, 0, 0, 0, 0, 0), List.of("minecraft:stone"), new int[] {0}), file);
        try (InputStream in = Files.newInputStream(file)) {
            assertThat(Nbt.read(in)).containsEntry("DataVersion", 5023);
        }
    }

    @Test
    void readsVersion3WithMultiByteIndices() throws Exception {
        Map<String, Object> palette = new LinkedHashMap<>();
        palette.put("minecraft:stone", 0);
        palette.put("minecraft:glass", 200);
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", palette);
        blocks.put("Data", new byte[] {0, (byte) 0xC8, 0x01});
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("Version", 3);
        inner.put("DataVersion", 4189);
        inner.put("Width", (short) 2);
        inner.put("Height", (short) 1);
        inner.put("Length", (short) 1);
        inner.put("Blocks", blocks);
        Path file = write("v3.schem", Map.of("Schematic", inner));

        BlockGrid g = Schematic.load(file, "world", new BlockPos(10, 64, 0));
        assertThat(g.region()).isEqualTo(Region.of("world", 10, 64, 0, 11, 64, 0));
        assertThat(g.stateAt(10, 64, 0)).isEqualTo("minecraft:stone");
        assertThat(g.stateAt(11, 64, 0)).isEqualTo("minecraft:glass");
    }

    @Test
    void paletteAbove127StatesRoundTrips() throws Exception {
        List<String> palette = new ArrayList<>();
        int[] blocks = new int[200];
        for (int i = 0; i < blocks.length; i++) {
            palette.add("minecraft:block_" + i);
            blocks[i] = blocks.length - 1 - i;
        }
        Path file = dir.resolve("wide.schem");
        Schematic.save(new BlockGrid(Region.of("world", 0, 0, 0, 199, 0, 0), palette, blocks), file);

        BlockGrid g = Schematic.load(file, "world", new BlockPos(0, 0, 0));
        assertThat(g.stateAt(0, 0, 0)).isEqualTo("minecraft:block_199");
        assertThat(g.stateAt(72, 0, 0)).isEqualTo("minecraft:block_127");
        assertThat(g.stateAt(199, 0, 0)).isEqualTo("minecraft:block_0");
    }

    @Test
    void malformedFilesGiveToolErrors() throws Exception {
        Path text = dir.resolve("text.schem");
        Files.writeString(text, "not a schematic");
        assertThatThrownBy(() -> Schematic.load(text, "world", new BlockPos(0, 0, 0)))
                .isInstanceOf(ToolException.class).hasMessageContaining("cannot read").hasMessageContaining("as a schematic");

        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("Width", (short) 1);
        legacy.put("Height", (short) 1);
        legacy.put("Length", (short) 1);
        legacy.put("Materials", "Alpha");
        legacy.put("Blocks", new byte[] {1});
        Path old = write("old.schematic", legacy);
        assertThatThrownBy(() -> Schematic.load(old, "world", new BlockPos(0, 0, 0)))
                .isInstanceOf(ToolException.class).hasMessage("old.schematic is not a Sponge schematic this tool understands (v2 or v3)");
    }

    private Path write(String name, Map<String, Object> root) throws Exception {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            Nbt.write(out, "", root);
        }
        return file;
    }
}
