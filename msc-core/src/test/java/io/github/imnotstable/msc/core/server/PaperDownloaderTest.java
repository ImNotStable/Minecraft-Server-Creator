package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.ClosedPort;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class PaperDownloaderTest {
    @TempDir
    Path cache;

    @Test
    void anUnreachableJarHostIsReportedAndLeavesNoPartFile() throws Exception {
        try (ClosedPort dead = ClosedPort.reserve()) {
            reportsUnreachable("http://127.0.0.1:" + dead.port() + "/paper.jar");
        }
    }

    private void reportsUnreachable(String jarUrl) throws Exception {
        byte[] builds = ("[{\"id\":7,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"url\":\"" + jarUrl
                + "\"}}}]").getBytes(StandardCharsets.UTF_8);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            ex.sendResponseHeaders(200, builds.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(builds);
            }
        });
        http.start();
        try {
            PaperDownloader paper = new PaperDownloader("http://127.0.0.1:" + http.getAddress().getPort() + "/", cache);
            assertThatThrownBy(() -> paper.download("26.3")).isInstanceOf(ToolException.class)
                    .hasMessageStartingWith("cannot reach " + jarUrl);
            try (var left = Files.walk(cache)) {
                assertThat(left.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".part"));
            }
        } finally {
            http.stop(0);
        }
    }

    /** #428: with the builds service unreachable, the cached build with the highest number, digits counted. */
    @ParameterizedTest
    @CsvSource({"9 10, 10", "999 1000, 1000", "9999 10000, 10000"})
    void offlineTheNewestCachedBuildIsUsed(String builds, int newest) throws Exception {
        for (String build : builds.split(" ")) {
            Files.writeString(cache.resolve("paper-26.3-" + build + ".jar"), build);
        }
        try (ClosedPort unreachable = ClosedPort.reserve()) {
            PaperDownloader paper = new PaperDownloader("http://127.0.0.1:" + unreachable.port(), cache);
            assertThat(paper.download("26.3"))
                    .isEqualTo(new PaperDownloader.Download(cache.resolve("paper-26.3-" + newest + ".jar"), "26.3",
                            newest, true));
            assertThatThrownBy(() -> paper.download("1.0")).isInstanceOf(ToolException.class)
                    .hasMessageStartingWith("cannot reach ");
        }
    }
}
