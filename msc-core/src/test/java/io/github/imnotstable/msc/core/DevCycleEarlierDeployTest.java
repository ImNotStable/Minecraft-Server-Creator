package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #398: dev_cycle deploys the new jar, then removes the earlier deploy. When the earlier deploy names the same file
 * as the new jar under another spelling (on macOS and Windows {@code Lobby.jar} is {@code lobby.jar}), the new jar
 * stays. Where this file system is case-sensitive, a record through a linked folder spells the new jar another way.
 */
class DevCycleEarlierDeployTest {
    @TempDir
    Path ws;

    private CoreHarness h;
    private Path plugins;

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @BeforeEach
    void open() throws Exception {
        plugins = Files.createDirectories(ws.resolve("run/plugins"));
        h = new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class)));
    }

    @AfterEach
    void close() throws Exception {
        h.close();
    }

    private Path project(String folder, String jarName, String version) throws IOException {
        Path project = Files.createDirectories(ws.resolve(folder));
        Files.writeString(project.resolve("version.txt"), version);
        Files.writeString(project.resolve("name.txt"), jarName);
        return project;
    }

    private List<String> jars() throws IOException {
        try (Stream<Path> list = Files.list(plugins)) {
            return list.map(p -> p.getFileName().toString()).toList();
        }
    }

    /** The first dev_cycle of a project in folder Lobby whose jar is lobby: no record, so Lobby.jar is the guess. */
    @Test
    void aFolderNamedLikeTheJarButForCaseKeepsTheNewJar() throws Exception {
        project("Lobby", "lobby", "2.0");
        boolean caseInsensitive = Files.exists(Files.writeString(plugins.resolve("lobby.jar"), "probe")
                .resolveSibling("Lobby.jar"));
        Files.delete(plugins.resolve("lobby.jar"));
        // Deployed by an older core under the folder name: on a case-sensitive volume another file, which goes.
        Files.writeString(plugins.resolve("Lobby.jar"), "1.0");
        assertThat(h.ok("dev_cycle", "project", "Lobby", "restart", false)).contains("as plugins/lobby.jar");
        assertThat(plugins.resolve("lobby.jar")).hasContent("2.0");
        assertThat(jars()).hasSize(1);
        if (!caseInsensitive) {
            assertThat(jars()).containsExactly("lobby.jar");
        }
    }

    @Test
    void aRecordThatSpellsTheNewJarAnotherWayKeepsIt() throws Exception {
        Path project = project("demo", "arena", "2.0");
        Path same;
        try {
            same = Files.createSymbolicLink(plugins.resolve("same"), Path.of("."));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links: " + e.getMessage());
            return;
        }
        Properties record = new Properties();
        record.setProperty(project.toAbsolutePath().normalize().toString(), "plugins/same/arena.jar");
        Files.createDirectories(ws.resolve(".msc"));
        try (OutputStream out = Files.newOutputStream(ws.resolve(".msc/deployed.properties"))) {
            record.store(out, null);
        }
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("as plugins/arena.jar");
        assertThat(plugins.resolve("arena.jar")).hasContent("2.0");
        assertThat(same.resolve("arena.jar")).hasContent("2.0");
        // The record now names the jar as deployed, and the next dev_cycle keeps it too.
        Files.writeString(project.resolve("version.txt"), "3.0");
        h.ok("dev_cycle", "project", "demo", "restart", false);
        assertThat(plugins.resolve("arena.jar")).hasContent("3.0");
    }

    @Test
    void theSameNameIsReplacedAndAnotherNameRemoved() throws Exception {
        Path project = project("demo", "demo", "1.0");
        h.ok("dev_cycle", "project", "demo", "restart", false);
        Files.writeString(project.resolve("version.txt"), "2.0");
        h.ok("dev_cycle", "project", "demo", "restart", false);
        assertThat(jars()).containsExactly("demo.jar");
        assertThat(plugins.resolve("demo.jar")).hasContent("2.0");
        Files.writeString(project.resolve("name.txt"), "arena");
        h.ok("dev_cycle", "project", "demo", "restart", false);
        assertThat(jars()).containsExactly("arena.jar");
    }
}
