package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

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
}
