package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevCycleMirrorTest {
    @TempDir
    Path dir;

    @Test
    void copiesOnlyChangedJarsAndDropsOnesNoLongerThere() throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path dest = dir.resolve("dest");
        Files.writeString(src.resolve("a.jar"), "a");
        Files.writeString(src.resolve("b.jar"), "b");
        assertThat(DevCycle.mirror(src, dest, true)).isEqualTo(2);
        assertThat(dest).doesNotExist();
        assertThat(DevCycle.mirror(src, dest, false)).isEqualTo(2);
        assertThat(DevCycle.mirror(src, dest, true)).isZero();

        Files.writeString(src.resolve("b.jar"), "B");
        Files.writeString(dest.resolve("gone.jar"), "x");
        assertThat(DevCycle.mirror(src, dest, false)).isEqualTo(2);
        assertThat(dest.resolve("b.jar")).hasContent("B");
        assertThat(dest.resolve("gone.jar")).doesNotExist();
    }
}
