package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which {@code server.jar} server_setup leaves for the two platforms that share it without installing it (Minestom,
 * Fabric), per state of the folder's marker, and that the reply says when it removed one (#518). A folder with no
 * marker keeps its jar; only a marker that gives the jar to another id removes it. Fake downloads, no real server.
 */
class SharedJarClaimTest {
    private static final String MARKER = ".msc-loader";
    private static FakeDownloads downloads;

    @TempDir
    Path dir;

    @BeforeAll
    static void serve() throws IOException {
        downloads = FakeDownloads.start();
    }

    @AfterAll
    static void stop() {
        downloads.close();
    }

    /** A marker state: what the folder's {@code .msc-loader} says before the setup. */
    enum Marker { NONE, OWN, OTHER, OLD_OWN, OLD_OTHER }

    static Stream<Arguments> table() {
        String fabric = "fabric " + FakeDownloads.MINECRAFT;
        return Stream.of(
                Arguments.of("minestom", "minestom", "neoforge 21.0.1", Marker.NONE, false),
                Arguments.of("minestom", "minestom", "neoforge 21.0.1", Marker.OWN, false),
                Arguments.of("minestom", "minestom", "neoforge 21.0.1", Marker.OLD_OWN, false),
                Arguments.of("minestom", "minestom", "neoforge 21.0.1", Marker.OTHER, true),
                Arguments.of("minestom", "minestom", "neoforge 21.0.1", Marker.OLD_OTHER, true),
                Arguments.of("minestom", "minestom", fabric, Marker.OTHER, true),
                Arguments.of("fabric", fabric, "minestom", Marker.NONE, false),
                Arguments.of("fabric", fabric, "minestom", Marker.OWN, false),
                Arguments.of("fabric", fabric, "minestom", Marker.OLD_OWN, false),
                Arguments.of("fabric", fabric, "minestom", Marker.OTHER, true),
                Arguments.of("fabric", fabric, "minestom", Marker.OLD_OTHER, true),
                Arguments.of("fabric", fabric, "fabric 1.20.1", Marker.OTHER, true));
    }

    @ParameterizedTest(name = "{0} over {2} marker {3}")
    @MethodSource("table")
    void serverSetupRemovesOnlyAJarTheMarkerGivesToAnother(String platform, String own, String other, Marker marker,
                                                           boolean removed) throws Exception {
        Path ws = workspace();
        Path run = Files.createDirectories(ws.resolve("run"));
        Files.writeString(run.resolve("server.jar"), "left jar");
        switch (marker) {
            case NONE -> { }
            case OWN -> Files.writeString(run.resolve(MARKER), own + "\n" + sha256(run.resolve("server.jar")) + " server.jar\n");
            case OTHER -> Files.writeString(run.resolve(MARKER), other + "\n" + sha256(run.resolve("server.jar")) + " server.jar\n");
            case OLD_OWN -> Files.writeString(run.resolve(MARKER), own);
            case OLD_OTHER -> Files.writeString(run.resolve(MARKER), other);
        }
        String out;
        try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
            out = h.ok("server_setup", "platform", platform, "version", FakeDownloads.MINECRAFT);
        }
        if (removed) {
            assertThat(run.resolve("server.jar")).doesNotExist();
            assertThat(out).contains("removed server.jar, which the folder held for " + other + "; ");
        } else {
            assertThat(run.resolve("server.jar")).hasContent("left jar");
            assertThat(out).doesNotContain("removed server.jar");
        }
        assertThat(Files.readString(run.resolve(MARKER)).lines().findFirst()).contains(own);
    }

    /** The case from the issue: a Minestom deploy, with no setup before it, marks the folder, so setup keeps it. */
    @Test
    void aMinestomDeployMarksTheFolderSoSetupKeepsTheProjectJar() throws Exception {
        Path ws = workspace();
        Path project = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(project.resolve("build.gradle.kts"), "dependencies { implementation(\"net.minestom:minestom\") }\n");
        Files.writeString(project.resolve("version.txt"), "1.0");
        try (CoreHarness h = new CoreHarness(ws, c -> downloads.apply(c).withBuildCommand(java(FakeBuildMain.class)))) {
            h.ok("dev_cycle", "project", "demo", "restart", false);
            assertThat(ws.resolve("run/" + MARKER)).content().startsWith("minestom\n");
            assertThat(h.ok("server_setup", "platform", "minestom")).doesNotContain("removed");
            assertThat(ws.resolve("run/server.jar")).hasContent("1.0");
            // A Fabric-marked folder (#516): the deploy takes it over, and Fabric's setup then removes the project jar.
            h.ok("server_setup", "platform", "fabric", "version", FakeDownloads.MINECRAFT);
            Files.writeString(ws.resolve("run/server.jar"), "fabric vanilla");
            h.ok("dev_cycle", "project", "demo", "restart", false);
            assertThat(ws.resolve("run/server.jar")).hasContent("1.0");
            assertThat(h.ok("server_setup", "platform", "fabric", "version", FakeDownloads.MINECRAFT))
                    .contains("removed server.jar, which the folder held for minestom; ");
        }
        assertThat(ws.resolve("run/server.jar")).doesNotExist();
    }

    private Path workspace() throws IOException {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        Path agents = Files.createDirectories(ws.resolve(".msc/agent"));
        for (String agent : List.of("paper", "fabric", "neoforge", "forge")) {
            Files.writeString(agents.resolve("msc-agent-" + agent + "-0.1.0.jar"), "agent " + agent);
        }
        return ws;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }
}
