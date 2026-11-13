package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderFolderTest {
    @TempDir
    Path dir;

    @Test
    void onlyTheNewestRendersAreKept() throws Exception {
        Path latest = null;
        for (int i = 0; i <= WorldTools.KEPT_RENDERS; i++) {
            latest = Files.writeString(dir.resolve("render-" + i + ".png"), "png");
            Files.setLastModifiedTime(latest, FileTime.fromMillis(1_000_000L + i * 1000L));
            WorldTools.pruneRenders(dir, latest, WorldTools.KEPT_RENDERS);
        }
        Files.writeString(dir.resolve("notes.txt"), "kept");
        WorldTools.pruneRenders(dir, latest, WorldTools.KEPT_RENDERS);
        try (Stream<Path> list = Files.list(dir)) {
            assertThat(list.map(p -> p.getFileName().toString()))
                    .hasSize(WorldTools.KEPT_RENDERS + 1)
                    .contains("notes.txt", "render-" + WorldTools.KEPT_RENDERS + ".png")
                    .doesNotContain("render-0.png");
        }
    }
}
