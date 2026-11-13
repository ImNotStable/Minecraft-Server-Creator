package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperJarsTest {
    private static final byte[] JAR = "jar bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cache;
    private HttpServer http;

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    /** Serves one stable build whose listed sha256 is {@code sha256}, and {@link #JAR} as its file. */
    private String serve(String sha256) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        byte[] builds = ("[{\"id\":7,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"url\":\"" + base
                + "/paper.jar\",\"checksums\":{\"sha256\":\"" + sha256 + "\"}}}}]").getBytes(StandardCharsets.UTF_8);
        http.createContext("/", ex -> {
            byte[] body = ex.getRequestURI().getPath().endsWith(".jar") ? JAR : builds;
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

    @Test
    void aDownloadThatDoesNotMatchItsChecksumIsRefusedAndDeleted() throws Exception {
        String base = serve("00".repeat(32));
        assertThatThrownBy(() -> PaperJars.resolve(List.of(cache), "26.3", base))
                .isInstanceOf(IOException.class).hasMessageContaining("checksum mismatch");
        assertThat(cache).isEmptyDirectory();
    }
}
