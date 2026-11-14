package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #417: what each loader loads from its scan folder (the facts in {@link ScanFolders.Loader}, read from the loaders'
 * code), and that no name msc-core gives a temp or aside file is among them, on any loader.
 */
class ScanFoldersTest {
    @TempDir
    Path dir;

    private static final ScanFolders.Loader PAPER = ScanFolders.Loader.PAPER;
    private static final ScanFolders.Loader FABRIC = ScanFolders.Loader.FABRIC;
    private static final ScanFolders.Loader QUILT = ScanFolders.Loader.QUILT;
    private static final ScanFolders.Loader NEOFORGE = ScanFolders.Loader.NEOFORGE;
    private static final ScanFolders.Loader FORGE = ScanFolders.Loader.FORGE;

    private record Shape(String name, boolean folder, Set<ScanFolders.Loader> loadedBy) {
    }

    private static Set<ScanFolders.Loader> none() {
        return EnumSet.noneOf(ScanFolders.Loader.class);
    }

    @Test
    void whatEachLoaderLoads() {
        List<Shape> table = List.of(
                new Shape("lobby.jar", false, EnumSet.allOf(ScanFolders.Loader.class)),
                new Shape(".lobby.jar", false, EnumSet.of(PAPER, NEOFORGE, FORGE)),
                new Shape("lobby.jar.msc-removing", false, EnumSet.of(QUILT)),
                new Shape("lobby.zip", false, EnumSet.of(QUILT)),
                new Shape("lobby", true, EnumSet.of(QUILT)),
                new Shape("lobby.jar.disabled", false, none()),
                new Shape(".lobby.jar.msc-removing", false, none()),
                new Shape(".lobby.jar.k3x9.part", false, none()));
        for (Shape shape : table) {
            for (ScanFolders.Loader loader : ScanFolders.Loader.values()) {
                assertThat(loader.loads(shape.name(), shape.folder())).as("%s loads %s", loader, shape.name())
                        .isEqualTo(shape.loadedBy().contains(loader));
            }
        }
    }

    @Test
    void ourNamesLoadNowhereAndOurFolderIsOutsideTheScanFolders() throws IOException {
        Path server = dir.resolve("run");
        Path mods = Files.createDirectories(server.resolve("mods"));
        Path earlier = mods.resolve("lobby.jar");
        String besideAside = ScanFolders.aside(earlier, server, true).getFileName().toString();
        assertThat(ScanFolders.aside(earlier, server, true).getParent()).isEqualTo(mods);
        // The temp name AtomicFile gives a write beside its target, the fallback when staging is on another volume.
        Path written = Files.writeString(mods.resolve("x.jar"), "x");
        AtomicFile.replace(written, to -> {
            try (Stream<Path> list = Files.list(mods)) {
                List<String> temps = list.map(p -> p.getFileName().toString()).filter(AtomicFile::isTempName).toList();
                assertThat(temps).hasSize(1);
                for (ScanFolders.Loader loader : ScanFolders.Loader.values()) {
                    assertThat(loader.loads(temps.getFirst(), false)).as("%s loads %s", loader, temps).isFalse();
                }
                assertThat(ScanFolders.leftover(temps.getFirst())).isTrue();
            }
            Files.writeString(to, "y");
        });
        for (ScanFolders.Loader loader : ScanFolders.Loader.values()) {
            assertThat(loader.loads(besideAside, false)).as("%s loads %s", loader, besideAside).isFalse();
        }
        Path staging = ScanFolders.staging(server);
        for (String folder : List.of("plugins", "mods", "libs")) {
            assertThat(staging.startsWith(server.resolve(folder))).isFalse();
        }
        assertThat(staging.getParent().getFileName().toString()).startsWith(".");
        assertThat(ScanFolders.leftover(besideAside)).isTrue();
        assertThat(ScanFolders.leftover("lobby.jar.msc-removing")).as("the aside name of cores before #417").isTrue();
        assertThat(ScanFolders.leftover("lobby.jar")).isFalse();
        assertThat(ScanFolders.leftover(".hidden.jar")).isFalse();
    }

    @Test
    void aCopyLeavesNothingInTheScanFolderAndTheSweepTakesOnlyOurs() throws IOException {
        Path server = dir.resolve("run");
        Path mods = Files.createDirectories(server.resolve("mods"));
        Path source = Files.writeString(dir.resolve("built.jar"), "2.0");
        ScanFolders.copy(source, mods.resolve("lobby.jar"), server);
        assertThat(entries(mods)).containsExactly("lobby.jar");
        assertThat(mods.resolve("lobby.jar")).hasContent("2.0");
        Files.writeString(mods.resolve("lobby.jar.msc-removing"), "old");
        Files.writeString(mods.resolve(".lobby.jar.msc-removing"), "old");
        Files.writeString(mods.resolve(".lobby.jar.1a2b3c.part"), "half");
        Files.writeString(mods.resolve(".user-notes"), "kept");
        Files.writeString(Files.createDirectories(ScanFolders.staging(server).resolve("x")).resolve("y"), "z");
        Files.writeString(ScanFolders.staging(server).resolve("lobby.jar.msc-removing"), "old");
        assertThat(ScanFolders.sweep(server, List.of(mods, server.resolve("plugins")))).hasSize(5);
        assertThat(entries(mods)).containsExactlyInAnyOrder("lobby.jar", ".user-notes");
        assertThat(entries(ScanFolders.staging(server))).isEmpty();
    }

    private static List<String> entries(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(p -> p.getFileName().toString()).toList();
        }
    }
}
