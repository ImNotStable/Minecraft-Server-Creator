package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlatMainWorldTest {
    @Test
    void theMainWorldIsTheDevFloorUnlessServerPropertiesSaysOtherwise(@TempDir Path dir) throws Exception {
        assertThat(WorldTools.flatMainWorld(dir)).isTrue(); // no server.properties: MSC's flat dev world
        Files.writeString(dir.resolve("server.properties"), "level-type=minecraft\\:flat\n");
        assertThat(WorldTools.flatMainWorld(dir)).isTrue();
        Files.writeString(dir.resolve("server.properties"), "level-type=minecraft\\:normal\n");
        assertThat(WorldTools.flatMainWorld(dir)).isFalse();
    }
}
