package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0046: Geyser and Floodgate from GeyserMC's v2 API (a local fake), sha256-checked, cached and stored. */
class GeyserDownloaderTest {
    @TempDir
    Path tmp;

    private HttpServer http;
    private String base;
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            requests.add(path);
            String body = answers.get(path);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort() + "/api/";
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    private void serve(String project, String version, int build, String jar, String sha) {
        answers.put("/api/v2/projects/" + project + "/versions/latest/builds/latest",
                "{\"version\":\"" + version + "\",\"build\":" + build + ",\"downloads\":{\"spigot\":{\"name\":\"x.jar\","
                        + "\"sha256\":\"" + sha + "\"},\"velocity\":{\"name\":\"y.jar\",\"sha256\":\"00\"}}}");
        answers.put("/api/v2/projects/" + project + "/versions/" + version + "/builds/" + build + "/downloads/spigot",
                jar);
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void downloadsTheNewestSpigotBuildThenReusesTheCacheAndTheStore() throws Exception {
        serve("geyser", "2.12.1", 1302, "geyser jar", sha("geyser jar"));
        Path store = tmp.resolve("store");
        GeyserDownloader one = new GeyserDownloader(base, tmp.resolve("one/cache"), new DownloadStore(store));

        GeyserDownloader.Download d = one.download(GeyserDownloader.Project.GEYSER);

        assertThat(d.describe()).isEqualTo("geyser 2.12.1 build 1302");
        assertThat(d.cached()).isFalse();
        assertThat(d.jar()).hasFileName("geyser-spigot-1302.jar").hasContent("geyser jar");
        assertThat(one.download(GeyserDownloader.Project.GEYSER).cached()).isTrue();
        // Another workspace copies it from the machine's store.
        GeyserDownloader.Download two = new GeyserDownloader(base, tmp.resolve("two/cache"), new DownloadStore(store))
                .download(GeyserDownloader.Project.GEYSER);
        assertThat(two.cached()).isTrue();
        assertThat(two.jar()).hasContent("geyser jar");
        assertThat(requests.stream().filter(r -> r.endsWith("/downloads/spigot"))).hasSize(1);
    }

    @Test
    void aJarThatDoesNotMatchItsSha256IsRefusedAndNotKept() throws Exception {
        serve("floodgate", "2.2.5", 141, "tampered", sha("floodgate jar"));
        GeyserDownloader g = new GeyserDownloader(base, tmp.resolve("cache"), DownloadStore.NONE);

        assertThatThrownBy(() -> g.download(GeyserDownloader.Project.FLOODGATE)).isInstanceOf(ToolException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(tmp.resolve("cache/floodgate-spigot-141.jar")).doesNotExist();
    }

    @Test
    void offlineTheNewestCachedBuildIsUsed() throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(cache.resolve("geyser-spigot-999.jar"), "old");
        Files.writeString(cache.resolve("geyser-spigot-1000.jar"), "new");
        try (Socket closed = new Socket()) {
            closed.bind(new InetSocketAddress("127.0.0.1", 0));
            GeyserDownloader g = new GeyserDownloader("http://127.0.0.1:" + closed.getLocalPort(), cache,
                    DownloadStore.NONE);

            GeyserDownloader.Download d = g.download(GeyserDownloader.Project.GEYSER);

            assertThat(d.describe()).isEqualTo("geyser build 1000");
            assertThat(d.jar()).hasContent("new");
            assertThatThrownBy(() -> g.download(GeyserDownloader.Project.FLOODGATE))
                    .isInstanceOf(ToolException.class).hasMessageStartingWith("cannot reach ");

            // Nothing cached, but the folder has the plugin: it is kept, and the note says why.
            Path kept = Files.writeString(Files.createDirectories(tmp.resolve("plugins")).resolve("floodgate-spigot.jar"),
                    "installed");
            GeyserDownloader.Download k = g.download(GeyserDownloader.Project.FLOODGATE, kept);
            assertThat(k.jar()).isEqualTo(kept).hasContent("installed");
            assertThat(k.describe()).isEqualTo("floodgate");
            assertThat(k.note()).startsWith("cannot reach ").endsWith("; keeping the jar in plugins/")
                    .doesNotContain("cached");
            assertThat(g.download(GeyserDownloader.Project.GEYSER, kept).jar()).hasContent("new");
        }
    }

    @Test
    void anAnswerWithoutAUsableVersionBuildOrSha256IsRefusedInOneLine() {
        String sha = "a".repeat(64);
        for (String json : List.of("[]", "not json", "{\"build\":1,\"downloads\":{\"spigot\":{\"sha256\":\"" + sha + "\"}}}",
                "{\"version\":\"../x\",\"build\":1,\"downloads\":{\"spigot\":{\"sha256\":\"" + sha + "\"}}}",
                "{\"version\":\"2.1\",\"build\":1.5,\"downloads\":{\"spigot\":{\"sha256\":\"" + sha + "\"}}}",
                "{\"version\":\"2.1\",\"build\":\"1\",\"downloads\":{\"spigot\":{\"sha256\":\"" + sha + "\"}}}",
                "{\"version\":\"2.1\",\"build\":1,\"downloads\":{\"velocity\":{\"sha256\":\"" + sha + "\"}}}",
                "{\"version\":\"2.1\",\"build\":1,\"downloads\":{\"spigot\":{\"sha256\":\"abc\"}}}")) {
            assertThatThrownBy(() -> GeyserDownloader.parse(GeyserDownloader.Project.GEYSER, json)).as(json)
                    .isInstanceOf(ToolException.class).hasMessageStartingWith("GeyserMC's answer for geyser ")
                    .hasMessageNotContaining("\n");
        }
        assertThat(GeyserDownloader.parse(GeyserDownloader.Project.GEYSER,
                "{\"version\":\"2.12.1\",\"build\":7,\"downloads\":{\"spigot\":{\"sha256\":\"" + sha + "\"}}}"))
                .isEqualTo(new GeyserDownloader.Build("2.12.1", 7, sha));
    }

    @Test
    void anErrorAnswerIsRefused() {
        GeyserDownloader g = new GeyserDownloader(base, tmp.resolve("cache"), DownloadStore.NONE);
        assertThatThrownBy(() -> g.download(GeyserDownloader.Project.GEYSER)).isInstanceOf(ToolException.class)
                .hasMessageStartingWith("GeyserMC downloads answered 404");
    }
}
