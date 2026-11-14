package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.tool.PathArg;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #474: a name the length check lets through (up to 255 bytes) is written by every tool that writes a named file. */
class LongestNameWritesTest {
    @TempDir
    Path workspace;
    private CoreHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace).withAgent();
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    private static String name(char c, String suffix) {
        return String.valueOf(c).repeat(PathArg.MAX_NAME_BYTES - suffix.length()) + suffix;
    }

    @Test
    void theLongestNameIsWrittenByEveryWriter() throws Exception {
        String png = name('c', ".png");
        assertThat(h.ok("texture", "name", png, "kind", "gem", "color", "#d4263a")).startsWith("wrote ");
        assertThat(workspace.resolve(png)).isRegularFile();

        // In the pack the longest file is the animation's name.png.mcmeta.
        String id = "d".repeat(PathArg.MAX_NAME_BYTES - ".png.mcmeta".length());
        h.ok("texture", "name", "item/" + id, "kind", "gem", "color", "#d4263a", "animate", "pulse");
        Path textures = workspace.resolve(".msc/pack/assets/msc/textures/item");
        assertThat(textures.resolve(id + ".png.mcmeta")).isRegularFile();

        String zip = name('e', ".zip");
        h.ok("pack", "file", zip, "apply", false);
        assertThat(workspace.resolve(zip)).isRegularFile();

        h.ok("build_batch", "steps", "set 0,64,0 stone");
        String schem = name('f', ".schem");
        assertThat(h.ok("structure_save", "region", "0,64,0 0,64,0", "file", schem)).startsWith("saved ");
        assertThat(Files.size(workspace.resolve(schem))).isPositive();

        try (var tree = Files.walk(workspace)) {
            assertThat(tree.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".part"));
        }
    }
}
