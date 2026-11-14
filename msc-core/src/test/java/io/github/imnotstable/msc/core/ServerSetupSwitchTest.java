package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.server.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #513: switching a server folder from platform A to B and back leaves A's start jar A's. Several platforms use the
 * same file name (server.jar: NeoForge's starter, Minestom's project, the vanilla jar Fabric's launcher downloads and
 * Quilt's installer writes), so "already there" must mean "this platform's", not "a file by that name". Fake download
 * services and installers; no real download, no real server, the EULA is never accepted.
 */
class ServerSetupSwitchTest {
    /** Who may own server.jar, by what the fakes write; Paper, Purpur and Forge never read it. */
    private static final Map<Platform, String> SHARED_JAR = Map.of(Platform.NEOFORGE, "neoforge server",
            Platform.QUILT, "quilt vanilla", Platform.FABRIC, "fabric vanilla", Platform.MINESTOM, "minestom project");
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

    static Stream<Arguments> pairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (Platform a : Platform.values()) {
            for (Platform b : Platform.values()) {
                if (a != b) {
                    pairs.add(Arguments.of(a, b));
                }
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} -> {0}")
    @MethodSource("pairs")
    void aPlatformSetUpAgainStartsItsOwnJar(Platform a, Platform b) throws Exception {
        Path ws = workspace();
        Path run = ws.resolve("run");
        try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
            h.ok("server_setup", "platform", a.id());
            deployAndStart(a, run);
            h.ok("server_setup", "platform", b.id());
            deployAndStart(b, run);
            h.ok("server_setup", "platform", a.id());
        }
        if (a != Platform.MINESTOM) {
            assertThat(run.resolve(a.serverJar())).hasContent(a.id() + " server");
        }
        Path shared = run.resolve("server.jar");
        if (SHARED_JAR.containsKey(a) && Files.exists(shared)) {
            assertThat(shared).as("server.jar, which %s starts", a).hasContent(SHARED_JAR.get(a));
        }
    }

    /** The case from the issue, with a real dev_cycle: NeoForge, then Minestom and a deploy, then NeoForge again. */
    @Test
    void neoforgeAfterAMinestomDeployIsInstalledAgain() throws Exception {
        Path ws = workspace();
        Path project = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(project.resolve("build.gradle.kts"), "dependencies { implementation(\"net.minestom:minestom\") }\n");
        Files.writeString(project.resolve("version.txt"), "1.0");
        try (CoreHarness h = new CoreHarness(ws, c -> downloads.apply(c).withBuildCommand(java(FakeBuildMain.class)))) {
            assertThat(h.ok("server_setup", "platform", "neoforge")).contains("(installed)");
            h.ok("server_setup", "platform", "minestom");
            h.ok("dev_cycle", "project", "demo");
            assertThat(ws.resolve("run/server.jar")).content().isNotEqualTo("neoforge server");
            String again = h.ok("server_setup", "platform", "neoforge");
            assertThat(again).contains("(installed)").doesNotContain("(already there)");
        }
        assertThat(ws.resolve("run/server.jar")).hasContent("neoforge server");
    }

    /** What B leaves in the folder before the switch back: a deploy, and for Fabric the launcher's first start. */
    private static void deployAndStart(Platform p, Path run) throws IOException {
        if (p == Platform.MINESTOM) {
            Files.writeString(run.resolve("server.jar"), "minestom project");
            return;
        }
        Files.writeString(run.resolve(p.addonDir()).resolve("project.jar"), p.id() + " project");
        if (p == Platform.FABRIC && !Files.exists(run.resolve("server.jar"))) {
            Files.writeString(run.resolve("server.jar"), "fabric vanilla");
        }
    }

    private Path workspace() throws IOException {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        Path agents = Files.createDirectories(ws.resolve(".msc/agent"));
        for (String agent : List.of("paper", "fabric", "neoforge", "forge")) {
            Files.writeString(agents.resolve("msc-agent-" + agent + "-0.1.0.jar"), "agent " + agent);
        }
        return ws;
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }
}
