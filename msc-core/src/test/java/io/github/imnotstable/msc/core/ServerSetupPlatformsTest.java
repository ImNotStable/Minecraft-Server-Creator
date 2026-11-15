package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * server_setup runs through for every platform, on a workspace without run/ and on one that has it, against local
 * fake download services and fake installers (#367: Purpur failed on a fresh workspace because nothing created run/).
 * No real download, no real server, and the EULA is never accepted.
 */
class ServerSetupPlatformsTest {
    private static final List<String> AGENTS = List.of("paper", "fabric", "neoforge", "forge");
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

    @ParameterizedTest
    @EnumSource(Platform.class)
    void setupWorksOnAFreshWorkspace(Platform platform) throws Exception {
        Path ws = workspace();
        try (CoreHarness s = harness(ws)) {
            assertReady(platform, ws, s.ok("server_setup", "platform", platform.id()));
        }
    }

    @ParameterizedTest
    @EnumSource(Platform.class)
    void setupWorksOnAnExistingServerFolderAndAgain(Platform platform) throws Exception {
        Path ws = workspace();
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/server.properties"), "motd=kept\n");
        try (CoreHarness s = harness(ws)) {
            assertReady(platform, ws, s.ok("server_setup", "platform", platform.id()));
            assertReady(platform, ws, s.ok("server_setup", "platform", platform.id()));
        }
        assertThat(ws.resolve("run/server.properties")).hasContent("motd=kept\n");
    }

    private Path workspace() throws IOException {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        Path agents = Files.createDirectories(ws.resolve(".msc/agent"));
        for (String agent : AGENTS) {
            Files.writeString(agents.resolve("msc-agent-" + agent + "-0.1.0.jar"), "agent " + agent);
        }
        return ws;
    }

    private static CoreHarness harness(Path ws) throws Exception {
        return new CoreHarness(ws, downloads::apply);
    }

    private static void assertReady(Platform platform, Path ws, String out) throws IOException {
        Path run = ws.resolve("run");
        assertThat(run).isDirectory();
        assertThat(ws.resolve(".msc/platform")).hasContent(platform.id() + "\n");
        if (platform == Platform.MINESTOM) {
            assertThat(out).startsWith("minestom needs no server download");
            assertThat(run.resolve("eula.txt")).doesNotExist();
            return;
        }
        if (platform == Platform.VELOCITY) {
            // ADR 0045: a proxy has no EULA step, no agent and no Minecraft defaults; it gets its own config.
            assertThat(out).doesNotContain("internal").doesNotContain("warning").doesNotContain("EULA is not")
                    .contains("velocity " + FakeDownloads.VELOCITY + " build 45").contains("velocity.toml")
                    .endsWith("next: server_start or project_scaffold template=velocity-plugin then dev_cycle");
            assertThat(run.resolve("velocity.jar")).hasContent("velocity server");
            assertThat(run.resolve("velocity.toml")).content().contains("player-info-forwarding-mode = \"MODERN\"");
            assertThat(run.resolve("forwarding.secret")).content().matches("[0-9a-f]{32}");
            assertThat(run.resolve("plugins")).isDirectory().isEmptyDirectory();
            assertThat(run.resolve("eula.txt")).doesNotExist();
            return;
        }
        assertThat(out).doesNotContain("internal").doesNotContain("warning")
                .contains("agent installed in " + platform.addonDir() + "/")
                .contains("EULA is not accepted").endsWith("next: server_start or project_scaffold"
                        + switch (platform) {
                            case FABRIC, QUILT -> " template=fabric-mod";
                            case NEOFORGE -> " template=neoforge-mod";
                            case FORGE -> " template=forge-mod";
                            default -> "";
                        } + " then dev_cycle");
        assertThat(run.resolve(platform.serverJar())).hasContent(platform.id() + " server");
        assertThat(run.resolve(platform.addonDir()).resolve(platform.agentJar())).hasContent("agent " + platform.agent());
        assertThat(run.resolve("server.properties")).exists();
        assertThat(Files.readAllLines(run.resolve("eula.txt"))).contains("eula=false").doesNotContain("eula=true");
        if (platform == Platform.FABRIC || platform == Platform.QUILT) {
            assertThat(run.resolve("mods/fabric-api.jar")).hasContent("fabric api");
        }
    }
}
