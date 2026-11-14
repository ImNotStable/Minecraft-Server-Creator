package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.tools.LifecycleToolsAccess;
import java.io.IOException;
import java.io.InputStream;
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
 * #405: a dev_cycle that ends leaves the deploy record naming what is deployed, so a state that tripped one call does
 * not trip every later one. Each case is a state that used to fail every dev_cycle the same way: the second call here
 * must go through like any other.
 */
class DevCycleRecoveryTest {
    @TempDir
    Path ws;

    private CoreHarness h;
    private Path project;
    private Path plugins;

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @BeforeEach
    void open() throws Exception {
        project = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(Files.createDirectories(project.resolve("src/main/resources")).resolve("plugin.yml"),
                "name: Arena\n");
        Files.writeString(project.resolve("version.txt"), "2.0");
        Files.writeString(project.resolve("name.txt"), "arena");
        plugins = Files.createDirectories(ws.resolve("run/plugins"));
        h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)));
    }

    @AfterEach
    void close() throws Exception {
        LifecycleToolsAccess.sameFile(null);
        try {
            h.call("server_stop");
        } finally {
            h.close();
        }
    }

    private Path record() {
        return ws.resolve(".msc/deployed.properties");
    }

    private String recorded() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(record())) {
            p.load(in);
        }
        return p.getProperty(project.toAbsolutePath().normalize().toString());
    }

    private List<String> jars() throws IOException {
        try (Stream<Path> list = Files.list(plugins)) {
            return list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).toList();
        }
    }

    /**
     * A volume that reports two names of one file as two files, and where removing one removes both: the record
     * spells the jar through a linked folder, and the sameness check is made to answer "different".
     */
    @Test
    void aVolumeThatHidesOneFileUnderTwoNamesEndsConsistentAndTheNextCallIsOrdinary() throws Exception {
        try {
            Files.createSymbolicLink(plugins.resolve("same"), Path.of("."));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links: " + e.getMessage());
        }
        Files.writeString(plugins.resolve("arena.jar"), "1.0");
        Properties p = new Properties();
        p.setProperty(project.toAbsolutePath().normalize().toString(), "plugins/same/arena.jar");
        Files.createDirectories(record().getParent());
        try (OutputStream out = Files.newOutputStream(record())) {
            p.store(out, null);
        }
        h.ok("server_start", "timeoutSeconds", 60);
        LifecycleToolsAccess.sameFile((a, b) -> false);

        String first = h.ok("dev_cycle", "project", "demo");
        assertThat(first).contains("is the new jar under another name on this volume, so it was kept")
                .contains("server ready");
        assertThat(plugins.resolve("arena.jar")).hasContent("2.0");
        assertThat(recorded()).isEqualTo("plugins/arena.jar");
        assertThat(h.core.server().running()).isTrue();

        Files.writeString(project.resolve("version.txt"), "3.0");
        String second = h.ok("dev_cycle", "project", "demo");
        assertThat(second).doesNotContain("another name").contains("server ready");
        assertThat(plugins.resolve("arena.jar")).hasContent("3.0");
        assertThat(jars()).containsExactly("arena.jar");
        assertThat(h.core.server().running()).isTrue();
    }

    /** A record no Properties reader can load (a broken unicode escape) is replaced, not read again at every call. */
    @Test
    void anUnreadableRecordIsReplaced() throws Exception {
        Files.createDirectories(record().getParent());
        Files.writeString(record(), "broken=\\uZZZZ\n");
        Files.writeString(plugins.resolve("demo.jar"), "1.0");
        h.ok("dev_cycle", "project", "demo", "restart", false);
        assertThat(jars()).containsExactly("arena.jar");
        assertThat(recorded()).isEqualTo("plugins/arena.jar");
        Files.writeString(project.resolve("version.txt"), "3.0");
        h.ok("dev_cycle", "project", "demo", "restart", false);
        assertThat(plugins.resolve("arena.jar")).hasContent("3.0");
    }
}
