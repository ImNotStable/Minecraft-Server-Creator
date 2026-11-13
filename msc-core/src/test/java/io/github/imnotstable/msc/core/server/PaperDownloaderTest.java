package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperDownloaderTest {
    @TempDir
    Path cache;

    @Test
    void anUnreachableJarHostIsReportedAndLeavesNoPartFile() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        String jarUrl = "http://127.0.0.1:" + deadPort + "/paper.jar";
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
            assertThat(cache.resolve("paper-26.3-7.jar.part")).doesNotExist();
        } finally {
            http.stop(0);
        }
    }
}
