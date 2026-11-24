package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.tools.StepsAccess;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #399: every file a tool writes whole (through a temp file, since #388) ends with the permissions it had before
 * #388. A new file gets what the plain write it replaced gave: 0666 minus the umask, or for a copy the source's mode
 * minus the umask; never the 0600 of a temp file. A file that was there keeps its permissions: as the in-place writes
 * before #388 did (structure_save, deployed.properties, server.properties, the pack zip), and now also where the
 * earlier write recreated the file (the copies and ImageIO).
 */
class OutputPermissionsTest {
    /** No umask gives this, so a kept mode cannot be mistaken for a default one. */
    private static final Set<PosixFilePermission> ODD = PosixFilePermissions.fromString("rw----r--");

    @TempDir
    Path ws;

    @BeforeEach
    void posix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    /** What a plain Files.write (or a Files.copy of a file it wrote) gives a new file here. */
    private Set<PosixFilePermission> plainNew() throws IOException {
        Path probe = Files.writeString(ws.resolve("probe"), "");
        try {
            return Files.getPosixFilePermissions(probe);
        } finally {
            Files.delete(probe);
        }
    }

    private static Set<PosixFilePermission> mode(Path file) throws IOException {
        return Files.getPosixFilePermissions(file);
    }

    private static Path odd(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, ODD);
        return file;
    }

    @Test
    void structureSave() throws Exception {
        try (CoreHarness h = new CoreHarness(ws).withAgent()) {
            h.ok("structure_save", "region", "0,60,0 1,61,1", "file", "s/new.schem");
            assertThat(mode(ws.resolve("s/new.schem"))).isEqualTo(plainNew());
            Path old = odd(ws.resolve("s/old.schem"), "old");
            h.ok("structure_save", "region", "0,60,0 1,61,1", "file", "s/old.schem");
            assertThat(mode(old)).isEqualTo(ODD);
        }
    }

    @Test
    void textureToAPng() throws Exception {
        try (CoreHarness h = new CoreHarness(ws)) {
            h.ok("texture", "name", "art/new.png", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
            assertThat(mode(ws.resolve("art/new.png"))).isEqualTo(plainNew());
            Path old = odd(ws.resolve("art/old.png"), "old");
            h.ok("texture", "name", "art/old.png", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
            assertThat(mode(old)).isEqualTo(ODD);
        }
    }

    @Test
    void packFile() throws Exception {
        try (CoreHarness h = new CoreHarness(ws)) {
            h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
            h.ok("pack", "file", "out/new.zip");
            assertThat(mode(ws.resolve("out/new.zip"))).isEqualTo(plainNew());
            Path old = odd(ws.resolve("out/old.zip"), "old");
            h.ok("pack", "file", "out/old.zip");
            assertThat(mode(old)).isEqualTo(ODD);
        }
    }

    /** A pack whose send fails takes server.properties and the served zip back: both keep their permissions. */
    @Test
    void packTakeBack() throws Exception {
        try (CoreHarness h = new CoreHarness(ws).withAgent()) {
            h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
            String built = h.ok("pack");
            String url = built.lines().filter(l -> l.startsWith("url: ")).findFirst().orElseThrow().substring(5);
            Path properties = odd(ws.resolve("run/server.properties"), "motd=kept\nresource-pack=" + url + "\n");
            Path zip = ws.resolve(".msc/build/pack.zip");
            Files.setPosixFilePermissions(zip, ODD);
            h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#2655d4"), "rows", Drawings.rows());
            StepsAccess.failAt("send it to the server's players");
            String error;
            try {
                error = h.error("pack");
            } finally {
                StepsAccess.failAt(null);
            }
            assertThat(error).contains("taken back");
            assertThat(properties).content().contains(url);
            assertThat(mode(properties)).isEqualTo(ODD);
            assertThat(mode(zip)).isEqualTo(ODD);
        }
    }

    @Test
    void devCycleJarAndDeployRecord() throws Exception {
        Path project = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        Files.createDirectories(ws.resolve("run/plugins"));
        try (CoreHarness h = new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class)))) {
            h.ok("dev_cycle", "project", "demo", "restart", false);
            Path jar = ws.resolve("run/plugins/demo.jar");
            Path record = ws.resolve(".msc/deployed.properties");
            assertThat(mode(jar)).isEqualTo(plainNew());
            assertThat(mode(record)).isEqualTo(plainNew());
            Files.setPosixFilePermissions(jar, ODD);
            Files.setPosixFilePermissions(record, ODD);
            Files.writeString(project.resolve("version.txt"), "2.0");
            h.ok("dev_cycle", "project", "demo", "restart", false);
            assertThat(jar).hasContent("2.0");
            assertThat(mode(jar)).isEqualTo(ODD);
            assertThat(mode(record)).isEqualTo(ODD);
        }
    }
}
