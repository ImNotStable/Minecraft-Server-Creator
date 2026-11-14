package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0038: a checksummed jar is downloaded once per machine, and a stored file is used only while it matches. */
class DownloadStoreTest {
    private static final byte[] JAR = "paper jar bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    @Test
    void aSecondWorkspaceCopiesTheJarFromTheStoreWithoutTheNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve(requests);
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/paper.jar");
            Downloads.Checksum sha = Downloads.Checksum.of("SHA-256", sha256());
            Downloads downloads = new Downloads(new DownloadStore(tmp.resolve("store")));

            assertThat(downloads.fetch(uri, tmp.resolve("one/.msc/cache/paper.jar"), "gone", sha)).isFalse();
            assertThat(downloads.fetch(uri, tmp.resolve("two/.msc/cache/paper.jar"), "gone", sha)).isTrue();

            assertThat(requests).hasValue(1);
            assertThat(tmp.resolve("two/.msc/cache/paper.jar")).hasBinaryContent(JAR);
            assertThat(tmp.resolve("store/sha256-" + sha256() + ".jar")).hasBinaryContent(JAR);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aStoredFileThatNoLongerMatchesItsNameIsDownloadedOver() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve(requests);
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/paper.jar");
            Path stored = Files.createDirectories(tmp.resolve("store")).resolve("sha256-" + sha256() + ".jar");
            Files.writeString(stored, "damaged");
            Downloads downloads = new Downloads(new DownloadStore(tmp.resolve("store")));

            assertThat(downloads.fetch(uri, tmp.resolve("ws/paper.jar"), "gone",
                    Downloads.Checksum.of("SHA-256", sha256()))).isFalse();

            assertThat(requests).hasValue(1);
            assertThat(tmp.resolve("ws/paper.jar")).hasBinaryContent(JAR);
            assertThat(stored).hasBinaryContent(JAR);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDownloadWithoutChecksumIsNeitherLookedUpNorKept() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve(requests);
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/paper.jar");
            Downloads downloads = new Downloads(new DownloadStore(tmp.resolve("store")));

            downloads.fetch(uri, tmp.resolve("a.jar"), "gone", null);
            downloads.fetch(uri, tmp.resolve("b.jar"), "gone", null);

            assertThat(requests).hasValue(2);
            assertThat(tmp.resolve("store")).doesNotExist();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theEnvironmentMovesOrTurnsOffTheStore() {
        Path home = tmp.resolve("home");
        assertThat(DownloadStore.fromEnvironment(Map.of(), home).dir()).isEqualTo(home.resolve(".msc/downloads"));
        assertThat(DownloadStore.fromEnvironment(Map.of(DownloadStore.ENV, " NONE "), home)).isEqualTo(DownloadStore.NONE);
        assertThat(DownloadStore.fromEnvironment(Map.of(DownloadStore.ENV, tmp.resolve("s").toString()), home).dir())
                .isEqualTo(tmp.resolve("s"));
    }

    @Test
    void aChecksumThatIsNoFileNameIsNotStored() throws Exception {
        Path file = Files.writeString(tmp.resolve("x.jar"), "x");
        DownloadStore store = new DownloadStore(tmp.resolve("store"));
        assertThat(store.keep(file, Downloads.Checksum.of("SHA-256", "../../evil"))).isFalse();
        assertThat(store.find(Downloads.Checksum.of("SHA-256", "../../evil"))).isNull();
        assertThat(tmp.resolve("store")).doesNotExist();
    }

    private static HttpServer serve(AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/paper.jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, JAR.length);
            try (var body = exchange.getResponseBody()) {
                body.write(JAR);
            }
        });
        server.start();
        return server;
    }

    private String sha256() throws Exception {
        Path file = Files.write(tmp.resolve("expected.bin"), JAR);
        return Downloads.sha256(file);
    }
}
