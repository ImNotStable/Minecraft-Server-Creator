package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0091 against local fakes: what server_export writes for Docker and Pterodactyl, what it leaves behind, and its
 * refusals. A real run of both exports on omen is in the ADR.
 */
class ServerExportTest {
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

    private CoreHarness harness(String name) throws Exception {
        Path home = dir.resolve("home");
        return new CoreHarness(Files.createDirectories(dir.resolve(name)), c -> downloads.apply(c)
                .withProxySecret(home.resolve(".msc/velocity.secret")).withMachineEula(MachineEula.forHome(home)));
    }

    /** A Paper server with an own plugin, one installed from Modrinth (recorded), the agent and a world. */
    private static void paperServer(Path run) throws IOException {
        Files.writeString(run.resolve("plugins/MyGame.jar"), "own");
        Files.writeString(run.resolve("plugins/msc-agent-paper.jar"), "agent");
        Files.writeString(run.resolve("plugins/Chunky-1.5.jar"), "chunky");
        Files.writeString(run.resolve(".msc-plugins"), "modrinth:chunky Chunky-1.5.jar\n");
        Files.createDirectories(run.resolve("world/region"));
        Files.writeString(run.resolve("world/level.dat"), "level");
        Files.createDirectories(run.resolve("logs"));
        Files.writeString(run.resolve("logs/latest.log"), "log");
        ServerPropertiesFile.set(run.resolve(ServerPropertiesFile.NAME), java.util.Map.of("server-ip", "127.0.0.1",
                "online-mode", "false", "resource-pack", "http://127.0.0.1:8123/pack.zip"));
    }

    @Test
    void dockerShipsTheServerWithUpdatesAndBackups() throws Exception {
        try (CoreHarness h = harness("paper")) {
            h.ok("server_setup", "platform", "paper");
            Path run = dir.resolve("paper/run");
            paperServer(run);
            String out = h.ok("server_export");
            assertThat(out).startsWith("exported paper for Docker Compose into export/docker")
                    .contains("plugins copied: MyGame.jar").contains("kept up to date by the image: chunky")
                    .contains("backups: itzg/mc-backup every").contains("the world (world) stays behind")
                    .contains("resource-pack pointed at MSC's local pack host");
            Path ex = dir.resolve("paper/export/docker");
            assertThat(ex.resolve("docker-compose.yml")).content().contains("image: itzg/minecraft-server:java25")
                    .contains("TYPE: PAPER\n      VERSION: \"" + FakeDownloads.MINECRAFT + "\"")
                    .contains("MODRINTH_PROJECTS: \"chunky\"").contains("EULA: \"${EULA:-FALSE}\"")
                    .contains("  mc-backups:\n    image: itzg/mc-backup");
            assertThat(ex.resolve(".env")).content().contains("EULA=FALSE\n").containsPattern("RCON_PASSWORD=[0-9a-f]{32}");
            assertThat(names(ex.resolve("data/plugins"))).containsExactly("MyGame.jar");
            assertThat(ex.resolve("data/world")).doesNotExist();
            assertThat(ex.resolve("data/logs")).doesNotExist();
            assertThat(ex.resolve("data/paper.jar")).doesNotExist();
            assertThat(ServerPropertiesFile.read(ex.resolve("data/" + ServerPropertiesFile.NAME)))
                    .containsEntry("online-mode", "true").containsEntry("server-ip", "")
                    .containsEntry("resource-pack", "");
            // A folder with files is never written into.
            assertThat(h.error("server_export")).contains("is not empty: export into a new folder (dir=)");
            // The world on request: only with the server stopped, which it is here.
            h.ok("server_export", "dir", "export/with-world", "world", true);
            assertThat(dir.resolve("paper/export/with-world/data/world/level.dat")).hasContent("level");
        }
    }

    @Test
    void pterodactylGetsAnEggAndTheFiles() throws Exception {
        try (CoreHarness h = harness("ptero")) {
            h.ok("server_setup", "platform", "paper");
            paperServer(dir.resolve("ptero/run"));
            assertThat(h.ok("server_export", "target", "pterodactyl")).contains("egg-paper.json")
                    .contains("plugins in the zip: Chunky-1.5.jar, MyGame.jar");
            Path ex = dir.resolve("ptero/export/pterodactyl");
            JsonObject egg = JsonParser.parseString(Files.readString(ex.resolve("egg-paper.json"))).getAsJsonObject();
            assertThat(egg.getAsJsonObject("meta").get("version").getAsString()).isEqualTo("PTDL_v2");
            assertThat(egg.get("startup").getAsString()).endsWith("-jar {{SERVER_JARFILE}} nogui");
            assertThat(egg.getAsJsonObject("scripts").getAsJsonObject("installation").get("script").getAsString())
                    .contains("https://fill.papermc.io/v3/projects/paper/versions/${VERSION}/builds/latest");
            assertThat(egg.getAsJsonArray("variables").get(0).getAsJsonObject().get("default_value").getAsString())
                    .isEqualTo(FakeDownloads.MINECRAFT);
            assertThat(egg.getAsJsonObject("config").get("stop").getAsString()).isEqualTo("stop");
            try (ZipFile zip = new ZipFile(ex.resolve("server-files.zip").toFile())) {
                assertThat(zip.getEntry("plugins/MyGame.jar")).isNotNull();
                assertThat(zip.getEntry("plugins/msc-agent-paper.jar")).isNull();
                assertThat(zip.getEntry("server.properties")).isNotNull();
            }
        }
    }

    @Test
    void aProxyNetworkIsOneComposeProject() throws Exception {
        try (CoreHarness h = harness("net")) {
            h.ok("server_setup", "platform", "velocity", "servers", "lobby\ngame", "acceptEula", true);
            assertThat(h.ok("server_export")).contains("servers/")
                    .contains("the proxy reaches them as <name>:25565");
            Path ex = dir.resolve("net/export/docker");
            assertThat(ex.resolve("docker-compose.yml")).content().contains("  proxy:\n    image: itzg/mc-proxy:java25")
                    .contains("      TYPE: VELOCITY\n").contains("  lobby:\n    image: itzg/minecraft-server:java25")
                    .contains("      - ./servers/game/data:/data\n").contains("  game-backups:\n")
                    .contains("\"25577:25577\"");
            assertThat(ex.resolve("data/velocity.toml")).content().contains("bind = \"0.0.0.0:25577\"")
                    .contains("lobby = \"lobby:25565\"").contains("game = \"game:25565\"");
            assertThat(ServerPropertiesFile.read(ex.resolve("servers/game/data/" + ServerPropertiesFile.NAME)))
                    .containsEntry("online-mode", "false").containsEntry("server-port", "25565");
            assertThat(h.error("server_export", "target", "pterodactyl", "dir", "export/p"))
                    .contains("a proxy network is several servers");
        }
    }

    @Test
    void refusalsChangeNothing() throws Exception {
        try (CoreHarness h = harness("refuse")) {
            assertThat(h.error("server_export", "target", "kubernetes"))
                    .isEqualTo("error: target must be docker or pterodactyl, not \"kubernetes\"");
            h.ok("server_setup", "platform", "neoforge");
            assertThat(h.error("server_export", "target", "pterodactyl")).contains("neoforge ships with target=docker");
            assertThat(dir.resolve("refuse/export")).doesNotExist();
        }
    }

    private static List<String> names(Path folder) throws IOException {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(folder)) {
            s.filter(Files::isRegularFile).forEach(p -> out.add(p.getFileName().toString()));
        }
        return out;
    }
}
