package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.server.DownloadStore;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0038 end to end (#549): server_setup in a second workspace takes Paper's jar (SHA-256) and Purpur's (MD5) from
 * the machine's store, with no second jar request, and says it was cached.
 */
class DownloadStoreSetupTest {
    private static final String MINECRAFT = "26.3";
    private static final byte[] PAPER = "paper server".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PURPUR = "purpur server".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;
    private HttpServer http;
    private String base;
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    @BeforeEach
    void serve() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + http.getAddress().getPort();
        Map<String, byte[]> files = Map.of(
                "/fill/v3/projects/paper/versions/" + MINECRAFT + "/builds", bytes("[{\"id\":5,\"channel\":\"STABLE\","
                        + "\"downloads\":{\"server:default\":{\"name\":\"paper.jar\",\"url\":\"" + base
                        + "/fill/paper.jar\",\"checksums\":{\"sha256\":\"" + hex("SHA-256", PAPER) + "\"}}}}]"),
                "/fill/paper.jar", PAPER,
                "/purpur/v2/purpur/" + MINECRAFT + "/latest", bytes("{\"build\":\"9\",\"md5\":\"" + hex("MD5", PURPUR) + "\"}"),
                "/purpur/v2/purpur/" + MINECRAFT + "/9/download", PURPUR);
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            byte[] body = files.get(path);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        http.start();
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    @Test
    void aSecondWorkspaceTakesPaperFromTheStore() throws Exception {
        Path store = dir.resolve("store");
        assertThat(setup("a", store, "paper")).contains("(downloaded)");
        assertThat(setup("b", store, "paper")).contains("paper " + MINECRAFT + " build 5 (cached)");
        assertThat(requests.get("/fill/paper.jar")).hasValue(1);
        assertThat(store.resolve("sha256-" + hex("SHA-256", PAPER) + ".jar")).hasBinaryContent(PAPER);
        assertThat(dir.resolve("b/run/paper.jar")).hasBinaryContent(PAPER);
    }

    @Test
    void aSecondWorkspaceTakesPurpurFromTheStoreByItsMd5() throws Exception {
        Path store = dir.resolve("store");
        assertThat(setup("a", store, "purpur")).contains("(installed)");
        assertThat(setup("b", store, "purpur")).contains("purpur " + MINECRAFT + " build 9 (already there)");
        assertThat(requests.get("/purpur/v2/purpur/" + MINECRAFT + "/9/download")).hasValue(1);
        assertThat(store.resolve("md5-" + hex("MD5", PURPUR) + ".jar")).hasBinaryContent(PURPUR);
        assertThat(dir.resolve("b/run/purpur.jar")).hasBinaryContent(PURPUR);
    }

    @Test
    void theEnvironmentReachesTheConfig() {
        Path store = dir.resolve("elsewhere");
        assertThat(CoreConfig.fromEnvironment(Map.of(DownloadStore.ENV, store.toString())).downloadStore().dir())
                .isEqualTo(store);
        assertThat(CoreConfig.fromEnvironment(Map.of(DownloadStore.ENV, "none")).downloadStore())
                .isEqualTo(DownloadStore.NONE);
    }

    private String setup(String workspace, Path store, String platform) throws Exception {
        Path ws = Files.createDirectories(dir.resolve(workspace));
        Path agents = Files.createDirectories(ws.resolve(".msc/agent"));
        for (String agent : List.of("paper", "fabric", "neoforge", "forge")) {
            Files.writeString(agents.resolve("msc-agent-" + agent + "-0.1.0.jar"), "agent " + agent);
        }
        try (CoreHarness h = new CoreHarness(ws, c -> c.withDownloadsUrl(base + "/fill").withMinecraft(MINECRAFT)
                .withLoaderUrls(new LoaderInstaller.Urls(base + "/neo", base + "/forge", base + "/quilt", base + "/purpur",
                        base + "/geyser"))
                .withDownloadStore(new DownloadStore(store)))) {
            return h.ok("server_setup", "platform", platform, "version", MINECRAFT);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
