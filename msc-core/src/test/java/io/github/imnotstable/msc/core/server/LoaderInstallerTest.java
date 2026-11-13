package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoaderInstallerTest {
    @TempDir
    Path dir;

    private HttpServer http;
    private final List<String> requests = new ArrayList<>();
    private LoaderInstaller installer;

    @BeforeEach
    void serve() throws IOException {
        Map<String, String> files = Map.of(
                "/purpur/v2/purpur/26.3/latest", "{\"project\":\"purpur\",\"version\":\"26.3\",\"build\":\"2658\"}",
                "/purpur/v2/purpur/26.3/2658/download", "purpur jar",
                "/neo/net/neoforged/neoforge/26.3.0.1/neoforge-26.3.0.1-installer.jar", "not a jar");
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            requests.add(ex.getRequestURI().getPath());
            String body = files.get(ex.getRequestURI().getPath());
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        http.start();
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        installer = new LoaderInstaller(new LoaderInstaller.Urls(base + "/neo", base + "/forge", base + "/quilt",
                base + "/purpur/"), dir.resolve("cache"), java);
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    @Test
    void purpurIsTheNewestBuildAndIsCachedAfterwards() throws Exception {
        Path server = Files.createDirectories(dir.resolve("server"));
        assertThat(installer.purpur("26.3", server)).isEqualTo(new LoaderInstaller.Result("26.3 build 2658", false));
        assertThat(server.resolve("purpur.jar")).hasContent("purpur jar");
        assertThat(installer.purpur("26.3", server).cached()).isTrue();
        assertThat(requests).containsOnlyOnce("/purpur/v2/purpur/26.3/2658/download");
        assertThatThrownBy(() -> installer.purpur("1.0", server)).isInstanceOf(ToolException.class)
                .hasMessage("Purpur has no version 1.0");
    }

    @Test
    void anInstalledLoaderIsNotInstalledAgain() throws Exception {
        Path server = Files.createDirectories(dir.resolve("server"));
        Files.createDirectories(server.resolve("libraries/net/neoforged/neoforge/26.3.0.1"));
        Files.writeString(server.resolve("server.jar"), "starter");
        Files.createDirectories(server.resolve("libraries/org/quiltmc/quilt-loader/0.30.2"));
        Files.writeString(server.resolve("quilt-server-launch.jar"), "launcher");

        Files.writeString(server.resolve(LoaderInstaller.MARKER), "neoforge 26.3.0.1");
        assertThat(installer.neoforge("26.3.0.1", server).cached()).isTrue();
        Files.writeString(server.resolve(LoaderInstaller.MARKER), "quilt 26.3 0.30.2");
        assertThat(installer.quilt("26.3", "0.30.2", "0.15.1", server).cached()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void aDirectoryInstalledAtAnotherVersionIsInstalledAgain() throws Exception {
        Path server = Files.createDirectories(dir.resolve("server"));
        Files.createDirectories(server.resolve("libraries/org/quiltmc/quilt-loader/0.30.2"));
        Files.writeString(server.resolve("quilt-server-launch.jar"), "launcher");
        Files.writeString(server.resolve(LoaderInstaller.MARKER), "quilt 26.2 0.30.2");
        assertThatThrownBy(() -> installer.quilt("26.3", "0.30.2", "0.15.1", server))
                .hasMessage("Quilt has no installer 0.15.1");

        Files.createDirectories(server.resolve("libraries/net/neoforged/neoforge/26.3.0.1"));
        Files.writeString(server.resolve("server.jar"), "starter");
        Files.writeString(server.resolve(LoaderInstaller.MARKER), "neoforge 26.3.0.2");
        assertThatThrownBy(() -> installer.neoforge("26.3.0.1", server))
                .hasMessageStartingWith("the NeoForge installer failed (exit 1)");

        Files.writeString(server.resolve("forge-26.3-66.1.5-shim.jar"), "shim A");
        Files.writeString(server.resolve("forge-26.3-66.1.6-shim.jar"), "shim B");
        Files.writeString(server.resolve("forge-server.jar"), "shim B");
        assertThat(installer.forge("26.3-66.1.5", server).cached()).isFalse();
        assertThat(server.resolve("forge-server.jar")).hasContent("shim A");
    }

    @Test
    void anUnmarkedDirectoryIsInstalledAgain() throws Exception {
        Path server = Files.createDirectories(dir.resolve("server"));
        Files.createDirectories(server.resolve("libraries/net/neoforged/neoforge/26.3.0.1"));
        Files.writeString(server.resolve("server.jar"), "starter");
        assertThatThrownBy(() -> installer.neoforge("26.3.0.1", server))
                .hasMessageStartingWith("the NeoForge installer failed (exit 1)");
    }

    @Test
    void forgesShimJarIsCopiedToAFixedName() throws Exception {
        Path server = Files.createDirectories(dir.resolve("server"));
        Files.writeString(server.resolve("forge-26.3-66.1.5-shim.jar"), "shim");

        assertThat(installer.forge("26.3-66.1.5", server).cached()).isFalse();
        assertThat(server.resolve("forge-server.jar")).hasContent("shim");
        assertThat(installer.forge("26.3-66.1.5", server).cached()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void aMissingVersionOrABrokenInstallerIsReported() throws Exception {
        Path server = Files.createDirectories(dir.resolve("server"));
        assertThatThrownBy(() -> installer.forge("26.3-0.0.0", server)).isInstanceOf(ToolException.class)
                .hasMessage("Forge has no version 26.3-0.0.0");
        assertThatThrownBy(() -> installer.neoforge("26.3.0.1", server)).isInstanceOf(ToolException.class)
                .hasMessageStartingWith("the NeoForge installer failed (exit 1)");
    }

    @Test
    void anInstallerFailureShowsItsLastLines() {
        assertThat(LoaderInstaller.tail("a\n\nb\nc\nd\n", 2)).isEqualTo("c\nd");
        assertThat(LoaderInstaller.latestBuild("{\"build\":\"7\"}")).isEqualTo("7");
    }
}
