package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.process.StallingHttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class PaperJarsTest {
    private static final byte[] JAR = "jar bytes".getBytes(StandardCharsets.UTF_8);

    private static final String BUILDS = "/v3/projects/paper/versions/26.3/builds";

    @TempDir
    Path cache;
    @TempDir
    Path dir;
    private HttpServer http;
    /** The paths the fake downloads service was asked for, in order. */
    private final List<String> asked = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    /** Serves one stable build whose listed sha256 is {@code sha256}, and {@link #JAR} as its file. */
    private String serve(String sha256) throws IOException {
        return serve(sha256, null);
    }

    /** As {@link #serve(String)}, with the build's file at {@code jarUrl} when it is not null. */
    private String serve(String sha256, String jarUrl) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        byte[] builds = ("[{\"id\":7,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"url\":\""
                + (jarUrl == null ? base + "/paper.jar" : jarUrl) + "\",\"checksums\":{\"sha256\":\"" + sha256 + "\"}}}}]").getBytes(StandardCharsets.UTF_8);
        // Answers only the paths PaperMC has, as PaperMC answers 400 for //v3/... (#649), so a bad join fails here too.
        http.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            asked.add(path);
            byte[] body = path.equals("/paper.jar") ? JAR : path.equals(BUILDS) ? builds : null;
            if (body == null) {
                ex.sendResponseHeaders(path.startsWith("/v3/projects/paper/versions/") ? 404 : 400, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        http.start();
        return base;
    }

    @Test
    void aDownloadWithTheListedChecksumIsCached() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JAR));
        Path jar = PaperJars.resolve(List.of(cache), "26.3", serve(sha));
        assertThat(jar).isEqualTo(cache.resolve("paper-26.3-7.jar")).hasBinaryContent(JAR);
    }

    /** #649: a downloadsUrl written with a trailing slash loads trimmed, so the bench asks PaperMC for /v3/..., not //v3. */
    @Test
    void aDownloadsUrlWithATrailingSlashAsksForTheBuildsPath() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JAR));
        String base = serve(sha);
        Path file = dir.resolve("bench.json");
        Files.writeString(file, "{\"server\":{\"downloadsUrl\":\"" + base + "/\"}}");
        BenchConfig config = BenchConfig.load(file, Files.createDirectories(dir.resolve("home")));

        Path jar = PaperJars.resolve(List.of(cache), config.minecraftVersion(), config.server().downloadsUrl());

        assertThat(asked).containsExactly(BUILDS, "/paper.jar");
        assertThat(jar).isEqualTo(cache.resolve("paper-26.3-7.jar")).hasBinaryContent(JAR);
    }

    @Test
    void aVersionPaperMcDoesNotHaveIsNamed() throws Exception {
        String base = serve("00".repeat(32));
        assertThatThrownBy(() -> PaperJars.resolve(List.of(cache), "9.9", base))
                .isInstanceOf(IOException.class).hasMessage("PaperMC has no version 9.9");
        assertThat(cache).isEmptyDirectory();
    }

    /** As msc-core reads it: the whitespace around a listed checksum is no part of it. */
    @Test
    void aListedChecksumIsReadWithoutTheWhitespaceAroundIt() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JAR));
        Path jar = PaperJars.resolve(List.of(cache), "26.3", serve(" " + sha + "\\n"));
        assertThat(jar).hasBinaryContent(JAR);
    }

    @Test
    void aDownloadThatDoesNotMatchItsChecksumIsRefusedAndDeleted() throws Exception {
        String base = serve("00".repeat(32));
        assertThatThrownBy(() -> PaperJars.resolve(List.of(cache), "26.3", base))
                .isInstanceOf(IOException.class).hasMessageContaining("checksum mismatch");
        assertThat(cache).isEmptyDirectory();
    }

    /** #454: a jar download that goes silent after its headers fails after the stall limit and leaves nothing. */
    @Test
    void aDownloadThatStallsFailsNamingTheHostAndLeavesNothing() throws Exception {
        try (StallingHttpServer stalling = new StallingHttpServer(100_000, 10, Duration.ZERO)) {
            String base = serve("00".repeat(32), stalling.url("/paper.jar"));
            assertThatThrownBy(() -> PaperJars.resolve(List.of(cache), "26.3", base, Duration.ofMillis(500)))
                    .isInstanceOf(IOException.class)
                    .hasMessage("download from 127.0.0.1 stalled (no data for 500 ms); check the network connection "
                            + "and run the bench again");
            assertThat(cache).isEmptyDirectory();
        }
    }
}
