package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private static final String MINECRAFT = minecraft();
    private static final List<String> AGENTS = List.of("paper", "fabric", "neoforge", "forge");
    private static HttpServer http;
    private static String base;

    @TempDir
    Path dir;

    @BeforeAll
    static void serve() throws IOException {
        byte[] installer = FakeLoaderInstaller.jar();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + http.getAddress().getPort();
        Map<String, String> texts = Map.of(
                "/fill/v3/projects/paper/versions/" + MINECRAFT + "/builds",
                "[{\"id\":5,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-" + MINECRAFT
                        + "-5.jar\",\"url\":\"" + base + "/fill/paper.jar\"}}}]",
                "/fill/paper.jar", "paper server",
                "/purpur/v2/purpur/" + MINECRAFT + "/latest", "{\"build\":\"9\"}",
                "/purpur/v2/purpur/" + MINECRAFT + "/9/download", "purpur server",
                "/meta/v2/versions/installer", "[{\"version\":\"1.1.2\",\"stable\":true}]",
                "/meta/v2/versions/loader/" + MINECRAFT + "/" + Templates.versions().get("fabricLoaderVersion")
                        + "/1.1.2/server/jar", "fabric server");
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            byte[] body;
            if (texts.containsKey(path)) {
                body = texts.get(path).getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/maven/net/fabricmc/fabric-api/fabric-api/") && path.endsWith(".jar")) {
                body = "fabric api".getBytes(StandardCharsets.UTF_8);
            } else if (path.matches("/(neo|forge|quilt)/.*installer.*\\.jar")) {
                body = installer;
            } else {
                body = null;
            }
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        http.start();
    }

    @AfterAll
    static void stop() {
        http.stop(0);
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
        return new CoreHarness(ws, c -> c.withDownloadsUrl(base + "/fill")
                .withFabricUrls(base + "/meta", base + "/maven").withMinecraft(MINECRAFT)
                .withLoaderUrls(new LoaderInstaller.Urls(base + "/neo", base + "/forge", base + "/quilt",
                        base + "/purpur")));
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
        assertThat(out).doesNotContain("internal").doesNotContain("warning")
                .contains("agent installed in " + platform.addonDir() + "/")
                .contains("EULA is not accepted").endsWith("next: server_start or dev_cycle");
        assertThat(run.resolve(platform.serverJar())).hasContent(platform.id() + " server");
        assertThat(run.resolve(platform.addonDir()).resolve(platform.agentJar())).hasContent("agent " + platform.agent());
        assertThat(run.resolve("server.properties")).exists();
        assertThat(Files.readAllLines(run.resolve("eula.txt"))).contains("eula=false").doesNotContain("eula=true");
        if (platform == Platform.FABRIC || platform == Platform.QUILT) {
            assertThat(run.resolve("mods/fabric-api.jar")).hasContent("fabric api");
        }
    }

    private static String minecraft() {
        String api = Templates.versions().get("fabricApiVersion");
        return api.substring(api.indexOf('+') + 1);
    }
}
