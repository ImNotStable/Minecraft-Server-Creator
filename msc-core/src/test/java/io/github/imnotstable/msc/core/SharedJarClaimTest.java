package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.templates.Templates;
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
 * Which {@code server.jar} server_setup leaves, so which one server_start would launch, for every ordered pair of the
 * platforms that use that name (NeoForge, Quilt, Fabric, Minestom), per state of the folder's marker. A jar is kept
 * only when the marker names the caller and records the jar's hash (#524); anything else is removed and the reply
 * says so, or, for an installer platform, replaced by the installer's own. Fake downloads, no real server.
 */
class SharedJarClaimTest {
    private static final String MARKER = ".msc-loader";
    private static final List<String> PLATFORMS = List.of("neoforge", "quilt", "fabric", "minestom");
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
    enum Marker { NONE, OWN, OWN_CLAIMED, OWN_WRONG_HASH, OTHER, OTHER_CLAIMED, OLD_FORMAT }

    static Stream<Arguments> table() {
        return PLATFORMS.stream().flatMap(caller -> PLATFORMS.stream().filter(other -> !other.equals(caller))
                .flatMap(other -> Stream.of(Marker.values()).map(m -> Arguments.of(caller, other, m))));
    }

    @ParameterizedTest(name = "{0} over {1}, marker {2}")
    @MethodSource("table")
    void serverSetupKeepsOnlyAJarTheMarkerVouchesForAsTheCallers(String caller, String other, Marker marker)
            throws Exception {
        Path ws = workspace();
        Path run = Files.createDirectories(ws.resolve("run"));
        Path jar = run.resolve("server.jar");
        Files.writeString(jar, "left jar");
        String hash = sha256(jar);
        switch (marker) {
            case NONE -> { }
            case OWN -> Files.writeString(run.resolve(MARKER), id(caller) + "\n" + hash + " server.jar\n");
            // As a claim leaves it, before the launcher's download or the deploy wrote the jar (#548).
            case OWN_CLAIMED -> Files.writeString(run.resolve(MARKER), id(caller) + "\n- server.jar\n");
            case OWN_WRONG_HASH -> Files.writeString(run.resolve(MARKER), id(caller) + "\n" + "0".repeat(64) + " server.jar\n");
            case OTHER -> Files.writeString(run.resolve(MARKER), id(other) + "\n" + hash + " server.jar\n");
            case OTHER_CLAIMED -> Files.writeString(run.resolve(MARKER), id(other) + "\n- server.jar\n");
            case OLD_FORMAT -> Files.writeString(run.resolve(MARKER), id(caller));
        }
        String out;
        String start;
        try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
            // NeoForge takes its own version; Minestom takes none (#619).
            out = caller.equals("neoforge") || caller.equals("minestom") ? h.ok("server_setup", "platform", caller)
                    : h.ok("server_setup", "platform", caller, "version", FakeDownloads.MINECRAFT);
            // Only where nothing would launch: a Minestom folder without a jar refuses before starting anything.
            start = caller.equals("minestom") && !Files.exists(jar) ? h.error("server_start") : null;
        }
        assertThat(Files.readString(run.resolve(MARKER)).lines().findFirst()).contains(id(caller));
        switch (caller) {
            case "neoforge", "quilt" -> {
                // The installer replaces whatever was there with its own jar (no libraries, so never "cached").
                assertThat(jar).hasContent(caller.equals("neoforge") ? "neoforge server" : "quilt vanilla");
                assertThat(out).contains("(installed)").doesNotContain("removed server.jar");
            }
            default -> {
                if (marker == Marker.OWN || marker == Marker.OWN_CLAIMED) {
                    assertThat(jar).hasContent("left jar");
                    assertThat(out).doesNotContain("removed server.jar");
                    assertThat(run.resolve(MARKER)).hasContent(id(caller) + "\n" + hash + " server.jar\n");
                    return;
                }
                assertThat(jar).doesNotExist();
                String next = caller.equals("fabric") ? "; the launcher downloads the vanilla server on first start\n"
                        : "; dev_cycle deploys this project's jar there\n";
                String why = switch (marker) {
                    case NONE -> ": no marker says which platform it belongs to";
                    case OTHER, OTHER_CLAIMED -> ", which the folder held for " + id(other);
                    default -> ": the marker for " + id(caller) + " records no matching hash for it";
                };
                assertThat(out).contains("removed server.jar" + why + next);
                assertThat(out.lines().filter(l -> l.contains("removed server.jar")).count()).isEqualTo(1);
                if (start != null) {
                    assertThat(start).contains("dev_cycle");
                }
            }
        }
    }

    private static String id(String platform) {
        return switch (platform) {
            case "neoforge" -> "neoforge " + Templates.versions().get("neoforgeVersion");
            case "quilt" -> "quilt " + FakeDownloads.MINECRAFT + " " + Templates.versions().get("quiltLoaderVersion");
            case "fabric" -> "fabric " + FakeDownloads.MINECRAFT;
            default -> platform;
        };
    }

    /** #548: the vanilla jar Fabric's launcher downloaded at first start survives any later setup of that version. */
    @Test
    void aFabricSetupAgainKeepsTheVanillaJarItsLauncherDownloaded() throws Exception {
        Path ws = workspace();
        Path jar = ws.resolve("run/server.jar");
        try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
            h.ok("server_setup", "platform", "fabric", "version", FakeDownloads.MINECRAFT);
            Files.writeString(jar, "fabric vanilla"); // what the launcher downloads on first start
            assertThat(h.ok("server_setup", "mode", "game")).doesNotContain("removed");
            assertThat(h.ok("server_setup", "platform", "fabric", "version", FakeDownloads.MINECRAFT))
                    .doesNotContain("removed");
            assertThat(jar).hasContent("fabric vanilla");
            // Now recorded: a jar changed since is no longer the launcher's.
            Files.writeString(jar, "changed");
            assertThat(h.ok("server_setup", "mode", "game")).contains("removed server.jar: the marker for fabric "
                    + FakeDownloads.MINECRAFT + " records no matching hash for it");
        }
        assertThat(jar).doesNotExist();
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
            // A Fabric workspace does not run the Minestom project (ADR 0048); one remembered as Minestom over the
            // Fabric folder (an older core, a hand edit) does.
            assertThat(h.error("dev_cycle", "project", "demo", "restart", false))
                    .startsWith("error: demo is a Minestom project, but this workspace runs Fabric");
            Platform.remember(h.core.config(), Platform.MINESTOM);
            assertThat(h.ok("dev_cycle", "project", "demo", "restart", false))
                    .contains("removed server.jar, which the folder held for fabric " + FakeDownloads.MINECRAFT + "; ");
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
